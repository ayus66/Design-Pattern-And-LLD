# Facade Design Pattern — Detailed Notes

## 1. What Is It?

The Facade pattern is a **structural design pattern** that provides a simplified, unified interface to a complex subsystem. Instead of forcing the client to interact with dozens of classes, configure dependencies, and call methods in the right sequence, the Facade wraps it all behind one clean API.

**GoF Definition:** "Provide a unified interface to a set of interfaces in a subsystem. Facade defines a higher-level interface that makes the subsystem easier to use."

**Core Idea:** Hide complexity. The client talks to one class (the Facade), and the Facade coordinates the messy internals — multiple services, APIs, configurations, sequencing — behind the scenes.

**Also Known As:** Simplified Interface, Gateway.

**Real-world analogy:** A restaurant waiter is a Facade. You don't go to the kitchen to tell the chef what to cook, then walk to the bar for your drink, then find the dishwasher. You tell the waiter "I'll have the pasta and a coffee" — the waiter coordinates everything behind the scenes.

---

## 2. When to Use

- A subsystem has **many classes and interactions**, and clients only need a fraction of its capabilities.
- You want to **decouple client code** from the internal structure of a subsystem — if internals change, only the Facade changes.
- You need to **orchestrate multiple services** in a specific sequence for a single business operation.
- You're integrating a **third-party library** or **legacy system** and want to shield your code from its API quirks.
- You want to provide a **simple API for common use cases** while still allowing power users to access the subsystem directly.
- You're building a **service layer** in a layered architecture — the service layer IS a Facade over repositories, validators, mappers, and external APIs.

**Don't use when:**
- The subsystem is already simple — wrapping a single class adds unnecessary indirection.
- Clients genuinely need fine-grained control over every subsystem class — a Facade would limit them.
- You're hiding complexity that the caller *should* understand (e.g., transaction boundaries, error handling semantics).

---

## 3. UML Structure

```
┌──────────────────┐
│     Client        │
└────────┬─────────┘
         │ uses
         ▼
┌──────────────────────────────────────────┐
│              Facade                       │
├──────────────────────────────────────────┤
│ + simpleOperation(): Result               │
│                                           │
│   internally coordinates:                 │
│   → SubsystemA.stepOne()                  │
│   → SubsystemB.stepTwo()                  │
│   → SubsystemC.stepThree()               │
│   → SubsystemD.stepFour()                │
└──────────────────────────────────────────┘
         │ delegates to
         ▼
┌────────────┐ ┌────────────┐ ┌────────────┐ ┌────────────┐
│ SubsystemA │ │ SubsystemB │ │ SubsystemC │ │ SubsystemD │
└────────────┘ └────────────┘ └────────────┘ └────────────┘
```

**Three Participants:**

| Role | Responsibility |
|------|----------------|
| **Facade** | Provides simplified methods that orchestrate subsystem calls. Knows which subsystem classes handle which part of the request. |
| **Subsystem Classes** | Implement the actual functionality. Have no knowledge of the Facade — they don't reference it or depend on it. |
| **Client** | Uses the Facade instead of calling subsystem classes directly. Can still access subsystems directly if advanced control is needed. |

**Key characteristic:** The Facade doesn't add new functionality. It just orchestrates existing subsystem functionality behind a simpler API. The subsystem classes do all the real work.

---

## 4. Java Implementation

### 4.1 — Problem Without Facade

Consider an e-commerce checkout flow. To place an order, the client must coordinate multiple subsystems in the correct sequence:

