import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;

public class UserService {

    public static void main(String[] args) throws IOException {
        // 1. Initialize DB before server starts
        DatabaseManager.initialize();

        int port = 8081;
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newFixedThreadPool(20));

        server.createContext("/edit", new EditHandler());
        server.createContext("/retrieve", new RetrieveHandler());

        server.start();
        System.out.println("Server started on port " + port);
    }

    // --- HANDLER FOR POST (Create, Update, Delete) ---
    static class EditHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Error: Method must be POST");
                return;
            }

            try {
                // Read and Parse JSON
                String body = getRequestBody(exchange);
                Gson gson = new Gson();
                UserData user = gson.fromJson(body, UserData.class);

                if (user.command == null) throw new IllegalArgumentException("Command is required");

                // Execute Logic based on Command
                switch (user.command.toLowerCase()) {
                    case "create":
                        DatabaseManager.createUser(user.username, user.email, user.password);
                        sendResponse(exchange, 200, "User Created: " + user.username);
                        break;
                    case "update":
                        if (user.id == 0) throw new IllegalArgumentException("ID required for update");
                        DatabaseManager.updateUser(user.id, user.username, user.email);
                        sendResponse(exchange, 200, "User Updated: " + user.id);
                        break;
                    case "delete":
                        if (user.id == 0) throw new IllegalArgumentException("ID required for delete");
                        DatabaseManager.deleteUser(user.id);
                        sendResponse(exchange, 200, "User Deleted: " + user.id);
                        break;
                    default:
                        sendResponse(exchange, 400, "Unknown command: " + user.command);
                }
            } catch (JsonSyntaxException e) {
                sendResponse(exchange, 400, "Invalid JSON format");
            } catch (Exception e) {
                e.printStackTrace(); // Log to console for debugging
                sendResponse(exchange, 500, "Server Error: " + e.getMessage());
            }
        }
    }

    // --- HANDLER FOR GET (Retrieve) ---
    static class RetrieveHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Error: Method must be GET");
                return;
            }

            // Parse ID from URL: /retrieve?id=1
            Map<String, String> params = parseQuery(exchange.getRequestURI().getQuery());
            
            if (params.containsKey("id")) {
                int id = Integer.parseInt(params.get("id"));
                UserData user = DatabaseManager.getUser(id);

                if (user != null) {
                    Gson gson = new Gson();
                    String jsonResponse = gson.toJson(user);
                    // Send strictly JSON back
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    sendResponse(exchange, 200, jsonResponse);
                } else {
                    sendResponse(exchange, 404, "User not found");
                }
            } else {
                sendResponse(exchange, 400, "Missing 'id' parameter");
            }
        }
    }

    // --- HELPER METHODS ---

    private static void sendResponse(HttpExchange exchange, int statusCode, String response) throws IOException {
        exchange.sendResponseHeaders(statusCode, response.length());
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(response.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String getRequestBody(HttpExchange exchange) throws IOException {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))) {
            StringBuilder requestBody = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                requestBody.append(line);
            }
            return requestBody.toString();
        }
    }

    // Parses "id=1&name=bob" into a Map
    private static Map<String, String> parseQuery(String query) {
        Map<String, String> result = new HashMap<>();
        if (query == null) return result;
        for (String param : query.split("&")) {
            String[] entry = param.split("=");
            if (entry.length > 1) {
                result.put(entry[0], entry[1]);
            } else {
                result.put(entry[0], "");
            }
        }
        return result;
    }
}