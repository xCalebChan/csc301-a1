import java.sql.*;

public class DatabaseManager {

    // Creates products.db in the working directory you run from
    private static final String DB_URL = "jdbc:sqlite:products.db";

    public static void initDb() {
        try (Connection c = DriverManager.getConnection(DB_URL);
             Statement st = c.createStatement()) {

            // Recommended for concurrent reads/writes:
            st.execute("PRAGMA journal_mode=WAL;");
            st.execute("PRAGMA foreign_keys=ON;");

            st.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS products (" +
                            "id INTEGER PRIMARY KEY," +
                            "productname TEXT NOT NULL," +
                            "price REAL NOT NULL," +
                            "quantity INTEGER NOT NULL" +
                            ")"
            );
        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialize DB: " + e.getMessage(), e);
        }
    }

    public static Connection openConn() throws SQLException {
        return DriverManager.getConnection(DB_URL);
    }

    public static boolean dbExistsById(Connection c, int id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM products WHERE id=?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public static ProductService.Product dbGetById(Connection c, int id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id, productname, price, quantity FROM products WHERE id=?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new ProductService.Product(
                        rs.getInt("id"),
                        rs.getString("productname"),
                        rs.getDouble("price"),
                        rs.getInt("quantity")
                );
            }
        }
    }
}