```java
public class CheckoutController {

    public void checkout(CartDTO cart, String userId) {
        // Step 1: Validate inventory
        InventoryService inventoryService = new InventoryService();
        for (CartItem item : cart.getItems()) {
            boolean available = inventoryService.checkStock(item.getProductId(), item.getQty());
            if (!available) {
                throw new RuntimeException("Out of stock: " + item.getProductId());
            }
        }

        // Step 2: Calculate pricing
        PricingService pricingService = new PricingService();
        double subtotal = pricingService.calculateSubtotal(cart.getItems());
        double tax = pricingService.calculateTax(subtotal, cart.getShippingAddress());
        double discount = pricingService.applyPromoCode(cart.getPromoCode(), subtotal);
        double total = subtotal + tax - discount;

        // Step 3: Process payment
        PaymentGateway paymentGateway = new PaymentGateway();
        PaymentResult result = paymentGateway.charge(userId, total, cart.getPaymentMethod());
        if (!result.isSuccess()) {
            throw new RuntimeException("Payment failed: " + result.getError());
        }

        // Step 4: Reserve inventory
        for (CartItem item : cart.getItems()) {
            inventoryService.reserveStock(item.getProductId(), item.getQty());
        }

        // Step 5: Create order
        OrderRepository orderRepo = new OrderRepository();
        Order order = orderRepo.createOrder(userId, cart.getItems(), total, result.getTransactionId());

        // Step 6: Send notification
        NotificationService notificationService = new NotificationService();
        notificationService.sendOrderConfirmation(userId, order.getId());

        // Step 7: Update analytics
        AnalyticsService analyticsService = new AnalyticsService();
        analyticsService.trackPurchase(userId, order.getId(), total);
    }
}
```

**Problems:**
- Client knows about **7 subsystems** and their APIs.
- Client manages the **orchestration sequence** — if the order changes, every client breaks.
- **Error handling** is scattered — what happens if payment succeeds but inventory reservation fails? Client must handle rollback.
- **Duplicated** wherever checkout is needed (mobile API, web API, admin panel).
- **Untestable** — can't test the checkout flow without mocking 7 services.

### 4.2 — Solution With Facade

**Step 1: Subsystem Classes (Unchanged)**

```java
public class InventoryService {

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

public class PricingService {

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

public class PaymentGateway {

    public PaymentResult charge(String userId, double amount, String paymentMethod) {
        System.out.println("Charging ₹" + amount + " via " + paymentMethod + " for user " + userId);
        return new PaymentResult(true, "TXN-" + System.currentTimeMillis(), null);
    }

    public void refund(String transactionId, double amount) {
        System.out.println("Refunding ₹" + amount + " for transaction " + transactionId);
    }
}

public class OrderRepository {

    public Order createOrder(String userId, List<CartItem> items,
                             double total, String transactionId) {
        String orderId = "ORD-" + System.currentTimeMillis();
        System.out.println("Order created: " + orderId + " | Total: ₹" + total);
        return new Order(orderId, userId, items, total, transactionId);
    }
}

public class NotificationService {

    public void sendOrderConfirmation(String userId, String orderId) {
        System.out.println("Email sent to " + userId + " for order " + orderId);
    }
}

public class AnalyticsService {

    public void trackPurchase(String userId, String orderId, double amount) {
        System.out.println("Analytics: Purchase tracked — " + orderId + " ₹" + amount);
    }
}
```

**Step 2: The Facade**

```java
public class CheckoutFacade {

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
        Order order = orderRepository.createOrder(
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
```

**Step 3: Client Code**

```java
public class CheckoutController {

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
```

**Step 4: Wiring It All Together (Plain Java)**

The Facade takes 6 subsystems via constructor. In plain Java, someone has to create all of them — that's the **composition root** (typically `main()`):

```java
public class Main {
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
            List.of(
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
```

**In Spring Boot — the container does Step 1 and 2 for you:**

```java
// Spring creates all subsystems as beans, injects them into the Facade,
// and injects the Facade into the controller — no manual wiring

@Service
public class CheckoutFacade {

    // Spring auto-injects all 6 dependencies
    public CheckoutFacade(InventoryService inventoryService,
                          PricingService pricingService,
                          PaymentGateway paymentGateway,
                          OrderRepository orderRepository,
                          NotificationService notificationService,
                          AnalyticsService analyticsService) {
        // ...
    }
}

@RestController
public class CheckoutController {

    // Spring auto-injects the Facade
    public CheckoutController(CheckoutFacade checkoutFacade) {
        // ...
    }
}
```

**Where does `new` live?**

| Approach | Who creates subsystems? | Who creates Facade? | Who creates Controller? |
|----------|------------------------|--------------------|-----------------------|
| **Plain Java** | `main()` — manually | `main()` — manually | `main()` — manually |
| **Spring Boot** | Spring container | Spring container | Spring container |

