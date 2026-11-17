import com.fasterxml.jackson.databind.JsonNode;

import java.util.Locale;

/**
 * Teste de ponta a ponta em PAPER: compra 100 USD de BTC/USD, espera um pouco e vende tudo.
 * Grava as duas ordens na base de dados (ambiente "ALPACA_PAPER_TESTE"), por isso aparecem no Grafana.
 * Só funciona com a conta paper (o Alpaca.java bloqueia a API de dinheiro real).
 *
 * Uso (dentro do container do bot):
 *   docker compose exec botmulti java -cp "/app/app.jar:/app/libs/*" TesteTrade
 */
public class TesteTrade {

    static final double VALOR_USD = 100.0;
    static final long ESPERA_ENTRE_ORDENS_MS = 30_000;
    static final String AMBIENTE = "ALPACA_PAPER_TESTE";

    static Mercados.Mercado btc() {
        for (Mercados.Mercado m : Mercados.LISTA) {
            if (m.simbolo().equals("BTC/USD")) return m;
        }
        throw new IllegalStateException("BTC/USD não está na lista de mercados");
    }

    static void gravar(String simbolo, String lado, double qtd, double preco, String orderId) {
        try {
            Db.guardarTrade(simbolo, lado, qtd, preco, qtd * preco, orderId, AMBIENTE);
            System.out.println("  (gravado na base de dados)");
        } catch (Exception e) {
            System.out.println("  AVISO: não foi possível gravar na BD: " + e.getMessage());
        }
    }

    public static void main(String[] args) throws Exception {
        if (System.getenv("ALPACA_KEY") == null || System.getenv("ALPACA_SECRET") == null) {
            System.out.println("Faltam as variáveis ALPACA_KEY e ALPACA_SECRET.");
            return;
        }
        Mercados.Mercado m = btc();
        JsonNode conta = Alpaca.conta();
        System.out.println("Conta " + conta.path("status").asText() + " (paper) | capital "
                + conta.path("equity").asText() + " USD");

        if (Alpaca.posicao(m) != null) {
            System.out.println("Já existe uma posição aberta de BTC/USD. Não faço o teste para não mexer nela.");
            return;
        }

        System.out.printf(Locale.US, "1) A comprar %.2f USD de BTC/USD...%n", VALOR_USD);
        JsonNode compra = Alpaca.comprar(m.simbolo(), VALOR_USD, true);
        if (!compra.path("status").asText().equals("filled")) {
            System.out.println("Compra não preenchida (estado: " + compra.path("status").asText() + "). A parar.");
            return;
        }
        double qtdCompra = compra.path("filled_qty").asDouble();
        double precoCompra = compra.path("filled_avg_price").asDouble();
        System.out.printf(Locale.US, "   COMPRA: %.6f BTC a %.2f USD (total %.2f USD)%n",
                qtdCompra, precoCompra, qtdCompra * precoCompra);
        gravar(m.simbolo(), "BUY", qtdCompra, precoCompra, compra.path("id").asText());

        System.out.println("2) A esperar " + ESPERA_ENTRE_ORDENS_MS / 1000 + " segundos (vê a posição na Alpaca)...");
        Thread.sleep(ESPERA_ENTRE_ORDENS_MS);

        JsonNode pos = null;
        for (int i = 0; i < 10 && pos == null; i++) {
            pos = Alpaca.posicao(m);
            if (pos == null) Thread.sleep(2000);
        }
        if (pos == null) {
            System.out.println("Não encontrei a posição para vender. Verifica na Alpaca.");
            return;
        }

        System.out.println("3) A vender a posição toda...");
        JsonNode venda = Alpaca.fechar(m);
        if (!venda.path("status").asText().equals("filled")) {
            System.out.println("Venda não preenchida (estado: " + venda.path("status").asText()
                    + "). Verifica na Alpaca e fecha a posição à mão se for preciso.");
            return;
        }
        double qtdVenda = venda.path("filled_qty").asDouble();
        double precoVenda = venda.path("filled_avg_price").asDouble();
        System.out.printf(Locale.US, "   VENDA: %.6f BTC a %.2f USD (total %.2f USD)%n",
                qtdVenda, precoVenda, qtdVenda * precoVenda);
        gravar(m.simbolo(), "SELL", qtdVenda, precoVenda, venda.path("id").asText());

        double resultado = qtdVenda * precoVenda - qtdCompra * precoCompra;
        System.out.printf(Locale.US, "Resultado do teste: %.2f USD (inclui comissões/spread da simulação)%n", resultado);
    }
}