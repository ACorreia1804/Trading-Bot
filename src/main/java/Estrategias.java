import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Três famílias de estratégia, todas "long only" (compra ou fica de fora), definidas como
 * uma POSIÇÃO-ALVO a cada vela fechada. O backtest e o bot usam exatamente este código,
 * por isso o que se testa é o que corre.
 *
 *  - TENDENCIA (trend following): comprado enquanto a média rápida estiver acima da lenta.
 *  - MOMENTUM: comprado enquanto o retorno das últimas N velas for positivo.
 *  - REVERSAO (mean reversion): compra quando o RSI(2) cai abaixo de um limiar (queda exagerada)
 *    e sai quando o preço volta acima da média de 5 velas, ou ao fim de N velas.
 *
 * Regra de execução: o sinal forma-se ao FECHO de uma vela e a ordem executa na vela seguinte.
 * Entra-se na vela em que a posição-alvo passa de "fora" a "dentro"; sai-se quando passa a "fora".
 */
public class Estrategias {

    enum Familia { TENDENCIA, MOMENTUM, REVERSAO }

    /** Velas iniciais ignoradas, para todos os indicadores (até SMA200) estarem calculados. */
    static final int AQUECIMENTO = 201;

    /**
     * TENDENCIA: a = média rápida, b = média lenta.
     * MOMENTUM:  a = nº de velas do retorno.
     * REVERSAO:  a = limiar do RSI(2) para entrar, b = máx. de velas em posição, c = 1 se usa filtro SMA200.
     */
    record Config(Familia familia, String timeframe, int a, int b, int c) {

        Duration duracao() {
            return switch (timeframe) {
                case "1Hour" -> Duration.ofHours(1);
                case "4Hour" -> Duration.ofHours(4);
                case "1Day" -> Duration.ofDays(1);
                default -> throw new IllegalArgumentException("timeframe desconhecido: " + timeframe);
            };
        }

        /** Texto guardado na coluna `intervalo` da tabela candles. */
        String intervaloBd() {
            return switch (timeframe) {
                case "1Hour" -> "1h";
                case "4Hour" -> "4h";
                default -> "1d";
            };
        }

        String descricao() {
            return switch (familia) {
                case TENDENCIA -> "tendência: SMA " + a + "/" + b;
                case MOMENTUM -> "momentum: retorno de " + a + " velas > 0";
                case REVERSAO -> "reversão: RSI(2)<" + a + ", máx " + b + " velas, filtro SMA200 " + (c == 1 ? "sim" : "não");
            };
        }

        String codigoJava() {
            return "new Estrategias.Config(Estrategias.Familia." + familia + ", \"" + timeframe + "\", "
                    + a + ", " + b + ", " + c + ")";
        }
    }

    record Resultado(double retorno, int trades, double acerto, double drawdown) {
        /** Retorno a dividir pelo drawdown (com mínimo de 5%), para não premiar só o risco. */
        double score() {
            return retorno / Math.max(5.0, drawdown);
        }
    }

    // ---------------------------------------------------------------- indicadores

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

    /** RSI com suavização de Wilder. */
    static double[] rsi(double[] c, int n) {
        double[] r = new double[c.length];
        java.util.Arrays.fill(r, Double.NaN);
        double ganho = 0, perda = 0;
        for (int i = 1; i < c.length; i++) {
            double d = c[i] - c[i - 1];
            double g = Math.max(d, 0), p = Math.max(-d, 0);
            if (i <= n) {
                ganho += g;
                perda += p;
                if (i < n) continue;
                ganho /= n;
                perda /= n;
            } else {
                ganho = (ganho * (n - 1) + g) / n;
                perda = (perda * (n - 1) + p) / n;
            }
            r[i] = perda == 0 ? 100 : 100 - 100 / (1 + ganho / perda);
        }
        return r;
    }

    // ---------------------------------------------------------------- posição-alvo