> **The principle is the same in both:** Constructor injection ensures every class declares its dependencies explicitly. The only difference is *who does the wiring* — you in `main()`, or Spring's DI container. The business logic is identical.

**What changed:**

| Before (No Facade) | After (With Facade) |
|---------------------|---------------------|
| Client knows 7 subsystems | Client knows 1 class (`CheckoutFacade`) |
| Client manages sequencing | Facade manages sequencing |
| No rollback logic | Facade handles payment refund on inventory failure |
| Non-critical failures crash checkout | Facade wraps notifications/analytics in try-catch |
| Duplicated in every checkout entry point | One Facade, reused everywhere |
| Mocking 7 services in tests | Mock one Facade for controller tests |

---

## 5. Domain Models Used in the Example

```java
public class CartItem {
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

public class CartDTO {
    private final List<CartItem> items;
    private final String shippingAddress;
    private final String promoCode;
    private final String paymentMethod;

    public CartDTO(List<CartItem> items, String shippingAddress,
                   String promoCode, String paymentMethod) {
        this.items = List.copyOf(items);
        this.shippingAddress = shippingAddress;
        this.promoCode = promoCode;
        this.paymentMethod = paymentMethod;
    }

    public List<CartItem> getItems() { return items; }
    public String getShippingAddress() { return shippingAddress; }
    public String getPromoCode() { return promoCode; }
    public String getPaymentMethod() { return paymentMethod; }
}

public class PaymentResult {
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

public class Order {
    private final String id;
    private final String userId;
    private final List<CartItem> items;
    private final double total;
    private final String transactionId;

    public Order(String id, String userId, List<CartItem> items,
                 double total, String transactionId) {
        this.id = id;
        this.userId = userId;
        this.items = List.copyOf(items);
        this.total = total;
        this.transactionId = transactionId;
    }

    public String getId() { return id; }
    public String getUserId() { return userId; }
    public List<CartItem> getItems() { return items; }
    public double getTotal() { return total; }
    public String getTransactionId() { return transactionId; }
}

public class OrderResult {
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

// ─── Custom Exceptions ───

public class OutOfStockException extends RuntimeException {
    public OutOfStockException(String message) {
        super(message);
    }
}

public class PaymentFailedException extends RuntimeException {
    public PaymentFailedException(String message) {
        super(message);
    }
}

public class CheckoutException extends RuntimeException {
    public CheckoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

---

## 6. Should Facade Have an Interface?

Like we discussed in Observer (Subject interface), the same question applies here.

**Short answer:** Usually no — but sometimes yes.

```java
// Option A: Concrete Facade (the default)
public class CheckoutFacade { ... }

// Option B: Interface + Implementation
public interface CheckoutService {
    OrderResult checkout(String userId, CartDTO cart);
}

public class CheckoutFacadeImpl implements CheckoutService { ... }
```

### When Do You Need an Interface?

| Scenario | Interface? | Reasoning |
|----------|-----------|-----------|
| **Single implementation, internal use** | No | Over-engineering. Concrete class is fine. |
| **Unit testing the client** | Yes | Mock the Facade interface instead of the concrete class. |
| **Multiple implementations** | Yes | E.g., `StandardCheckoutFacade` vs `ExpressCheckoutFacade`. |
| **API boundary (published to other teams/modules)** | Yes | Stable contract — implementation can change freely. |
| **Spring Boot with single impl** | Optional | Spring can mock concrete classes too (`@MockBean`), so interface isn't strictly required for testing. |

> **Practical default:** In Spring Boot, start without an interface. Add one when you need a second implementation or when the Facade is an API boundary consumed by other teams.

---

## 7. Facade vs God Class — The Critical Distinction

A common anti-pattern is building a Facade that grows into a God class — hundreds of methods, thousands of lines, doing everything.

**Facade (Good):**
- Has a **small, focused API** — typically 3-8 methods for one business domain.
- **Delegates** all real work to subsystem classes.
- Contains **orchestration logic only** — sequencing, error handling, rollback.
- Each method represents a **complete business operation** (checkout, refund, cancel).

**God Class (Bad):**
- Has **dozens of methods** spanning unrelated concerns.
- Contains **business logic directly** instead of delegating.
- Grows with every new feature — becomes a dumping ground.
- Every change risks breaking unrelated features.

```java
// GOD CLASS — NOT a Facade
public class EverythingService {
    public void checkout(...) { /* 200 lines of logic */ }
    public void processReturn(...) { /* 150 lines of logic */ }
    public void updateProfile(...) { /* 100 lines of logic */ }
    public void generateReport(...) { /* 300 lines of logic */ }
    public void sendBulkEmail(...) { /* 180 lines of logic */ }
    // ... 40 more methods
}

