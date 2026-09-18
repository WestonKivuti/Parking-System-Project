import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Map;
import java.util.HashMap;
import java.nio.charset.StandardCharsets;

/**
 * Web layer for the Modern Parking System.
 * Implements Module M1 (visual display), wires M2/M3/M5 from ParkingSystem,
 * and implements Module M4 (Payment & Barrier Control) as a simulated
 * confirm-then-open step.
 *
 * Uses only Java's built-in com.sun.net.httpserver — no external
 * dependencies or build tools required. Just:
 *   javac -d out src/ParkingSystem.java src/ParkingServer.java
 *   java -cp out ParkingServer
 * then open http://localhost:8000 in a browser.
 */
public class ParkingServer {

    private static final ParkingSystem parkingSystem = new ParkingSystem(10); // 10 demo slots

    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(8000), 0);

        // M1: Slot Availability Module — the visual display drivers see before entry
        server.createContext("/", ParkingServer::handleHomePage);
        server.createContext("/availability", ParkingServer::handleAvailability);

        // M2: Vehicle Entry Module
        server.createContext("/entry", ParkingServer::handleEntry);

        // M3 + M4 + M5: fee calculation, payment/barrier, slot release
        server.createContext("/exit", ParkingServer::handleExit);

        server.setExecutor(null);
        server.start();
        System.out.println("Parking system running at http://localhost:8000");
    }

    // ---------- M1: home page (simple visual display) ----------
    private static void handleHomePage(HttpExchange exchange) throws IOException {
        String html = "<!DOCTYPE html><html><head><title>Modern Parking System</title></head><body>"
            + "<h1>Modern Parking System</h1>"
            + "<p>Available slots: <span id='count'>...</span> / " + parkingSystem.getTotalSlots() + "</p>"
            + "<h3>Vehicle Entry</h3>"
            + "<input id='entryPlate' placeholder='Plate number'>"
            + "<button onclick=\"post('/entry')\">Enter</button>"
            + "<h3>Vehicle Exit</h3>"
            + "<input id='exitPlate' placeholder='Plate number'>"
            + "<button onclick=\"post('/exit')\">Exit</button>"
            + "<p id='result'></p>"
            + "<script>"
            + "function refresh(){fetch('/availability').then(r=>r.json()).then(d=>{"
            + "document.getElementById('count').innerText=d.available;});}"
            + "function post(path){"
            + "var idField = path=='/entry' ? 'entryPlate' : 'exitPlate';"
            + "var plate=document.getElementById(idField).value;"
            + "fetch(path+'?plate='+encodeURIComponent(plate),{method:'POST'})"
            + ".then(r=>r.text()).then(t=>{document.getElementById('result').innerText=t; refresh();});}"
            + "refresh();"
            + "</script></body></html>";
        sendResponse(exchange, 200, html, "text/html");
    }

    // ---------- M1: JSON availability endpoint ----------
    private static void handleAvailability(HttpExchange exchange) throws IOException {
        int available = parkingSystem.checkAvailability();
        int total = parkingSystem.getTotalSlots();
        String json = "{\"available\":" + available + ",\"total\":" + total + "}";
        sendResponse(exchange, 200, json, "application/json");
    }

    // ---------- M2: Vehicle Entry endpoint ----------
    private static void handleEntry(HttpExchange exchange) throws IOException {
        Map<String, String> params = queryParams(exchange.getRequestURI());
        String plate = params.getOrDefault("plate", "").trim();

        if (plate.isEmpty()) {
            sendResponse(exchange, 400, "Plate number required", "text/plain");
            return;
        }

        String result = parkingSystem.vehicleEntry(plate);
        sendResponse(exchange, 200, result, "text/plain");
    }

    // ---------- M3 + M4 + M5: Exit, fee, payment/barrier, slot release ----------
    private static void handleExit(HttpExchange exchange) throws IOException {
        Map<String, String> params = queryParams(exchange.getRequestURI());
        String plate = params.getOrDefault("plate", "").trim();

        if (plate.isEmpty()) {
            sendResponse(exchange, 400, "Plate number required", "text/plain");
            return;
        }

        try {
            // M3: compute duration + fee
            ParkingSystem.SessionRecord bill = parkingSystem.computeExitBill(plate);

            // M4: Payment & Barrier Control (simulated — in production this would
            // wait for an actual cash/M-Pesa confirmation callback before proceeding)
            boolean paymentConfirmed = true; // simulated confirmation
            if (!paymentConfirmed) {
                sendResponse(exchange, 402, "Payment pending - barrier stays closed", "text/plain");
                return;
            }

            // Barrier opens, vehicle clears, M5 frees the slot
            parkingSystem.releaseSlot(plate);

            String result = String.format(
                "Vehicle %s | Duration: %d min | Amount: Kshs %.2f | Barrier: OPENED",
                plate, bill.durationMinutes, bill.amountDue
            );
            sendResponse(exchange, 200, result, "text/plain");

        } catch (java.util.NoSuchElementException e) {
            sendResponse(exchange, 404, e.getMessage(), "text/plain");
        }
    }

    // ---------- Helpers ----------
    private static Map<String, String> queryParams(URI uri) {
        Map<String, String> params = new HashMap<>();
        String query = uri.getQuery();
        if (query == null) return params;
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) params.put(kv[0], kv[1]);
        }
        return params;
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
