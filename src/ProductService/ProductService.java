import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.concurrent.Executors;

public class ProductService {

    private static final int PORT = 8081;

    // Creates products.db in the working directory you run from
    private static final String DB_URL = "jdbc:sqlite:products.db";

    // ---------- Model ----------
    static class Product {
        int id;
        String productname;
        double price;
        int quantity;

        Product(int id, String productname, double price, int quantity) {
            this.id = id;
            this.productname = productname;
            this.price = price;
            this.quantity = quantity;
        }

        String toJson() {
            return "{\n" +
                    "    \"id\": " + id + ",\n" +
                    "    \"productname\": \"" + escape(productname) + "\",\n" +
                    "    \"price\": " + price + " ,\n" +
                    "    \"quantity\": " + quantity + "\n" +
                    "}";
        }

        private static String escape(String s) {
            if (s == null) return "";
            return s.replace("\\", "\\\\").replace("\"", "\\\"");
        }
    }

    // ---- validation helpers ----
    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static void requireNonNull(HttpExchange ex, Object value, String field) throws IOException {
        if (value == null) {
            sendJson(ex, 400, "{\"error\":\"Missing required field: " + field + "\"}");
            throw new IllegalArgumentException("Missing " + field);
        }
    }

    private static void requireNonBlank(HttpExchange ex, String value, String field) throws IOException {
        if (isBlank(value)) {
            // Treat blank as invalid (even if present)
            sendJson(ex, 400, "{\"error\":\"Field cannot be empty: " + field + "\"}");
            throw new IllegalArgumentException("Empty " + field);
        }
    }

    private static void requirePositiveInt(HttpExchange ex, Integer value, String field) throws IOException {
        requireNonNull(ex, value, field);
        if (value <= 0) {
            sendJson(ex, 400, "{\"error\":\"Field must be > 0: " + field + "\"}");
            throw new IllegalArgumentException("Invalid " + field);
        }
    }

    private static void requireNonNegativeInt(HttpExchange ex, Integer value, String field) throws IOException {
        requireNonNull(ex, value, field);
        if (value < 0) {
            sendJson(ex, 400, "{\"error\":\"Field must be >= 0: " + field + "\"}");
            throw new IllegalArgumentException("Invalid " + field);
        }
    }

    private static void requireNonNegativeDouble(HttpExchange ex, Double value, String field) throws IOException {
        requireNonNull(ex, value, field);
        if (value.isNaN() || value.isInfinite() || value < 0.0) {
            sendJson(ex, 400, "{\"error\":\"Field must be >= 0: " + field + "\"}");
            throw new IllegalArgumentException("Invalid " + field);
        }
    }

    // For PATCH-like update: validate only if provided
    private static void validateOptionalNonBlank(HttpExchange ex, String value, String field) throws IOException {
        if (value != null && isBlank(value)) {
            sendJson(ex, 400, "{\"error\":\"Field cannot be empty: " + field + "\"}");
            throw new IllegalArgumentException("Empty " + field);
        }
    }

    private static void validateOptionalNonNegativeInt(HttpExchange ex, Integer value, String field) throws IOException {
        if (value != null && value < 0) {
            sendJson(ex, 400, "{\"error\":\"Field must be >= 0: " + field + "\"}");
            throw new IllegalArgumentException("Invalid " + field);
        }
    }

    private static void validateOptionalNonNegativeDouble(HttpExchange ex, Double value, String field) throws IOException {
        if (value != null && (value.isNaN() || value.isInfinite() || value < 0.0)) {
            sendJson(ex, 400, "{\"error\":\"Field must be >= 0: " + field + "\"}");
            throw new IllegalArgumentException("Invalid " + field);
        }
    }


    // ---------- Main ----------
    public static void main(String[] args) throws IOException {
        initDb();

        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.setExecutor(Executors.newFixedThreadPool(20));
        server.createContext("/product", new ProductHandler());
        server.start();

        System.out.println("ProductService started on port " + PORT);
        System.out.println("SQLite DB: products.db");
    }