// PROPER FACADES — one per domain
public class CheckoutFacade {
    public OrderResult checkout(...) { /* delegates to 6 services */ }
    public RefundResult refund(...) { /* delegates to 4 services */ }
}

public class ReportFacade {
    public Report generate(...) { /* delegates to 3 services */ }
    public void schedule(...) { /* delegates to 2 services */ }
}
```

> **Rule of thumb:** If your Facade has more than 10 public methods or more than 500 lines, it's probably a God class. Split it into domain-specific Facades.

---

## 8. Facade in the JDK

| Class / API | What It Simplifies |
|------------|-------------------|
| `javax.faces.context.FacesContext` | Facade over JSF's request, response, application, and lifecycle objects |
| `java.net.URL` | Facade over protocol handlers, stream connections, and content parsing |
| `javax.servlet.http.HttpSession` | Facade over server-side session management (storage, replication, expiry) |
| `java.util.Collections` | Facade-like utility — wraps complex operations (sort, shuffle, unmodifiable) behind static methods |
| `java.lang.Runtime` | Facade over JVM internals (memory, GC, process execution) |
| `java.sql.DriverManager` | Facade over JDBC driver loading, connection pooling, and URL parsing |
| `SLF4J Logger` | Facade over logging frameworks (Log4j, Logback, JUL). Literally named "Simple Logging Facade for Java" |

### SLF4J — A Facade in Name and Design

```java
// Client code uses SLF4J — doesn't know if Log4j, Logback, or JUL is behind it
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

Logger logger = LoggerFactory.getLogger(MyClass.class);
logger.info("Order placed: {}", orderId);
```

SLF4J defines a simple API. The actual logging framework (Log4j2, Logback) is the subsystem. You can swap Logback for Log4j2 without changing a single line of application code — that's the Facade's power.

---

## 9. Facade in the Spring Boot World

### 9.1 — The Service Layer IS a Facade

In a typical layered Spring Boot architecture, the `@Service` layer is a Facade over repositories, validators, mappers, and external APIs:

```
Controller → Service (Facade) → Repository
                               → Validator
                               → External API Client
                               → Mapper
                               → Event Publisher
```

```java
@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final InventoryClient inventoryClient;
    private final PaymentClient paymentClient;
    private final OrderMapper mapper;
    private final ApplicationEventPublisher eventPublisher;

    public OrderService(OrderRepository orderRepository,
                        InventoryClient inventoryClient,
                        PaymentClient paymentClient,
                        OrderMapper mapper,
                        ApplicationEventPublisher eventPublisher) {
        this.orderRepository = orderRepository;
        this.inventoryClient = inventoryClient;
        this.paymentClient = paymentClient;
        this.mapper = mapper;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public OrderResponse placeOrder(OrderRequest request) {
        // Validate
        inventoryClient.validateStock(request.getItems());

        // Create entity
        OrderEntity entity = mapper.toEntity(request);

        // Charge
        PaymentResult payment = paymentClient.charge(request.getPaymentInfo());
        entity.setTransactionId(payment.getTransactionId());

        // Persist
        OrderEntity saved = orderRepository.save(entity);

        // Publish event (Observer pattern for side-effects)
        eventPublisher.publishEvent(new OrderPlacedEvent(saved.getId()));

        // Map to response
        return mapper.toResponse(saved);
    }
}
```

The controller calls **one method** — `orderService.placeOrder(request)`. The service orchestrates 5 subsystems. This is Facade.

### 9.2 — Facade Over External APIs

When your app integrates with multiple third-party services, a Facade shields your code from their individual quirks:

```java
@Component
public class ShippingFacade {

