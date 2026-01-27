import java.sql.*;

public class DatabaseManager {

    private static final String DB_URL = "jdbc:postgresql://db.qvmekyovznxapjzhyudt.supabase.co:5432/postgres?sslmode=require";
    private static final String DB_USER = "postgres";
    private static final String DB_PASSWORD = "HmNUB18zTR4Fjjqc";

    // Match UserService naming
    public static void initialize() {
        try (Connection c = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
             Statement st = c.createStatement()) {

            st.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS products (" +
                            "id INTEGER PRIMARY KEY," +
                            "name TEXT NOT NULL," +
                            "description TEXT NOT NULL," +
                            "price DOUBLE PRECISION NOT NULL," +
                            "quantity INTEGER NOT NULL" +
                            ")"
            );

            // If table existed from an older version, ensure description exists
            try {
                st.executeUpdate("ALTER TABLE products ADD COLUMN description TEXT NOT NULL DEFAULT 'N/A'");
            } catch (SQLException ignored) {
                // duplicate column, ok
            }

        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialize DB: " + e.getMessage(), e);
        }
    }

    private static Connection openConn() throws SQLException {
        return DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
    }

    public static boolean productExists(int id) throws SQLException {
        try (Connection c = openConn();
             PreparedStatement ps = c.prepareStatement("SELECT 1 FROM products WHERE id=?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public static ProductService.Product getProduct(int id) throws SQLException {
        try (Connection c = openConn();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id, name, description, price, quantity FROM products WHERE id=?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new ProductService.Product(
                        rs.getInt("id"),
                        rs.getString("name"),
                        rs.getString("description"),
                        rs.getDouble("price"),
                        rs.getInt("quantity")
                );
            }
        }
    }

    // returns false if id already exists
    public static boolean createProduct(ProductService.Product p) throws SQLException {
        try (Connection c = openConn()) {
            if (productExists(p.id)) return false;

            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO products(id, name, description, price, quantity) VALUES(?,?,?,?,?)")) {
                ps.setInt(1, p.id);
                ps.setString(2, p.name);
                ps.setString(3, p.description);
                ps.setDouble(4, p.price);
                ps.setInt(5, p.quantity);
                ps.executeUpdate();
                return true;
            }
        }
    }

    // returns false if id not found
    public static boolean updateProduct(ProductService.Product p) throws SQLException {
        try (Connection c = openConn();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE products SET name=?, description=?, price=?, quantity=? WHERE id=?")) {
            ps.setString(1, p.name);
            ps.setString(2, p.description);
            ps.setDouble(3, p.price);
            ps.setInt(4, p.quantity);
            ps.setInt(5, p.id);

            int affected = ps.executeUpdate();
            return affected > 0;
        }
    }

    public enum DeleteResult { NOT_FOUND, MISMATCH, DELETED }

    public static DeleteResult deleteProduct(int id, String name, double price, int quantity) throws SQLException {
        try (Connection c = openConn()) {
            if (!productExists(id)) return DeleteResult.NOT_FOUND;

            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM products WHERE id=? AND name=? AND price=? AND quantity=?")) {
                ps.setInt(1, id);
                ps.setString(2, name);
                ps.setDouble(3, price);
                ps.setInt(4, quantity);

                int affected = ps.executeUpdate();
                return (affected > 0) ? DeleteResult.DELETED : DeleteResult.MISMATCH;
            }
        }
    }
}
