import com.fasterxml.jackson.databind.JsonNode;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

/**
 * Preenche a tabela candles com as velas fechadas dos últimos N dias.
 * Pode correr as vezes que quiser: velas que já existem são ignoradas
 * (UNIQUE + ON CONFLICT DO NOTHING).
 *
 * Usar só com intervalos grandes (1h, 4h, 1d). Em 1m, 90 dias são ~130 mil velas.
 */
public class Backfill {

    static int preencher(String simbolo, String intervalo, int dias) throws Exception {
        long agora = System.currentTimeMillis();
        long inicio = Instant.now().minus(Duration.ofDays(dias)).toEpochMilli();

        String sql = "INSERT INTO candles (symbol, intervalo, fecho_em, abertura, maximo, minimo, fecho, volume) "
                + "VALUES (?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING";
        int novas = 0;

        try (Connection c = Db.ligar(); PreparedStatement ps = c.prepareStatement(sql)) {
            while (inicio < agora) {
                String url = "https://api.binance.com/api/v3/klines?symbol=" + simbolo
                        + "&interval=" + intervalo
                        + "&startTime=" + inicio + "&limit=1000";
                HttpRequest req = HttpRequest.newBuilder(URI.create(url)).GET().build();
                String corpo = Testnet.CLIENT.send(req, HttpResponse.BodyHandlers.ofString()).body();
                JsonNode velas = Testnet.MAPPER.readTree(corpo);

                if (!velas.isArray()) {
                    throw new IllegalStateException("Resposta inesperada da Binance: " + corpo);
                }
                if (velas.size() == 0) break;

                for (JsonNode k : velas) {
                    long fecho = k.get(6).asLong();
                    if (fecho > agora) continue; // ignora a vela ainda aberta
                    ps.setString(1, simbolo);
                    ps.setString(2, intervalo);
                    ps.setTimestamp(3, Timestamp.from(Instant.ofEpochMilli(fecho)));
                    ps.setBigDecimal(4, new java.math.BigDecimal(k.get(1).asText()));
                    ps.setBigDecimal(5, new java.math.BigDecimal(k.get(2).asText()));
                    ps.setBigDecimal(6, new java.math.BigDecimal(k.get(3).asText()));
                    ps.setBigDecimal(7, new java.math.BigDecimal(k.get(4).asText()));
                    ps.setBigDecimal(8, new java.math.BigDecimal(k.get(5).asText()));
                    ps.addBatch();
                }
                for (int n : ps.executeBatch()) {
                    if (n != 0) novas++; // 0 = já existia
                }

                inicio = velas.get(velas.size() - 1).get(6).asLong() + 1;
                Thread.sleep(250); // pausa curta para não sobrecarregar a API
            }
        }
        return novas;
    }

    // para correr à mão: mvn compile exec:java "-Dexec.mainClass=Backfill"
    public static void main(String[] args) throws Exception {
        int n = preencher("BTCUSDT", "4h", 90);
        System.out.println("Backfill concluído: " + n + " velas novas gravadas.");
    }
}