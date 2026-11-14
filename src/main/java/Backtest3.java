import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;

import java.time.Duration;
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

    static void analisar(String nome, BarSeries s) {
        ClosePriceIndicator fecho = new ClosePriceIndicator(s);
        int n = s.getBarCount();
        double[] c = new double[n];
        for (int i = 0; i < n; i++) c[i] = fecho.getValue(i).doubleValue();

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

        // referência: a estratégia que o bot usa agora (20/50, sem filtro, stop 2% e sem alvo)
        for (Linha l : linhas) {
            Params p = l.p();
            if (p.rapida() == 20 && p.lenta() == 50 && !p.filtro() && p.stop() == 2 && p.alvo() == 0) {
                System.out.println("\nESTRATÉGIA ATUAL DO BOT (20/50, stop 2%):");
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

    public static void main(String[] args) throws Exception {
        System.out.println("A descarregar dados (pode demorar um minuto)...\n");
        BarSeries h4 = Historico.carregar("BTCUSDT", "4h", Duration.ofHours(4), 1800);
        BarSeries diario = Historico.carregar("BTCUSDT", "1d", Duration.ofDays(1), 1800);

        analisar("4 HORAS", h4);
        analisar("DIÁRIO", diario);
    }
}