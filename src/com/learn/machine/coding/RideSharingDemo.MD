# Ride Sharing App (Uber/Ola-lite) — Low Level Design

## Problem Statement

Design a ride-sharing system where riders can request trips, drivers are matched using swappable strategies, fares are calculated using swappable pricing, and the trip lifecycle is enforced through explicit state transitions — with guard rails against double-booking and invalid state jumps.

---

## Design Patterns Used

| Pattern | Where |
|---|---|
| **Strategy** | `DriverMatchingStrategy` (nearest / best-match) and `PricingStrategy` (flat rate / surge) — swappable at `RideService` construction |
| **State** | `Trip` enforces `REQUESTED → ACCEPTED → ONGOING → COMPLETED / CANCELLED` and rejects invalid transitions |

---

## Class Diagram

```
RideService
├── DriverManager         ──► Map<String, Driver>
├── RiderManager          ──► Map<String, Rider>
│                              Map<String, String>  activeTripByRider
├── DriverMatchingStrategy ──► NearestDriverStrategy | BestMatchStrategy
├── PricingStrategy        ──► FlatRatePricing | SurgePricing
└── Map<String, Trip>

Trip  (state machine)
├── Rider
├── Driver  (assigned after REQUESTED)
├── Location pickup / drop
├── TripState  (REQUESTED → ACCEPTED → ONGOING → COMPLETED | CANCELLED)
└── double fare  (set on COMPLETED)

Driver
├── id, name, currentLocation
├── volatile DriverStatus  (AVAILABLE / BUSY / OFFLINE)
└── rating  (running average, updated by addRating)

Rider
└── id, name

Location
└── distanceTo(other)  — Euclidean (trade-off: not true geo-distance)
```

---

## Core Classes

### `RideService`

Orchestration only — no business logic lives here beyond coordinating the managers, strategies, and trip store.

| Method | Description |
|---|---|
| `requestRide(rider, pickup, drop)` | Guards against double-booking, matches a driver atomically, creates and stores the trip |
| `startTrip(tripId)` | Delegates to `Trip.startTrip()` |
| `completeTrip(tripId, distanceKm, durationMinutes)` | Delegates to `Trip.completeTrip(pricingStrategy, ...)`, clears rider's active trip |
| `cancelTrip(tripId)` | Delegates to `Trip.cancelTrip()`, clears rider's active trip |

The **match-and-assign** step is the only section guarded by `matchLock` — this is the one window where two concurrent requests could grab the same available driver. Everything else runs lock-free.

---

### `Trip` (State Machine)

Each method checks the current state and throws `IllegalStateException` on an invalid transition.

```
REQUESTED ──assignDriver──► ACCEPTED ──startTrip──► ONGOING ──completeTrip──► COMPLETED
    │                           │                      │
    └─────────cancelTrip────────┴──────cancelTrip──────┘
```

| Transition | Guard |
|---|---|
| `assignDriver` | Must be `REQUESTED` |
| `startTrip` | Must be `ACCEPTED` |
| `completeTrip` | Must be `ONGOING` |
| `cancelTrip` | Must not be `COMPLETED` or already `CANCELLED` |
| `rateDriver` | Must be `COMPLETED` (rating is post-trip, non-blocking) |

On `completeTrip` the assigned driver is set back to `AVAILABLE`. On `cancelTrip` the driver is freed only if one was already assigned.

---

### `DriverManager`

Owns all driver records. Provides `getAvailableDrivers()` (status filter) used by `RideService` before delegating to the matching strategy. Status filtering is intentionally here, not in the strategy, so strategies stay simple.

---

### `RiderManager`

Tracks which riders have an active (non-terminal) trip via `activeTripByRider`. Prevents double-booking: `hasActiveTrip` is checked at the start of `requestRide` before any matching happens.

---

### `DriverMatchingStrategy`

```java
Optional<Driver> findMatch(Location pickup, List<Driver> availableDrivers);
```

| Implementation | Logic |
|---|---|
| `NearestDriverStrategy` | Picks the driver with minimum Euclidean distance to pickup |
| `BestMatchStrategy(distanceWeight, ratingWeight)` | Scores each driver as `distanceWeight × normalizedDistance + ratingWeight × ratingPenalty`; lower score wins. Weights are tunable. |

