import java.util.List;

/**
 * Lista FECHADA dos únicos mercados que o bot pode negociar:
 * Bitcoin, NASDAQ, Petróleo, Ouro e S&P 500. Mais nenhum.
 *
 * Via Alpaca (paper trading) o Bitcoin negoceia-se diretamente (BTC/USD) e os outros
 * quatro através de ETFs que seguem o índice ou a matéria-prima:
 *   SPY = S&P 500 | QQQ = NASDAQ-100 | GLD = ouro | USO = petróleo (WTI)
 *
 * Cada mercado tem a sua estratégia (família + timeframe + parâmetros). Se for null, o bot
 * não negoceia esse mercado (só observa).
 *
 * As configurações abaixo são PROVISÓRIAS (as hipóteses iniciais: reversão nos índices,
 * momentum no Bitcoin, tendência no ouro e no petróleo). Corre o Backtest5 e substitui-as
 * pelas que ele escolher com base nos dados.
 */
public class Mercados {

    record Mercado(String simbolo, String nome, boolean cripto, Estrategias.Config cfg) {
        /** Símbolo usado nos pedidos de posições (sem barra). */
        String idPosicao() {
            return simbolo.replace("/", "");
        }

        /** Custo assumido por lado (comissão + spread + slippage). Ajusta se for preciso. */
        double custo() {
            return cripto ? 0.0030 : 0.0005;
        }
    }

    // Escolhidas com o Backtest5 (treino/validação/teste). Ver README para a justificação.
    static final List<Mercado> LISTA = List.of(
            new Mercado("BTC/USD", "Bitcoin", true,
                    new Estrategias.Config(Estrategias.Familia.TENDENCIA, "4Hour", 20, 100, 0)),
            new Mercado("SPY", "S&P 500 (ETF SPY)", false,
                    new Estrategias.Config(Estrategias.Familia.REVERSAO, "1Day", 20, 5, 0)),
            new Mercado("QQQ", "NASDAQ-100 (ETF QQQ)", false,
                    new Estrategias.Config(Estrategias.Familia.MOMENTUM, "1Day", 20, 0, 0)),
            new Mercado("GLD", "Ouro (ETF GLD)", false,
                    new Estrategias.Config(Estrategias.Familia.MOMENTUM, "1Hour", 200, 0, 0)),
            new Mercado("USO", "Petróleo (ETF USO)", false,
                    new Estrategias.Config(Estrategias.Familia.REVERSAO, "1Hour", 10, 5, 0)));

    static boolean permitido(String simbolo) {
        for (Mercado m : LISTA) {
            if (m.simbolo().equals(simbolo)) return true;
        }
        return false;
    }
}