import java.util.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;

/**
 * Core parking logic — implements Modules M1 (Availability), M2 (Entry),
 * M3 (Fee Calculation), M5 (Exit / Slot Release), M6 (Dynamic Tariff),
 * and M7 (Auditable Record).
 * M4 (Payment & Barrier Control) is handled by the caller (ParkingServer),
 * which also owns login/roles (M8, see ParkingServer.java).
 *
 * Data structures used (see TASK_ONE_DESIGN.md section 2b for full reasoning):
 *  - ParkingSlot[]                   : fixed slots, O(1) index access
 *  - Deque<Integer> freeSlotsQueue   : O(1) allocate / release of a slot
 *  - HashMap<String, SessionRecord>  : O(1) lookup of an active session by plate
 *  - List<SessionRecord> history     : in-memory log, mirrored to receipts.csv
 *  - List<double[]> tariffTiers      : loaded fresh from tariffs.txt each time,
 *                                       so management edits rates with NO code change
 */
public class ParkingSystem {

    // ---------- Data structures ----------

    static class ParkingSlot {
        int slotId;
        boolean occupied;
        String currentPlate;

        ParkingSlot(int slotId) {
            this.slotId = slotId;
        }
    }

    static class SessionRecord {
        String plateNumber;
        int slotId;
        LocalDateTime entryTime;
        LocalDateTime exitTime;
        long durationMinutes;
        double amountDue;
        String paymentMethod = "cash"; // default, overwritten at exit

        SessionRecord(String plateNumber, int slotId, LocalDateTime entryTime) {
            this.plateNumber = plateNumber;
            this.slotId = slotId;
            this.entryTime = entryTime;
        }
    }

    private final ParkingSlot[] slots;
    private final Deque<Integer> freeSlotsQueue = new ArrayDeque<>();
    private final Map<String, SessionRecord> activeSessions = new HashMap<>();
    private final List<SessionRecord> history = new ArrayList<>();

    private static final String TARIFF_FILE = "tariffs.txt";
    private static final String RECEIPTS_FILE = "receipts.csv";
    private static final String PAYMENT_METHODS_FILE = "payment-methods.txt";

    public ParkingSystem(int totalSlots) {
        slots = new ParkingSlot[totalSlots];
        for (int i = 0; i < totalSlots; i++) {
            slots[i] = new ParkingSlot(i);
            freeSlotsQueue.add(i);
        }
        ensureReceiptsFileHasHeader();
    }

    // ---------- M1: Slot Availability Module ----------
    public int checkAvailability() {
        return freeSlotsQueue.size(); // O(1)
    }

    public int getTotalSlots() {
        return slots.length;
    }

    // ---------- M2: Vehicle Entry Module ----------
    public synchronized String vehicleEntry(String plateNumber) {
        if (activeSessions.containsKey(plateNumber)) {
            return "ERROR: vehicle already parked";
        }
        if (freeSlotsQueue.isEmpty()) {
            return "PARKING FULL";
        }

        int slotId = freeSlotsQueue.poll(); // O(1)
        slots[slotId].occupied = true;
        slots[slotId].currentPlate = plateNumber;

        SessionRecord record = new SessionRecord(plateNumber, slotId, LocalDateTime.now());
        activeSessions.put(plateNumber, record); // O(1)

        // persistToDB(record); // INSERT INTO ParkingSessions ...

        return "Slot " + slotId + " assigned to " + plateNumber;
    }

    // ---------- M3: Fee Calculation Module ----------
    // Tariffs are NOT hard-coded. They are read from tariffs.txt on every
    // call (M6), so management can change a price via the admin panel and
    // the very next exit uses the new rate — no code change, no recompile.
    private double calculateFee(long minutesParked) {
        List<double[]> tiers = loadTariffTiers();

        for (double[] tier : tiers) {
            double maxMinutes = tier[0];
            double amount = tier[1];
            if (maxMinutes < 0) return amount; // -1 = catch-all "over X" tier
            if (minutesParked <= maxMinutes) return amount;
        }
        // Fallback defaults if the file was empty/unreadable
        if (minutesParked <= 30) return 0;
        if (minutesParked <= 120) return 50;
        if (minutesParked <= 240) return 100;
        if (minutesParked <= 360) return 300;
        return 500;
    }