    /** Para cada vela, true se a estratégia quer estar comprada ao fecho dessa vela. */
    static boolean[] alvo(double[] c, Config cfg) {
        int n = c.length;
        boolean[] r = new boolean[n];
        switch (cfg.familia()) {
            case TENDENCIA -> {
                double[] rap = sma(c, cfg.a()), len = sma(c, cfg.b());
                for (int i = 0; i < n; i++) r[i] = !Double.isNaN(len[i]) && rap[i] > len[i];
            }
            case MOMENTUM -> {
                for (int i = cfg.a(); i < n; i++) r[i] = c[i] > c[i - cfg.a()];
            }
            case REVERSAO -> {
                double[] rsi = rsi(c, 2), s5 = sma(c, 5), s200 = sma(c, 200);
                boolean dentro = false;
                int barras = 0;
                for (int i = 0; i < n; i++) {
                    if (!dentro) {
                        boolean valido = !Double.isNaN(rsi[i]) && !Double.isNaN(s200[i]);
                        boolean filtro = cfg.c() == 0 || c[i] > s200[i];
                        if (valido && rsi[i] < cfg.a() && filtro) {
                            dentro = true;
                            barras = 0;
                        }
                    } else {
                        barras++;
                        if (c[i] > s5[i] || barras >= cfg.b()) dentro = false;
                    }
                    r[i] = dentro;
                }
            }
        }
        return r;
    }

    /** Decisão do bot ao fecho da última vela: 1 = comprar, -1 = vender, 0 = nada. */
    static int decisao(double[] fechos, Config cfg, boolean emPosicao) {
        if (fechos.length < AQUECIMENTO + 2) return 0;
        boolean[] a = alvo(fechos, cfg);
        int u = fechos.length - 1;
        if (!emPosicao && a[u] && !a[u - 1]) return 1;
        if (emPosicao && !a[u]) return -1;
        return 0;
    }

    // ---------------------------------------------------------------- simulação

    /**
     * Simula a posição-alvo entre as velas ini e fim. Custo por lado (comissão + slippage).
     * Entradas e saídas ao preço de abertura da vela seguinte ao sinal. Drawdown marcado a mercado.
     */
    static Resultado simular(double[] o, double[] c, boolean[] alvo, double custo, int ini, int fim) {
        double equity = 1.0, equityAntes = 1.0, base = 0, entrada = 0;
        double pico = 1.0, maxDD = 0;
        boolean dentro = false;
        int ordem = 0; // 1 = entrar, -1 = sair (executa ao open desta vela)
        int trades = 0, ganhos = 0;

        for (int i = ini; i <= fim; i++) {
            if (ordem == 1) {
                equityAntes = equity;
                base = equity * (1 - custo);
                entrada = o[i];
                dentro = true;
            } else if (ordem == -1) {
                equity = base * o[i] / entrada * (1 - custo);
                dentro = false;
                trades++;
                if (equity > equityAntes) ganhos++;
            }
            ordem = 0;

            double valor = dentro ? base * c[i] / entrada : equity;
            pico = Math.max(pico, valor);
            maxDD = Math.max(maxDD, (pico - valor) / pico);

            if (i < fim) {
                if (!dentro && alvo[i] && !alvo[i - 1]) ordem = 1;
                else if (dentro && !alvo[i]) ordem = -1;
            }
        }
        if (dentro) { // fecha no fim da janela
            equity = base * c[fim] / entrada * (1 - custo);
            trades++;
            if (equity > equityAntes) ganhos++;
        }
        double acerto = trades > 0 ? 100.0 * ganhos / trades : 0;
        return new Resultado((equity - 1) * 100, trades, acerto, maxDD * 100);
    }

    static double buyHold(double[] c, double custo, int ini, int fim) {
        return (c[fim] / c[ini] - 1 - 2 * custo) * 100;
    }

    // ---------------------------------------------------------------- grelha de parâmetros

    static List<Config> grelha(Familia f, String timeframe) {
        List<Config> l = new ArrayList<>();
        switch (f) {
            case TENDENCIA -> {
                int[][] pares = {{5, 20}, {10, 30}, {10, 50}, {20, 50}, {20, 100}, {50, 200}};
                for (int[] p : pares) l.add(new Config(f, timeframe, p[0], p[1], 0));
            }
            case MOMENTUM -> {
                for (int n : new int[]{5, 10, 20, 50, 100, 200}) l.add(new Config(f, timeframe, n, 0, 0));
            }
            case REVERSAO -> {
                for (int limiar : new int[]{5, 10, 20, 30})
                    for (int maxBarras : new int[]{5, 10})
                        for (int filtro : new int[]{0, 1})
                            l.add(new Config(f, timeframe, limiar, maxBarras, filtro));
            }
        }
        return l;
    }
}