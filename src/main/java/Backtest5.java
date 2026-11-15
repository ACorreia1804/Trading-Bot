import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Backtest5: para CADA mercado (Bitcoin, S&P 500, NASDAQ, Ouro, Petróleo) escolhe, com base nos dados,
 * a família de estratégia (tendência, momentum ou reversão) e o timeframe (1h, 4h ou 1 dia).
 *
 * Para não fazer batota, os dados de cada mercado são divididos no tempo em 3 partes:
 *   TREINO     (50%): para cada família e timeframe, escolhe-se aqui os melhores parâmetros.
 *   VALIDAÇÃO  (25%): escolhe-se aqui a melhor família+timeframe (tem de dar lucro e ter operações).
 *   TESTE      (25%): nunca usado nas escolhas. É o resultado "a sério".
 * Se nada der lucro na validação, o mercado fica SEM estratégia (o bot não negoceia esse mercado).
 *
 * Precisa de ALPACA_KEY e ALPACA_SECRET (os dados históricos são gratuitos na conta paper).
 */
public class Backtest5 {

    static final String[] TIMEFRAMES = {"1Hour", "4Hour", "1Day"};
    static final int MIN_OPS_TREINO = 10;
    static final int MIN_OPS_VALIDACAO = 5;

    record Candidato(String timeframe, Estrategias.Config cfg,
                     Estrategias.Resultado treino, Estrategias.Resultado valid, Estrategias.Resultado teste,
                     double bhValid, double bhTeste) {}

    public static void main(String[] args) throws Exception {
        System.out.println("A descarregar dados da Alpaca (pode demorar alguns minutos)...\n");
        List<String> codigo = new ArrayList<>();

        for (Mercados.Mercado m : Mercados.LISTA) {
            System.out.println("==========================================================================");
            System.out.printf("%s (%s) | custo assumido %.2f%% por lado%n", m.nome(), m.simbolo(), m.custo() * 100);
            System.out.println("==========================================================================");

            List<Candidato> candidatos = new ArrayList<>();
            for (String tf : TIMEFRAMES) {
                try {
                    candidatos.addAll(analisarTimeframe(m, tf));
                } catch (Exception e) {
                    System.out.println("  (" + tf + ": falhou - " + e.getMessage() + ")");
                }
            }

            System.out.printf("%n%-10s %-5s %-47s | %-13s | %-22s | %-22s | %s%n", "família", "tempo",
                    "melhores parâmetros no treino", "treino%  ops", "validação%  ops   DD%",
                    "teste%  ops   DD%", "B&H teste%");
            for (Candidato c : candidatos) {
                System.out.printf("%-10s %-5s %-47s | %7.1f %5d | %9.1f %4d %6.1f | %8.1f %4d %6.1f | %8.1f%n",
                        c.cfg().familia(), c.timeframe().replace("Hour", "h").replace("1Day", "1d"),
                        c.cfg().descricao().replaceAll("^[^:]+: ", ""),
                        c.treino().retorno(), c.treino().trades(),
                        c.valid().retorno(), c.valid().trades(), c.valid().drawdown(),
                        c.teste().retorno(), c.teste().trades(), c.teste().drawdown(), c.bhTeste());
            }

            Optional<Candidato> escolha = candidatos.stream()
                    .filter(c -> c.valid().trades() >= MIN_OPS_VALIDACAO && c.valid().retorno() > 0)
                    .max(Comparator.comparingDouble(c -> c.valid().score()));

            if (escolha.isPresent()) {
                Candidato c = escolha.get();
                System.out.printf("%nESCOLHA (pela validação): %s em %s%n", c.cfg().descricao(), c.timeframe());
                System.out.printf("  Resultado no TESTE (dados nunca usados): %.1f%% com %d operações e drawdown %.1f%%"
                                + " | buy & hold no teste: %.1f%%%n%n",
                        c.teste().retorno(), c.teste().trades(), c.teste().drawdown(), c.bhTeste());
                codigo.add("            new Mercado(\"" + m.simbolo() + "\", \"" + m.nome() + "\", " + m.cripto()
                        + ",\n                    " + c.cfg().codigoJava() + "),");
            } else {
                System.out.println("\nESCOLHA: nenhuma estratégia deu lucro na validação. Este mercado fica SEM estratégia"
                        + " (o bot não o negoceia).\n");
                codigo.add("            new Mercado(\"" + m.simbolo() + "\", \"" + m.nome() + "\", " + m.cripto()
                        + ", null),");
            }
        }

        System.out.println("==========================================================================");
        System.out.println("COLA ISTO NO Mercados.java (substitui a lista LISTA):\n");
        System.out.println("    static final List<Mercado> LISTA = List.of(");
        for (int i = 0; i < codigo.size(); i++) {
            String linha = codigo.get(i);
            if (i == codigo.size() - 1) linha = linha.substring(0, linha.length() - 1); // sem vírgula final
            System.out.println(linha);
        }
        System.out.println("    );\n");
        System.out.println("Cuidados: cada mercado teve 9 candidatos (3 famílias x 3 timeframes), por isso alguma sorte entra");
        System.out.println("na escolha. O resultado do TESTE é uma única amostra, e os dados cobrem poucos ciclos de mercado.");
        System.out.println("Desconfia de resultados com poucas operações. Isto é simulação: não é aconselhamento financeiro.");
    }

    static List<Candidato> analisarTimeframe(Mercados.Mercado m, String tf) throws Exception {
        List<Alpaca.Barra> barras = Alpaca.barras(m, tf, Instant.now().minus(Duration.ofDays(1800)));
        int n = barras.size();
        int ini = Estrategias.AQUECIMENTO;
        if (n < ini + 400) {
            throw new IllegalStateException("só " + n + " velas, poucas para testar");
        }
        double[] o = new double[n], c = new double[n];
        for (int i = 0; i < n; i++) {
            o[i] = barras.get(i).abertura();
            c[i] = barras.get(i).fecho();
        }

        int total = n - ini;
        int fimTreino = ini + total / 2 - 1;
        int iniValid = fimTreino + 1;
        int fimValid = ini + total * 3 / 4 - 1;
        int iniTeste = fimValid + 1;
        int fimTeste = n - 1;
        double custo = m.custo();
        double bhValid = Estrategias.buyHold(c, custo, iniValid, fimValid);
        double bhTeste = Estrategias.buyHold(c, custo, iniTeste, fimTeste);

        List<Candidato> resultado = new ArrayList<>();
        for (Estrategias.Familia familia : Estrategias.Familia.values()) {
            Estrategias.Config melhor = null;
            double melhorScore = Double.NEGATIVE_INFINITY;
            boolean[] melhorAlvo = null;
            Estrategias.Resultado melhorTreino = null;

            for (Estrategias.Config cfg : Estrategias.grelha(familia, tf)) {
                boolean[] alvo = Estrategias.alvo(c, cfg);
                Estrategias.Resultado r = Estrategias.simular(o, c, alvo, custo, ini, fimTreino);
                if (r.trades() < MIN_OPS_TREINO) continue;
                if (r.score() > melhorScore) {
                    melhorScore = r.score();
                    melhor = cfg;
                    melhorAlvo = alvo;
                    melhorTreino = r;
                }
            }
            if (melhor == null) continue;

            resultado.add(new Candidato(tf, melhor, melhorTreino,
                    Estrategias.simular(o, c, melhorAlvo, custo, iniValid, fimValid),
                    Estrategias.simular(o, c, melhorAlvo, custo, iniTeste, fimTeste),
                    bhValid, bhTeste));
        }
        return resultado;
    }
}