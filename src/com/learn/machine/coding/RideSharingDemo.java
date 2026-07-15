package com.learn.machine.coding;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/* ============================================================
   RIDE-SHARING APP (Uber/Ola-lite) - Machine Coding
   Patterns used: Strategy (matching, pricing), State (trip lifecycle)
   ============================================================ */

// ---------- Enums ----------

enum DriverStatus { AVAILABLE, BUSY, OFFLINE }
enum TripState { REQUESTED, ACCEPTED, ONGOING, COMPLETED, CANCELLED }

// ---------- Entities ----------

class Location {
    private final double lat;
    private final double lng;

    public Location(double lat, double lng) {
        this.lat = lat;
        this.lng = lng;
    }

    public double getLat() { return lat; }
    public double getLng() { return lng; }

    // Simplified Euclidean distance for interview purposes.
    // Production would use the Haversine formula for real geo-distance
    // on a sphere - flagging this trade-off explicitly.
    public double distanceTo(Location other) {
        double dLat = this.lat - other.lat;
        double dLng = this.lng - other.lng;
        return Math.sqrt(dLat * dLat + dLng * dLng);
    }

    @Override
    public String toString() {
        return String.format("(%.2f, %.2f)", lat, lng);
    }
}

class Driver {
    private final String id;
    private final String name;
    private volatile Location currentLocation;
    private volatile DriverStatus status = DriverStatus.OFFLINE;
    // Average rating out of 5, updated after every completed trip.
    private volatile double rating;
    private volatile int ratingCount;

    public Driver(String id, String name, Location location) {
        this(id, name, location, 5.0); // new drivers start at a neutral 5.0
    }

    public Driver(String id, String name, Location location, double initialRating) {
        this.id = id;
        this.name = name;
        this.currentLocation = location;
        this.rating = initialRating;
        this.ratingCount = 1;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public Location getCurrentLocation() { return currentLocation; }
    public void setCurrentLocation(Location location) { this.currentLocation = location; }
    public DriverStatus getStatus() { return status; }
    public void setStatus(DriverStatus status) { this.status = status; }
    public double getRating() { return rating; }

    // Running average, not just overwrite - one bad rating shouldn't
    // wipe out a driver's history from hundreds of prior trips.
    public synchronized void addRating(double newRating) {
        if (newRating < 0 || newRating > 5) {
            throw new IllegalArgumentException("Rating must be between 0 and 5");
        }
        this.rating = ((rating * ratingCount) + newRating) / (ratingCount + 1);
        this.ratingCount++;
    }

    @Override
    public String toString() {
        return String.format("%s(%s, %.1f*)", name, status, rating);
    }
}

class Rider {
    private final String id;
    private final String name;

    public Rider(String id, String name) {
        this.id = id;
        this.name = name;
    }

    public String getId() { return id; }
    public String getName() { return name; }

    @Override
    public String toString() { return name; }
}

// ---------- Driver matching strategy ----------

interface DriverMatchingStrategy {
    Optional<Driver> findMatch(Location pickup, List<Driver> availableDrivers);
}

class NearestDriverStrategy implements DriverMatchingStrategy {
    @Override
    public Optional<Driver> findMatch(Location pickup, List<Driver> availableDrivers) {
        // No status filtering here - that is DriverManager's job.
        // This strategy only decides "which one is nearest".
        return availableDrivers.stream()
                .min(Comparator.comparingDouble(d -> d.getCurrentLocation().distanceTo(pickup)));
    }
}

// Combines proximity and driver rating into a single score, instead of
// matching on distance alone. Lower score wins. Weights are tunable so
// a product owner could dial up "prioritize rating" vs "prioritize ETA"
// without touching RideService.
class BestMatchStrategy implements DriverMatchingStrategy {
    private final double distanceWeight;
    private final double ratingWeight;

    public BestMatchStrategy(double distanceWeight, double ratingWeight) {
        this.distanceWeight = distanceWeight;
        this.ratingWeight = ratingWeight;
    }

    @Override
    public Optional<Driver> findMatch(Location pickup, List<Driver> availableDrivers) {
        if (availableDrivers.isEmpty()) return Optional.empty();

        // Rating is 0-5 (higher is better), distance is unbounded (lower is
        // better) - normalize rating onto a comparable "penalty" scale
        // before combining, so one signal doesn't dominate purely due to
        // unit scale.
        double maxDistance = availableDrivers.stream()
                .mapToDouble(d -> d.getCurrentLocation().distanceTo(pickup))
                .max().orElse(1.0);
        if (maxDistance == 0) maxDistance = 1.0; // avoid divide-by-zero

        double finalMaxDistance = maxDistance;
        return availableDrivers.stream()
                .min(Comparator.comparingDouble(d -> score(d, pickup, finalMaxDistance)));
    }

