import java.math.BigDecimal;
import java.sql.*;
import java.time.Instant;

public class Db {

    private static final String URL =
            System.getenv().getOrDefault("DB_URL", "jdbc:postgresql://localhost:5432/trading");
    private static final String USER = System.getenv().getOrDefault("DB_USER", "bot");
    private static final String PASS = System.getenv("POSTGRES_PASSWORD");

    static Connection ligar() throws SQLException {
        if (PASS == null) {
            throw new IllegalStateException("Falta a variável de ambiente POSTGRES_PASSWORD");
        }
        return DriverManager.getConnection(URL, USER, PASS);
    }

    static void guardarVela(String simbolo, String intervalo, Instant fechoEm,
                            double abertura, double maximo, double minimo,
                            double fecho, double volume) throws SQLException {
        String sql = "INSERT INTO candles (symbol, intervalo, fecho_em, abertura, maximo, minimo, fecho, volume) "
                + "VALUES (?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING";
        try (Connection c = ligar(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, simbolo);
            ps.setString(2, intervalo);
            ps.setTimestamp(3, Timestamp.from(fechoEm));
            ps.setBigDecimal(4, BigDecimal.valueOf(abertura));
            ps.setBigDecimal(5, BigDecimal.valueOf(maximo));
            ps.setBigDecimal(6, BigDecimal.valueOf(minimo));
            ps.setBigDecimal(7, BigDecimal.valueOf(fecho));
            ps.setBigDecimal(8, BigDecimal.valueOf(volume));
            ps.executeUpdate();
        }
    }

    static void guardarTrade(String simbolo, String lado, double quantidade, double preco,
                             double valorUsdt, String orderId, String ambiente) throws SQLException {
        String sql = "INSERT INTO trades (symbol, lado, quantidade, preco, valor_usdt, order_id, ambiente) "
                + "VALUES (?,?,?,?,?,?,?)";
        try (Connection c = ligar(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, simbolo);
            ps.setString(2, lado);
            ps.setBigDecimal(3, BigDecimal.valueOf(quantidade));
            ps.setBigDecimal(4, BigDecimal.valueOf(preco));
            ps.setBigDecimal(5, BigDecimal.valueOf(valorUsdt));
            ps.setString(6, orderId);
            ps.setString(7, ambiente);
            ps.executeUpdate();
        }
    }

    // teste rápido da ligação
    public static void main(String[] args) throws Exception {
        try (Connection c = ligar();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM candles")) {
            rs.next();
            System.out.println("Ligação OK. Velas na base de dados: " + rs.getLong(1));
        }
    }
}