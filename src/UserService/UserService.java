
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

import com.google.gson.JsonSyntaxException;

public class UserService {

    public static void main(String[] args) throws IOException {
        int port = 8081; // might want to change port
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        // Example: Set a custom executor with a fixed-size thread pool
        server.setExecutor(Executors.newFixedThreadPool(20)); // Adjust the pool size as needed
        // Set up context for /test POST request
        server.createContext("/edit", new EditHandler());

        // Set up context for /test2 GET request
        server.createContext("/retrieve", new RetrieveHandler());


        server.setExecutor(null); // creates a default executor gemini says to remove to use all 20 threads

        server.start();

        System.out.println("Server started on port " + port);
    }

    static class EditHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            // Handle POST request for /test
            if ("POST".equals(exchange.getRequestMethod())) {
                try {
                    Gson gson = new Gson();
                    // need to get data
                    UserData user = gson.fromJson(jsonBody, UserData.class);
                    
                    // 2. If we get here, the types were correct. 
                    // TODO: check command and procede based on that
                    sendResponse(exchange, "Success! User processed.");

                } catch (JsonSyntaxException e) {
                    // 3. If we catch this, the user sent bad types (e.g. "abc" instead of 123)
                    System.err.println("JSON Error: " + e.getMessage());
                    
                    // Send HTTP 400 (Bad Request)
                    String errorResponse = "Error: Invalid data types in JSON.";
                    exchange.sendResponseHeaders(400, errorResponse.length());
                    OutputStream os = exchange.getResponseBody();
                    os.write(errorResponse.getBytes());
                    os.close();
                }

            } else {
                // Send a 405 Method Not Allowed response for non-POST requests
                exchange.sendResponseHeaders(405, 0);
                exchange.close();
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

    }

    static class RetrieveHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            // Handle GET request for /test2
            // TODO let's do this in class.
            if ("GET".equals(exchange.getRequestMethod())) {
                // need to get user data, verify, grab from db
                String requestMethod = exchange.getRequestMethod();
                String clientAddress = exchange.getRemoteAddress().getAddress().toString();
                String requestURI = exchange.getRequestURI().toString();

                System.out.println("Request method: " + requestMethod);
                System.out.println("Client Address: " + clientAddress);
                System.out.println("Request URI: " + requestURI);


                String response = "Received GET for /test2 lecture foo W.";
                sendResponse(exchange, response);




            } else {
                exchange.sendResponseHeaders(405,0);
                exchange.close();
            }

        }
    }

    static class TestUserHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            // Handle GET request for /test2
            // TODO let's do this in class.
        }
    }

    private static void sendResponse(HttpExchange exchange, String response) throws IOException {
        exchange.sendResponseHeaders(200, response.length());
        OutputStream os = exchange.getResponseBody();
        os.write(response.getBytes(StandardCharsets.UTF_8));
        os.close();
    }
}

class UserData { // need to check types for ID and potentially for email and hash password
    String command;
    int id;
    String username;
    String email;
    String password;
}