    private double score(Driver d, Location pickup, double maxDistance) {
        double normalizedDistance = d.getCurrentLocation().distanceTo(pickup) / maxDistance; // 0..1, lower is better
        double normalizedRatingPenalty = (5.0 - d.getRating()) / 5.0; // 0..1, lower is better
        return distanceWeight * normalizedDistance + ratingWeight * normalizedRatingPenalty;
    }
}

// ---------- Pricing strategy ----------

interface PricingStrategy {
    double calculateFare(double distanceKm, long durationMinutes);
}

class FlatRatePricing implements PricingStrategy {
    private static final double RATE_PER_KM = 10.0;

    @Override
    public double calculateFare(double distanceKm, long durationMinutes) {
        return distanceKm * RATE_PER_KM;
    }
}

class SurgePricing implements PricingStrategy {
    private static final double BASE_RATE_PER_KM = 10.0;
    private final double surgeMultiplier;

    public SurgePricing(double surgeMultiplier) {
        this.surgeMultiplier = surgeMultiplier;
    }

    @Override
    public double calculateFare(double distanceKm, long durationMinutes) {
        return distanceKm * BASE_RATE_PER_KM * surgeMultiplier;
    }
}

// ---------- Trip: the state machine ----------

class Trip {
    private final String id;
    private final Rider rider;
    private final Location pickup;
    private final Location drop;
    private Driver driver;
    private TripState state = TripState.REQUESTED;
    private double fare;

    public Trip(String id, Rider rider, Location pickup, Location drop) {
        this.id = id;
        this.rider = rider;
        this.pickup = pickup;
        this.drop = drop;
    }

    public void assignDriver(Driver driver) {
        if (state != TripState.REQUESTED) {
            throw new IllegalStateException("Cannot assign driver in state " + state);
        }
        this.driver = driver;
        this.state = TripState.ACCEPTED;
        driver.setStatus(DriverStatus.BUSY);
    }

    public void startTrip() {
        if (state != TripState.ACCEPTED) {
            throw new IllegalStateException("Cannot start trip in state " + state);
        }
        this.state = TripState.ONGOING;
    }

    public void completeTrip(PricingStrategy pricingStrategy, double distanceKm, long durationMinutes) {
        if (state != TripState.ONGOING) {
            throw new IllegalStateException("Cannot complete trip in state " + state);
        }
        this.fare = pricingStrategy.calculateFare(distanceKm, durationMinutes);
        this.state = TripState.COMPLETED;
        driver.setStatus(DriverStatus.AVAILABLE);
    }

    // Optional post-trip rating from the rider. Kept separate from
    // completeTrip() since rating can arrive later (or not at all) -
    // it shouldn't block the trip from being marked COMPLETED.
    public void rateDriver(double ratingValue) {
        if (state != TripState.COMPLETED) {
            throw new IllegalStateException("Cannot rate a trip in state " + state);
        }
        driver.addRating(ratingValue);
    }

    public void cancelTrip() {
        if (state == TripState.COMPLETED) {
            throw new IllegalStateException("Cannot cancel a completed trip");
        }
        if (state == TripState.CANCELLED) {
            throw new IllegalStateException("Trip already cancelled");
        }
        this.state = TripState.CANCELLED;
        if (driver != null) driver.setStatus(DriverStatus.AVAILABLE);
    }

    public String getId() { return id; }
    public Rider getRider() { return rider; }
    public Driver getDriver() { return driver; }
    public Location getPickup() { return pickup; }
    public Location getDrop() { return drop; }
    public TripState getState() { return state; }
    public double getFare() { return fare; }

    @Override
    public String toString() {
        return String.format("Trip[%s] %s state=%s fare=%.2f", id, rider, state, fare);
    }
}

// ---------- Driver management ----------

class DriverManager {
    private final Map<String, Driver> drivers = new ConcurrentHashMap<>();

    public void registerDriver(Driver driver) {
        drivers.put(driver.getId(), driver);
    }

    public Driver getDriver(String driverId) {
        return drivers.get(driverId);
    }

    public List<Driver> getAvailableDrivers() {
        return drivers.values().stream()
                .filter(d -> d.getStatus() == DriverStatus.AVAILABLE)
                .collect(Collectors.toList());
    }

    public void goOnline(String driverId) {
        Driver d = drivers.get(driverId);
        if (d != null) d.setStatus(DriverStatus.AVAILABLE);
    }

