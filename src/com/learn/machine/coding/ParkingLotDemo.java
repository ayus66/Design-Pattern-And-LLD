package com.learn.machine.coding;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

class ParkingFloor{
    private final int floorNum;
    private final Map<String, ParkingSpot> spots;

    public ParkingFloor(int floorNum) {
        this.floorNum = floorNum;
        this.spots = new ConcurrentHashMap<>();
    }

    public void addSpot(ParkingSpot spot){
        spots.put(spot.getSpotId(),spot);
    }

    public synchronized Optional<ParkingSpot> findAvailableSpot(Vehicle vehicle){
        return spots.values().stream()
                .filter(spot -> spot.isAvailable() && spot.canFitVehicle(vehicle))
                .sorted(Comparator.comparing(ParkingSpot::getSpotSize))
                .findFirst();
    }

    public void displayAvailableSpot(){
        System.out.println("---------Follwing Spot Are Availble for Floor No. "+ this.floorNum+"---------");

        Map<VehicleSize, Long> count = spots.values().stream()
                .filter(spot -> spot.isAvailable())
                .collect(Collectors.groupingBy(ParkingSpot::getSpotSize, Collectors.counting()));

        for(VehicleSize size : VehicleSize.values()){
            System.out.println("Spot Size: "  + size + " No of Available spot: " + count.getOrDefault(size,0L));
        }
    }
}

class ParkingSpot{
    private final VehicleSize spotSize;
    private final String spotId;
    private boolean isOccupied;
    private Vehicle vehicle;

    public ParkingSpot(VehicleSize spotSize, String spotId) {
        this.spotSize = spotSize;
        this.spotId = spotId;
    }

    public VehicleSize getSpotSize() {
        return spotSize;
    }

    public String getSpotId() {
        return spotId;
    }

    public Vehicle getVehicle() {
        return vehicle;
    }

    public boolean isOccupied() {
        return isOccupied;
    }

    public synchronized void parkVehicle(Vehicle vehicle){
        this.vehicle = vehicle;
        this.isOccupied = true;
    }

    public synchronized void unparkVehicle(){
        this.vehicle = null;
        this.isOccupied = false;
    }

    public synchronized boolean isAvailable(){
        return !this.isOccupied;
    }

    public boolean canFitVehicle(Vehicle vehicle){
        if (this.isOccupied) return false;

        switch (vehicle.getSize()){
            case BIG:
                return VehicleSize.BIG == spotSize;
            case MEDIUM:
                return VehicleSize.MEDIUM == spotSize || VehicleSize.BIG == spotSize;
            case SMALL:
                return VehicleSize.SMALL == spotSize;
            default:
                return false;
        }
    }
}

class ParkingTicket{
    private final ParkingSpot spot;
    private final Vehicle vehicle;
    private final String ticketID;
    private final long entryTimeStamp;
    private long exitTimeStamp;

    public ParkingTicket(ParkingSpot spot, Vehicle vehicle) {
        this.spot = spot;
        this.vehicle = vehicle;
        this.ticketID = UUID.randomUUID().toString();
        this.entryTimeStamp = new Date().getTime();
    }

    public ParkingSpot getSpot() {
        return spot;
    }

    public Vehicle getVehicle() {
        return vehicle;
    }

    public String getTicketID() {
        return ticketID;
    }

    public long getEntryTimeStamp() {
        return entryTimeStamp;
    }

    public long getExitTimeStamp() {
        return exitTimeStamp;
    }

    public void setExitTimeStamp(){
        this.exitTimeStamp = new Date().getTime();
    }
}

enum VehicleSize{
    SMALL,
    MEDIUM,
    BIG
}

abstract class Vehicle{
    private final VehicleSize size;
    private final String licenseNumber;
    public Vehicle(VehicleSize size, String licenseNumber) {
        this.size = size;
        this.licenseNumber = licenseNumber;
    }

    public VehicleSize getSize() {
        return size;
    }

    public String getLicenseNumber() {
        return licenseNumber;
    }
}

class Bike extends Vehicle{
    public Bike(String license){
        super(VehicleSize.SMALL,license);
    }
}

class Car extends Vehicle{
    public Car(String license){
        super(VehicleSize.MEDIUM, license);
    }
}

class Truck extends Vehicle{
    public Truck(String license){
        super(VehicleSize.BIG, license);
    }
}

interface FeeStrategy {
    double calculateFee(ParkingTicket parkingTicket);
}

class vehicleBasedFeeStrategy implements FeeStrategy{

    private static final Map<VehicleSize, Double> HOURLY_RATES;

    static {
        Map<VehicleSize, Double> tempMap = new HashMap<>();
        tempMap.put(VehicleSize.SMALL, 10.0);
        tempMap.put(VehicleSize.MEDIUM, 20.0);
        tempMap.put(VehicleSize.BIG, 30.0);
        HOURLY_RATES = Collections.unmodifiableMap(tempMap);
    }


    @Override
    public double calculateFee(ParkingTicket parkingTicket) {
        long duration = parkingTicket.getExitTimeStamp() - parkingTicket.getEntryTimeStamp();
        long hours = (duration/(1000*60*60)) + 1;
        return HOURLY_RATES.get(parkingTicket.getVehicle().getSize()) * hours;

    }
}

interface ParkingStrategy {
    Optional<ParkingSpot> findSpot(List<ParkingFloor> floors, Vehicle vehicle);
}

