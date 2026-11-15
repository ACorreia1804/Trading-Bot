import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeriesBuilder;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Backtest3: testa variações da estratégia (filtro de tendência, stop-loss, take-profit)
 * com SEPARAÇÃO TREINO/TESTE.
 *
 *  - Treino (primeiros 60% dos dados): é onde "escolhes" a melhor combinação.
 *  - Teste  (últimos 40%): dados que a escolha nunca viu. Se a combinação que
 *    ganhou no treino também ganhar aqui, há alguma esperança. Se não, era overfitting.
 *
 * Custo: 0,15% por lado (0,1% fee + 0,05% slippage). Entradas e saídas ao fecho da vela.
 * Stop-loss e take-profit são verificados ao fecho da vela (o bot real verifica ao minuto,
 * por isso o backtest é um pouco menos reativo).
 */
public class Backtest3 {

    // Allowlist fechada: o bot sÃ³ pode analisar estes cinco mercados.
    static final List<Mercado> MERCADOS_PERMITIDOS = List.of(
            new Mercado("Bitcoin", "BTC-USD"),
            new Mercado("NASDAQ", "^IXIC"),
            new Mercado("Oil", "CL=F"),
            new Mercado("Gold", "GC=F"),
            new Mercado("S&P 500", "^GSPC")
    );

    record Mercado(String nome, String simbolo) {}

    record Timeframe(String nome, String yahooInterval, Duration duracao, int dias) {}

    static final List<Timeframe> TIMEFRAMES = List.of(
            new Timeframe("Diário", "1d", Duration.ofDays(1), 1800),
            new Timeframe("Semanal", "1wk", Duration.ofDays(7), 3650),
            new Timeframe("Mensal", "1mo", Duration.ofDays(30), 3650)
    );

    record Candidato(String estrategia, int parametroA, int parametroB, double limiar,
                     Timeframe timeframe, Resultado treino, Resultado teste) {}

    static final double CUSTO = 0.0015;
    static final int TENDENCIA = 200;   // período da média do filtro de tendência
    static final int AQUECIMENTO = 201; // velas iniciais ignoradas (para todas as médias existirem)

    record Params(int rapida, int lenta, boolean filtro, double stop, double alvo) {
        String nome() {
            return String.format("%2d/%3d | filtro SMA%d: %-3s | stop %3s | alvo %3s",
                    rapida, lenta, TENDENCIA, filtro ? "sim" : "não",
                    stop > 0 ? stop + "%" : "-", alvo > 0 ? alvo + "%" : "-");
        }
    }

    record Resultado(double retorno, int trades, double acerto, double drawdown) {}

    record Linha(Params p, Resultado treino, Resultado teste) {}

    static double[] sma(double[] c, int n) {
        double[] r = new double[c.length];
        double soma = 0;
        for (int i = 0; i < c.length; i++) {
            soma += c[i];
            if (i >= n) soma -= c[i - n];
            r[i] = i >= n - 1 ? soma / n : Double.NaN;
        }
        return r;
    }

    static Resultado simular(double[] c, double[] rap, double[] len, double[] tend,
                             Params p, int ini, int fim) {
        double equity = 1.0, pico = 1.0, maxDD = 0;
        double entrada = 0, base = 0, equityAntes = 1.0;
        boolean dentro = false;
        int trades = 0, ganhos = 0;

        for (int i = ini; i <= fim; i++) {
            if (dentro) {
                equity = base * c[i] / entrada; // marca a mercado a cada vela
                pico = Math.max(pico, equity);
                maxDD = Math.max(maxDD, (pico - equity) / pico);

                boolean cruzouBaixo = rap[i - 1] >= len[i - 1] && rap[i] < len[i];
                boolean stop = p.stop() > 0 && c[i] <= entrada * (1 - p.stop() / 100);
                boolean alvo = p.alvo() > 0 && c[i] >= entrada * (1 + p.alvo() / 100);
                boolean ultima = i == fim; // fecha a posição no fim da janela

                if (cruzouBaixo || stop || alvo || ultima) {
                    equity = equity * (1 - CUSTO);
                    trades++;
                    if (equity > equityAntes) ganhos++;
                    dentro = false;
                    pico = Math.max(pico, equity);
                }
            } else {
                boolean cruzouCima = rap[i - 1] <= len[i - 1] && rap[i] > len[i];
                boolean tendenciaOk = !p.filtro() || c[i] > tend[i];
                if (cruzouCima && tendenciaOk && i < fim) {
                    equityAntes = equity;
                    base = equity * (1 - CUSTO);
                    entrada = c[i];
                    dentro = true;
                }
            }
        }
        double acerto = trades > 0 ? 100.0 * ganhos / trades : 0;
        return new Resultado((equity - 1) * 100, trades, acerto, maxDD * 100);
    }

