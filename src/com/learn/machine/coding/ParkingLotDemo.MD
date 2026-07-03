# Parking Lot — Low Level Design

## Problem Statement

Design a multi-floor parking lot system that can park vehicles of different sizes, assign the best-fit spot, generate tickets, and calculate fees on exit.

---

## Design Patterns Used

| Pattern | Where |
|---|---|
| **Singleton** | `ParkingLotSystem` — single instance manages the entire lot |
| **Strategy** | `ParkingStrategy` (spot selection) and `FeeStrategy` (fee calculation) are swappable at runtime |

---

## Class Diagram

```
ParkingLotSystem (Singleton)
├── List<ParkingFloor>
│   └── Map<String, ParkingSpot>
│       └── Vehicle (parked)
├── Map<String, ParkingTicket>  (activeTickets by license number)
├── ParkingStrategy  ──►  BestFitParkingStrategy
└── FeeStrategy      ──►  vehicleBasedFeeStrategy

Vehicle (abstract)
├── Bike   → VehicleSize.SMALL
├── Car    → VehicleSize.MEDIUM
└── Truck  → VehicleSize.BIG

ParkingTicket
├── ParkingSpot
├── Vehicle
├── ticketID (UUID)
├── entryTimestamp
└── exitTimestamp
```

---

## Core Classes

### `ParkingLotSystem` (Singleton)

The central controller. Uses double-checked locking for thread-safe lazy initialization.

```java
ParkingLotSystem parkingLot = ParkingLotSystem.getInstance();
```

Key responsibilities:
- `parkVehicle(Vehicle)` — delegates spot search to `ParkingStrategy`, creates a `ParkingTicket`, stores it in `activeTickets`
- `unParkVehicle(Vehicle)` — removes ticket, frees the spot, delegates fee calculation to `FeeStrategy`
- `setParkingStrategy(...)` / `setFeeStrategy(...)` — swap strategies at runtime without changing core logic

---

### `ParkingFloor`

Holds a `ConcurrentHashMap` of `ParkingSpot`s. The `findAvailableSpot` method is `synchronized` and returns the smallest spot that can fit the vehicle (sorted by `VehicleSize` ordinal).

---

### `ParkingSpot`

Represents a single parking bay. Core logic lives in `canFitVehicle`:

| Vehicle Size | Fits in |
|---|---|
| SMALL (Bike) | SMALL only |
| MEDIUM (Car) | MEDIUM or BIG |
| BIG (Truck) | BIG only |

---

### `Vehicle` (abstract) & Subtypes

```
Vehicle
├── Bike  → SMALL
├── Car   → MEDIUM
└── Truck → BIG
```

---

### `ParkingTicket`

Issued on entry. Records the `ParkingSpot`, `Vehicle`, UUID ticket ID, and entry timestamp. Exit timestamp is set via `setExitTimeStamp()` just before fee calculation.

---

## Strategy Interfaces

### `ParkingStrategy`

```java
Optional<ParkingSpot> findSpot(List<ParkingFloor> floors, Vehicle vehicle);
```

**`BestFitParkingStrategy`** — iterates all floors, picks the spot whose size is the smallest possible match for the vehicle (minimizes wasted space).

### `FeeStrategy`

```java
double calculateFee(ParkingTicket parkingTicket);
```

**`vehicleBasedFeeStrategy`** — charges by the hour, rounded up, based on vehicle size:

| Vehicle | Rate |
|---|---|
| SMALL | $10 / hr |
| MEDIUM | $20 / hr |
| BIG | $30 / hr |

---

## Demo Walkthrough (`ParkingLotDemo.main`)

```
Floor 1: 1× SMALL (F1-S1), 1× MEDIUM (F1-M1), 1× BIG (F1-L1)
Floor 2: 2× MEDIUM (F2-M1, F2-M2)
```

| Step | Action | Result |
|---|---|---|
| 1 | Park Bike B-123 | Assigned F1-S1 (SMALL) |
| 2 | Park Car C-456 | Assigned F1-M1 (MEDIUM, best fit) |
| 3 | Park Truck T-789 | Assigned F1-L1 (BIG) |
| 4 | Park Car C-999 | Assigned F2-M1 (Floor 2) |
| 5 | Park Bike B-000 | **Failed** — no SMALL spot available |
| 6 | Unpark Car C-456 | Fee calculated, F1-M1 freed |

---

## Concurrency Notes

- `ParkingLotSystem.getInstance()` uses **double-checked locking** for thread-safe singleton creation.
- `ParkingFloor.findAvailableSpot` is `synchronized` to prevent two threads from claiming the same spot.
- `ParkingSpot.parkVehicle`, `unparkVehicle`, and `isAvailable` are `synchronized` for atomic state changes.
- `activeTickets` uses `ConcurrentHashMap` for safe concurrent ticket management.

---

## Extension Points

- **New fee model** — implement `FeeStrategy` (e.g., flat rate, time-of-day pricing) and call `setFeeStrategy(...)`.
- **New parking strategy** — implement `ParkingStrategy` (e.g., nearest-entrance, round-robin floors) and call `setParkingStrategy(...)`.
- **New vehicle type** — extend `Vehicle` with the appropriate `VehicleSize` and update `canFitVehicle` if needed.