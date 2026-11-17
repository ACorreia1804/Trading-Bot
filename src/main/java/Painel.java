import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Painel web do bot: gráficos de velas dos 5 mercados, estado das posições e conselhos do bot.
 * Só lê (base de dados e conta paper da Alpaca): não envia ordens.
 *
 * Abre em http://localhost:8080 (PAINEL_PORTA para mudar).
 * Variáveis: DB_URL, POSTGRES_USER (bot), POSTGRES_PASSWORD, ALPACA_KEY, ALPACA_SECRET (opcionais, só para
 * mostrar a conta e as posições), PAINEL_BIND (127.0.0.1 por defeito; 0.0.0.0 dentro do Docker).
 */
public class Painel {

    // ---------------------------------------------------------------- dados

    record Vela(long tempo, double abertura, double maximo, double minimo, double fecho, double volume) {}

    record Ordem(long tempo, String simbolo, String lado, double qtd, double preco, double valor, String ambiente) {}

    record Fechados(int total, double resultado) {}

    /** Acesso aos dados (separado para se poder testar sem base de dados). */
    interface Dados {
        List<Vela> velas(Mercados.Mercado m, int limite) throws Exception;

        List<Ordem> ordens(int limite) throws Exception;

        Map<String, Fechados> fechados() throws Exception;
    }

    static final String DB_URL = System.getenv().getOrDefault("DB_URL", "jdbc:postgresql://localhost:5432/trading");
    static final String DB_USER = System.getenv().getOrDefault("POSTGRES_USER", "bot");
    static final String DB_PASS = System.getenv("POSTGRES_PASSWORD");

    static String simbolosSql() {
        StringBuilder sb = new StringBuilder();
        for (Mercados.Mercado m : Mercados.LISTA) {
            if (sb.length() > 0) sb.append(",");
            sb.append("'").append(m.simbolo().replace("'", "")).append("'");
        }
        return sb.toString();
    }

    static class DadosPg implements Dados {
        private Connection ligar() throws Exception {
            if (DB_PASS == null) throw new IllegalStateException("Falta a variável de ambiente POSTGRES_PASSWORD");
            return DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
        }

