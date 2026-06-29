package com.learn.design.pattern;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

class CartItem {
    private final String productId;
    private final int qty;
    private final double price;

    public CartItem(String productId, int qty, double price) {
        this.productId = productId;
        this.qty = qty;
        this.price = price;
    }

    public String getProductId() { return productId; }
    public int getQty() { return qty; }
    public double getPrice() { return price; }
}

class CartDTO {
    private final List<CartItem> items;
    private final String shippingAddress;
    private final String promoCode;
    private final String paymentMethod;

    public CartDTO(List<CartItem> items, String shippingAddress,
                   String promoCode, String paymentMethod) {
        this.items = items;
        this.shippingAddress = shippingAddress;
        this.promoCode = promoCode;
        this.paymentMethod = paymentMethod;
    }

    public List<CartItem> getItems() { return items; }
    public String getShippingAddress() { return shippingAddress; }
    public String getPromoCode() { return promoCode; }
    public String getPaymentMethod() { return paymentMethod; }
}

class PaymentResult {
    private final boolean success;
    private final String transactionId;
    private final String error;

    public PaymentResult(boolean success, String transactionId, String error) {
        this.success = success;
        this.transactionId = transactionId;
        this.error = error;
    }

    public boolean isSuccess() { return success; }
    public String getTransactionId() { return transactionId; }
    public String getError() { return error; }
}

class OrderDTO {
    private final String id;
    private final String userId;
    private final List<CartItem> items;
    private final double total;
    private final String transactionId;

    public OrderDTO(String id, String userId, List<CartItem> items,
                 double total, String transactionId) {
        this.id = id;
        this.userId = userId;
        this.items = items;
        this.total = total;
        this.transactionId = transactionId;
    }

    public String getId() { return id; }
    public String getUserId() { return userId; }
    public List<CartItem> getItems() { return items; }
    public double getTotal() { return total; }
    public String getTransactionId() { return transactionId; }
}

class OrderResult {
    private final String orderId;
    private final double total;
    private final String transactionId;

    public OrderResult(String orderId, double total, String transactionId) {
        this.orderId = orderId;
        this.total = total;
        this.transactionId = transactionId;
    }

    public String getOrderId() { return orderId; }
    public double getTotal() { return total; }
    public String getTransactionId() { return transactionId; }
}
class OutOfStockException extends RuntimeException {
    public OutOfStockException(String message) {
        super(message);
    }
}

class PaymentFailedException extends RuntimeException {
    public PaymentFailedException(String message) {
        super(message);
    }
}

class CheckoutException extends RuntimeException {
    public CheckoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
class InventoryService {

    public boolean checkStock(String productId, int qty) {
        System.out.println("Checking stock for " + productId + " (qty: " + qty + ")");
        return true; // simplified
    }

    public void reserveStock(String productId, int qty) {
        System.out.println("Reserved " + qty + " units of " + productId);
    }

    public void releaseStock(String productId, int qty) {
        System.out.println("Released " + qty + " units of " + productId);
    }
}

class PricingService {

    public double calculateSubtotal(List<CartItem> items) {
        double subtotal = items.stream()
                .mapToDouble(i -> i.getPrice() * i.getQty())
                .sum();
        System.out.println("Subtotal: ₹" + subtotal);
        return subtotal;
    }

    public double calculateTax(double subtotal, String address) {
        double tax = subtotal * 0.18; // 18% GST
        System.out.println("Tax (GST 18%): ₹" + tax);
        return tax;
    }

    public double applyPromoCode(String promoCode, double subtotal) {
        if ("FLAT500".equals(promoCode)) {
            System.out.println("Promo applied: -₹500");
            return 500.0;
        }
        return 0.0;
    }
}

class PaymentGateway {

    public PaymentResult charge(String userId, double amount, String paymentMethod) {
        System.out.println("Charging ₹" + amount + " via " + paymentMethod + " for user " + userId);
        return new PaymentResult(true, "TXN-" + System.currentTimeMillis(), null);
    }

    public void refund(String transactionId, double amount) {
        System.out.println("Refunding ₹" + amount + " for transaction " + transactionId);
    }
}

class OrderRepository {

    public OrderDTO createOrder(String userId, List<CartItem> items,
                             double total, String transactionId) {
        String orderId = "ORD-" + System.currentTimeMillis();
        System.out.println("Order created: " + orderId + " | Total: ₹" + total);
        return new OrderDTO(orderId, userId, items, total, transactionId);
    }
}

class NotificationService {

    public void sendOrderConfirmation(String userId, String orderId) {
        System.out.println("Email sent to " + userId + " for order " + orderId);
    }
}

class AnalyticsService {

    public void trackPurchase(String userId, String orderId, double amount) {
        System.out.println("Analytics: Purchase tracked — " + orderId + " ₹" + amount);
    }
}

class CheckoutFacade {

    private final InventoryService inventoryService;
    private final PricingService pricingService;
    private final PaymentGateway paymentGateway;
    private final OrderRepository orderRepository;
    private final NotificationService notificationService;
    private final AnalyticsService analyticsService;