    private final BlueDartClient blueDartClient;
    private final DelhiveryClient delhiveryClient;
    private final FedExClient fedExClient;

    public ShippingFacade(BlueDartClient blueDartClient,
                          DelhiveryClient delhiveryClient,
                          FedExClient fedExClient) {
        this.blueDartClient = blueDartClient;
        this.delhiveryClient = delhiveryClient;
        this.fedExClient = fedExClient;
    }

    // One unified method — hides three different API contracts
    public ShippingQuote getBestQuote(String sourcePincode, String destPincode, double weightKg) {
        List<ShippingQuote> quotes = new ArrayList<>();

        quotes.add(toQuote("BlueDart",
            blueDartClient.getRateV2(sourcePincode, destPincode, weightKg)));
        quotes.add(toQuote("Delhivery",
            delhiveryClient.fetchPricing(weightKg, sourcePincode + "-" + destPincode)));
        quotes.add(toQuote("FedEx",
            fedExClient.estimateCost(new FedExRequest(sourcePincode, destPincode, weightKg))));

        return quotes.stream()
            .min(Comparator.comparingDouble(ShippingQuote::getCost))
            .orElseThrow(() -> new RuntimeException("No shipping quotes available"));
    }

    private ShippingQuote toQuote(String carrier, Object apiResponse) {
        // Normalize each carrier's different response format into a common model
        // ...
    }
}
```

Each carrier has a different API contract (`getRateV2`, `fetchPricing`, `estimateCost`), different request/response shapes. The Facade normalizes them into one `ShippingQuote` model. Client code never knows about BlueDart, Delhivery, or FedEx APIs.

---

## 10. Facade + Other Patterns

### 10.1 — Facade + Strategy

The Facade orchestrates the workflow; Strategy provides a pluggable algorithm inside it:

```java
public class CheckoutFacade {

    private final PricingStrategy pricingStrategy;  // Strategy
    private final PaymentGateway paymentGateway;
    private final OrderRepository orderRepository;

    public OrderResult checkout(String userId, CartDTO cart) {
        double total = pricingStrategy.calculate(cart);  // Strategy decides pricing logic
        PaymentResult payment = paymentGateway.charge(userId, total, cart.getPaymentMethod());
        Order order = orderRepository.createOrder(userId, cart.getItems(), total, payment.getTransactionId());
        return new OrderResult(order.getId(), total);
    }
}
```

### 10.2 — Facade + Factory

The Facade uses a Factory to create the right subsystem objects:

```java
public class ReportFacade {

    private final ReportGeneratorFactory factory;

    public byte[] exportReport(String format, List<Map<String, Object>> data) {
        ReportGenerator generator = factory.getGenerator(format);  // Factory picks the generator
        return generator.generateReport(data, "report");            // Facade orchestrates
    }
}
```

### 10.3 — Facade + Observer

The Facade publishes events after completing the operation; Observers handle side-effects:

```java
public class CheckoutFacade {

    private final ApplicationEventPublisher eventPublisher;

    public OrderResult checkout(String userId, CartDTO cart) {
        // ... core checkout logic ...

        // Fire-and-forget: observers handle email, analytics, etc.
        eventPublisher.publishEvent(new OrderPlacedEvent(order.getId()));

        return new OrderResult(order.getId(), total);
    }
}
```

### 10.4 — Facade + Decorator

Decorator wraps the Facade to add cross-cutting concerns:

```java
public class LoggingCheckoutFacade extends CheckoutFacade {

    private final CheckoutFacade delegate;