        @Override
        public List<Vela> velas(Mercados.Mercado m, int limite) throws Exception {
            Duration dur = m.cfg().duracao();
            String sql = "SELECT fecho_em, abertura::float8, maximo::float8, minimo::float8, fecho::float8, volume::float8 "
                    + "FROM candles WHERE symbol = ? AND intervalo = ? ORDER BY fecho_em DESC LIMIT ?";
            List<Vela> r = new ArrayList<>();
            try (Connection c = ligar(); PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, m.simbolo());
                ps.setString(2, m.cfg().intervaloBd());
                ps.setInt(3, limite);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Timestamp fim = rs.getTimestamp(1);
                        long abertura = fim.toInstant().minus(dur).plusMillis(1).getEpochSecond();
                        r.add(new Vela(abertura, rs.getDouble(2), rs.getDouble(3), rs.getDouble(4),
                                rs.getDouble(5), rs.getDouble(6)));
                    }
                }
            }
            Collections.reverse(r);
            return r;
        }

        @Override
        public List<Ordem> ordens(int limite) throws Exception {
            String sql = "SELECT executado_em, symbol, lado, quantidade::float8, preco::float8, valor_usdt::float8, ambiente "
                    + "FROM trades WHERE symbol IN (" + simbolosSql() + ") ORDER BY executado_em DESC, id DESC LIMIT ?";
            List<Ordem> r = new ArrayList<>();
            try (Connection c = ligar(); PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setInt(1, limite);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        r.add(new Ordem(rs.getTimestamp(1).toInstant().getEpochSecond(), rs.getString(2), rs.getString(3),
                                rs.getDouble(4), rs.getDouble(5), rs.getDouble(6), rs.getString(7)));
                    }
                }
            }
            return r;
        }

        @Override
        public Map<String, Fechados> fechados() throws Exception {
            String sql = "WITH r AS (SELECT symbol, lado, valor_usdt::float8 - LAG(valor_usdt::float8) "
                    + "OVER (PARTITION BY symbol ORDER BY id) AS res FROM trades WHERE symbol IN (" + simbolosSql() + ")) "
                    + "SELECT symbol, count(*) FILTER (WHERE lado = 'SELL'), COALESCE(SUM(res) FILTER (WHERE lado = 'SELL'), 0) "
                    + "FROM r GROUP BY symbol";
            Map<String, Fechados> m = new HashMap<>();
            try (Connection c = ligar(); PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) m.put(rs.getString(1), new Fechados(rs.getInt(2), rs.getDouble(3)));
            }
            return m;
        }
    }

    static Dados dados = new DadosPg();
    static final ObjectMapper MAPPER = new ObjectMapper();
    static final Locale PT = Locale.forLanguageTag("pt-PT");

    // ---------------------------------------------------------------- Alpaca (só leitura, em cache 20 s)

    static long alpacaTs = 0;
    static JsonNode alpacaConta = null;
    static Map<String, JsonNode> alpacaPosicoes = null; // null = não foi possível ler
    static Boolean bolsaAberta = null;

    static synchronized void atualizarAlpaca() {
        if (System.currentTimeMillis() - alpacaTs < 20_000) return;
        alpacaTs = System.currentTimeMillis();
        alpacaConta = null;
        alpacaPosicoes = null;
        bolsaAberta = null;
        try {
            alpacaConta = Alpaca.conta();
        } catch (Throwable e) {
            return; // sem chaves ou sem rede: o painel funciona só com a base de dados
        }
        try {
            JsonNode lista = Alpaca.pedido("GET", Alpaca.TRADING + "/v2/positions", null);
            Map<String, JsonNode> mapa = new HashMap<>();
            if (lista.isArray()) {
                for (JsonNode p : lista) mapa.put(p.path("symbol").asText().replace("/", ""), p);
            }
            alpacaPosicoes = mapa;
        } catch (Throwable e) {
            alpacaPosicoes = null;
        }
        try {
            bolsaAberta = Alpaca.mercadoAberto();
        } catch (Throwable e) {
            bolsaAberta = null;
        }
    }

    // ---------------------------------------------------------------- preço ao vivo (último negócio, em cache 10 s)

    static final Map<String, double[]> vivo = new HashMap<>(); // símbolo -> {preço, momento}

    /** Último preço negociado na Alpaca, ou null se não for possível ler (o painel usa então o fecho da última vela). */
    static synchronized Double precoAoVivo(Mercados.Mercado m) {
        long agora = System.currentTimeMillis();
        double[] c = vivo.get(m.simbolo());
        if (c != null && agora - (long) c[1] < 10_000) return c[0] > 0 ? c[0] : null;
        double p = 0;
        try {
            String simbolo = URLEncoder.encode(m.simbolo(), StandardCharsets.UTF_8);
            if (m.cripto()) {
                JsonNode r = Alpaca.pedido("GET", Alpaca.DATA + "/v1beta3/crypto/us/latest/trades?symbols=" + simbolo, null);
                p = r.path("trades").path(m.simbolo()).path("p").asDouble();
            } else {
                JsonNode r = Alpaca.pedido("GET", Alpaca.DATA + "/v2/stocks/" + simbolo + "/trades/latest?feed=" + Alpaca.FEED, null);
                p = r.path("trade").path("p").asDouble();
            }
        } catch (Throwable e) {
            p = 0;
        }
        vivo.put(m.simbolo(), new double[]{p, agora});
        return p > 0 ? p : null;
    }

    // ---------------------------------------------------------------- conselhos

    static String f(String formato, Object... args) {
        return String.format(PT, formato, args);
    }

    /** {ação, explicação}. Ação: COMPRAR, VENDER, MANTER ou ESPERAR. É o que o bot faria agora com a última vela. */
    static String[] conselho(Estrategias.Config cfg, double[] c, boolean emPosicao) {
        if (c.length < Estrategias.AQUECIMENTO + 2) {
            return new String[]{"SEM DADOS", "Ainda há poucas velas na base de dados (" + c.length
                    + "). O bot grava o histórico quando arranca."};
        }
        int dec = Estrategias.decisao(c, cfg, emPosicao);
        String acao = emPosicao ? (dec == -1 ? "VENDER" : "MANTER") : (dec == 1 ? "COMPRAR" : "ESPERAR");
        int u = c.length - 1;
        String t;
        switch (cfg.familia()) {
            case TENDENCIA -> {
                double rap = Estrategias.sma(c, cfg.a())[u], len = Estrategias.sma(c, cfg.b())[u];
                boolean alta = rap > len;
                t = f("Média rápida (SMA %d) em %,.2f, média lenta (SMA %d) em %,.2f: tendência de %s. ",
                        cfg.a(), rap, cfg.b(), len, alta ? "alta" : "baixa");
                t += switch (acao) {
                    case "COMPRAR" -> "A rápida acabou de cruzar para cima da lenta: o bot compra.";
                    case "VENDER" -> "A rápida caiu abaixo da lenta: o bot vende.";
                    case "MANTER" -> "Enquanto a rápida estiver acima da lenta, o bot mantém a posição.";
                    default -> alta ? "A tendência de alta já vai em curso. O bot só entra num novo cruzamento."
                            : "Enquanto a rápida estiver abaixo da lenta, o bot não compra.";
                };
            }
            case MOMENTUM -> {
                double ret = c[u] / c[u - cfg.a()] - 1;
                t = f("Retorno das últimas %d velas: %+.2f%%. ", cfg.a(), ret * 100);
                t += switch (acao) {
                    case "COMPRAR" -> "O retorno passou a positivo: o bot compra.";
                    case "VENDER" -> "O retorno deixou de ser positivo: o bot vende.";
                    case "MANTER" -> "Enquanto o retorno for positivo, o bot mantém a posição.";
                    default -> ret > 0 ? "Já é positivo, mas o bot só entra quando passa de negativo para positivo."
                            : "Retorno negativo: o bot não compra.";
                };
            }
            default -> {
                double rsi = Estrategias.rsi(c, 2)[u], s5 = Estrategias.sma(c, 5)[u];
                t = f("RSI(2) em %.1f (o bot compra abaixo de %d). Preço %,.2f, média de 5 velas %,.2f. ",
                        rsi, cfg.a(), c[u], s5);
                t += switch (acao) {
                    case "COMPRAR" -> "O preço está sobrevendido: o bot compra à espera de uma recuperação.";
                    case "VENDER" -> "O preço recuperou acima da média de 5 velas, ou o tempo máximo acabou: o bot sai.";
                    case "MANTER" -> f("À espera que o preço passe a média de 5 velas (no máximo %d velas).", cfg.b());
                    default -> "Sem sobrevenda: o bot espera que o RSI(2) caia abaixo do limite.";
                };
            }
        }
        return new String[]{acao, t};
    }

    static double[] fechos(List<Vela> v) {
        double[] c = new double[v.size()];
        for (int i = 0; i < c.length; i++) c[i] = v.get(i).fecho();
        return c;
    }

    // ---------------------------------------------------------------- API

    static ObjectNode estado() throws Exception {
        atualizarAlpaca();
        List<Ordem> ordens = dados.ordens(50);
        Map<String, Fechados> fech = dados.fechados();

        ObjectNode raiz = MAPPER.createObjectNode();
        raiz.put("atualizado", System.currentTimeMillis() / 1000);
        raiz.put("alpaca", alpacaConta != null);
        if (alpacaConta != null) {
            ObjectNode c = raiz.putObject("conta");
            c.put("estado", alpacaConta.path("status").asText());
            c.put("equity", alpacaConta.path("equity").asDouble());
            c.put("ultimoEquity", alpacaConta.path("last_equity").asDouble());
            c.put("dinheiro", alpacaConta.path("cash").asDouble());
        }
        if (bolsaAberta != null) raiz.put("bolsaAberta", bolsaAberta);

        ArrayNode lista = raiz.putArray("mercados");
        for (Mercados.Mercado m : Mercados.LISTA) {
            ObjectNode o = lista.addObject();
            o.put("simbolo", m.simbolo());
            o.put("nome", m.nome());
            o.put("cripto", m.cripto());
            Fechados fm = fech.get(m.simbolo());
            o.put("fechados", fm == null ? 0 : fm.total());
            o.put("resultado", fm == null ? 0 : fm.resultado());
            if (m.cfg() == null) {
                o.put("acao", "SEM ESTRATÉGIA");
                o.put("texto", "Nenhuma estratégia deu lucro na validação: o bot só observa este mercado.");
                continue;
            }
            o.put("tf", m.cfg().intervaloBd());
            o.put("estrategia", m.cfg().descricao());
            List<Vela> v = dados.velas(m, 460);
            if (!v.isEmpty()) {
                Vela ult = v.get(v.size() - 1);
                Double aoVivo = precoAoVivo(m);
                double preco = aoVivo != null ? aoVivo : ult.fecho();
                o.put("preco", preco);
                o.put("aoVivo", aoVivo != null);
                o.put("tempo", ult.tempo());
                if (aoVivo != null) o.put("variacao", preco / ult.fecho() - 1); // desde o fecho da última vela
                else if (v.size() > 1) o.put("variacao", ult.fecho() / v.get(v.size() - 2).fecho() - 1);
            }

            JsonNode pos = alpacaPosicoes == null ? null : alpacaPosicoes.get(m.idPosicao());
            boolean emPosicao;
            if (alpacaPosicoes != null) {
                emPosicao = pos != null;
            } else {
                emPosicao = false;
                for (Ordem or : ordens) { // a mais recente primeiro
                    if (or.simbolo().equals(m.simbolo())) {
                        emPosicao = or.lado().equals("BUY");
                        break;
                    }
                }
            }
            o.put("emPosicao", emPosicao);
            if (pos != null) {
                ObjectNode p = o.putObject("posicao");
                double entrada = pos.path("avg_entry_price").asDouble();
                p.put("qtd", pos.path("qty").asDouble());
                p.put("entrada", entrada);
                p.put("atual", pos.path("current_price").asDouble());
                p.put("pl", pos.path("unrealized_pl").asDouble());
                p.put("plpct", pos.path("unrealized_plpc").asDouble());
                p.put("stop", entrada * (1 - BotMulti.STOP_LOSS));
            }
            String[] cons = conselho(m.cfg(), fechos(v), emPosicao);
            o.put("acao", cons[0]);
            o.put("texto", cons[1]);
        }

        ArrayNode ao = raiz.putArray("ordens");
        for (Ordem or : ordens) {
            ObjectNode n = ao.addObject();
            n.put("tempo", or.tempo());
            n.put("simbolo", or.simbolo());
            n.put("lado", or.lado());
            n.put("qtd", or.qtd());
            n.put("preco", or.preco());
            n.put("valor", or.valor());
            n.put("ambiente", or.ambiente());
        }
        return raiz;
    }

    static ObjectNode velas(String simbolo) throws Exception {
        Mercados.Mercado m = null;
        for (Mercados.Mercado x : Mercados.LISTA) if (x.simbolo().equals(simbolo)) m = x;
        if (m == null || m.cfg() == null) throw new IllegalArgumentException("Mercado desconhecido: " + simbolo);

        List<Vela> v = dados.velas(m, 460);
        ObjectNode raiz = MAPPER.createObjectNode();
        raiz.put("simbolo", m.simbolo());
        raiz.put("nome", m.nome());
        raiz.put("tf", m.cfg().intervaloBd());
        ArrayNode av = raiz.putArray("velas");
        for (Vela x : v) {
            ArrayNode a = av.addArray();
            a.add(x.tempo()).add(x.abertura()).add(x.maximo()).add(x.minimo()).add(x.fecho()).add(x.volume());
        }

        double[] c = fechos(v);
        int[] periodos = switch (m.cfg().familia()) {
            case TENDENCIA -> new int[]{m.cfg().a(), m.cfg().b()};
            case REVERSAO -> new int[]{5, 200};
            default -> new int[]{20, 50};
        };
        ArrayNode as = raiz.putArray("medias");
        for (int p : periodos) {
            ObjectNode s = as.addObject();
            s.put("nome", "SMA " + p);
            ArrayNode dd = s.putArray("dados");
            double[] sma = Estrategias.sma(c, p);
            for (int i = 0; i < sma.length; i++) {
                if (!Double.isNaN(sma[i])) dd.addArray().add(v.get(i).tempo()).add(sma[i]);
            }
        }

        ArrayNode ao = raiz.putArray("ordens");
        for (Ordem or : dados.ordens(200)) {
            if (!or.simbolo().equals(m.simbolo())) continue;
            ObjectNode n = ao.addObject();
            n.put("tempo", or.tempo());
            n.put("lado", or.lado());
            n.put("preco", or.preco());
        }
        return raiz;
    }

    // ---------------------------------------------------------------- servidor

    static void responder(HttpExchange ex, int codigo, String tipo, byte[] corpo) throws Exception {
        ex.getResponseHeaders().set("Content-Type", tipo);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(codigo, corpo.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(corpo);
        }
    }

    static void json(HttpExchange ex, int codigo, Object o) throws Exception {
        responder(ex, codigo, "application/json; charset=utf-8", MAPPER.writeValueAsBytes(o));
    }

    static String param(String consulta, String nome) throws Exception {
        if (consulta == null) return null;
        for (String par : consulta.split("&")) {
            int i = par.indexOf('=');
            if (i > 0 && par.substring(0, i).equals(nome)) {
                return URLDecoder.decode(par.substring(i + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    static void tratar(HttpExchange ex) {
        try {
            if (!ex.getRequestMethod().equals("GET")) {
                json(ex, 405, Map.of("erro", "só GET"));
                return;
            }
            String caminho = ex.getRequestURI().getPath();
            switch (caminho) {
                case "/" -> responder(ex, 200, "text/html; charset=utf-8", PainelHtml.HTML.getBytes(StandardCharsets.UTF_8));
                case "/api/estado" -> json(ex, 200, estado());
                case "/api/velas" -> json(ex, 200, velas(param(ex.getRequestURI().getRawQuery(), "simbolo")));
                default -> json(ex, 404, Map.of("erro", "não encontrado"));
            }
        } catch (Throwable e) {
            try {
                System.out.println("ERRO " + ex.getRequestURI() + ": " + e);
                json(ex, 500, Map.of("erro", String.valueOf(e.getMessage())));
            } catch (Exception ignorar) {
                // a ligação já fechou
            }
        } finally {
            ex.close();
        }
    }

    public static void main(String[] args) throws Exception {
        int porta = Integer.parseInt(System.getenv().getOrDefault("PAINEL_PORTA", "8080"));
        String bind = System.getenv().getOrDefault("PAINEL_BIND", "127.0.0.1");
        HttpServer servidor = HttpServer.create(new InetSocketAddress(bind, porta), 0);
        servidor.createContext("/", Painel::tratar);
        servidor.setExecutor(Executors.newFixedThreadPool(4));
        servidor.start();
        System.out.println("Painel a correr em http://localhost:" + porta + " (só leitura)");
    }
}