import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeriesBuilder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

public class Historico {

    static BarSeries carregar(String simbolo, String intervalo, Duration duracao, int dias)
            throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        ObjectMapper mapper = new ObjectMapper();
        BarSeries series = new BaseBarSeriesBuilder()
                .withName(simbolo + " " + intervalo).build();

        long agora = System.currentTimeMillis();
        long inicio = Instant.now().minus(Duration.ofDays(dias)).toEpochMilli();

        while (inicio < agora) {
            String url = "https://api.binance.com/api/v3/klines?symbol=" + simbolo
                    + "&interval=" + intervalo
                    + "&startTime=" + inicio + "&limit=1000";

            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().build();
            HttpResponse<String> response =
                    client.send(request, HttpResponse.BodyHandlers.ofString());

            JsonNode velas = mapper.readTree(response.body());
            if (!velas.isArray()) {
                System.out.println("Erro da Binance: " + response.body());
                break;
            }
            if (velas.size() == 0) break;

            for (JsonNode v : velas) {
                long fecho = v.get(6).asLong();
                if (fecho > agora) continue; // ignora a vela que ainda está aberta
                ZonedDateTime fim = Instant.ofEpochMilli(fecho).atZone(ZoneId.of("UTC"));
                series.addBar(
                        duracao,
                        fim,
                        Double.parseDouble(v.get(1).asText()),
                        Double.parseDouble(v.get(2).asText()),
                        Double.parseDouble(v.get(3).asText()),
                        Double.parseDouble(v.get(4).asText()),
                        Double.parseDouble(v.get(5).asText()));
            }

            inicio = velas.get(velas.size() - 1).get(6).asLong() + 1;
            Thread.sleep(250); // pausa curta para não sobrecarregar a API
        }
        return series;
    }
}