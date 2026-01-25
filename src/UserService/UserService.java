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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import java.sql.SQLException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;

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
                if (user == null) {
                        sendResponse(exchange, 400, "Error: Empty request body");
                        return;
                    }
                if (user.command == null) {
                    sendResponse(exchange, 400, "Error: Command is required");
                    return;
                }
                // Execute Logic based on Command
                switch (user.command.toLowerCase()) {
                    case "create":
                        // NEED TO MAKE ID A PART OF THE SCHEMA
                        if (user.id == 0) {
                            sendResponse(exchange, 400, "Error: ID is required");
                            return;
                        }
                        if (user.username == null || user.username.isEmpty()) {
                            sendResponse(exchange, 400, "Error: Username is required");
                            return;
                        }
                        if (user.password == null || user.password.isEmpty()) {
                            sendResponse(exchange, 400, "Error: Password is required");
                            return;
                        }
                        if (user.email == null || !isValidEmail(user.email)) {
                            sendResponse(exchange, 400, "Error: Invalid or missing email");
                            return;
                        }
                        
                        // Hash password only after validation passes
                        String hashedPassword = hashPassword(user.password);
                        user.password = hashedPassword;
                        DatabaseManager.createUser(user.id, user.username, user.email, user.password);
                        // CHANGE: Return the user object as JSON instead of a text string
                        String jsonResponse = gson.toJson(user); 
                        sendResponse(exchange, 200, jsonResponse);
                        break;
                        
                    case "update":
                        if (user.id == 0) {
                            sendResponse(exchange, 400, "Error: ID required for update");
                            return;
                        }

                        // 1. Fetch the CURRENT database state
                        UserData existingUser = DatabaseManager.getUser(user.id);
                        if (existingUser == null) {
                            sendResponse(exchange, 404, "Error: User ID not found");
                            return;
                        }

                        // 2. Merge Email (Update only if valid and present)
                        if (user.email != null) {
                                if (user.email.isEmpty()) {
                                    sendResponse(exchange, 400, "Error: Email cannot be empty");
                                    return;
                                }
                                if (!isValidEmail(user.email)) {
                                    sendResponse(exchange, 400, "Error: Invalid email format");
                                    return;
                                }
                                existingUser.email = user.email;
                        }

                        // 3. Merge Username (Update only if present)
                        if (user.username != null) {
                                if (user.username.isEmpty()) {
                                    sendResponse(exchange, 400, "Error: Username cannot be empty");
                                    return;
                                }
                                existingUser.username = user.username;
                        }

                        // 4. Merge Password (Update and HASH if present)
                        if (user.password != null) {
                                if (user.password.isEmpty()) {
                                    sendResponse(exchange, 400, "Error: Password cannot be empty");
                                    return;
                                }
                                existingUser.password = hashPassword(user.password);
                        }

                        // 5. Save to Database
                        // Note: ensure DatabaseManager.updateUser is updated to accept 4 arguments
                        DatabaseManager.updateUser(existingUser.id, existingUser.username, existingUser.email, existingUser.password);

                        // 6. Return the MERGED object so the client sees the full updated state
                        sendResponse(exchange, 200, gson.toJson(existingUser));
                        break;
                        
                    case "delete":
                        if (user.id == 0) {
                            sendResponse(exchange, 400, "Error: ID required for delete");
                            return;
                        }
                        UserData dbUser = DatabaseManager.getUser(user.id);
                        if (dbUser == null) {
                            sendResponse(exchange, 404, "Error: User not found");
                            return;
                        }
                        if (user.password == null) {
                            sendResponse(exchange, 400, "Error: Password required for verification");
                            return;
                        }
                        if (user.username == null || user.username.isEmpty()) {
                            sendResponse(exchange, 400, "Error: Username is required");
                            return;
                        }
                        if (user.email == null || !isValidEmail(user.email)) {
                            sendResponse(exchange, 400, "Error: Invalid or missing email");
                            return;
                        }
                        String inputHash = hashPassword(user.password);
                        boolean usernameMatch = dbUser.username.equals(user.username);
                        boolean emailMatch = dbUser.email.equals(user.email);
                        boolean passMatch = dbUser.password.equals(inputHash);
                        if (usernameMatch && emailMatch && passMatch) {
                            DatabaseManager.deleteUser(user.id);
                            sendResponse(exchange, 200, "{}");
                        } else {
                            sendResponse(exchange, 401, "Error: Data mismatch. Delete failed.");
                        }
                        break;
                        
                    default:
                        sendResponse(exchange, 400, "{\"error\": \"Unknown command\"}");
                }
            } catch (SQLException e) {
                // SQLite throws specific messages for constraint violations
                if (e.getMessage().contains("UNIQUE constraint failed") || e.getMessage().contains("PRIMARY KEY")) {
                    sendResponse(exchange, 409, "Error: User with this ID or Username already exists");
                } else {
                    e.printStackTrace();
                    sendResponse(exchange, 500, "Database Error: " + e.getMessage());
                }
            } catch (JsonSyntaxException e) {
                String errorDetails = e.getMessage(); 
                sendResponse(exchange, 400, "Invalid JSON format: " + errorDetails);
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
                try {
                    // 1. Try to parse the ID
                    int id = Integer.parseInt(params.get("id"));
                    
                    // 2. Fetch User
                    UserData user = DatabaseManager.getUser(id);

                    if (user != null) {
                        Gson gson = new Gson();
                        // Security: Mask the password before sending it back
                        user.password = "***"; 
                        
                        String jsonResponse = gson.toJson(user);
                        exchange.getResponseHeaders().set("Content-Type", "application/json");
                        sendResponse(exchange, 200, jsonResponse);
                    } else {
                        sendResponse(exchange, 404, "User not found");
                    }
                    
                } catch (NumberFormatException e) {
                    // 3. Handle non-integer IDs (e.g., "id=abcd")
                    sendResponse(exchange, 400, "Error: ID must be a number");
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

    public static String hashPassword(String password) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] encodedhash = digest.digest(password.getBytes(StandardCharsets.UTF_8));
            
            // Convert byte array to Hex String
            StringBuilder hexString = new StringBuilder();
            for (byte b : encodedhash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString().toUpperCase(); // To match your uppercase output
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    private static final Pattern VALID_EMAIL_ADDRESS_REGEX = 
        Pattern.compile("^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$", Pattern.CASE_INSENSITIVE);

    public static boolean isValidEmail(String emailStr) {
        Matcher matcher = VALID_EMAIL_ADDRESS_REGEX.matcher(emailStr);
        return matcher.matches();
    }
}