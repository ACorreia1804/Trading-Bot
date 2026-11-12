import org.ta4j.core.*;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.rules.CrossedDownIndicatorRule;
import org.ta4j.core.rules.CrossedUpIndicatorRule;
import org.ta4j.core.backtest.BarSeriesManager;

public class Backtest {

    public static void main(String[] args) throws Exception {
        BarSeries series = Estrategia.carregarVelas("BTCUSDT", "1h", 1000);

        ClosePriceIndicator fecho = new ClosePriceIndicator(series);
        SMAIndicator rapida = new SMAIndicator(fecho, 20);
        SMAIndicator lenta = new SMAIndicator(fecho, 50);

        Rule entrada = new CrossedUpIndicatorRule(rapida, lenta);
        Rule saida = new CrossedDownIndicatorRule(rapida, lenta);
        Strategy estrategia = new BaseStrategy(entrada, saida);

        TradingRecord registo = new BarSeriesManager(series).run(estrategia);

        double comissao = 0.001; // 0,1% por operação (compra e venda)
        double capital = 1.0;
        double pico = 1.0;
        double maxDrawdown = 0;
        int ganhos = 0;
        int total = 0;

        for (Position p : registo.getPositions()) {
            double precoEntrada = p.getEntry().getPricePerAsset().doubleValue();
            double precoSaida = p.getExit().getPricePerAsset().doubleValue();

            double retorno = (precoSaida / precoEntrada) - 1 - 2 * comissao;
            capital *= (1 + retorno);
            total++;
            if (retorno > 0) ganhos++;

            pico = Math.max(pico, capital);
            maxDrawdown = Math.max(maxDrawdown, (pico - capital) / pico);

            System.out.printf("Trade %d: entrada %.2f -> saída %.2f | retorno %.2f%%%n",
                    total, precoEntrada, precoSaida, retorno * 100);
        }

        int inicio = 50; // a média lenta só é válida a partir daqui
        int fim = series.getEndIndex();
        double buyHold = fecho.getValue(fim).doubleValue()
                / fecho.getValue(inicio).doubleValue() - 1;

        System.out.println("\n===== RESULTADO =====");
        System.out.println("Velas analisadas: " + series.getBarCount());
        System.out.println("Operações fechadas: " + total);
        if (total > 0) {
            System.out.printf("Taxa de acerto: %.1f%%%n", 100.0 * ganhos / total);
        }
        System.out.printf("Retorno da estratégia: %.2f%%%n", (capital - 1) * 100);
        System.out.printf("Queda máxima (drawdown): %.2f%%%n", maxDrawdown * 100);
        System.out.printf("Retorno buy and hold: %.2f%%%n", buyHold * 100);
    }
}