    @Override
    public OrderResult checkout(String userId, CartDTO cart) {
        log.info("Checkout started for user {}", userId);
        OrderResult result = delegate.checkout(userId, cart);
        log.info("Checkout completed: order {}", result.getOrderId());
        return result;
    }
}
```

---

## 11. Real-World Use Cases

| Domain | Facade | Subsystems Hidden |
|--------|--------|-------------------|
| **E-commerce Checkout** | `CheckoutFacade` | Inventory, Pricing, Payment, Order, Notification, Analytics |
| **User Registration** | `RegistrationFacade` | UserRepo, EmailVerification, DefaultsSetup, WelcomeEmail, Analytics |
| **File Upload** | `FileUploadFacade` | VirusScanner, ImageResizer, S3Client, MetadataStore, CDNInvalidator |
| **Report Generation** | `ReportFacade` | DataFetcher, Aggregator, Formatter, S3Uploader, Scheduler |
| **Payment Processing** | `PaymentFacade` | FraudCheck, Gateway, Ledger, Receipt, Notification |
| **CI/CD Pipeline** | `BuildFacade` | GitClient, DockerBuilder, TestRunner, ArtifactStore, Deployer |
| **Shipping (Walmart-style)** | `ShipmentFacade` | CarrierSelector, LabelGenerator, TrackingService, InventoryDeductor, NotificationService |
| **Third-Party Integration** | `ShippingFacade` | BlueDartClient, DelhiveryClient, FedExClient (different APIs, one interface) |

---

## 12. SOLID Principles Alignment

| Principle | How Facade Helps |
|---|---|
| **S — Single Responsibility** | Each subsystem class handles one concern. Facade handles orchestration only. |
| **O — Open/Closed** | New subsystems can be added to the Facade's workflow without changing the client. |
| **L — Liskov Substitution** | If Facade has an interface, implementations are substitutable. |
| **I — Interface Segregation** | Facade exposes a small, focused API. Clients don't see subsystem complexity. |
| **D — Dependency Inversion** | Client depends on the Facade abstraction, not on 7 concrete subsystem classes. |

---

## 13. Facade vs Other Patterns

### Facade vs Adapter

| Aspect | Facade | Adapter |
|--------|--------|---------|
| **Intent** | Simplify a complex subsystem | Make incompatible interfaces work together |
| **Scope** | Wraps an entire subsystem (many classes) | Wraps a single class or interface |
| **Interface** | Defines a NEW simplified interface | Converts to an EXISTING expected interface |
| **Subsystem awareness** | Subsystems don't know about the Facade | Adapted class doesn't know about the Adapter |

### Facade vs Mediator

| Aspect | Facade | Mediator |
|--------|--------|---------|
| **Direction** | Unidirectional — client → Facade → subsystems | Bidirectional — colleagues ↔ Mediator ↔ colleagues |
| **Knowledge** | Subsystems don't know about the Facade | Colleagues know about the Mediator |
| **Purpose** | Simplify access for external clients | Coordinate communication between internal components |
| **Communication** | Client initiates; subsystems are passive | Any colleague can initiate; Mediator routes |

### Facade vs Proxy

| Aspect | Facade | Proxy |
|--------|--------|-------|
| **What it wraps** | An entire subsystem (many classes) | A single object |
| **Interface** | New, simplified interface | Same interface as the original |
| **Purpose** | Hide complexity | Control access (lazy loading, security, caching) |

### Facade vs Service Layer

They're essentially the same thing. In Spring Boot, the `@Service` layer IS a Facade over repositories, clients, and domain logic. The GoF calls it "Facade"; enterprise architecture calls it "Service Layer."

---

## 14. Common Pitfalls and How to Avoid Them

### 1. Facade Becomes a God Class

```java
// BAD — Facade does everything
public class AppFacade {
    public void checkout(...) { /* 200 lines */ }
    public void manageInventory(...) { /* 150 lines */ }
    public void handleReturns(...) { /* 180 lines */ }
    public void generateReports(...) { /* 120 lines */ }
    public void manageUsers(...) { /* 90 lines */ }
}
```

**Solution:** One Facade per bounded context / business domain.

### 2. Facade Contains Business Logic

```java
// BAD — price calculation lives in the Facade
public OrderResult checkout(String userId, CartDTO cart) {
    double total = 0;
    for (CartItem item : cart.getItems()) {
        total += item.getPrice() * item.getQty();
        if (item.getQty() > 10) total *= 0.95; // bulk discount logic in Facade!
    }
}