    // ---------- DB init / connection ----------
    private static void initDb() {
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

    private static Connection openConn() throws SQLException {
        return DriverManager.getConnection(DB_URL);
    }
    
    static class ProductHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                String method = exchange.getRequestMethod();

                if ("POST".equalsIgnoreCase(method)) {
                    handlePost(exchange);
                } else if ("GET".equalsIgnoreCase(method)) {
                    handleGet(exchange);
                } else {
                    exchange.sendResponseHeaders(405, 0);
                    exchange.close();
                }
            } catch (Exception e) {
                sendJson(exchange, 500, "{\"error\":\"Internal server error\"}");
            }
        }

        // -------- POST /product --------
        private void handlePost(HttpExchange exchange) throws IOException, SQLException {
            try {
                String body = readBody(exchange);

                JsonObject json = parseJsonObject(exchange, body);

                String command = jString(json, "command");
                Integer id = jInt(json, "id");


                requireNonBlank(exchange, command, "command");
                requirePositiveInt(exchange, id, "id");

                command = command.trim().toLowerCase();

                switch (command) {
                    case "create": handleCreate(exchange, json, body, id); break;
                    case "update": handleUpdate(exchange, json, body, id); break;
                    case "delete": handleDelete(exchange, json, body, id); break;
                    default: sendJson(exchange, 400, "{\"error\":\"Invalid command\"}");
                }
            } catch (IllegalArgumentException ignored) {
                // validation already responded with 400
            }
        }

        private void handleCreate(HttpExchange exchange, JsonObject json, String body, int id) throws IOException, SQLException {
            String productname = jString(json, "productname");
            Double price = jDouble(json, "price");
            Integer quantity = jInt(json, "quantity");

            try {
                requireNonBlank(exchange, productname, "productname");
                requireNonNegativeDouble(exchange, price, "price");
                requireNonNegativeInt(exchange, quantity, "quantity");
            } catch (IllegalArgumentException ignored) {
                return;
            }

            try (Connection c = openConn()) {
                if (dbExistsById(c, id)) {
                    sendJson(exchange, 409, "{\"error\":\"Product id already exists\"}");
                    return;
                }

                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO products(id, productname, price, quantity) VALUES(?,?,?,?)")) {
                    ps.setInt(1, id);
                    ps.setString(2, productname);
                    ps.setDouble(3, price);
                    ps.setInt(4, quantity);
                    ps.executeUpdate();
                }
            }

            sendJson(exchange, 200, new Product(id, productname, price, quantity).toJson());
        }

        private void handleUpdate(HttpExchange exchange, JsonObject json, String body, int id) throws IOException, SQLException {
            String productname = jString(json, "productname");
            Double price = jDouble(json, "price");
            Integer quantity = jInt(json, "quantity");

            try {
                // must provide at least one updatable field
                if (productname == null && price == null && quantity == null) {
                    sendJson(exchange, 400, "{\"error\":\"No updatable fields provided\"}");
                    return;
                }

                // validate only the provided ones
                validateOptionalNonBlank(exchange, productname, "productname");
                validateOptionalNonNegativeDouble(exchange, price, "price");
                validateOptionalNonNegativeInt(exchange, quantity, "quantity");
            } catch (IllegalArgumentException ignored) {
                return;
            }


            Product updated;
            try (Connection c = openConn()) {
                Product existing = dbGetById(c, id);
                if (existing == null) {
                    sendJson(exchange, 404, "{\"error\":\"Product not found\"}");
                    return;
                }

                if (productname != null) existing.productname = productname;
                if (price != null) existing.price = price;
                if (quantity != null) existing.quantity = quantity;

                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE products SET productname=?, price=?, quantity=? WHERE id=?")) {
                    ps.setString(1, existing.productname);
                    ps.setDouble(2, existing.price);
                    ps.setInt(3, existing.quantity);
                    ps.setInt(4, id);
                    ps.executeUpdate();
                }

                updated = existing;
            }

            sendJson(exchange, 200, updated.toJson());
        }

        private void handleDelete(HttpExchange exchange, JsonObject json, String body, int id) throws IOException, SQLException {
            String productname = jString(json, "productname");
            Double price = jDouble(json, "price");
            Integer quantity = jInt(json, "quantity");

            try {
                requireNonBlank(exchange, productname, "productname");
                requireNonNegativeDouble(exchange, price, "price");
                requireNonNegativeInt(exchange, quantity, "quantity");
            } catch (IllegalArgumentException ignored) {
                return;
            }

            try (Connection c = openConn()) {
                if (!dbExistsById(c, id)) {
                    sendJson(exchange, 404, "{\"error\":\"Product not found\"}");
                    return;
                }

                int affected;
                try (PreparedStatement ps = c.prepareStatement(
                        "DELETE FROM products WHERE id=? AND productname=? AND price=? AND quantity=?")) {
                    ps.setInt(1, id);
                    ps.setString(2, productname);
                    ps.setDouble(3, price);
                    ps.setInt(4, quantity);
                    affected = ps.executeUpdate();
                }

                if (affected == 0) {
                    sendJson(exchange, 401, "{\"error\":\"Delete failed: fields do not match\"}");
                    return;
                }
            }

            sendJson(exchange, 200, "{\"status\":\"deleted\"}");
        }

        // -------- GET /product/<id> --------
        private void handleGet(HttpExchange exchange) throws IOException, SQLException {
            String path = exchange.getRequestURI().getPath();

            if (path.equals("/product") || path.equals("/product/")) {
                sendJson(exchange, 400, "{\"error\":\"Missing product id\"}");
                return;
            }

            String prefix = "/product/";
            if (!path.startsWith(prefix)) {
                sendJson(exchange, 404, "{\"error\":\"Not found\"}");
                return;
            }

            String idStr = path.substring(prefix.length());
            int slash = idStr.indexOf('/');
            if (slash >= 0) idStr = idStr.substring(0, slash);

            int id;
            try {
                id = Integer.parseInt(idStr);
            } catch (NumberFormatException e) {
                sendJson(exchange, 400, "{\"error\":\"Invalid product id\"}");
                return;
            }

            Product p;
            try (Connection c = openConn()) {
                p = dbGetById(c, id);
            }

            if (p == null) {
                sendJson(exchange, 404, "{\"error\":\"Product not found\"}");
                return;
            }

            sendJson(exchange, 200, p.toJson());
        }

        private String readBody(HttpExchange exchange) throws IOException {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                return sb.toString();
            }
        }
    }

    // ---------- DB helpers ----------
    private static boolean dbExistsById(Connection c, int id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM products WHERE id=?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static Product dbGetById(Connection c, int id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id, productname, price, quantity FROM products WHERE id=?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new Product(
                        rs.getInt("id"),
                        rs.getString("productname"),
                        rs.getDouble("price"),
                        rs.getInt("quantity")
                );
            }
        }
    }

    // ---------- HTTP response helper ----------
    private static void sendJson(HttpExchange exchange, int status, String response) throws IOException {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    // Parse once per request body
    private static JsonObject parseJsonObject(HttpExchange ex, String body) throws IOException {
        try {
            return JsonParser.parseString(body).getAsJsonObject();
        } catch (JsonSyntaxException | IllegalStateException e) {
            sendJson(ex, 400, "{\"error\":\"Invalid JSON\"}");
            throw new IllegalArgumentException("invalid json");
        }
    }

    // Safe getters: return null if missing or explicit null
    private static String jString(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) return null;
        return e.getAsString();
    }

    private static Integer jInt(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) return null;
        try { return e.getAsInt(); } catch (Exception ignore) { return null; }
    }

    private static Double jDouble(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) return null;
        try { return e.getAsDouble(); } catch (Exception ignore) { return null; }
    }
}
