import com.fasterxml.jackson.databind.JsonNode;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * Bot multi-mercado (Alpaca PAPER trading): Bitcoin, S&P 500, NASDAQ, Ouro e Petróleo. Nada mais.
 * Cada mercado usa a estratégia e o timeframe definidos em Mercados.java (ver Estrategias.java).
 * A Alpaca é a fonte de verdade para saber se há posição aberta.
 */
public class BotMulti {

    // ---- Parâmetros (podes mexer aqui) ----
    static final double VALOR_USD = 1000.0;   // por compra, em dólares de simulação
    static final double STOP_LOSS = 0.10;     // rede de segurança (o backtest não usa stop-loss)
    static final int VELAS_NECESSARIAS = 450; // histórico lido a cada vela nova
    static final long ESPERA_MS = Long.parseLong(System.getenv().getOrDefault("ESPERA_MS", "60000"));

    static final Path LOG = Path.of("botmulti.log");
    static final Path ESTADO = Path.of("estado-multi.properties");
    static final Properties estado = new Properties();

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

    static void carregarEstado() {
        if (!Files.exists(ESTADO)) return;
        try (Reader r = Files.newBufferedReader(ESTADO)) {
            estado.load(r);
        } catch (Exception e) {
            log("AVISO: não foi possível ler o estado: " + e.getMessage());
        }
    }

    static void guardarEstado() {
        try (Writer w = Files.newBufferedWriter(ESTADO)) {
            estado.store(w, "estado do BotMulti");
        } catch (Exception e) {
            log("AVISO: não foi possível guardar o estado: " + e.getMessage());
        }
    }

    // ---- Base de dados: se falhar, o bot continua a funcionar ----
    static void gravarVela(Mercados.Mercado m, Alpaca.Barra b) {
        try {
            Estrategias.Config cfg = m.cfg();
            Db.guardarVela(m.simbolo(), cfg.intervaloBd(), b.inicio().plus(cfg.duracao()).minusMillis(1),
                    b.abertura(), b.maximo(), b.minimo(), b.fecho(), b.volume());
        } catch (Exception e) {
            log("AVISO: não foi possível gravar a vela na BD: " + e.getMessage());
        }
    }

    static void gravarTrade(Mercados.Mercado m, String lado, double qtd, double preco, String orderId) {
        try {
            Db.guardarTrade(m.simbolo(), lado, qtd, preco, qtd * preco, orderId, "ALPACA_PAPER");
        } catch (Exception e) {
            log("AVISO: não foi possível gravar o trade na BD: " + e.getMessage());
        }
    }

    /** Dias de calendário a pedir para ter VELAS_NECESSARIAS velas (estimativa por baixo). */
    static int diasHistorico(Mercados.Mercado m) {
        double velasPorDia = switch (m.cfg().timeframe()) {
            case "1Hour" -> m.cripto() ? 24 : 4.5;
            case "4Hour" -> m.cripto() ? 6 : 1.3;
            default -> m.cripto() ? 1 : 0.68;
        };
        return (int) Math.ceil(VELAS_NECESSARIAS / velasPorDia) + 10;
    }

    static void comprar(Mercados.Mercado m) throws Exception {
        JsonNode o = Alpaca.comprar(m.simbolo(), VALOR_USD, m.cripto());
        if (!o.path("status").asText().equals("filled")) {
            log("AVISO: compra de " + m.simbolo() + " não preenchida (estado: " + o.path("status").asText() + ")");
            return;
        }
        double qtd = o.path("filled_qty").asDouble();
        double preco = o.path("filled_avg_price").asDouble();
        log(String.format(Locale.US, "COMPRA %s (%s): %.6f a ~%.2f USD", m.simbolo(), m.nome(), qtd, preco));
        gravarTrade(m, "BUY", qtd, preco, o.path("id").asText());
    }

    static void vender(Mercados.Mercado m, JsonNode pos) throws Exception {
        double entrada = pos.path("avg_entry_price").asDouble();
        JsonNode o = Alpaca.fechar(m);
        if (!o.path("status").asText().equals("filled")) {
            log("AVISO: venda de " + m.simbolo() + " não preenchida (estado: " + o.path("status").asText() + ")");
            return;
        }
        double qtd = o.path("filled_qty").asDouble();
        double preco = o.path("filled_avg_price").asDouble();
        log(String.format(Locale.US, "VENDA %s (%s): %.6f a ~%.2f USD | resultado %.2f USD",
                m.simbolo(), m.nome(), qtd, preco, (preco - entrada) * qtd));
        gravarTrade(m, "SELL", qtd, preco, o.path("id").asText());
    }

    /** Executa a ordem pendente (sinal dado com a bolsa fechada) assim que se puder negociar. */
    static void executarPendente(Mercados.Mercado m, boolean podeNegociar, JsonNode pos) throws Exception {
        String chave = "pendente." + m.simbolo();
        String pendente = estado.getProperty(chave);
        if (pendente == null || !podeNegociar) return;

        if (pendente.equals("COMPRAR") && pos == null) comprar(m);
        else if (pendente.equals("VENDER") && pos != null) vender(m, pos);

        estado.remove(chave); // não repete: a posição na Alpaca decide o que acontece a seguir
        guardarEstado();
    }