    static double buyHold(double[] c, int ini, int fim) {
        return (c[fim] / c[ini] - 1 - 2 * CUSTO) * 100;
    }

    /** Carrega dados diÃ¡rios do Yahoo Finance para um mercado permitido. */
    static BarSeries carregarMercado(Mercado mercado, int dias) throws Exception {
        return carregarMercado(mercado, dias, "1d", Duration.ofDays(1));
    }

    static BarSeries carregarMercado(Mercado mercado, int dias, String intervalo,
                                     Duration duracao) throws Exception {
        if (!MERCADOS_PERMITIDOS.contains(mercado)) {
            throw new IllegalArgumentException("Mercado nÃ£o permitido: " + mercado.simbolo());
        }

        long agora = Instant.now().getEpochSecond();
        long inicio = Instant.now().minus(Duration.ofDays(dias)).getEpochSecond();
        String simboloUrl = mercado.simbolo().replace("^", "%5E").replace("=", "%3D");
        String url = "https://query1.finance.yahoo.com/v8/finance/chart/"
                + simboloUrl + "?period1=" + inicio + "&period2=" + agora
                + "&interval=" + intervalo + "&events=history";
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "Mozilla/5.0")
                .GET().build();
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Yahoo Finance devolveu HTTP " + response.statusCode()
                    + " para " + mercado.nome());
        }

        JsonNode result = new ObjectMapper().readTree(response.body())
                .path("chart").path("result").path(0);
        if (result.isMissingNode()) {
            throw new IllegalStateException("Sem dados para " + mercado.nome());
        }
        JsonNode timestamps = result.path("timestamp");
        JsonNode closes = result.path("indicators").path("quote").path(0).path("close");
        BarSeries series = new BaseBarSeriesBuilder().withName(mercado.nome()).build();
        for (int i = 0; i < timestamps.size(); i++) {
            JsonNode close = closes.get(i);
            if (close == null || close.isNull()) continue;
            ZonedDateTime fim = Instant.ofEpochSecond(timestamps.get(i).asLong())
                    .atZone(ZoneId.of("UTC"));
            double preco = close.asDouble();
            series.addBar(duracao, fim, preco, preco, preco, preco, 0);
        }
        return series;
    }

    static void analisar(String nome, BarSeries s) {
        ClosePriceIndicator fecho = new ClosePriceIndicator(s);
        int n = s.getBarCount();
        double[] c = new double[n];
        for (int i = 0; i < n; i++) c[i] = fecho.getValue(i).doubleValue();
        analisar(nome, c);
    }

    static void analisar(String nome, double[] c) {
        int n = c.length;
        int iniTreino = AQUECIMENTO;
        int iniTeste = iniTreino + (int) ((n - iniTreino) * 0.6);
        int fimTreino = iniTeste - 1;
        int fimTeste = n - 1;

        Map<Integer, double[]> medias = new HashMap<>();
        int[] periodos = {5, 10, 20, 30, 50, 100, TENDENCIA};
        for (int per : periodos) medias.put(per, sma(c, per));

        int[][] pares = {{5, 30}, {5, 50}, {5, 100}, {10, 30}, {10, 50}, {10, 100},
                {20, 30}, {20, 50}, {20, 100}};
        boolean[] filtros = {false, true};
        double[] stops = {0, 2, 5};
        double[] alvos = {0, 5, 10};

        List<Linha> linhas = new ArrayList<>();
        for (int[] par : pares)
            for (boolean filtro : filtros)
                for (double stop : stops)
                    for (double alvo : alvos) {
                        Params p = new Params(par[0], par[1], filtro, stop, alvo);
                        double[] rap = medias.get(par[0]);
                        double[] len = medias.get(par[1]);
                        double[] tend = medias.get(TENDENCIA);
                        linhas.add(new Linha(p,
                                simular(c, rap, len, tend, p, iniTreino, fimTreino),
                                simular(c, rap, len, tend, p, iniTeste, fimTeste)));
                    }

        double bhTreino = buyHold(c, iniTreino, fimTreino);
        double bhTeste = buyHold(c, iniTeste, fimTeste);

        System.out.println("==================================================================");
        System.out.printf("%s | %d velas | treino %d velas | teste %d velas%n",
                nome, n, fimTreino - iniTreino + 1, fimTeste - iniTeste + 1);
        System.out.printf("Buy & hold  -> treino %8.1f%%  | teste %8.1f%%%n", bhTreino, bhTeste);
        System.out.println("==================================================================");

        // referência: a estratégia dos bots (20/50, sem filtro, sem alvo; stop 2% ou 5%)
        System.out.println("\nESTRATÉGIA DOS BOTS (20/50, stop 2% e 5%):");
        for (Linha l : linhas) {
            Params p = l.p();
            if (p.rapida() == 20 && p.lenta() == 50 && !p.filtro() && (p.stop() == 2 || p.stop() == 5)
                    && p.alvo() == 0) {
                imprimir(l);
            }
        }

        linhas.sort(Comparator.comparingDouble((Linha l) -> l.treino().retorno()).reversed());

        System.out.println("\nTOP 8 NO TREINO (e o que fizeram no teste):");
        System.out.printf("%-52s | %8s %4s %6s | %8s %4s %6s%n",
                "combinação", "treino%", "ops", "DD%", "teste%", "ops", "DD%");
        for (int i = 0; i < 8 && i < linhas.size(); i++) imprimir(linhas.get(i));

        Linha melhor = linhas.get(0);
        System.out.println("\nSe escolhesses a melhor do treino:");
        System.out.printf("  treino %.1f%% -> teste %.1f%% (buy & hold no teste: %.1f%%)%n",
                melhor.treino().retorno(), melhor.teste().retorno(), bhTeste);

        // visão geral: quantas combinações aguentam no teste?
        List<Double> testes = new ArrayList<>();
        int lucro = 0, batemBH = 0;
        for (Linha l : linhas) {
            double r = l.teste().retorno();
            testes.add(r);
            if (r > 0) lucro++;
            if (r > bhTeste) batemBH++;
        }
        testes.sort(Double::compare);
        double mediana = testes.get(testes.size() / 2);
        System.out.printf("%nTodas as %d combinações no TESTE: com lucro %d (%.0f%%) | "
                        + "batem o buy & hold %d (%.0f%%) | mediana %.1f%%%n%n",
                linhas.size(), lucro, 100.0 * lucro / linhas.size(),
                batemBH, 100.0 * batemBH / linhas.size(), mediana);
    }

    static void imprimir(Linha l) {
        System.out.printf("%-52s | %8.1f %4d %6.1f | %8.1f %4d %6.1f%n",
                l.p().nome(),
                l.treino().retorno(), l.treino().trades(), l.treino().drawdown(),
                l.teste().retorno(), l.teste().trades(), l.teste().drawdown());
    }

    static double desvio(double[] c, int fim, int periodo, double media) {
        double soma = 0;
        for (int i = fim - periodo + 1; i <= fim; i++) {
            double d = c[i] - media;
            soma += d * d;
        }
        return Math.sqrt(soma / periodo);
    }

    static Resultado simularSinais(double[] c, boolean[] entradas, boolean[] saidas,
                                   int ini, int fim) {
        double equity = 1, pico = 1, maxDD = 0, entrada = 0, base = 0, equityAntes = 1;
        boolean dentro = false;
        int trades = 0, ganhos = 0;
        for (int i = ini; i <= fim; i++) {
            if (dentro) {
                equity = base * c[i] / entrada;
                pico = Math.max(pico, equity);
                maxDD = Math.max(maxDD, (pico - equity) / pico);
                if (saidas[i] || i == fim) {
                    equity *= 1 - CUSTO;
                    trades++;
                    if (equity > equityAntes) ganhos++;
                    dentro = false;
                    pico = Math.max(pico, equity);
                }
            } else if (entradas[i] && i < fim) {
                equityAntes = equity;
                base = equity * (1 - CUSTO);
                entrada = c[i];
                dentro = true;
            }
        }
        return new Resultado((equity - 1) * 100, trades,
                trades == 0 ? 0 : 100.0 * ganhos / trades, maxDD * 100);
    }

    static Candidato avaliarCandidato(double[] c, String estrategia, int a, int b,
                                      double limiar, Timeframe timeframe) {
        int n = c.length;
        boolean[] entradas = new boolean[n], saidas = new boolean[n];
        double[] rapida = sma(c, a);
        double[] lenta = b > 0 ? sma(c, b) : rapida;
        int aquecimento = Math.max(a, b) + 1;
        boolean dentro = false;
        for (int i = aquecimento; i < n; i++) {
            boolean entrar;
            boolean sair;
            if (estrategia.equals("Reversão à média")) {
                double desvio = desvio(c, i, a, rapida[i]);
                entrar = desvio > 0 && c[i] < rapida[i] - limiar * desvio;
                sair = c[i] >= rapida[i];
            } else if (estrategia.equals("Momentum")) {
                double retorno = c[i] / c[i - a] - 1;
                entrar = retorno > limiar;
                sair = retorno < 0;
            } else {
                entrar = rapida[i - 1] <= lenta[i - 1] && rapida[i] > lenta[i];
                sair = rapida[i] < lenta[i];
            }
            entradas[i] = !dentro && entrar;
            saidas[i] = dentro && sair;
            if (entradas[i]) dentro = true;
            if (saidas[i]) dentro = false;
        }
        int iniTreino = aquecimento;
        int iniTeste = iniTreino + (int) ((n - iniTreino) * 0.6);
        Resultado treino = simularSinais(c, entradas, saidas, iniTreino, iniTeste - 1);
        Resultado teste = simularSinais(c, entradas, saidas, iniTeste, n - 1);
        return new Candidato(estrategia, a, b, limiar, timeframe, treino, teste);
    }

    static void escolherEstrategia(Mercado mercado, Timeframe timeframe, double[] c) {
        List<Candidato> candidatos = new ArrayList<>();
        for (int periodo : new int[]{10, 20, 30, 50}) {
            for (double limiar : new double[]{0.5, 1.0, 1.5}) {
                candidatos.add(avaliarCandidato(c, "Reversão à média", periodo, 0, limiar, timeframe));
            }
        }
        for (int periodo : new int[]{5, 10, 20, 50}) {
            for (double limiar : new double[]{0.0, 0.02, 0.05}) {
                candidatos.add(avaliarCandidato(c, "Momentum", periodo, 0, limiar, timeframe));
            }
        }
        for (int[] par : new int[][]{{5, 20}, {10, 30}, {20, 50}, {50, 100}}) {
            candidatos.add(avaliarCandidato(c, "Trend following", par[0], par[1], 0, timeframe));
        }
        candidatos.removeIf(x -> x.treino().trades() < 3);
        if (candidatos.isEmpty()) return;
        candidatos.sort(Comparator.comparingDouble((Candidato x) ->
        x.treino().retorno() - 0.5 * x.treino().drawdown()).reversed());
        Candidato melhor = candidatos.get(0);
        System.out.printf("  %-18s | %-15s | treino %7.1f%% DD %5.1f%% | teste %7.1f%% DD %5.1f%% | ops %d/%d%n",
                melhor.estrategia(), melhor.timeframe().nome(), melhor.treino().retorno(),
                melhor.treino().drawdown(), melhor.teste().retorno(), melhor.teste().drawdown(),
                melhor.treino().trades(), melhor.teste().trades());
    }

    static void otimizarMercado(Mercado mercado) throws Exception {
        System.out.println("\n=== " + mercado.nome() + " (" + mercado.simbolo() + ") ===");
        for (Timeframe timeframe : TIMEFRAMES) {
            BarSeries series = carregarMercado(mercado, timeframe.dias(),
                    timeframe.yahooInterval(), timeframe.duracao());
            if (series.getBarCount() < 220) {
                System.out.println("  " + timeframe.nome() + ": dados insuficientes");
                continue;
            }
            double[] c = new double[series.getBarCount()];
            ClosePriceIndicator fecho = new ClosePriceIndicator(series);
            for (int i = 0; i < c.length; i++) c[i] = fecho.getValue(i).doubleValue();
            escolherEstrategia(mercado, timeframe, c);
        }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("A descarregar dados dos cinco mercados permitidos...\n");
        for (Mercado mercado : MERCADOS_PERMITIDOS) {
            try {
                otimizarMercado(mercado);
            } catch (Exception e) {
                System.err.println("Nao foi possivel carregar " + mercado.nome() + ": " + e.getMessage());
            }
        }

    }
}
