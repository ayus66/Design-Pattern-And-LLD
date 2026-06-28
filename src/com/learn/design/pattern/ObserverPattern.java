package com.learn.design.pattern;

/*
*
* Problem : Every new side-effect means modifying OrderService.
* SRP violated — OrderService knows about emailing, inventory, analytics.
* If one observer fails, it can break the entire flow.
* Adding/removing listeners requires recompilation.
*
* public class OrderService {

    private final EmailService emailService;
    private final InventoryService inventoryService;
    private final AnalyticsService analyticsService;

    public void placeOrder(Order order) {
        // core logic
        saveOrder(order);

        // tightly coupled side-effects
        emailService.sendConfirmation(order);      // what if email is down?
        inventoryService.reduceStock(order);         // what if we add SMS later?
        analyticsService.trackPurchase(order);       // modify this class every time
    }
}
* */

import java.time.LocalDateTime;
import java.util.*;

class OrderEvent {
    private final String orderId;
    private final String customerEmail;
    private final List<String> productIds;
    private final double totalAmount;
    private final LocalDateTime timestamp;

    public OrderEvent(String orderId, String customerEmail,
                      List<String> productIds, double totalAmount) {
        this.orderId = orderId;
        this.customerEmail = customerEmail;
        this.productIds = productIds;
        this.totalAmount = totalAmount;
        this.timestamp = LocalDateTime.now();
    }

    // getters only — event objects should be immutable
    public String getOrderId() { return orderId; }
    public String getCustomerEmail() { return customerEmail; }
    public List<String> getProductIds() { return productIds; }
    public double getTotalAmount() { return totalAmount; }
    public LocalDateTime getTimestamp() { return timestamp; }
}

class Order {
    private final String id;
    private final String customerEmail;
    private final List<String> productIds;
    private final double totalAmount;
    private final String warehousePincode;
    private final String trackingId;

    public Order(String id, String customerEmail, List<String> productIds, double totalAmount) {
        this(id, customerEmail, productIds, totalAmount, null, null);
    }

    public Order(String id, String customerEmail, List<String> productIds,
                 double totalAmount, String warehousePincode, String trackingId) {
        this.id = id;
        this.customerEmail = customerEmail;
        this.productIds = productIds;
        this.totalAmount = totalAmount;
        this.warehousePincode = warehousePincode;
        this.trackingId = trackingId;
    }

    public String getId() { return id; }
    public String getCustomerEmail() { return customerEmail; }
    public List<String> getProductIds() { return productIds; }
    public double getTotalAmount() { return totalAmount; }
    public String getWarehousePincode() { return warehousePincode; }
    public String getTrackingId() { return trackingId; }
}

interface OrderEventObserver{
    void onOrderPlaced(OrderEvent event);
}

class EmailNotificationObserver implements OrderEventObserver{

    @Override
    public void onOrderPlaced(OrderEvent event) {
        System.out.println("sending mail for order places for : " + event.getOrderId()
                + " to : " + event.getCustomerEmail());
    }
}

class InventoryObserver implements OrderEventObserver{
    @Override
    public void onOrderPlaced(OrderEvent event) {
        System.out.println("updating inventory for products : " + event.getProductIds());
    }
}

class AnalyticObserver implements OrderEventObserver{
    @Override
    public void onOrderPlaced(OrderEvent event){
        System.out.println("Tracking Purchase for order id : " + event.getOrderId() +
                " purchased at : " + event.getTimestamp());
    }
}

interface EventProcessor<T>{
    void addObserver(T observer);
    void removeObserver(T observer);
}

abstract class AbstractEventPublisher<T> implements EventProcessor<T>{
    private final Set<T> observers = new HashSet<>();

    @Override
    public void addObserver(T observer) {
        observers.add(observer);
    }

    @Override
    public void removeObserver(T observer) {
        observers.remove(observer);
    }

    public Set<T> getObservers(){
        return Collections.unmodifiableSet(observers);
    }
}

class OrderService extends AbstractEventPublisher<OrderEventObserver>{

    public void placeOrder(Order order){
        saveOrder(order);

        OrderEvent event = new OrderEvent(
                order.getId(),
                order.getCustomerEmail(),
                order.getProductIds(),
                order.getTotalAmount()
        );

        notifyObservers(event);
    }
    private void notifyObservers(OrderEvent event) {
        for (OrderEventObserver observer : getObservers()) {
            observer.onOrderPlaced(event);
        }
    }
    private void saveOrder(Order order) {
        System.out.println("Order saved: " + order.getId());
    }
}


public class ObserverPattern {
    public static void main(String[] args) {
        OrderService orderService = new OrderService();
        orderService.addObserver(new EmailNotificationObserver());
        orderService.addObserver(new InventoryObserver());
        orderService.addObserver(new AnalyticObserver());

        Order order = new Order("ORD-1001", "ayus@email.com",
                Arrays.asList("SKU-101", "SKU-202"), 62000.00);
        orderService.placeOrder(order);

        Order order2 = new Order("ORD-1002", "ayus1@email.com",
                Arrays.asList("SKU-102", "SKU-201"), 63000.00);
        orderService.placeOrder(order2);
    }
}
