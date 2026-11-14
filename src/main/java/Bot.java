import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class Bot {

    // ---- Parâmetros (podes mexer aqui) ----
    static final String SIMBOLO = "BTCUSDT";
    static final String INTERVALO = "4h";   // 1h, 4h ou 1d (o backfill só deve ser usado com estes)
    static final int RAPIDA = 20;
    static final int LENTA = 50;
    static final String VALOR_USDT = "100";
    static final double STOP_LOSS = 0.02;   // 2%
    static final long ESPERA_MS = 60_000;   // verifica de 1 em 1 minuto
    static final int BACKFILL_DIAS = 90;    // dias de histórico a preencher no arranque

    static final Path ESTADO = Path.of("estado.txt");
    static final Path LOG = Path.of("bot.log");

    static String chave, segredo;
    static boolean emPosicao = false;
    static BigDecimal quantidade = BigDecimal.ZERO;
    static double precoEntrada = 0;
    static long ultimaVela = 0;

    static void log(String msg) {
        String linha = LocalDateTime.now().withNano(0) + " | " + msg;
        System.out.println(linha);
        try {
            Files.writeString(LOG, linha + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            // se o log falhar, não deve parar o bot
        }
    }

    // ---- Base de dados: se falhar, o bot continua a funcionar ----
    static void gravarVela(JsonNode k) {
        try {
            Db.guardarVela(SIMBOLO, INTERVALO,
                    Instant.ofEpochMilli(k.get(6).asLong()),
                    Double.parseDouble(k.get(1).asText()),
                    Double.parseDouble(k.get(2).asText()),
                    Double.parseDouble(k.get(3).asText()),
                    Double.parseDouble(k.get(4).asText()),
                    Double.parseDouble(k.get(5).asText()));
        } catch (Exception e) {
            log("AVISO: não foi possível gravar a vela na BD: " + e.getMessage());
        }
    }

    static void gravarTrade(String lado, BigDecimal qtd, double preco, double valor, String orderId) {
        try {
            Db.guardarTrade(SIMBOLO, lado, qtd.doubleValue(), preco, valor, orderId, "TESTNET");
        } catch (Exception e) {
            log("AVISO: não foi possível gravar o trade na BD: " + e.getMessage());
        }
    }

    static void guardarEstado() throws Exception {
        Files.writeString(ESTADO, emPosicao + ";" + quantidade.toPlainString() + ";" + precoEntrada);
    }

    static void carregarEstado() throws Exception {
        if (!Files.exists(ESTADO)) return;
        String[] p = Files.readString(ESTADO).trim().split(";");
        emPosicao = Boolean.parseBoolean(p[0]);
        quantidade = new BigDecimal(p[1]);
        precoEntrada = Double.parseDouble(p[2]);
    }

    static JsonNode velas() throws Exception {
        // dados de mercado públicos (sem chave); as ordens vão para a testnet
        String url = "https://api.binance.com/api/v3/klines?symbol=" + SIMBOLO
                + "&interval=" + INTERVALO + "&limit=" + (LENTA + 5);
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).GET().build();
        String corpo = Testnet.CLIENT.send(req, HttpResponse.BodyHandlers.ofString()).body();
        return Testnet.MAPPER.readTree(corpo);
    }

    static double sma(List<Double> f, int fim, int n) {
        double soma = 0;
        for (int i = fim - n + 1; i <= fim; i++) soma += f.get(i);
        return soma / n;
    }

    static void comprar() throws Exception {
        JsonNode r = Testnet.pedidoAssinado("POST", "/api/v3/order",
                "symbol=" + SIMBOLO + "&side=BUY&type=MARKET&quoteOrderQty=" + VALOR_USDT,
                chave, segredo);
        if (r.has("code")) {
            log("ERRO ao comprar: " + r);
            return;
        }
        quantidade = new BigDecimal(r.get("executedQty").asText());
        double gasto = Double.parseDouble(r.get("cummulativeQuoteQty").asText());
        precoEntrada = gasto / quantidade.doubleValue();
        emPosicao = true;
        guardarEstado();
        log(String.format("COMPRA: %s BTC a ~%.2f", quantidade.stripTrailingZeros().toPlainString(), precoEntrada));
        gravarTrade("BUY", quantidade, precoEntrada, gasto, r.path("orderId").asText());
    }

    static void vender() throws Exception {
        JsonNode r = Testnet.pedidoAssinado("POST", "/api/v3/order",
                "symbol=" + SIMBOLO + "&side=SELL&type=MARKET&quantity="
                        + quantidade.stripTrailingZeros().toPlainString(),
                chave, segredo);
        if (r.has("code")) {
            log("ERRO ao vender: " + r);
            return;
        }
        double recebido = Double.parseDouble(r.get("cummulativeQuoteQty").asText());
        BigDecimal qtdVendida = quantidade;
        double gasto = precoEntrada * qtdVendida.doubleValue();
        double precoVenda = qtdVendida.doubleValue() > 0 ? recebido / qtdVendida.doubleValue() : 0;
        String orderId = r.path("orderId").asText();

        emPosicao = false;
        quantidade = BigDecimal.ZERO;
        precoEntrada = 0;
        guardarEstado();
        log(String.format("VENDA: recebido %.2f USDT | resultado %.2f USDT", recebido, recebido - gasto));
        gravarTrade("SELL", qtdVendida, precoVenda, recebido, orderId);
    }

    static void ciclo() throws Exception {
        JsonNode v = velas();
        if (!v.isArray()) {
            log("Resposta inesperada da Binance: " + v);
            return;
        }

        // a última vela ainda está aberta: o seu fecho serve de "preço atual"
        double precoAtual = Double.parseDouble(v.get(v.size() - 1).get(4).asText());

        // stop-loss: verifica-se em todos os ciclos
        if (emPosicao && precoAtual <= precoEntrada * (1 - STOP_LOSS)) {
            log(String.format("STOP-LOSS atingido (preço %.2f)", precoAtual));
            vender();
            return;
        }

        // sinal: só se avalia uma vez por vela fechada
        int ultima = v.size() - 2; // última vela já fechada
        long fechoUltima = v.get(ultima).get(6).asLong();
        if (fechoUltima == ultimaVela) return;
        ultimaVela = fechoUltima;

        // grava a vela fechada na base de dados
        gravarVela(v.get(ultima));

        List<Double> fechos = new ArrayList<>();
        for (int i = 0; i <= ultima; i++) {
            fechos.add(Double.parseDouble(v.get(i).get(4).asText()));
        }

        double rAgora = sma(fechos, ultima, RAPIDA);
        double lAgora = sma(fechos, ultima, LENTA);
        double rAntes = sma(fechos, ultima - 1, RAPIDA);
        double lAntes = sma(fechos, ultima - 1, LENTA);

        log(String.format("Vela fechada | preço %.2f | rápida %.2f | lenta %.2f | em posição: %s",
                fechos.get(ultima), rAgora, lAgora, emPosicao));

        if (rAntes <= lAntes && rAgora > lAgora && !emPosicao) {
            comprar();
        } else if (rAntes >= lAntes && rAgora < lAgora && emPosicao) {
            vender();
        }
    }

    public static void main(String[] args) throws Exception {
        chave = System.getenv("BINANCE_TESTNET_KEY");
        segredo = System.getenv("BINANCE_TESTNET_SECRET");
        if (chave == null || segredo == null) {
            System.out.println("Faltam as variáveis BINANCE_TESTNET_KEY e BINANCE_TESTNET_SECRET.");
            return;
        }

        carregarEstado();
        log("Bot iniciado | " + SIMBOLO + " " + INTERVALO + " | médias " + RAPIDA + "/" + LENTA
                + " | em posição: " + emPosicao);

        // preenche na BD as velas que faltam (por exemplo, das horas em que o PC esteve desligado)
        try {
            int n = Backfill.preencher(SIMBOLO, INTERVALO, BACKFILL_DIAS);
            log("Backfill: " + n + " velas novas gravadas na BD");
        } catch (Exception e) {
            log("AVISO: backfill falhou: " + e.getMessage());
        }

        while (true) {
            try {
                ciclo();
            } catch (Exception e) {
                log("ERRO no ciclo: " + e);
            }
            Thread.sleep(ESPERA_MS);
        }
    }
}