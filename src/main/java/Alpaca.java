import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Cliente mínimo da API da Alpaca, SÓ para paper trading (dinheiro de simulação).
 * Variáveis de ambiente: ALPACA_KEY e ALPACA_SECRET (chaves da conta PAPER).
 */
public class Alpaca {

    static final String TRADING = System.getenv().getOrDefault("ALPACA_TRADING_URL", "https://paper-api.alpaca.markets");
    static final String DATA = System.getenv().getOrDefault("ALPACA_DATA_URL", "https://data.alpaca.markets");

    /** Fonte dos dados de ações/ETFs: "iex" (gratuito, só a bolsa IEX) ou "sip" (mercado todo, atrasado na conta grátis). */
    static final String FEED = System.getenv().getOrDefault("ALPACA_FEED", "iex");

    static final HttpClient CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(15)).build();
    static final ObjectMapper MAPPER = new ObjectMapper();

    record Barra(Instant inicio, double abertura, double maximo, double minimo, double fecho, double volume) {}

    static class ErroHttp extends Exception {
        final int estado;

        ErroHttp(int estado, String corpo) {
            super("HTTP " + estado + ": " + corpo);
            this.estado = estado;
        }
    }

    static {
        // segurança: nunca falar com a API de dinheiro real
        boolean paper = TRADING.contains("paper-api.alpaca.markets")
                || TRADING.startsWith("http://localhost") || TRADING.startsWith("http://127.0.0.1");
        if (!paper) {
            throw new IllegalStateException("ALPACA_TRADING_URL tem de ser a API de paper trading: " + TRADING);
        }
    }

    static JsonNode pedido(String metodo, String url, String corpo) throws Exception {
        String chave = System.getenv("ALPACA_KEY");
        String segredo = System.getenv("ALPACA_SECRET");
        if (chave == null || segredo == null) {
            throw new IllegalStateException("Faltam as variáveis ALPACA_KEY e ALPACA_SECRET");
        }
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("APCA-API-KEY-ID", chave)
                .header("APCA-API-SECRET-KEY", segredo)
                .header("accept", "application/json");
        if (corpo != null) {
            b.header("Content-Type", "application/json")
                    .method(metodo, HttpRequest.BodyPublishers.ofString(corpo));
        } else {
            b.method(metodo, HttpRequest.BodyPublishers.noBody());
        }
        HttpResponse<String> r = CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofString());
        String texto = r.body() == null ? "" : r.body();
        if (r.statusCode() >= 400) throw new ErroHttp(r.statusCode(), texto);
        return texto.isBlank() ? MAPPER.createObjectNode() : MAPPER.readTree(texto);
    }

    static JsonNode conta() throws Exception {
        return pedido("GET", TRADING + "/v2/account", null);
    }

    static boolean mercadoAberto() throws Exception {
        return pedido("GET", TRADING + "/v2/clock", null).path("is_open").asBoolean(false);
    }

    /** Barras (velas) fechadas ou em curso desde `desde`, com paginação. */
    static List<Barra> barras(Mercados.Mercado m, String timeframe, Instant desde) throws Exception {
        String inicio = URLEncoder.encode(desde.truncatedTo(ChronoUnit.SECONDS).toString(), StandardCharsets.UTF_8);
        String simbolo = URLEncoder.encode(m.simbolo(), StandardCharsets.UTF_8);
        List<Barra> resultado = new ArrayList<>();
        String pagina = null;

        do {
            String url;
            if (m.cripto()) {
                url = DATA + "/v1beta3/crypto/us/bars?symbols=" + simbolo
                        + "&timeframe=" + timeframe + "&start=" + inicio + "&limit=1000&sort=asc";
            } else {
                url = DATA + "/v2/stocks/" + simbolo + "/bars?timeframe=" + timeframe
                        + "&start=" + inicio + "&limit=1000&adjustment=all&feed=" + FEED + "&sort=asc";
                if (FEED.equals("sip")) {
                    // na conta gratuita, os dados SIP só são permitidos com 15+ minutos de atraso
                    url += "&end=" + URLEncoder.encode(
                            Instant.now().minus(Duration.ofMinutes(20)).truncatedTo(ChronoUnit.SECONDS).toString(),
                            StandardCharsets.UTF_8);
                }
            }
            if (pagina != null) url += "&page_token=" + URLEncoder.encode(pagina, StandardCharsets.UTF_8);

            JsonNode r = pedido("GET", url, null);
            JsonNode lista = m.cripto() ? r.path("bars").path(m.simbolo()) : r.path("bars");
            if (lista.isArray()) {
                for (JsonNode k : lista) {
                    resultado.add(new Barra(Instant.parse(k.get("t").asText()),
                            k.get("o").asDouble(), k.get("h").asDouble(), k.get("l").asDouble(),
                            k.get("c").asDouble(), k.path("v").asDouble()));
                }
            }
            JsonNode prox = r.path("next_page_token");
            pagina = prox.isMissingNode() || prox.isNull() ? null : prox.asText();
            if (pagina != null) Thread.sleep(250);
        } while (pagina != null);

        return resultado;
    }

    /** Posição aberta (ou null se não existe). */
    static JsonNode posicao(Mercados.Mercado m) throws Exception {
        try {
            return pedido("GET", TRADING + "/v2/positions/" + m.idPosicao(), null);
        } catch (ErroHttp e) {
            if (e.estado == 404) return null;
            throw e;
        }
    }

    private static void exigirPermitido(String simbolo) {
        if (!Mercados.permitido(simbolo)) {
            throw new IllegalArgumentException("Mercado não permitido: " + simbolo);
        }
    }

    /** Compra a mercado por um valor em dólares (frações permitidas) e espera o preenchimento. */
    static JsonNode comprar(String simbolo, double valorUsd, boolean cripto) throws Exception {
        exigirPermitido(simbolo);
        ObjectNode corpo = MAPPER.createObjectNode();
        corpo.put("symbol", simbolo);
        corpo.put("notional", String.format(Locale.US, "%.2f", valorUsd));
        corpo.put("side", "buy");
        corpo.put("type", "market");
        corpo.put("time_in_force", cripto ? "gtc" : "day");
        JsonNode ordem = pedido("POST", TRADING + "/v2/orders", MAPPER.writeValueAsString(corpo));
        return aguardar(ordem.path("id").asText());
    }

    /** Fecha a posição inteira (venda a mercado) e espera o preenchimento. */
    static JsonNode fechar(Mercados.Mercado m) throws Exception {
        exigirPermitido(m.simbolo());
        JsonNode r = pedido("DELETE", TRADING + "/v2/positions/" + m.idPosicao(), null);
        JsonNode ordem = r.has("body") ? r.get("body") : r;
        return aguardar(ordem.path("id").asText());
    }

    private static JsonNode aguardar(String id) throws Exception {
        JsonNode o = null;
        for (int i = 0; i < 20; i++) {
            o = pedido("GET", TRADING + "/v2/orders/" + id, null);
            String estado = o.path("status").asText();
            if (estado.equals("filled") || estado.equals("canceled")
                    || estado.equals("rejected") || estado.equals("expired")) break;
            Thread.sleep(1000);
        }
        return o;
    }
}