
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

public class ProductService {

    // "database", asked chatgpt what I should be using for the database
    // NOT persistent, consider using JSON file or SQL
    static final java.util.Map<Integer, Product> products =
            new java.util.concurrent.ConcurrentHashMap<>();

    static class Product { //Product class
        int id;
        String productname;
        double price;
        int quantity;

        // Product constructor
        Product(int id, String productname, double price, int quantity) {
            this.id = id;
            this.productname = productname;
            this.price = price;
            this.quantity = quantity;
        }

        // JSON response format required by the spec
        String toJson() {
            return "{\n" +
                    "    \"id\": " + id + ",\n" +
                    "    \"productname\": \"" + escape(productname) + "\",\n" +
                    "    \"price\": " + price + " ,\n" +
                    "    \"quantity\": " + quantity + "\n" +
                    "}";
        }

        // GPTed, used to prevent JSON breaking out of quotations
        private static String escape(String s) {
            if (s == null) return "";
            return s.replace("\\", "\\\\").replace("\"", "\\\"");
        }
    }

    public static void main(String[] args) throws IOException {
        int port = 8081;
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        // Example: Set a custom executor with a fixed-size thread pool
        server.setExecutor(Executors.newFixedThreadPool(20)); // Adjust the pool size as needed

        // Context for /product API endpoint
        server.createContext("/product", new ProductHandler());

        server.setExecutor(null); // creates a default executor

        server.start();

        System.out.println("Server started on port " + port);
    }

    static class ProductHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();

            if ("POST".equals(method)) {
                handlePost(exchange);
            } else if ("GET".equals(method)) {
                handleGet(exchange);
            } else {
                exchange.sendResponseHeaders(405, 0);
                exchange.close();
            }
        }

        // -------- POST /product --------
        private void handlePost(HttpExchange exchange) throws IOException {
            String body = readBody(exchange);

            String command = getJsonString(body, "command");
            Integer id = getJsonInt(body, "id");

            if (command == null || id == null) {
                sendJson(exchange, 400, "{\"error\":\"Missing required field(s): command, id\"}");
                return;
            }

            command = command.trim().toLowerCase();

            switch (command) {
                case "create":
                    handleCreate(exchange, body, id);
                    break;
                case "update":
                    handleUpdate(exchange, body, id);
                    break;
                case "delete":
                    handleDelete(exchange, body, id);
                    break;
                default:
                    sendJson(exchange, 400, "{\"error\":\"Invalid command\"}");
            }
        }

        private void handleCreate(HttpExchange exchange, String body, int id) throws IOException {
            // required fields for create
            String productname = getJsonString(body, "productname");
            Double price = getJsonDouble(body, "price");
            Integer quantity = getJsonInt(body, "quantity");

            if (productname == null || price == null || quantity == null) {
                sendJson(exchange, 400, "{\"error\":\"Missing required field(s) for create\"}");
                return;
            }

            if (products.containsKey(id)) {
                sendJson(exchange, 409, "{\"error\":\"Product id already exists\"}");
                return;
            }

            Product p = new Product(id, productname, price, quantity);
            products.put(id, p);

            sendJson(exchange, 200, p.toJson());
        }

        private void handleUpdate(HttpExchange exchange, String body, int id) throws IOException {
            Product existing = products.get(id);
            if (existing == null) {
                sendJson(exchange, 404, "{\"error\":\"Product not found\"}");
                return;
            }

            // update only fields present
            String productname = getJsonString(body, "productname");
            Double price = getJsonDouble(body, "price");
            Integer quantity = getJsonInt(body, "quantity");

            if (productname != null) existing.productname = productname;
            if (price != null) existing.price = price;
            if (quantity != null) existing.quantity = quantity;

            sendJson(exchange, 200, existing.toJson());
        }

        private void handleDelete(HttpExchange exchange, String body, int id) throws IOException {
            Product existing = products.get(id);
            if (existing == null) {
                sendJson(exchange, 404, "{\"error\":\"Product not found\"}");
                return;
            }

            String productname = getJsonString(body, "productname");
            Double price = getJsonDouble(body, "price");
            Integer quantity = getJsonInt(body, "quantity");

            if (productname == null || price == null || quantity == null) {
                sendJson(exchange, 400, "{\"error\":\"Missing required field(s) for delete\"}");
                return;
            }

            boolean matches =
                    existing.productname.equals(productname) &&
                            Double.compare(existing.price, price) == 0 &&
                            existing.quantity == quantity;

            if (!matches) {
                // spec allows 401 or 404; using 401 for mismatch
                sendJson(exchange, 401, "{\"error\":\"Delete failed: fields do not match\"}");
                return;
            }

            products.remove(id);
            sendJson(exchange, 200, "{\"status\":\"deleted\"}");
        }


        private void handleGet(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath(); // e.g. "/product/23823" or "/product"
            // We registered context "/product", so we must handle both "/product" and "/product/<id>"
            // Expecting "/product/<id>"
            if (path.equals("/product") || path.equals("/product/")) {
                sendJson(exchange, 400, "{\"error\":\"Missing product id\"}");
                return;
            }

            // Extract id after "/product/"
            String prefix = "/product/";
            if (!path.startsWith(prefix)) {
                // Shouldn't happen with this context, but be safe
                sendJson(exchange, 404, "{\"error\":\"Not found\"}");
                return;
            }

            String idStr = path.substring(prefix.length()); // "23823" (maybe with extra slashes)
            int slash = idStr.indexOf('/');
            if (slash >= 0) idStr = idStr.substring(0, slash);

            int id;
            try {
                id = Integer.parseInt(idStr);
            } catch (NumberFormatException e) {
                sendJson(exchange, 400, "{\"error\":\"Invalid product id\"}");
                return;
            }

            Product p = products.get(id);
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

    // Send JSON with status + content-type
    private static void sendJson(HttpExchange exchange, int status, String response) throws IOException {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String getJsonString(String json, String key) {
        String pattern = "\"" + key + "\"";
        int k = json.indexOf(pattern);
        if (k < 0) return null;
        int colon = json.indexOf(':', k);
        if (colon < 0) return null;

        int firstQuote = json.indexOf('"', colon + 1);
        if (firstQuote < 0) return null;
        int secondQuote = json.indexOf('"', firstQuote + 1);
        if (secondQuote < 0) return null;

        return json.substring(firstQuote + 1, secondQuote);
    }

    private static Integer getJsonInt(String json, String key) {
        String raw = getJsonNumberRaw(json, key);
        if (raw == null) return null;
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Double getJsonDouble(String json, String key) {
        String raw = getJsonNumberRaw(json, key);
        if (raw == null) return null;
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String getJsonNumberRaw(String json, String key) {
        String pattern = "\"" + key + "\"";
        int k = json.indexOf(pattern);
        if (k < 0) return null;
        int colon = json.indexOf(':', k);
        if (colon < 0) return null;

        int i = colon + 1;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;

        int j = i;
        while (j < json.length() && ("-0123456789.".indexOf(json.charAt(j)) >= 0)) j++;

        if (j == i) return null;
        return json.substring(i, j);
    }
}