    public CheckoutFacade(InventoryService inventoryService,
                          PricingService pricingService,
                          PaymentGateway paymentGateway,
                          OrderRepository orderRepository,
                          NotificationService notificationService,
                          AnalyticsService analyticsService) {
        this.inventoryService = inventoryService;
        this.pricingService = pricingService;
        this.paymentGateway = paymentGateway;
        this.orderRepository = orderRepository;
        this.notificationService = notificationService;
        this.analyticsService = analyticsService;
    }

    public OrderResult checkout(String userId, CartDTO cart) {
        // Step 1: Validate inventory
        validateStock(cart.getItems());

        // Step 2: Calculate pricing
        double subtotal = pricingService.calculateSubtotal(cart.getItems());
        double tax = pricingService.calculateTax(subtotal, cart.getShippingAddress());
        double discount = pricingService.applyPromoCode(cart.getPromoCode(), subtotal);
        double total = subtotal + tax - discount;

        // Step 3: Process payment
        PaymentResult payment = paymentGateway.charge(userId, total, cart.getPaymentMethod());
        if (!payment.isSuccess()) {
            throw new PaymentFailedException("Payment failed: " + payment.getError());
        }

        // Step 4: Reserve inventory (with rollback on failure)
        try {
            reserveStock(cart.getItems());
        } catch (Exception e) {
            paymentGateway.refund(payment.getTransactionId(), total);
            throw new CheckoutException("Inventory reservation failed, payment refunded", e);
        }

        // Step 5: Create order
        OrderDTO order = orderRepository.createOrder(
                userId, cart.getItems(), total, payment.getTransactionId()
        );

        // Step 6: Fire-and-forget side effects
        trySendNotification(userId, order.getId());
        tryTrackAnalytics(userId, order.getId(), total);

        return new OrderResult(order.getId(), total, payment.getTransactionId());
    }

    private void validateStock(List<CartItem> items) {
        for (CartItem item : items) {
            if (!inventoryService.checkStock(item.getProductId(), item.getQty())) {
                throw new OutOfStockException("Out of stock: " + item.getProductId());
            }
        }
    }

    private void reserveStock(List<CartItem> items) {
        List<CartItem> reserved = new ArrayList<>();
        try {
            for (CartItem item : items) {
                inventoryService.reserveStock(item.getProductId(), item.getQty());
                reserved.add(item);
            }
        } catch (Exception e) {
            // Rollback already-reserved items
            for (CartItem item : reserved) {
                inventoryService.releaseStock(item.getProductId(), item.getQty());
            }
            throw e;
        }
    }

    private void trySendNotification(String userId, String orderId) {
        try {
            notificationService.sendOrderConfirmation(userId, orderId);
        } catch (Exception e) {
            System.err.println("Notification failed (non-critical): " + e.getMessage());
        }
    }

    private void tryTrackAnalytics(String userId, String orderId, double total) {
        try {
            analyticsService.trackPurchase(userId, orderId, total);
        } catch (Exception e) {
            System.err.println("Analytics tracking failed (non-critical): " + e.getMessage());
        }
    }
}

class CheckoutController {

    private final CheckoutFacade checkoutFacade;

    public CheckoutController(CheckoutFacade checkoutFacade) {
        this.checkoutFacade = checkoutFacade;
    }

    public void checkout(String userId, CartDTO cart) {
        OrderResult result = checkoutFacade.checkout(userId, cart);
        System.out.println("Order placed: " + result.getOrderId() +
                " | Total: ₹" + result.getTotal());
    }
}

public class FacadePattern {

    public static void main(String[] args) {
        // 1. Create all subsystems
        InventoryService inventoryService = new InventoryService();
        PricingService pricingService = new PricingService();
        PaymentGateway paymentGateway = new PaymentGateway();
        OrderRepository orderRepository = new OrderRepository();
        NotificationService notificationService = new NotificationService();
        AnalyticsService analyticsService = new AnalyticsService();

        // 2. Create the Facade — pass all subsystems in
        CheckoutFacade checkoutFacade = new CheckoutFacade(
                inventoryService,
                pricingService,
                paymentGateway,
                orderRepository,
                notificationService,
                analyticsService
        );

        // 3. Create the controller — pass the Facade in
        CheckoutController controller = new CheckoutController(checkoutFacade);

        // 4. Use it — controller only knows about the Facade
        CartDTO cart = new CartDTO(
                Arrays.asList(
                        new CartItem("SKU-101", 2, 25000.0),
                        new CartItem("SKU-202", 1, 500.0)
                ),
                "560001",  // shipping address pincode
                "FLAT500", // promo code
                "UPI"      // payment method
        );

        controller.checkout("user-ayus", cart);

        // Output:
        // Checking stock for SKU-101 (qty: 2)
        // Checking stock for SKU-202 (qty: 1)
        // Subtotal: ₹50500.0
        // Tax (GST 18%): ₹9090.0
        // Promo applied: -₹500
        // Charging ₹59090.0 via UPI for user user-ayus
        // Reserved 2 units of SKU-101
        // Reserved 1 units of SKU-202
        // Order created: ORD-17198... | Total: ₹59090.0
        // Email sent to user-ayus for order ORD-17198...
        // Analytics: Purchase tracked — ORD-17198... ₹59090.0
        // Order placed: ORD-17198... | Total: ₹59090.0
    }
}
