import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class Sell {

    public static void main(String[] args) throws Exception {
        String chave = System.getenv("BINANCE_TESTNET_KEY");
        String segredo = System.getenv("BINANCE_TESTNET_SECRET");
        if (chave == null || segredo == null) {
            System.out.println("Faltam as variáveis BINANCE_TESTNET_KEY e BINANCE_TESTNET_SECRET.");
            return;
        }
        if (args.length == 0) {
            System.out.println("Indica a quantidade de BTC a vender. Ex.: -Dexec.args=\"0.00139\"");
            return;
        }

        boolean real = args.length > 1 && args[1].equals("real");
        BigDecimal quantidade = new BigDecimal(args[0]);

        // 1) Descobrir o tamanho de lote (stepSize) do par BTCUSDT
        HttpRequest req = HttpRequest.newBuilder(
                URI.create(Testnet.BASE + "/api/v3/exchangeInfo?symbol=BTCUSDT")).GET().build();
        JsonNode info = Testnet.MAPPER.readTree(
                Testnet.CLIENT.send(req, HttpResponse.BodyHandlers.ofString()).body());

        BigDecimal passo = null;
        for (JsonNode f : info.get("symbols").get(0).get("filters")) {
            if (f.get("filterType").asText().equals("LOT_SIZE")) {
                passo = new BigDecimal(f.get("stepSize").asText());
            }
        }
        if (passo == null) {
            System.out.println("Não consegui obter o stepSize.");
            return;
        }

        // 2) Arredondar a quantidade PARA BAIXO ao múltiplo do passo
        BigDecimal arredondada = quantidade.divideToIntegralValue(passo).multiply(passo);
        String qtd = arredondada.stripTrailingZeros().toPlainString();
        System.out.println("Quantidade pedida: " + quantidade.toPlainString()
                + " | a vender (arredondada): " + qtd);

        // 3) Enviar a ordem
        String params = "symbol=BTCUSDT&side=SELL&type=MARKET&quantity=" + qtd;
        String caminho = real ? "/api/v3/order" : "/api/v3/order/test";
        JsonNode r = Testnet.pedidoAssinado("POST", caminho, params, chave, segredo);

        if (r.has("code")) {
            System.out.println("Erro da Binance: " + r);
            return;
        }
        if (!real) {
            System.out.println("Ordem de TESTE válida (nada foi executado).");
            return;
        }

        System.out.println("Venda executada na testnet:");
        System.out.println("  ID: " + r.get("orderId").asText());
        System.out.println("  Estado: " + r.get("status").asText());
        System.out.println("  BTC vendido: " + r.get("executedQty").asText());
        System.out.println("  USDT recebido: " + r.get("cummulativeQuoteQty").asText());
    }
}