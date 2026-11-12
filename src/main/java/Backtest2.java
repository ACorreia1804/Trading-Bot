import org.ta4j.core.*;
import org.ta4j.core.backtest.BarSeriesManager;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.rules.CrossedDownIndicatorRule;
import org.ta4j.core.rules.CrossedUpIndicatorRule;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public class Backtest2 {

    static final double COMISSAO = 0.0015; // 0,1% fee + 0,05% slippage por lado

    static void executar(BarSeries series, int r, int l, String rotulo) {
        ClosePriceIndicator fecho = new ClosePriceIndicator(series);
        SMAIndicator rapida = new SMAIndicator(fecho, r);
        SMAIndicator lenta = new SMAIndicator(fecho, l);

        Strategy estrategia = new BaseStrategy(
                new CrossedUpIndicatorRule(rapida, lenta),
                new CrossedDownIndicatorRule(rapida, lenta));
        TradingRecord registo = new BarSeriesManager(series).run(estrategia);

        // lista de trades: {indice entrada, indice saída}
        List<int[]> trades = new ArrayList<>();
        for (Position p : registo.getPositions())
            trades.add(new int[]{p.getEntry().getIndex(), p.getExit().getIndex()});
        Position ab = registo.getCurrentPosition();
        if (ab.isOpened())
            trades.add(new int[]{ab.getEntry().getIndex(), series.getEndIndex()});

        double capital = 1.0, pico = 1.0, maxDD = 0;
        int ganhos = 0, total = 0;

        for (int[] t : trades) {
            double entrada = fecho.getValue(t[0]).doubleValue();
            double antes = capital;
            double base = capital * (1 - COMISSAO);
            for (int i = t[0] + 1; i <= t[1]; i++) { // marca a mercado vela a vela
                double eq = base * fecho.getValue(i).doubleValue() / entrada;
                pico = Math.max(pico, eq);
                maxDD = Math.max(maxDD, (pico - eq) / pico);
            }
            capital = base * fecho.getValue(t[1]).doubleValue() / entrada * (1 - COMISSAO);
            pico = Math.max(pico, capital);
            total++;
            if (capital > antes) ganhos++;
        }

        double buyHold = fecho.getValue(series.getEndIndex()).doubleValue()
                / fecho.getValue(l).doubleValue() - 1 - 2 * COMISSAO;

        System.out.printf("%-28s | velas %5d | ops %3d | acerto %5.1f%% | estratégia %8.2f%% | buy&hold %8.2f%% | drawdown %5.1f%%%n",
                rotulo, series.getBarCount(), total,
                total > 0 ? 100.0 * ganhos / total : 0.0,
                (capital - 1) * 100, buyHold * 100, maxDD * 100);
    }

    static void testarTudo(String nome, BarSeries s, int r, int l) {
        int meio = s.getBarCount() / 2;
        executar(s, r, l, nome + " (total)");
        executar(s.getSubSeries(0, meio), r, l, nome + " (1ª metade)");
        executar(s.getSubSeries(meio, s.getBarCount()), r, l, nome + " (2ª metade)");
        System.out.println();
    }

    public static void main(String[] args) throws Exception {
        System.out.println("A descarregar dados (pode demorar um minuto)...\n");

        BarSeries diario = Historico.carregar("BTCUSDT", "1d", Duration.ofDays(1), 1800);
        BarSeries h4 = Historico.carregar("BTCUSDT", "4h", Duration.ofHours(4), 1095);
        BarSeries h1 = Historico.carregar("BTCUSDT", "1h", Duration.ofHours(1), 730);
        BarSeries m1 = Historico.carregar("BTCUSDT", "1m", Duration.ofMinutes(1), 7200); // ~5 dias

        int[][] pares = {{5, 20}, {10, 50}, {20, 50}, {50, 200}};
        for (int[] p : pares) {
            System.out.println("=== SMA " + p[0] + "/" + p[1] + " ===");
            testarTudo("Diário", diario, p[0], p[1]);
            testarTudo("4 horas", h4, p[0], p[1]);
            testarTudo("1 hora", h1, p[0], p[1]);
            testarTudo("1 minuto", m1, p[0], p[1]);
        }
    }
}