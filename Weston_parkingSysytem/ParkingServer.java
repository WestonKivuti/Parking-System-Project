import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;

/**
 * Web layer for the Modern Parking System.
 * Wires M1-M7 (from ParkingSystem) plus M8 (Login & Role Access) here.
 *
 * M8 — Login & Role Access Module
 *   - Two roles: "admin" (can edit tariffs and payment methods) and
 *     "client" (can use entry/exit/receipts, but not the admin panel).
 *   - Session tokens are a random UUID, held in memory (sessionTokens map).
 *     This is a class-project-appropriate implementation — a production
 *     system would use hashed passwords, HTTPS, and expiring tokens.
 *
 * Uses only Java's built-in com.sun.net.httpserver — no external
 * dependencies or build tools required. Run with:
 *   javac -d out ParkingSystem.java ParkingServer.java
 *   java -cp out ParkingServer
 * then open http://localhost:8000 in a browser.
 */
public class ParkingServer {

    private static final ParkingSystem parkingSystem = new ParkingSystem(10); // 10 demo slots
    private static final Map<String, String> sessionTokens = new ConcurrentHashMap<>(); // token -> role
    private static final String USERS_FILE = "users.txt";

    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(8000), 0);

        server.createContext("/", ParkingServer::handleHomePage);
        server.createContext("/login", ParkingServer::handleLogin);

        server.createContext("/availability", ParkingServer::handleAvailability);
        server.createContext("/entry", ParkingServer::handleEntry);
        server.createContext("/exit", ParkingServer::handleExit);
        server.createContext("/receipts", ParkingServer::handleReceipts);
        server.createContext("/payment-methods", ParkingServer::handlePaymentMethodsPublic);
        server.createContext("/slots", ParkingServer::handleSlots);
        server.createContext("/active", ParkingServer::handleActive);

        // Admin-only
        server.createContext("/admin/tariffs", ParkingServer::handleAdminTariffs);
        server.createContext("/admin/payment-methods", ParkingServer::handleAdminPaymentMethods);

        server.setExecutor(null);
        server.start();
        System.out.println("Parking system running at http://localhost:8000");
    }

    // ---------- M8: Login & Role Access ----------
    private static void handleLogin(HttpExchange exchange) throws IOException {
        Map<String, String> params = queryParams(exchange.getRequestURI());
        String username = params.getOrDefault("username", "").trim();
        String password = params.getOrDefault("password", "").trim();

        String role = checkCredentials(username, password);
        if (role == null) {
            sendResponse(exchange, 401, "{\"error\":\"Invalid username or password\"}", "application/json");
            return;
        }

        String token = UUID.randomUUID().toString();
        sessionTokens.put(token, role);
        sendResponse(exchange, 200, "{\"token\":\"" + token + "\",\"role\":\"" + role + "\"}", "application/json");
    }

    // Reads users.txt fresh each login, so adding/removing accounts needs no restart
    private static String checkCredentials(String username, String password) {
        try {
            List<String> lines = Files.readAllLines(Paths.get(USERS_FILE), StandardCharsets.UTF_8);
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] parts = line.split(",");
                if (parts.length == 3 && parts[0].trim().equals(username) && parts[1].trim().equals(password)) {
                    return parts[2].trim(); // role
                }
            }
        } catch (IOException e) {
            System.err.println("Could not read users file: " + e.getMessage());
        }
        return null;
    }

    // Returns the role for a token, or null if not logged in
    private static String roleForToken(HttpExchange exchange) {
        Map<String, String> params = queryParams(exchange.getRequestURI());
        String token = params.getOrDefault("token", "");
        return sessionTokens.get(token);
    }

    // ---------- M1: home page — serves the separate dashboard.html file ----------
    private static void handleHomePage(HttpExchange exchange) throws IOException {
        try {
            byte[] html = Files.readAllBytes(Paths.get("dashboard.html"));
            exchange.getResponseHeaders().set("Content-Type", "text/html");
            exchange.sendResponseHeaders(200, html.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(html); }
        } catch (IOException e) {
            sendResponse(exchange, 500, "dashboard.html not found - make sure it's in the same folder as the compiled classes", "text/plain");
        }
    }

    // ---------- M1: JSON availability endpoint (public, no login needed) ----------
    private static void handleAvailability(HttpExchange exchange) throws IOException {
        int available = parkingSystem.checkAvailability();
        int total = parkingSystem.getTotalSlots();
        sendResponse(exchange, 200, "{\"available\":" + available + ",\"total\":" + total + "}", "application/json");
    }

    // Lets the dashboard populate the payment-method dropdown (any logged-in user)
    private static void handlePaymentMethodsPublic(HttpExchange exchange) throws IOException {
        if (roleForToken(exchange) == null) {
            sendResponse(exchange, 401, "{\"error\":\"Login required\"}", "application/json");
            return;
        }
        List<String> methods = parkingSystem.getEnabledPaymentMethods();
        sendResponse(exchange, 200, "[\"" + String.join("\",\"", methods) + "\"]", "application/json");
    }

    // ---------- Powers the visual slot grid (requires login) ----------
    private static void handleSlots(HttpExchange exchange) throws IOException {
        if (roleForToken(exchange) == null) {
            sendResponse(exchange, 401, "[]", "application/json");
            return;
        }
        StringBuilder json = new StringBuilder("[");
        List<ParkingSystem.ParkingSlot> slots = parkingSystem.getSlotsStatus();
        for (int i = 0; i < slots.size(); i++) {
            ParkingSystem.ParkingSlot s = slots.get(i);
            if (i > 0) json.append(",");
            json.append(String.format("{\"slotId\":%d,\"occupied\":%b,\"plate\":\"%s\"}",
                s.slotId, s.occupied, s.currentPlate == null ? "" : s.currentPlate));
        }
        json.append("]");
        sendResponse(exchange, 200, json.toString(), "application/json");
    }

    // ---------- Powers the "Currently Parked" live panel (requires login) ----------
    private static void handleActive(HttpExchange exchange) throws IOException {
        if (roleForToken(exchange) == null) {
            sendResponse(exchange, 401, "[]", "application/json");
            return;
        }
        StringBuilder json = new StringBuilder("[");
        List<ParkingSystem.SessionRecord> active = parkingSystem.getActiveSessionsList();
        for (int i = 0; i < active.size(); i++) {
            ParkingSystem.SessionRecord r = active.get(i);
            if (i > 0) json.append(",");
            json.append(String.format("{\"plate\":\"%s\",\"slotId\":%d,\"entryTime\":\"%s\"}",
                r.plateNumber, r.slotId, r.entryTime));
        }
        json.append("]");
        sendResponse(exchange, 200, json.toString(), "application/json");
    }

    // ---------- M2: Vehicle Entry (requires login) ----------
    private static void handleEntry(HttpExchange exchange) throws IOException {
        if (roleForToken(exchange) == null) {
            sendResponse(exchange, 401, "Login required", "text/plain");
            return;
        }
        Map<String, String> params = queryParams(exchange.getRequestURI());
        String plate = params.getOrDefault("plate", "").trim();
        if (plate.isEmpty()) {
            sendResponse(exchange, 400, "Plate number required", "text/plain");
            return;
        }
        sendResponse(exchange, 200, parkingSystem.vehicleEntry(plate), "text/plain");
    }

    // ---------- M3 + M4 + M5: Exit, fee, payment/barrier, slot release (requires login) ----------
    private static void handleExit(HttpExchange exchange) throws IOException {
        if (roleForToken(exchange) == null) {
            sendResponse(exchange, 401, "Login required", "text/plain");
            return;
        }
        Map<String, String> params = queryParams(exchange.getRequestURI());
        String plate = params.getOrDefault("plate", "").trim();
        String method = params.getOrDefault("method", "cash").trim();

        if (plate.isEmpty()) {
            sendResponse(exchange, 400, "Plate number required", "text/plain");
            return;
        }

        try {
            ParkingSystem.SessionRecord bill = parkingSystem.computeExitBill(plate);

            // M4: Payment & Barrier Control (simulated — a real system would wait
            // for an actual M-Pesa/card/cash confirmation callback here)
            boolean paymentConfirmed = true;
            if (!paymentConfirmed) {
                sendResponse(exchange, 402, "Payment pending - barrier stays closed", "text/plain");
                return;
            }

            parkingSystem.releaseSlot(plate, method);

            String result = String.format(
                "Vehicle %s | Duration: %d min | Amount: Kshs %.2f | Paid via: %s | Barrier: OPENED",
                plate, bill.durationMinutes, bill.amountDue, method
            );
            sendResponse(exchange, 200, result, "text/plain");

        } catch (NoSuchElementException e) {
            sendResponse(exchange, 404, e.getMessage(), "text/plain");
        }
    }

    // ---------- M7: Auditable Record endpoint (requires login) ----------
    private static void handleReceipts(HttpExchange exchange) throws IOException {
        if (roleForToken(exchange) == null) {
            sendResponse(exchange, 401, "Login required", "text/plain");
            return;
        }
        sendResponse(exchange, 200, parkingSystem.getReceiptsCsv(), "text/plain");
    }

    // ---------- M6: Admin — edit tariffs (admin role only) ----------
    private static void handleAdminTariffs(HttpExchange exchange) throws IOException {
        if (!"admin".equals(roleForToken(exchange))) {
            sendResponse(exchange, 403, "Admin login required", "text/plain");
            return;
        }
        if ("GET".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 200, parkingSystem.getTariffFileContent(), "text/plain");
        } else if ("POST".equals(exchange.getRequestMethod())) {
            String body = readBody(exchange);
            parkingSystem.setTariffFileContent(body);
            sendResponse(exchange, 200, "Tariffs updated", "text/plain");
        }
    }

    // ---------- Admin — edit payment methods (admin role only) ----------
    private static void handleAdminPaymentMethods(HttpExchange exchange) throws IOException {
        if (!"admin".equals(roleForToken(exchange))) {
            sendResponse(exchange, 403, "Admin login required", "text/plain");
            return;
        }
        if ("GET".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 200, parkingSystem.getPaymentMethodsFileContent(), "text/plain");
        } else if ("POST".equals(exchange.getRequestMethod())) {
            String body = readBody(exchange);
            parkingSystem.setPaymentMethodsFileContent(body);
            sendResponse(exchange, 200, "Payment methods updated", "text/plain");
        }
    }

    // ---------- Helpers ----------
    private static Map<String, String> queryParams(URI uri) {
        Map<String, String> params = new HashMap<>();
        String query = uri.getQuery();
        if (query == null) return params;
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) {
                try {
                    params.put(kv[0], java.net.URLDecoder.decode(kv[1], "UTF-8"));
                } catch (Exception e) {
                    params.put(kv[0], kv[1]);
                }
            }
        }
        return params;
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void sendResponse(HttpExchange exchange, int status, String body, String contentType) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
