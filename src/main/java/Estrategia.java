import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeriesBuilder;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

public class Estrategia {

    static BarSeries carregarVelas(String simbolo, String intervalo, int limite) throws Exception {
        String url = "https://api.binance.com/api/v3/klines?symbol=" + simbolo
                + "&interval=" + intervalo + "&limit=" + limite;

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().build();
        HttpResponse<String> response =
                client.send(request, HttpResponse.BodyHandlers.ofString());

        JsonNode velas = new ObjectMapper().readTree(response.body());
        BarSeries series = new BaseBarSeriesBuilder().withName(simbolo).build();

                for (JsonNode v : velas) {
            ZonedDateTime fim = Instant.ofEpochMilli(v.get(6).asLong())
                    .atZone(ZoneId.of("UTC"));
            series.addBar(
                    Duration.ofHours(1),
                    fim,
                    Double.parseDouble(v.get(1).asText()),
                    Double.parseDouble(v.get(2).asText()),
                    Double.parseDouble(v.get(3).asText()),
                    Double.parseDouble(v.get(4).asText()),
                    Double.parseDouble(v.get(5).asText()));
        }
        return series;
    }

    public static void main(String[] args) throws Exception {
        BarSeries series = carregarVelas("BTCUSDT", "1h", 500);

        ClosePriceIndicator fecho = new ClosePriceIndicator(series);
        SMAIndicator rapida = new SMAIndicator(fecho, 20);
        SMAIndicator lenta = new SMAIndicator(fecho, 50);

        // A última vela da Binance ainda está aberta, por isso usamos a anterior (já fechada)
        int ultima = series.getEndIndex() - 1;
        int anterior = ultima - 1;

        double rapidaAnt = rapida.getValue(anterior).doubleValue();
        double lentaAnt = lenta.getValue(anterior).doubleValue();
        double rapidaAgora = rapida.getValue(ultima).doubleValue();
        double lentaAgora = lenta.getValue(ultima).doubleValue();

        System.out.printf("Média rápida (20): %.2f  (antes: %.2f)%n", rapidaAgora, rapidaAnt);
        System.out.printf("Média lenta  (50): %.2f  (antes: %.2f)%n", lentaAgora, lentaAnt);

        String sinal = "HOLD";
        if (rapidaAnt <= lentaAnt && rapidaAgora > lentaAgora) {
            sinal = "BUY";
        } else if (rapidaAnt >= lentaAnt && rapidaAgora < lentaAgora) {
            sinal = "SELL";
        }
        System.out.println("Sinal: " + sinal);
    }
}
