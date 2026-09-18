-- Dynamic Database Design for Modern Parking System
-- "Dynamic" = pricing (Tariffs) and lot/slot counts are data, not hard-coded

CREATE TABLE ParkingLots (
    lot_id INT PRIMARY KEY AUTO_INCREMENT,
    lot_name VARCHAR(100) NOT NULL,
    location VARCHAR(150)
);

CREATE TABLE Slots (
    slot_id INT PRIMARY KEY AUTO_INCREMENT,
    lot_id INT NOT NULL,
    slot_number VARCHAR(20) NOT NULL,
    status ENUM('FREE','OCCUPIED') DEFAULT 'FREE',
    FOREIGN KEY (lot_id) REFERENCES ParkingLots(lot_id)
);

CREATE TABLE Vehicles (
    vehicle_id INT PRIMARY KEY AUTO_INCREMENT,
    plate_number VARCHAR(20) UNIQUE NOT NULL,
    vehicle_type VARCHAR(30)
);

-- Editable pricing tiers: change fees here, no code changes needed
CREATE TABLE Tariffs (
    tariff_id INT PRIMARY KEY AUTO_INCREMENT,
    max_minutes INT NULL,        -- NULL = "over six hours" (no upper bound)
    amount DECIMAL(10,2) NOT NULL
);

INSERT INTO Tariffs (max_minutes, amount) VALUES
(30, 0),
(120, 50),
(240, 100),
(360, 300),
(NULL, 500);

CREATE TABLE ParkingSessions (
    session_id INT PRIMARY KEY AUTO_INCREMENT,
    vehicle_id INT NOT NULL,
    slot_id INT NOT NULL,
    entry_time DATETIME NOT NULL,
    exit_time DATETIME NULL,
    duration_minutes INT NULL,
    amount_charged DECIMAL(10,2) NULL,
    payment_status ENUM('PENDING','PAID') DEFAULT 'PENDING',
    FOREIGN KEY (vehicle_id) REFERENCES Vehicles(vehicle_id),
    FOREIGN KEY (slot_id) REFERENCES Slots(slot_id)
);

CREATE TABLE Payments (
    payment_id INT PRIMARY KEY AUTO_INCREMENT,
    session_id INT NOT NULL,
    amount DECIMAL(10,2) NOT NULL,
    method VARCHAR(20) NOT NULL,        -- 'cash' or 'mpesa'
    transaction_ref VARCHAR(50) NULL,   -- M-Pesa code, if applicable
    paid_at DATETIME NOT NULL,
    FOREIGN KEY (session_id) REFERENCES ParkingSessions(session_id)
);

-- Handy view: live availability count per lot (mirrors the in-memory queue)
CREATE VIEW LotAvailability AS
SELECT lot_id, COUNT(*) AS free_slots
FROM Slots
WHERE status = 'FREE'
GROUP BY lot_id;
