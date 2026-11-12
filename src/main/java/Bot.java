import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class Bot {

    // ---- Parâmetros (podes mexer aqui) ----
    static final String SIMBOLO = "BTCUSDT";
    static final String INTERVALO = "1m";   // só para testar; depois passa a "1h" ou "4h"
    static final int RAPIDA = 5;
    static final int LENTA = 20;
    static final String VALOR_USDT = "100";
    static final double STOP_LOSS = 0.02;   // 2%
    static final long ESPERA_MS = 10_000;   // verifica de 10 em 10 segundos

    static final Path ESTADO = Path.of("estado.txt");
    static final Path LOG = Path.of("bot.log");

    static String chave, segredo;
    static boolean emPosicao = false;
    static BigDecimal quantidade = BigDecimal.ZERO;
    static double precoEntrada = 0;
    static long ultimaVela = 0;

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

    static void guardarEstado() throws Exception {
        Files.writeString(ESTADO, emPosicao + ";" + quantidade.toPlainString() + ";" + precoEntrada);
    }

    static void carregarEstado() throws Exception {
        if (!Files.exists(ESTADO)) return;
        String[] p = Files.readString(ESTADO).trim().split(";");
        emPosicao = Boolean.parseBoolean(p[0]);
        quantidade = new BigDecimal(p[1]);
        precoEntrada = Double.parseDouble(p[2]);
    }

    static JsonNode velas() throws Exception {
        // dados de mercado públicos (sem chave); as ordens vão para a testnet
        String url = "https://api.binance.com/api/v3/klines?symbol=" + SIMBOLO
                + "&interval=" + INTERVALO + "&limit=" + (LENTA + 5);
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).GET().build();
        String corpo = Testnet.CLIENT.send(req, HttpResponse.BodyHandlers.ofString()).body();
        return Testnet.MAPPER.readTree(corpo);
    }

    static double sma(List<Double> f, int fim, int n) {
        double soma = 0;
        for (int i = fim - n + 1; i <= fim; i++) soma += f.get(i);
        return soma / n;
    }

    static void comprar() throws Exception {
        JsonNode r = Testnet.pedidoAssinado("POST", "/api/v3/order",
                "symbol=" + SIMBOLO + "&side=BUY&type=MARKET&quoteOrderQty=" + VALOR_USDT,
                chave, segredo);
        if (r.has("code")) {
            log("ERRO ao comprar: " + r);
            return;
        }
        quantidade = new BigDecimal(r.get("executedQty").asText());
        double gasto = Double.parseDouble(r.get("cummulativeQuoteQty").asText());
        precoEntrada = gasto / quantidade.doubleValue();
        emPosicao = true;
        guardarEstado();
        log(String.format("COMPRA: %s BTC a ~%.2f", quantidade.stripTrailingZeros().toPlainString(), precoEntrada));
    }

    static void vender() throws Exception {
        JsonNode r = Testnet.pedidoAssinado("POST", "/api/v3/order",
                "symbol=" + SIMBOLO + "&side=SELL&type=MARKET&quantity="
                        + quantidade.stripTrailingZeros().toPlainString(),
                chave, segredo);
        if (r.has("code")) {
            log("ERRO ao vender: " + r);
            return;
        }
        double recebido = Double.parseDouble(r.get("cummulativeQuoteQty").asText());
        double gasto = precoEntrada * quantidade.doubleValue();
        emPosicao = false;
        quantidade = BigDecimal.ZERO;
        precoEntrada = 0;
        guardarEstado();
        log(String.format("VENDA: recebido %.2f USDT | resultado %.2f USDT", recebido, recebido - gasto));
    }

    static void ciclo() throws Exception {
        JsonNode v = velas();
        if (!v.isArray()) {
            log("Resposta inesperada da Binance: " + v);
            return;
        }

        // a última vela ainda está aberta: o seu fecho serve de "preço atual"
        double precoAtual = Double.parseDouble(v.get(v.size() - 1).get(4).asText());

        // stop-loss: verifica-se em todos os ciclos
        if (emPosicao && precoAtual <= precoEntrada * (1 - STOP_LOSS)) {
            log(String.format("STOP-LOSS atingido (preço %.2f)", precoAtual));
            vender();
            return;
        }

        // sinal: só se avalia uma vez por vela fechada
        int ultima = v.size() - 2; // última vela já fechada
        long fechoUltima = v.get(ultima).get(6).asLong();
        if (fechoUltima == ultimaVela) return;
        ultimaVela = fechoUltima;

        List<Double> fechos = new ArrayList<>();
        for (int i = 0; i <= ultima; i++) {
            fechos.add(Double.parseDouble(v.get(i).get(4).asText()));
        }

        double rAgora = sma(fechos, ultima, RAPIDA);
        double lAgora = sma(fechos, ultima, LENTA);
        double rAntes = sma(fechos, ultima - 1, RAPIDA);
        double lAntes = sma(fechos, ultima - 1, LENTA);

        log(String.format("Vela fechada | preço %.2f | rápida %.2f | lenta %.2f | em posição: %s",
                fechos.get(ultima), rAgora, lAgora, emPosicao));

        if (rAntes <= lAntes && rAgora > lAgora && !emPosicao) {
            comprar();
        } else if (rAntes >= lAntes && rAgora < lAgora && emPosicao) {
            vender();
        }
    }

    public static void main(String[] args) throws Exception {
        chave = System.getenv("BINANCE_TESTNET_KEY");
        segredo = System.getenv("BINANCE_TESTNET_SECRET");
        if (chave == null || segredo == null) {
            System.out.println("Faltam as variáveis BINANCE_TESTNET_KEY e BINANCE_TESTNET_SECRET.");
            return;
        }

        carregarEstado();
        log("Bot iniciado | " + SIMBOLO + " " + INTERVALO + " | médias " + RAPIDA + "/" + LENTA
                + " | em posição: " + emPosicao);

        while (true) {
            try {
                ciclo();
            } catch (Exception e) {
                log("ERRO no ciclo: " + e);
            }
            Thread.sleep(ESPERA_MS);
        }
    }
}
