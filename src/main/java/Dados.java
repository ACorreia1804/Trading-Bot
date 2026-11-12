import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.ZoneId;

public class Dados {
    public static void main(String[] args) throws Exception {
        String url = "https://api.binance.com/api/v3/klines"
                + "?symbol=BTCUSDT&interval=1h&limit=200";

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().build();
        HttpResponse<String> response =
                client.send(request, HttpResponse.BodyHandlers.ofString());

        JsonNode velas = new ObjectMapper().readTree(response.body());
        System.out.println("Velas recebidas: " + velas.size());

        for (int i = velas.size() - 5; i < velas.size(); i++) {
            JsonNode v = velas.get(i);
            Instant quando = Instant.ofEpochMilli(v.get(0).asLong());
            System.out.printf("%s | abertura %s | max %s | min %s | fecho %s%n",
                    quando.atZone(ZoneId.of("Europe/Lisbon")),
                    v.get(1).asText(), v.get(2).asText(),
                    v.get(3).asText(), v.get(4).asText());
        }
    }
}