    static void ciclo(Mercados.Mercado m, boolean podeNegociar) throws Exception {
        Estrategias.Config cfg = m.cfg();
        JsonNode pos = Alpaca.posicao(m);

        // 1) stop-loss de segurança: só quando se pode negociar
        if (pos != null && podeNegociar) {
            double entrada = pos.path("avg_entry_price").asDouble();
            double atual = pos.path("current_price").asDouble();
            if (entrada > 0 && atual > 0 && atual <= entrada * (1 - STOP_LOSS)) {
                log(String.format(Locale.US, "STOP-LOSS em %s (entrada %.2f, atual %.2f)", m.simbolo(), entrada, atual));
                vender(m, pos);
                estado.remove("pendente." + m.simbolo());
                guardarEstado();
                return;
            }
        }

        // 2) ordens pendentes de sinais anteriores
        executarPendente(m, podeNegociar, pos);
        if (estado.getProperty("pendente." + m.simbolo()) == null) {
            pos = Alpaca.posicao(m); // a posição pode ter mudado
        }

        // 3) velas fechadas (descarta a que ainda está em curso)
        Instant agora = Instant.now();
        List<Alpaca.Barra> todas = Alpaca.barras(m, cfg.timeframe(), agora.minus(Duration.ofDays(diasHistorico(m))));
        List<Alpaca.Barra> barras = new ArrayList<>();
        for (Alpaca.Barra b : todas) {
            if (!b.inicio().plus(cfg.duracao()).isAfter(agora)) barras.add(b);
        }
        if (barras.size() < Estrategias.AQUECIMENTO + 2) {
            log("AVISO: poucas velas para " + m.simbolo() + " (" + barras.size() + ")");
            return;
        }

        int ultima = barras.size() - 1;
        Instant inicioUltima = barras.get(ultima).inicio();
        String chaveUltima = "ultima." + m.simbolo();
        String anterior = estado.getProperty(chaveUltima);
        if (inicioUltima.toString().equals(anterior)) return; // esta vela já foi avaliada

        // grava na BD: histórico todo no arranque, só a vela nova nas voltas seguintes
        if (anterior == null) {
            for (Alpaca.Barra b : barras) gravarVela(m, b);
        } else {
            gravarVela(m, barras.get(ultima));
        }
        estado.setProperty(chaveUltima, inicioUltima.toString());

        double[] fechos = new double[barras.size()];
        for (int i = 0; i < fechos.length; i++) fechos[i] = barras.get(i).fecho();

        boolean emPosicao = pos != null;
        int decisao = Estrategias.decisao(fechos, cfg, emPosicao);
        log(String.format(Locale.US, "Vela fechada %s | %s %s | preço %.2f | decisão: %s | em posição: %s",
                m.simbolo(), cfg.timeframe(), inicioUltima.toString().substring(0, 16).replace('T', ' '),
                fechos[ultima], decisao == 1 ? "COMPRAR" : decisao == -1 ? "VENDER" : "nada", emPosicao));

        if (decisao == 1) estado.setProperty("pendente." + m.simbolo(), "COMPRAR");
        else if (decisao == -1) estado.setProperty("pendente." + m.simbolo(), "VENDER");
        guardarEstado();
        executarPendente(m, podeNegociar, pos);
    }

    public static void main(String[] args) throws Exception {
        if (System.getenv("ALPACA_KEY") == null || System.getenv("ALPACA_SECRET") == null) {
            System.out.println("Faltam as variáveis ALPACA_KEY e ALPACA_SECRET (chaves da conta PAPER).");
            return;
        }

        carregarEstado();
        JsonNode conta = Alpaca.conta(); // falha logo aqui se as chaves estiverem erradas
        log("BotMulti iniciado | conta " + conta.path("status").asText() + " | capital "
                + conta.path("equity").asText() + " USD (simulação)");
        log("Mercados permitidos (e só estes):");
        for (Mercados.Mercado m : Mercados.LISTA) {
            log("  " + m.simbolo() + " (" + m.nome() + ") -> "
                    + (m.cfg() == null ? "sem estratégia (só observa)"
                    : m.cfg().timeframe() + ", " + m.cfg().descricao()));
        }

        while (true) {
            boolean bolsaAberta;
            try {
                bolsaAberta = Alpaca.mercadoAberto();
            } catch (Exception e) {
                log("AVISO: não foi possível ler o horário da bolsa: " + e.getMessage());
                bolsaAberta = false;
            }
            for (Mercados.Mercado m : Mercados.LISTA) {
                if (m.cfg() == null) continue;
                try {
                    ciclo(m, m.cripto() || bolsaAberta);
                } catch (Exception e) {
                    log("ERRO em " + m.simbolo() + ": " + e);
                }
            }
            Thread.sleep(ESPERA_MS);
        }
    }
}