    public void goOffline(String driverId) {
        Driver d = drivers.get(driverId);
        if (d != null) d.setStatus(DriverStatus.OFFLINE);
    }
}

// ---------- Rider management ----------

class RiderManager {
    private final Map<String, Rider> riders = new ConcurrentHashMap<>();
    // riderId -> id of their currently active (non-terminal) trip, if any.
    // Prevents the same rider from holding two simultaneous active trips.
    private final Map<String, String> activeTripByRider = new ConcurrentHashMap<>();

    public void registerRider(Rider rider) {
        riders.put(rider.getId(), rider);
    }

    public Rider getRider(String riderId) {
        return riders.get(riderId);
    }

    public boolean hasActiveTrip(String riderId) {
        return activeTripByRider.containsKey(riderId);
    }

    public void markTripActive(String riderId, String tripId) {
        activeTripByRider.put(riderId, tripId);
    }

    public void clearActiveTrip(String riderId) {
        activeTripByRider.remove(riderId);
    }
}

// ---------- RideService: orchestration only ----------

class RideService {
    private final DriverManager driverManager;
    private final RiderManager riderManager;
    private final DriverMatchingStrategy matchingStrategy;
    private final PricingStrategy pricingStrategy;
    private final Map<String, Trip> trips = new ConcurrentHashMap<>();
    // Lock is scoped only around "find an available driver + assign them" -
    // that is the one step that must be atomic to stop two riders from
    // grabbing the same available driver in a race. Everything else can
    // run without holding this lock.
    private final Object matchLock = new Object();

    public RideService(DriverManager driverManager, RiderManager riderManager,
                       DriverMatchingStrategy matchingStrategy, PricingStrategy pricingStrategy) {
        this.driverManager = driverManager;
        this.riderManager = riderManager;
        this.matchingStrategy = matchingStrategy;
        this.pricingStrategy = pricingStrategy;
    }

    public Optional<Trip> requestRide(Rider rider, Location pickup, Location drop) {
        if (riderManager.hasActiveTrip(rider.getId())) {
            throw new IllegalStateException(rider.getName() + " already has an active trip");
        }

        Trip trip = new Trip(UUID.randomUUID().toString(), rider, pickup, drop);

        synchronized (matchLock) {
            List<Driver> available = driverManager.getAvailableDrivers();
            Optional<Driver> match = matchingStrategy.findMatch(pickup, available);
            if (!match.isPresent()) {
                return Optional.empty();
            }
            trip.assignDriver(match.get());
        }

        trips.put(trip.getId(), trip);
        riderManager.markTripActive(rider.getId(), trip.getId());
        return Optional.of(trip);
    }

    public void startTrip(String tripId) {
        getTripOrThrow(tripId).startTrip();
    }

    public void completeTrip(String tripId, double distanceKm, long durationMinutes) {
        Trip trip = getTripOrThrow(tripId);
        trip.completeTrip(pricingStrategy, distanceKm, durationMinutes);
        riderManager.clearActiveTrip(trip.getRider().getId());
    }

    public void cancelTrip(String tripId) {
        Trip trip = getTripOrThrow(tripId);
        trip.cancelTrip();
        riderManager.clearActiveTrip(trip.getRider().getId());
    }

    public Trip getTrip(String tripId) {
        return getTripOrThrow(tripId);
    }

