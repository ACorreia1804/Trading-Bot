import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Calls.java - dá calls (COMPRA / VENDA / NEUTRO) com base em indicadores técnicos.
 * Usa só dados públicos da Binance (sem chaves). Não executa ordens.
 *
 * Uso:   java Calls.java [SIMBOLO] [INTERVALO] [CAPITAL] [MINUTOS_LOOP]
 * Ex.:   java Calls.java BTCUSDT 4h 1000
 *        java Calls.java ETHUSDT 1h 1000 15     (repete de 15 em 15 minutos)
 */
public class Calls {
    // Parâmetros que podes afinar
    static final double RISK_PCT = 0.01;   // risco por trade: 1% do capital
    static final double STOP_ATR = 1.5;    // stop a 1.5 x ATR
    static final double TARGET_ATR = 3.0;  // alvo a 3 x ATR (relação 1:2)
    static final int BUY_AT = 4;           // pontuação mínima para COMPRA
    static final int SELL_AT = -4;         // pontuação máxima para VENDA

    public static void main(String[] a) throws Exception {
        String symbol = a.length > 0 ? a[0].toUpperCase() : "BTCUSDT";
        String interval = a.length > 1 ? a[1] : "4h";
        double capital = a.length > 2 ? Double.parseDouble(a[2]) : 1000;
        int loopMin = a.length > 3 ? Integer.parseInt(a[3]) : 0;
        do {
            try {
                analyse(symbol, interval, capital, fetch(symbol, interval));
            } catch (Exception e) {
                System.out.println("Erro: " + e.getMessage());
            }
            if (loopMin > 0) Thread.sleep(loopMin * 60_000L);
        } while (loopMin > 0);
    }

    // Velas: [abertura, máximo, mínimo, fecho, volume]
    static double[][] fetch(String symbol, String interval) throws Exception {
        String[] hosts = {"https://api.binance.com", "https://data-api.binance.vision"};
        HttpClient http = HttpClient.newHttpClient();
        Exception last = null;
        for (String h : hosts) {
            try {
                HttpRequest rq = HttpRequest.newBuilder(URI.create(
                        h + "/api/v3/klines?symbol=" + symbol + "&interval=" + interval + "&limit=300")).build();
                HttpResponse<String> rs = http.send(rq, HttpResponse.BodyHandlers.ofString());
                if (rs.statusCode() != 200) throw new RuntimeException("HTTP " + rs.statusCode() + " " + rs.body());
                Matcher m = Pattern.compile(
                        "\\[(\\d+),\"([^\"]+)\",\"([^\"]+)\",\"([^\"]+)\",\"([^\"]+)\",\"([^\"]+)\"").matcher(rs.body());
                List<double[]> rows = new ArrayList<>();
                while (m.find()) {
                    rows.add(new double[]{d(m, 2), d(m, 3), d(m, 4), d(m, 5), d(m, 6)});
                }
                if (rows.size() < 250) throw new RuntimeException("Poucos dados para " + symbol);
                rows.remove(rows.size() - 1); // descarta a vela ainda aberta
                return rows.toArray(new double[0][]);
            } catch (Exception e) {
                last = e;
            }
        }
        throw last;
    }

    static double d(Matcher m, int g) { return Double.parseDouble(m.group(g)); }

