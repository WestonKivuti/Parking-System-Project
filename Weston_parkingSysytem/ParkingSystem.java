import java.util.*;
import java.time.*;
import java.time.temporal.ChronoUnit;

/**
 * Core parking logic — implements Modules M1 (Availability), M2 (Entry),
 * M3 (Fee Calculation), M5 (Exit / Slot Release).
 * M4 (Payment & Barrier Control) is handled by the caller (ParkingServer)
 * after this class reports the amount due.
 *
 * Data structures used (see TASK_ONE_DESIGN.md section 2b for full reasoning):
 *  - ParkingSlot[]                   : fixed slots, O(1) index access
 *  - Deque<Integer> freeSlotsQueue   : O(1) allocate / release of a slot
 *  - HashMap<String, SessionRecord>  : O(1) lookup of an active session by plate
 *  - List<SessionRecord> history     : append-only log for receipts/reporting
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

    public ParkingSystem(int totalSlots) {
        slots = new ParkingSlot[totalSlots];
        for (int i = 0; i < totalSlots; i++) {
            slots[i] = new ParkingSlot(i);
            freeSlotsQueue.add(i);
        }
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
    // Tariff (per client TOR): 0-30min free, <=2hr 50, <=4hr 100, <=6hr 300, >6hr 500
    // In a live system this reads the Tariffs table (see schema.sql) instead of
    // being hard-coded, so the client can change prices without redeploying code.
    private double calculateFee(long minutesParked) {
        if (minutesParked <= 30) return 0;
        if (minutesParked <= 120) return 50;
        if (minutesParked <= 240) return 100;
        if (minutesParked <= 360) return 300;
        return 500;
    }

    // ---------- M5: Vehicle Exit / Slot-Release Module ----------
    // (Calls M3 for the fee; M4 payment/barrier confirmation happens in ParkingServer
    //  before this method is trusted to actually free the slot.)
    public synchronized SessionRecord computeExitBill(String plateNumber) {
        SessionRecord record = activeSessions.get(plateNumber); // O(1)
        if (record == null) {
            throw new NoSuchElementException("Vehicle not found: " + plateNumber);
        }
        record.exitTime = LocalDateTime.now();
        record.durationMinutes = ChronoUnit.MINUTES.between(record.entryTime, record.exitTime);
        record.amountDue = calculateFee(record.durationMinutes);
        return record;
    }

    // Called only after M4 confirms payment succeeded.
    public synchronized void releaseSlot(String plateNumber) {
        SessionRecord record = activeSessions.remove(plateNumber); // O(1)
        if (record == null) return;

        slots[record.slotId].occupied = false;
        slots[record.slotId].currentPlate = null;
        freeSlotsQueue.add(record.slotId); // O(1) — back into the pool, M1 reflects it instantly

        history.add(record);
        // updateDB(record); // UPDATE ParkingSessions SET exit_time=..., amount_charged=...
    }

    public List<SessionRecord> getHistory() {
        return history;
    }
}