    private Trip getTripOrThrow(String tripId) {
        Trip trip = trips.get(tripId);
        if (trip == null) throw new NoSuchElementException("No trip with id " + tripId);
        return trip;
    }
}

// ---------- Demo Harness ----------

public class RideSharingDemo {
    public static void main(String[] args) {
        DriverManager driverManager = new DriverManager();
        RiderManager riderManager = new RiderManager();

        Driver d1 = new Driver("d1", "Driver Raj", new Location(12.90, 77.60));
        Driver d2 = new Driver("d2", "Driver Sam", new Location(12.95, 77.65));
        driverManager.registerDriver(d1);
        driverManager.registerDriver(d2);
        driverManager.goOnline("d1");
        driverManager.goOnline("d2");

        Rider alice = new Rider("r1", "Alice");
        riderManager.registerRider(alice);

        RideService rideService = new RideService(
                driverManager, riderManager,
                new NearestDriverStrategy(),
                new FlatRatePricing());

        // Happy path
        Location pickup = new Location(12.91, 77.61);
        Location drop = new Location(12.98, 77.70);

        Optional<Trip> tripOpt = rideService.requestRide(alice, pickup, drop);
        if (!tripOpt.isPresent()) {
            System.out.println("No driver available");
            return;
        }
        Trip trip = tripOpt.get();
        System.out.println("Matched: " + trip);
        System.out.println("Assigned driver status: " + trip.getDriver().getStatus());

        rideService.startTrip(trip.getId());
        System.out.println("After start: " + trip.getState());

        double distanceKm = pickup.distanceTo(drop) * 111; // rough deg-to-km for demo
        rideService.completeTrip(trip.getId(), distanceKm, 25);
        System.out.println("After complete: " + trip);
        System.out.println("Driver freed up: " + trip.getDriver().getStatus());

        // Edge case 1: rider books again after their earlier trip is
        // already completed - should succeed since clearActiveTrip freed them
        Optional<Trip> secondTripOpt = rideService.requestRide(alice, pickup, drop);
        System.out.println("Second request after completion: " + secondTripOpt.isPresent());

        // Edge case 1b: rider tries to double-book WHILE a trip is active
        try {
            rideService.requestRide(alice, pickup, drop);
            System.out.println("Unexpected: double-booking succeeded");
        } catch (IllegalStateException e) {
            System.out.println("Blocked double-booking as expected: " + e.getMessage());
        }

        // Edge case 2: no available driver
        driverManager.goOffline("d1");
        driverManager.goOffline("d2");
        Rider dave = new Rider("r4", "Dave");
        riderManager.registerRider(dave);
        Optional<Trip> noDriverTrip = rideService.requestRide(dave, pickup, drop);
        System.out.println("No-driver-available result: " + noDriverTrip.isPresent());

        // Edge case 3: invalid state transition - completing a trip that
        // never started
        driverManager.goOnline("d1");
        Rider bob = new Rider("r2", "Bob");
        riderManager.registerRider(bob);
        Trip bobTrip = rideService.requestRide(bob, pickup, drop).orElseThrow(null);
        try {
            rideService.completeTrip(bobTrip.getId(), 5, 10);
        } catch (IllegalStateException e) {
            System.out.println("Correctly rejected: " + e.getMessage());
        }

        // Edge case 4: cancelling an already-completed trip
        try {
            rideService.cancelTrip(trip.getId()); // trip is already COMPLETED
        } catch (IllegalStateException e) {
            System.out.println("Correctly rejected: " + e.getMessage());
        }

        // Edge case 5: surge pricing swap - zero changes to RideService needed
        RideService surgeService = new RideService(
                driverManager, riderManager,
                new NearestDriverStrategy(),
                new SurgePricing(1.8));
        Rider carol = new Rider("r3", "Carol");
        riderManager.registerRider(carol);
        driverManager.goOnline("d2");
        Trip surgeTrip = surgeService.requestRide(carol, pickup, drop).orElseThrow(null);
        surgeService.startTrip(surgeTrip.getId());
        surgeService.completeTrip(surgeTrip.getId(), 10.0, 20);
        System.out.println("Surge fare for 10km: " + surgeTrip.getFare());

        // ---- Rating-aware matching demo ----
        // A driver very close to pickup but poorly rated, vs one slightly
        // farther but excellently rated. Pure NearestDriverStrategy always
        // picks the close one; BestMatchStrategy weighs rating in too.
        DriverManager ratingDemoManager = new DriverManager();
        Driver closeLowRated = new Driver("d10", "Driver Poor", new Location(12.922, 77.622), 2.0);
        Driver farBut5Star = new Driver("d11", "Driver Great", new Location(12.930, 77.630), 5.0);
        ratingDemoManager.registerDriver(closeLowRated);
        ratingDemoManager.registerDriver(farBut5Star);
        ratingDemoManager.goOnline("d10");
        ratingDemoManager.goOnline("d11");

        Optional<Driver> nearestPick = new NearestDriverStrategy()
                .findMatch(pickup, ratingDemoManager.getAvailableDrivers());
        System.out.println("NearestDriverStrategy picks: " + nearestPick.get());

        Optional<Driver> bestMatchPick = new BestMatchStrategy(0.5, 0.5)
                .findMatch(pickup, ratingDemoManager.getAvailableDrivers());
        System.out.println("BestMatchStrategy (50/50) picks: " + bestMatchPick.get());

        // Demo of rating updating after a completed trip
        closeLowRated.addRating(4.0);
        System.out.println("Driver Poor rating after new 4.0 rating: "
                + String.format("%.2f", closeLowRated.getRating()));
    }
}

/* ============================================================
   TEST CASES TO HAVE READY (verbalize even if not all written)
   ============================================================
   1. nearestDriverStrategyPicksClosestAvailableDriver()
   2. requestRideFailsWhenNoDriversAvailable()
   3. requestRideBlockedWhileRiderHasActiveTrip()
   4. cannotStartTripBeforeDriverAssigned()
   5. cannotCompleteTripThatNeverStarted()
   6. cannotCancelAlreadyCompletedTrip()
   7. completingTripFreesDriverAndClearsRiderActiveTrip()
   8. surgePricingProducesHigherFareThanFlatRate()
   9. concurrentRequestsDontDoubleAssignSameDriver()  -- concurrency proof
   ============================================================ */
