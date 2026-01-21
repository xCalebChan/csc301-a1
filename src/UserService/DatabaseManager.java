import java.io.File;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class DatabaseManager {
    private static final String DB_FILE_NAME = "user_service.db";
    private static final String DB_URL = "jdbc:sqlite:" + DB_FILE_NAME;
    private static Connection connection = null;

    // --- Singleton Connection ---
    public static Connection connect() throws SQLException {
        if (connection == null || connection.isClosed()) {
            boolean fileExists = new File(DB_FILE_NAME).exists();
            if (!fileExists) {
                System.out.println("Creating new database file...");
            }
            connection = DriverManager.getConnection(DB_URL);
        }
        return connection;
    }

    // --- Initialization ---
    public static void initialize() {
        String sql = "CREATE TABLE IF NOT EXISTS users (" +
                     "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                     "username TEXT NOT NULL UNIQUE, " +
                     "email TEXT, " +
                     "password TEXT)";
        try (Connection conn = connect(); Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
            System.out.println("Database initialized.");
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    // --- CRUD OPERATIONS ---

    public static void createUser(String username, String email, String password) throws SQLException {
        String sql = "INSERT INTO users(username, email, password) VALUES(?,?,?)";
        try (Connection conn = connect(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, username);
            pstmt.setString(2, email);
            pstmt.setString(3, password);
            pstmt.executeUpdate();
        }
    }

    public static void updateUser(int id, String username, String email) throws SQLException {
        String sql = "UPDATE users SET username = ?, email = ? WHERE id = ?";
        try (Connection conn = connect(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, username);
            pstmt.setString(2, email);
            pstmt.setInt(3, id);
            pstmt.executeUpdate();
        }
    }

    public static void deleteUser(int id) throws SQLException {
        String sql = "DELETE FROM users WHERE id = ?";
        try (Connection conn = connect(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, id);
            pstmt.executeUpdate();
        }
    }

    public static UserData getUser(int id) {
        String sql = "SELECT * FROM users WHERE id = ?";
        try (Connection conn = connect(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, id);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                UserData user = new UserData();
                user.id = rs.getInt("id");
                user.username = rs.getString("username");
                user.email = rs.getString("email");
                user.password = rs.getString("password"); // In production, never return passwords!
                return user;
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null; // User not found
    }
}