    static void analyse(String symbol, String interval, double capital, double[][] k) throws Exception {
        int n = k.length - 1;
        double[] c = new double[k.length], v = new double[k.length];
        for (int i = 0; i < k.length; i++) { c[i] = k[i][3]; v[i] = k[i][4]; }

        double[] e20 = ema(c, 20), e50 = ema(c, 50), e200 = ema(c, 200);
        double[] e12 = ema(c, 12), e26 = ema(c, 26), macd = new double[c.length];
        for (int i = 0; i < c.length; i++) macd[i] = e12[i] - e26[i];
        double[] sig = ema(macd, 9);
        double hist = macd[n] - sig[n], histPrev = macd[n - 1] - sig[n - 1];
        double rsi = rsi(c, 14), atr = atr(k, 14);
        double volAvg = 0;
        for (int i = n - 20; i < n; i++) volAvg += v[i] / 20;

        int score = 0;
        List<String> why = new ArrayList<>();

        if (c[n] > e50[n] && e20[n] > e50[n]) { score += 2; why.add("Tendência de alta (preço e EMA20 acima da EMA50)"); }
        else if (c[n] < e50[n] && e20[n] < e50[n]) { score -= 2; why.add("Tendência de baixa (preço e EMA20 abaixo da EMA50)"); }
        else why.add("Tendência indefinida (médias misturadas)");

        if (c[n] > e200[n]) { score++; why.add("Preço acima da EMA200"); }
        else { score--; why.add("Preço abaixo da EMA200"); }

        if (hist > 0 && hist > histPrev) { score++; why.add("MACD positivo e a subir"); }
        else if (hist < 0 && hist < histPrev) { score--; why.add("MACD negativo e a descer"); }
        else why.add("MACD sem direção clara");

        if (rsi >= 75) { score--; why.add(String.format("RSI %.0f: sobrecomprado, risco de correção", rsi)); }
        else if (rsi <= 25) { score++; why.add(String.format("RSI %.0f: sobrevendido, possível ressalto", rsi)); }
        else if (rsi > 50) { score++; why.add(String.format("RSI %.0f: momentum positivo", rsi)); }
        else { score--; why.add(String.format("RSI %.0f: momentum negativo", rsi)); }

        boolean green = c[n] > k[n][0];
        if (v[n] > 1.5 * volAvg) {
            score += green ? 1 : -1;
            why.add("Volume alto numa vela " + (green ? "verde" : "vermelha"));
        }

        String call = score >= BUY_AT ? "COMPRA" : score <= SELL_AT ? "VENDA" : "NEUTRO";
        double price = c[n], stopDist = STOP_ATR * atr;
        double stop = call.equals("VENDA") ? price + stopDist : price - stopDist;
        double target = call.equals("VENDA") ? price - TARGET_ATR * atr : price + TARGET_ATR * atr;
        double qty = Math.min(capital * RISK_PCT / stopDist, capital / price);

        System.out.println("\n=== " + symbol + " (" + interval + ") | " + LocalDateTime.now().withNano(0) + " ===");
        System.out.println("CALL: " + call + "   (pontuação " + score + " de -6 a +6)");
        for (String w : why) System.out.println("  - " + w);
        if (call.equals("NEUTRO")) {
            System.out.println("Sem sinal forte: não fazer nada também é uma decisão.");
        } else {
            System.out.println("Entrada ~ " + f(price) + " | Stop " + f(stop) + " | Alvo " + f(target));
            System.out.println("Tamanho (risco " + (int) (RISK_PCT * 100) + "% de " + capital + "): "
                    + String.format("%.6f", qty) + " unidades (~" + f(qty * price) + ")");
            if (call.equals("VENDA")) System.out.println("Nota: VENDA = short (TradingView paper trading ou futuros) ou sair de posição comprada.");
        }
        System.out.println("Invalida a ideia se o preço fechar para lá do stop. Isto não é aconselhamento financeiro.");
        log(symbol, interval, call, score, price, stop, target);
    }

    static String f(double x) { return String.format(x < 1 ? "%.6f" : "%.2f", x); }

    // Diário das calls (para veres mais tarde se acertaram)
    static void log(String s, String i, String call, int score, double p, double st, double t) throws Exception {
        Path file = Path.of("calls.csv");
        if (!Files.exists(file)) Files.writeString(file, "data,simbolo,intervalo,call,pontuacao,entrada,stop,alvo\n");
        Files.writeString(file, String.format("%s,%s,%s,%s,%d,%s,%s,%s%n",
                LocalDateTime.now().withNano(0), s, i, call, score, f(p), f(st), f(t)), StandardOpenOption.APPEND);
    }

    static double[] ema(double[] x, int n) {
        double[] r = new double[x.length];
        double k = 2.0 / (n + 1);
        r[0] = x[0];
        for (int i = 1; i < x.length; i++) r[i] = x[i] * k + r[i - 1] * (1 - k);
        return r;
    }

    static double rsi(double[] c, int n) {
        double g = 0, l = 0;
        for (int i = 1; i <= n; i++) { double d = c[i] - c[i - 1]; if (d > 0) g += d; else l -= d; }
        g /= n; l /= n;
        for (int i = n + 1; i < c.length; i++) {
            double d = c[i] - c[i - 1];
            g = (g * (n - 1) + Math.max(d, 0)) / n;
            l = (l * (n - 1) + Math.max(-d, 0)) / n;
        }
        return l == 0 ? 100 : 100 - 100 / (1 + g / l);
    }

    static double atr(double[][] k, int n) {
        double a = 0;
        for (int i = 1; i <= n; i++) a += tr(k, i);
        a /= n;
        for (int i = n + 1; i < k.length; i++) a = (a * (n - 1) + tr(k, i)) / n;
        return a;
    }

    static double tr(double[][] k, int i) {
        double pc = k[i - 1][3];
        return Math.max(k[i][1] - k[i][2], Math.max(Math.abs(k[i][1] - pc), Math.abs(k[i][2] - pc)));
    }
}