    private List<double[]> loadTariffTiers() {
        List<double[]> tiers = new ArrayList<>();
        try {
            List<String> lines = Files.readAllLines(Paths.get(TARIFF_FILE), StandardCharsets.UTF_8);
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] parts = line.split(",");
                if (parts.length != 2) continue;
                tiers.add(new double[]{Double.parseDouble(parts[0].trim()), Double.parseDouble(parts[1].trim())});
            }
        } catch (IOException | NumberFormatException e) {
            // Falls back to defaults in calculateFee()
        }
        return tiers;
    }

    // ---------- M6: Dynamic Tariff Module (admin panel support) ----------
    public String getTariffFileContent() {
        try {
            return new String(Files.readAllBytes(Paths.get(TARIFF_FILE)), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    public void setTariffFileContent(String content) throws IOException {
        Files.write(Paths.get(TARIFF_FILE), content.getBytes(StandardCharsets.UTF_8));
    }

    // ---------- Payment methods (admin-editable, mirrors the tariff pattern) ----------
    public List<String> getEnabledPaymentMethods() {
        List<String> methods = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(Paths.get(PAYMENT_METHODS_FILE), StandardCharsets.UTF_8)) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#")) methods.add(line);
            }
        } catch (IOException e) {
            methods.add("cash"); // safe fallback
        }
        return methods;
    }

    public String getPaymentMethodsFileContent() {
        try {
            return new String(Files.readAllBytes(Paths.get(PAYMENT_METHODS_FILE)), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    public void setPaymentMethodsFileContent(String content) throws IOException {
        Files.write(Paths.get(PAYMENT_METHODS_FILE), content.getBytes(StandardCharsets.UTF_8));
    }

    // ---------- M5: Vehicle Exit / Slot-Release Module ----------
    public synchronized SessionRecord computeExitBill(String plateNumber) {
        SessionRecord record = activeSessions.get(plateNumber);
        if (record == null) {
            throw new NoSuchElementException("Vehicle not found: " + plateNumber);
        }
        record.exitTime = LocalDateTime.now();
        record.durationMinutes = ChronoUnit.MINUTES.between(record.entryTime, record.exitTime);
        record.amountDue = calculateFee(record.durationMinutes);
        return record;
    }

    // Called only after M4 confirms payment succeeded.
    public synchronized void releaseSlot(String plateNumber, String paymentMethod) {
        SessionRecord record = activeSessions.remove(plateNumber);
        if (record == null) return;

        record.paymentMethod = (paymentMethod == null || paymentMethod.isEmpty()) ? "cash" : paymentMethod;

        slots[record.slotId].occupied = false;
        slots[record.slotId].currentPlate = null;
        freeSlotsQueue.add(record.slotId); // O(1) — back into the pool, M1 reflects it instantly

        history.add(record);
        appendReceipt(record); // M7 — permanent, auditable record of the shilling collected
        // updateDB(record);
    }

    public List<SessionRecord> getHistory() {
        return history;
    }

    // ---------- Supports the interactive dashboard: live slot grid ----------
    public synchronized List<ParkingSlot> getSlotsStatus() {
        return Arrays.asList(slots); // snapshot for rendering the visual slot grid
    }

    // ---------- Supports the interactive dashboard: "currently parked" panel ----------
    public synchronized List<SessionRecord> getActiveSessionsList() {
        return new ArrayList<>(activeSessions.values());
    }

    // ---------- M7: Auditable Record Module ----------
    private void ensureReceiptsFileHasHeader() {
        File file = new File(RECEIPTS_FILE);
        if (!file.exists()) {
            try (FileWriter fw = new FileWriter(file, true)) {
                fw.write("plate_number,slot_id,entry_time,exit_time,duration_minutes,amount_kes,payment_method\n");
            } catch (IOException e) {
                System.err.println("Could not create receipts file: " + e.getMessage());
            }
        }
    }

    private void appendReceipt(SessionRecord record) {
        try (FileWriter fw = new FileWriter(RECEIPTS_FILE, true)) {
            fw.write(String.format("%s,%d,%s,%s,%d,%.2f,%s%n",
                record.plateNumber, record.slotId, record.entryTime, record.exitTime,
                record.durationMinutes, record.amountDue, record.paymentMethod
            ));
        } catch (IOException e) {
            System.err.println("Could not write receipt: " + e.getMessage());
        }
    }

    public String getReceiptsCsv() {
        try {
            return new String(Files.readAllBytes(Paths.get(RECEIPTS_FILE)), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "plate_number,slot_id,entry_time,exit_time,duration_minutes,amount_kes,payment_method\n";
        }
    }
}
