import com.fasterxml.jackson.databind.JsonNode;

public class Order {

    public static void main(String[] args) throws Exception {
        String chave = System.getenv("BINANCE_TESTNET_KEY");
        String segredo = System.getenv("BINANCE_TESTNET_SECRET");
        if (chave == null || segredo == null) {
            System.out.println("Faltam as variáveis BINANCE_TESTNET_KEY e BINANCE_TESTNET_SECRET.");
            return;
        }

        boolean real = args.length > 0 && args[0].equals("real");

        String params = "symbol=BTCUSDT&side=BUY&type=MARKET&quoteOrderQty=100";
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

        System.out.println("Ordem executada na testnet:");
        System.out.println("  ID: " + r.get("orderId").asText());
        System.out.println("  Estado: " + r.get("status").asText());
        System.out.println("  BTC comprado: " + r.get("executedQty").asText());
        System.out.println("  USDT gasto: " + r.get("cummulativeQuoteQty").asText());
    }
}