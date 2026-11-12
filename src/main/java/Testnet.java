import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Set;

public class Testnet {

    static final String BASE = "https://testnet.binance.vision";
    static final HttpClient CLIENT = HttpClient.newHttpClient();
    static final ObjectMapper MAPPER = new ObjectMapper();

    static String assinar(String texto, String segredo) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(segredo.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(texto.getBytes(StandardCharsets.UTF_8)));
    }

    static JsonNode pedidoAssinado(String metodo, String caminho, String params,
                                   String chave, String segredo) throws Exception {
        String query = params + (params.isEmpty() ? "" : "&")
                + "timestamp=" + System.currentTimeMillis() + "&recvWindow=5000";
        String url = BASE + caminho + "?" + query + "&signature=" + assinar(query, segredo);

        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .header("X-MBX-APIKEY", chave);
        HttpRequest req = metodo.equals("POST")
                ? b.POST(HttpRequest.BodyPublishers.noBody()).build()
                : b.GET().build();

        HttpResponse<String> res = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
        return MAPPER.readTree(res.body());
    }

    public static void main(String[] args) throws Exception {
        String chave = System.getenv("BINANCE_TESTNET_KEY");
        String segredo = System.getenv("BINANCE_TESTNET_SECRET");
        if (chave == null || segredo == null) {
            System.out.println("Faltam as variáveis BINANCE_TESTNET_KEY e BINANCE_TESTNET_SECRET.");
            return;
        }

        JsonNode conta = pedidoAssinado("GET", "/api/v3/account", "", chave, segredo);

        if (conta.has("code")) {
            System.out.println("Erro da Binance: " + conta);
            return;
        }

        System.out.println("Ligação à testnet OK. Saldos principais:");
        Set<String> interesse = Set.of("BTC", "USDT", "BNB");
        for (JsonNode saldo : conta.get("balances")) {
            String ativo = saldo.get("asset").asText();
            double livre = Double.parseDouble(saldo.get("free").asText());
            if (interesse.contains(ativo) && livre > 0) {
                System.out.printf("  %s: %s%n", ativo, saldo.get("free").asText());
            }
        }
    }
}