`BestMatchStrategy` normalizes both signals to `[0, 1]` before combining so neither unit scale dominates. Rating penalty = `(5.0 − rating) / 5.0`.

---

### `PricingStrategy`

```java
double calculateFare(double distanceKm, long durationMinutes);
```

| Implementation | Formula |
|---|---|
| `FlatRatePricing` | `distanceKm × ₹10` |
| `SurgePricing(multiplier)` | `distanceKm × ₹10 × multiplier` |

Swapped at `RideService` construction — zero changes to any other class.

---

### `Driver` — Rating

`addRating` uses a running average so one bad rating does not erase a long history:

```
newRating = (currentRating × ratingCount + newRating) / (ratingCount + 1)
```

The method is `synchronized` to prevent concurrent rating updates from racing. `currentLocation`, `status`, and `rating` are `volatile` for safe reads across threads without locking.

---

### `Location`

Uses simplified Euclidean distance. Known trade-off: production should use the **Haversine formula** for accurate geo-distance on a sphere. The `deg-to-km` conversion in the demo (`× 111`) is also an approximation.

---

## Demo Walkthrough (`RideSharingDemo.main`)

**Setup:** Driver Raj (12.90, 77.60), Driver Sam (12.95, 77.65); Rider Alice; pickup (12.91, 77.61)

| Step | Scenario | Result |
|---|---|---|
| 1 | Alice requests ride — `NearestDriverStrategy` | Matched to Driver Raj (closer to pickup) |
| 2 | `startTrip` | State → `ONGOING` |
| 3 | `completeTrip(distanceKm, 25min)` | Fare calculated, Driver Raj freed, state → `COMPLETED` |
| 4 | Alice requests again after completion | Succeeds — `clearActiveTrip` freed her |
| 5 | Alice double-books while trip is active | `IllegalStateException` — blocked as expected |
| 6 | Both drivers go offline; Dave requests | `Optional.empty()` — no driver available |
| 7 | Bob's trip: `completeTrip` without `startTrip` | `IllegalStateException` — invalid state transition |
| 8 | Cancel already-completed trip | `IllegalStateException` — correctly rejected |
| 9 | Carol with `SurgePricing(1.8)` for 10 km | Fare = `10 × 10 × 1.8 = ₹180.0` |
| 10 | `NearestDriverStrategy` vs `BestMatchStrategy(0.5, 0.5)` | Nearest picks the close low-rated driver; BestMatch may pick the farther 5-star driver |
| 11 | `addRating(4.0)` on a 2.0-rated driver | Running average updated — single rating doesn't wipe history |

---

## Concurrency Notes

- `matchLock` guards only the **find-available + assign** window to prevent two riders from grabbing the same driver simultaneously.
- `Driver.status`, `Driver.rating`, and `Driver.currentLocation` are `volatile` for safe cross-thread reads.
- `Driver.addRating` is `synchronized` to prevent lost updates from concurrent rating submissions.
- `DriverManager`, `RiderManager`, and `RideService.trips` use `ConcurrentHashMap` for thread-safe registry operations.

---

## Test Cases (Key Scenarios)

| # | Test |
|---|---|
| 1 | `nearestDriverStrategyPicksClosestAvailableDriver` |
| 2 | `requestRideFailsWhenNoDriversAvailable` |
| 3 | `requestRideBlockedWhileRiderHasActiveTrip` |
| 4 | `cannotStartTripBeforeDriverAssigned` |
| 5 | `cannotCompleteTripThatNeverStarted` |
| 6 | `cannotCancelAlreadyCompletedTrip` |
| 7 | `completingTripFreesDriverAndClearsRiderActiveTrip` |
| 8 | `surgePricingProducesHigherFareThanFlatRate` |
| 9 | `concurrentRequestsDontDoubleAssignSameDriver` |

---

## Extension Points

- **New matching strategy** — implement `DriverMatchingStrategy` (e.g., `PreferredDriverStrategy`, `GenderPreferenceStrategy`) and pass to `RideService`.
- **New pricing model** — implement `PricingStrategy` (e.g., time-based, minimum-fare) and pass to `RideService`.
- **Real geo-distance** — replace `Location.distanceTo` with Haversine; no other class changes.
- **Driver vehicle types** — add a `VehicleType` field to `Driver` and filter by it inside `getAvailableDrivers` or the matching strategy.