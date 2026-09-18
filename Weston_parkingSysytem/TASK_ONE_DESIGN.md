# Task One — Modern Parking System (Data Structures & Algorithms)

## 1. Analysis of Client Terms of Reference → Proposed Modules

Reading the brief line by line:

| Client requirement (from TOR) | Module it implies |
|---|---|
| "drivers to see (visual display) of parking slots available **before entry**" | **M1: Slot Availability Module** |
| "records vehicles **on arrival**" | **M2: Vehicle Entry Module** |
| "automatically calculates total time spent... and amount to pay **at exit**" | **M3: Fee Calculation Module** |
| "barrier opens to allow exit **on payment**" | **M4: Payment & Barrier Control Module** |
| (implicit — a session must end, the slot must be freed for the next driver) | **M5: Vehicle Exit / Slot-Release Module** |

**Proposed Modules:** M1 Slot Availability · M2 Vehicle Entry · M3 Fee Calculation · M4 Payment & Barrier Control · M5 Vehicle Exit / Slot Release

---

## 2a. Algorithm for Each Module

### M1 — Slot Availability Module
```
function getAvailability():
    return freeSlotsQueue.size()   // O(1), always current
```

### M2 — Vehicle Entry Module
```
function vehicleEntry(plateNumber):
    if freeSlotsQueue.isEmpty():
        return "PARKING FULL"

    slotId = freeSlotsQueue.dequeue()
    slots[slotId].occupied = true
    slots[slotId].currentPlate = plateNumber

    session = SessionRecord(plateNumber, slotId, now())
    activeSessions[plateNumber] = session
    persistToDB(session)                 // INSERT

    return "Slot " + slotId + " assigned"
```

### M3 — Fee Calculation Module
Tariff (per client's TOR):

| Duration | Fee |
|---|---|
| Up to 30 minutes | **Free** |
| Up to 2 hours | Kshs 50 |
| Up to 4 hours | Kshs 100 |
| Up to 6 hours | Kshs 300 |
| Over 6 hours | Kshs 500 |

```
function calculateFee(durationMinutes):
    if durationMinutes <= 30:   return 0
    if durationMinutes <= 120:  return 50
    if durationMinutes <= 240:  return 100
    if durationMinutes <= 360:  return 300
    return 500
```

### M4 — Payment & Barrier Control Module
```
function processExitPayment(session):
    amountDue = calculateFee(session.durationMinutes)
    display(amountDue)                    // shown to driver at exit terminal

    paymentConfirmed = awaitPayment()      // cash / M-Pesa confirmation
    if paymentConfirmed:
        openBarrier()
        waitForVehicleToClearSensor()
        closeBarrier()
        return "EXIT GRANTED"
    else:
        return "PAYMENT PENDING - barrier stays closed"
```

### M5 — Vehicle Exit / Slot-Release Module
```
function vehicleExit(plateNumber):
    session = activeSessions.get(plateNumber)
    if session == null:
        return "ERROR: vehicle not found"

    session.exitTime = now()
    session.durationMinutes = minutesBetween(session.entryTime, session.exitTime)
    session.amountDue = calculateFee(session.durationMinutes)   // calls M3

    result = processExitPayment(session)   // calls M4
    if result != "EXIT GRANTED":
        return result

    slots[session.slotId].occupied = false
    slots[session.slotId].currentPlate = null
    freeSlotsQueue.enqueue(session.slotId)  // slot returns to pool → M1 updates instantly

    activeSessions.remove(plateNumber)
    history.add(session)
    updateDB(session)                       // UPDATE exit_time, amount, status

    return session.amountDue
```

---

## 2b. Data Structures Used & Reason for Each

| Data Structure | Used in Module | Reason for choosing it |
|---|---|---|
| **Array of `ParkingSlot` objects** | M1, M2, M5 | Fixed number of physical bays; direct index access by `slot_id` is O(1) — no searching needed. |
| **Queue (FIFO) of free slot IDs** (`Deque`) | M1, M2, M5 | Assigning the next free slot (M2) and returning a released slot (M5) are both O(1) operations — nothing beats a queue for "who's next / put this back" pool management. |
| **HashMap `plate_number → SessionRecord`** | M3, M4, M5 | A car can exit at any time, not in arrival order — need O(1) lookup by plate number, not a linear scan of every car ever parked. |
| **List/array of completed `SessionRecord`s** | M4, M5 (audit) | Append-only history for receipts, reporting, and dispute resolution; doesn't need fast lookup, just chronological storage. |

---

## 2c. Dynamic Database Design

"Dynamic" here means: **fee tiers, slot counts, and lot details are stored as data — not hard-coded** — so the client can change pricing or add slots/branches without touching the source code.

### Tables

**ParkingLots** *(supports more than one branch/site — makes the whole DB dynamic/scalable)*
| Column | Type |
|---|---|
| lot_id | INT PK |
| lot_name | VARCHAR |
| location | VARCHAR |

**Slots**
| Column | Type |
|---|---|
| slot_id | INT PK |
| lot_id | INT FK → ParkingLots |
| slot_number | VARCHAR |
| status | ENUM('FREE','OCCUPIED') |

**Vehicles**
| Column | Type |
|---|---|
| vehicle_id | INT PK |
| plate_number | VARCHAR UNIQUE |
| vehicle_type | VARCHAR |

**Tariffs** *(the "dynamic" pricing table — edit rows, not code)*
| Column | Type |
|---|---|
| tariff_id | INT PK |
| max_minutes | INT | *(30, 120, 240, 360, NULL=∞)* |
| amount | DECIMAL |

**ParkingSessions**
| Column | Type |
|---|---|
| session_id | INT PK |
| vehicle_id | INT FK → Vehicles |
| slot_id | INT FK → Slots |
| entry_time | DATETIME |
| exit_time | DATETIME NULL |
| duration_minutes | INT NULL |
| amount_charged | DECIMAL NULL |
| payment_status | ENUM('PENDING','PAID') |

**Payments**
| Column | Type |
|---|---|
| payment_id | INT PK |
| session_id | INT FK → ParkingSessions |
| amount | DECIMAL |
| method | VARCHAR *(cash / M-Pesa)* |
| transaction_ref | VARCHAR NULL |
| paid_at | DATETIME |

### Relationships
- One `ParkingLot` → many `Slots`.
- One `Vehicle` → many `ParkingSessions` (full history across visits).
- One `Slot` → many `ParkingSessions` over time, but only **one active** session (`exit_time IS NULL`) at a time.
- One `ParkingSession` → one `Payment` (created once M4 confirms payment).
- Fee lookup (M3) reads from `Tariffs` instead of a hard-coded value — this is what makes the database "dynamic."