class BestFitParkingStrategy implements ParkingStrategy{
    @Override
    public Optional<ParkingSpot> findSpot(List<ParkingFloor> floors, Vehicle vehicle) {
        Optional<ParkingSpot> bestSpot = Optional.empty();
        for(ParkingFloor floor : floors){
            Optional<ParkingSpot> spot = floor.findAvailableSpot(vehicle);
            if(spot.isPresent()){
                if (!bestSpot.isPresent() || spot.get().getSpotSize().ordinal() < bestSpot.get().getSpotSize().ordinal()) {
                    bestSpot = spot;
                }
            }
        }
        return bestSpot;
    }
}


class ParkingLotSystem{
    private static ParkingLotSystem INTANCE;
    private ParkingStrategy parkingStrategy;
    private FeeStrategy feeStrategy;
    private final List<ParkingFloor> floors;
    private final Map<String, ParkingTicket> activeTickets;
    private ParkingLotSystem(){
        this.parkingStrategy = new BestFitParkingStrategy();
        this.feeStrategy = new vehicleBasedFeeStrategy();
        this.floors = new ArrayList<>();
        this.activeTickets = new ConcurrentHashMap<>();
    }

    public static ParkingLotSystem getInstance(){
        if(INTANCE == null){
            synchronized (ParkingLotSystem.class){
                if(INTANCE == null){
                    INTANCE = new ParkingLotSystem();
                }
            }
        }
        return INTANCE;
    }

    public void addFloor(ParkingFloor floor) {
        floors.add(floor);
    }

    public void setFeeStrategy (FeeStrategy feeStrategy) {
        this.feeStrategy = feeStrategy;
    }

    public void setParkingStrategy(ParkingStrategy parkingStrategy) {
        this.parkingStrategy = parkingStrategy;
    }

    public Optional<ParkingTicket> parkVehicle(Vehicle vehicle){
        Optional<ParkingSpot> spot = parkingStrategy.findSpot(floors,vehicle);

        if(spot.isPresent()){
            ParkingSpot parkingSpot = spot.get();
            parkingSpot.parkVehicle(vehicle);
            ParkingTicket ticket = new ParkingTicket(parkingSpot,vehicle);
            activeTickets.put(vehicle.getLicenseNumber(), ticket);
            System.out.println("Vehicle with license Number : " + vehicle.getLicenseNumber() +
                    " has been parked in parking spot : " + parkingSpot.getSpotId());
            return Optional.of(ticket);
        }
        System.out.println("No Parking spot available for vehicle with license : "+ vehicle.getLicenseNumber());
        return Optional.empty();
    }

    public Optional<Double> unParkVehicle(Vehicle vehicle){
        ParkingTicket ticket = activeTickets.remove(vehicle.getLicenseNumber());
        if (ticket == null) {
            System.out.println("Ticket not found");
            return Optional.empty();
        }
        ParkingSpot spot = ticket.getSpot();
        spot.unparkVehicle();
        ticket.setExitTimeStamp();
        double price  = feeStrategy.calculateFee(ticket);
        return Optional.of(price);
    }
}

public class ParkingLotDemo {
    public static void main(String[] args) {
        ParkingLotSystem parkingLot = ParkingLotSystem.getInstance();

        // 1. Initialize the parking lot with floors and spots
        ParkingFloor floor1 = new ParkingFloor(1);
        floor1.addSpot(new ParkingSpot(VehicleSize.SMALL, "F1-S1"));
        floor1.addSpot(new ParkingSpot(VehicleSize.MEDIUM, "F1-M1"));
        floor1.addSpot(new ParkingSpot(VehicleSize.BIG, "F1-L1"));

        ParkingFloor floor2 = new ParkingFloor(2);
        floor2.addSpot(new ParkingSpot(VehicleSize.MEDIUM, "F2-M1"));
        floor2.addSpot(new ParkingSpot(VehicleSize.MEDIUM, "F2-M2"));

        parkingLot.addFloor(floor1);
        parkingLot.addFloor(floor2);

        System.out.println("\n--- Vehicle Entries ---");
        floor1.displayAvailableSpot();
        floor2.displayAvailableSpot();

        // 2. Simulate vehicle entries
        Vehicle bike = new Bike("B-123");
        Vehicle car = new Car("C-456");
        Vehicle truck = new Truck("T-789");

        Optional<ParkingTicket> bikeTicketOpt = parkingLot.parkVehicle(bike);

        Optional<ParkingTicket> carTicketOpt = parkingLot.parkVehicle(car);

        Optional<ParkingTicket> truckTicketOpt = parkingLot.parkVehicle(truck);

        System.out.println("\n--- Availability after parking ---");

        floor1.displayAvailableSpot();
        floor2.displayAvailableSpot();

        // 3. Simulate another car entry (should go to floor 2)
        Vehicle car2 = new Car("C-999");
        Optional<ParkingTicket> car2TicketOpt = parkingLot.parkVehicle(car2);

        // 4. Simulate a vehicle entry that fails (no available spots)
        Vehicle bike2 = new Bike("B-000");
        Optional<ParkingTicket> failedBikeTicketOpt = parkingLot.parkVehicle(bike2);

        // 5. Simulate vehicle exits and fee calculation
        System.out.println("\n--- Vehicle Exits ---");

        if (carTicketOpt.isPresent()) {
            Optional<Double> feeOpt = parkingLot.unParkVehicle(car);
            feeOpt.ifPresent(fee -> System.out.printf("Car C-456 unparked. Fee: $%.2f\n", fee));
        }

        System.out.println("\n--- Availability after one car leaves ---");

        floor1.displayAvailableSpot();
        floor2.displayAvailableSpot();

    }
}