// GOOD — Facade delegates to PricingService
public OrderResult checkout(String userId, CartDTO cart) {
    double total = pricingService.calculateTotal(cart); // delegate
}
```

**Rule:** The Facade orchestrates — it calls methods on subsystems in the right order. It doesn't implement the logic those methods should contain.

### 3. Facade Hides Too Much

```java
// BAD — hides transaction boundaries the caller should control
public class OrderFacade {
    @Transactional
    public void placeAndShip(OrderRequest request) {
        placeOrder(request);   // if shipping fails, order is rolled back too
        shipOrder(request);    // caller might want these as separate transactions
    }
}

// BETTER — expose meaningful operations the caller can compose
public class OrderFacade {
    @Transactional
    public OrderResult placeOrder(OrderRequest request) { ... }

    @Transactional
    public ShipmentResult shipOrder(String orderId) { ... }
}
```

**Rule:** Simplify, don't over-simplify. If the caller needs control over individual steps, expose them as separate Facade methods.

### 4. Subsystems Depend on the Facade (Circular Dependency)

```java
// BAD — subsystem calls back to the Facade
public class InventoryService {
    private final CheckoutFacade checkoutFacade; // circular!
}
```

Subsystems should NEVER know about the Facade. The dependency is strictly one-way: Facade → Subsystems.

---

## 15. Advantages and Disadvantages

### Advantages

- **Simplifies client code** — one method call instead of coordinating 7 subsystems.
- **Decouples client from subsystems** — internals can change without affecting clients.
- **Centralizes orchestration** — sequencing, error handling, rollback logic in one place.
- **Improves testability** — mock one Facade instead of 7 services for controller tests.
- **Reduces duplication** — multiple entry points (web, mobile, admin) share one Facade.

### Disadvantages

- **Can become a God class** — grows unchecked if not scoped per domain.
- **Extra layer of indirection** — trivial subsystems don't need a wrapper.
- **Risk of over-simplification** — hiding details the client genuinely needs to control.
- **Tight coupling to subsystems** — Facade references all subsystem classes (mitigated by injecting interfaces).

---

## 16. Interview-Ready Talking Points

**"Explain the Facade pattern and where you've used it."**

> "Facade provides a simplified interface over a complex subsystem. At Walmart, our order fulfillment system coordinated multiple services — inventory validation, slot allocation for the conveyor system, label generation, carrier selection, and shipment tracking. Without a Facade, every entry point (warehouse management UI, batch scheduler, API) would have to call these 5-6 services in the right sequence and handle rollback if one failed. We created a `FulfillmentFacade` that exposed one `fulfillOrder()` method. It orchestrated the sequence, handled partial failures, and every entry point just called the Facade."

**"How is Facade different from Adapter?"**

> "Adapter converts one interface to another — it makes an incompatible class work where a specific interface is expected. Facade doesn't convert anything — it creates a new, simplified interface over multiple classes. Adapter wraps one class; Facade wraps a subsystem. Adapter is about compatibility; Facade is about simplification."

**"Isn't Facade just a Service Layer?"**

> "Essentially yes — in Spring Boot, the `@Service` layer is the Facade. It sits between controllers and repositories/clients, orchestrating the workflow. The GoF named the pattern 'Facade'; enterprise architects call it 'Service Layer'. The principles are identical: hide subsystem complexity, centralize orchestration, present a simple API."

**"When would you NOT use Facade?"**

> "When the subsystem is already simple — wrapping one service in a Facade is just indirection for indirection's sake. Also when the client needs fine-grained control over individual subsystem operations — a Facade that hides transaction boundaries or error semantics the caller should see does more harm than good."

---

## 17. Quick Implementation Checklist

1. Identify the **subsystem classes** that the client currently coordinates directly.
2. Define the **Facade class** with methods representing complete business operations.
3. Inject subsystem dependencies via **constructor injection** (not `new` inside the Facade).
4. Implement each Facade method as **orchestration only** — sequence subsystem calls, handle errors, manage rollback.
5. **Delegate all business logic** to subsystem classes — the Facade contains zero domain rules.
6. Wrap **non-critical side-effects** (notifications, analytics) in try-catch so they don't crash the main flow.
7. Keep the Facade **scoped to one domain** — don't let it grow into a God class.
8. In Spring — the `@Service` layer IS your Facade. Use `@Transactional` for atomicity, `ApplicationEventPublisher` for fire-and-forget side-effects.
