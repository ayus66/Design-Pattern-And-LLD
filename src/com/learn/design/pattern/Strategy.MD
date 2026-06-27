# Strategy Design Pattern — Detailed Notes

## 1. What Is It?

The Strategy pattern is a **behavioral design pattern** that defines a family of algorithms, encapsulates each one into its own class, and makes them interchangeable at runtime. The client code delegates the work to a strategy object instead of implementing the algorithm directly.

**GoF Definition:** "Define a family of algorithms, encapsulate each one, and make them interchangeable. Strategy lets the algorithm vary independently from clients that use it."

**Core Idea:** Favor composition over inheritance. Instead of subclassing to change behavior, inject a strategy object.

---

## 2. When to Use

- You have **multiple algorithms** for a specific task and want to switch between them at runtime.
- You want to **eliminate conditional logic** (long if-else / switch chains) that selects behavior.
- You have classes that differ **only in their behavior** — strategy lets you extract the varying part.
- You need to **isolate the algorithm's implementation** details from the code that uses it.
- The algorithm uses data the client shouldn't know about — strategy avoids exposing complex, algorithm-specific data structures.

---

## 3. UML Structure

```
┌─────────────────────────┐
│        Context           │
├─────────────────────────┤
│ - strategy: Strategy     │
├─────────────────────────┤
│ + setStrategy(Strategy)  │
│ + executeStrategy()      │
└──────────┬──────────────┘
           │ uses
           ▼
┌─────────────────────────┐
│    <<interface>>         │
│       Strategy           │
├─────────────────────────┤
│ + execute(data): Result  │
└──────────┬──────────────┘
           │ implements
     ┌─────┴──────┐
     ▼            ▼
┌──────────┐ ┌──────────┐
│StrategyA │ │StrategyB │
└──────────┘ └──────────┘
```

**Three Participants:**

| Role | Responsibility |
|------|----------------|
| **Strategy (Interface)** | Declares the contract that all concrete strategies must follow. |
| **ConcreteStrategy** | Implements a specific algorithm behind the Strategy interface. |
| **Context** | Holds a reference to a Strategy object. Delegates work to it. Doesn't know which concrete strategy it's using. |

---

## 4. Java Implementation

### 4.1 — Problem Without Strategy (The Anti-Pattern)

```java
public class PaymentService {

    public void pay(String type, double amount) {
        if ("CREDIT_CARD".equals(type)) {
            // 30 lines of credit card logic
        } else if ("UPI".equals(type)) {
            // 25 lines of UPI logic
        } else if ("NET_BANKING".equals(type)) {
            // 20 lines of net banking logic
        }
        // Adding PayPal? Modify this class. OCP violated.
    }
}
```

**Problems:** Violates OCP (Open/Closed Principle), class grows with every new payment method, hard to test individual algorithms, tight coupling.

### 4.2 — Solution With Strategy

**Step 1: Define the Strategy Interface**

```java
public interface PaymentStrategy {
    void pay(double amount);
}
```

**Step 2: Implement Concrete Strategies**

```java
public class CreditCardPayment implements PaymentStrategy {
    private final String cardNumber;

    public CreditCardPayment(String cardNumber) {
        this.cardNumber = cardNumber;
    }

    @Override
    public void pay(double amount) {
        System.out.println("Paid ₹" + amount + " via Credit Card ending " +
            cardNumber.substring(cardNumber.length() - 4));
    }
}

public class UpiPayment implements PaymentStrategy {
    private final String upiId;

    public UpiPayment(String upiId) {
        this.upiId = upiId;
    }

    @Override
    public void pay(double amount) {
        System.out.println("Paid ₹" + amount + " via UPI: " + upiId);
    }
}

public class NetBankingPayment implements PaymentStrategy {
    private final String bankName;

    public NetBankingPayment(String bankName) {
        this.bankName = bankName;
    }

    @Override
    public void pay(double amount) {
        System.out.println("Paid ₹" + amount + " via Net Banking: " + bankName);
    }
}
```

**Step 3: Create the Context**

```java
public class PaymentContext {
    private PaymentStrategy strategy;

    // Can inject via constructor
    public PaymentContext(PaymentStrategy strategy) {
        this.strategy = strategy;
    }

    // Can also swap at runtime
    public void setStrategy(PaymentStrategy strategy) {
        this.strategy = strategy;
    }

    public void checkout(double amount) {
        strategy.pay(amount);
    }
}
```

**Step 4: Client Code**

```java
public class Main {
    public static void main(String[] args) {
        PaymentContext ctx = new PaymentContext(new CreditCardPayment("4111111111111234"));
        ctx.checkout(1500.00);
        // Output: Paid ₹1500.0 via Credit Card ending 1234

        // Switch strategy at runtime — no code change needed
        ctx.setStrategy(new UpiPayment("ayus@upi"));
        ctx.checkout(500.00);
        // Output: Paid ₹500.0 via UPI: ayus@upi
    }
}
```

---

## 5. Strategy Pattern with Java 8+ (Functional Style)

Since `Strategy` is a single-method interface (a **functional interface**), you can replace concrete classes with lambdas:

```java
@FunctionalInterface
public interface PaymentStrategy {
    void pay(double amount);
}

// No need for separate classes — lambdas are enough
PaymentContext ctx = new PaymentContext(
    amount -> System.out.println("Paid ₹" + amount + " via Wallet")
);
ctx.checkout(200.00);
```

**When to use lambdas vs classes:**

| Use Lambdas When | Use Classes When |
|---|---|
| Strategy is simple (1–3 lines) | Strategy has its own state (e.g., card number, config) |
| No internal state needed | Logic is complex or reusable across modules |
| Quick, throwaway behavior | You need to unit test the strategy in isolation |

---

## 6. Multi-Method Strategy (When Strategy Has 2+ Responsibilities)

When a strategy involves **more than one cohesive operation**, the interface has multiple methods. This is common in real systems — a single algorithm domain often needs several related behaviors that vary together.

**Example: Shipping Strategy in an E-Commerce System**

Different shipping carriers have different cost calculations, delivery estimates, AND tracking URL formats. These three things always vary together — if you switch from BlueDart to FedEx, all three change. That makes them a single strategy, not three separate ones.

### Step 1: Multi-Method Strategy Interface

```java
public interface ShippingStrategy {

    double calculateCost(double weightKg, String destPincode);

    int estimateDeliveryDays(String sourcePincode, String destPincode);

    String generateTrackingUrl(String trackingId);
}
```

> **Key insight:** These three methods are **cohesive** — they all belong to the shipping carrier domain and always change together. This is NOT a violation of ISP. It would be a violation if some strategies needed only `calculateCost` but were forced to implement `generateTrackingUrl`.

### Step 2: Concrete Strategies

```java
public class BlueDartShipping implements ShippingStrategy {

    @Override
    public double calculateCost(double weightKg, String destPincode) {
        double baseCost = 50.0;
        double perKgRate = 30.0;
        boolean isMetro = destPincode.startsWith("56") || destPincode.startsWith("40");
        double zoneMultiplier = isMetro ? 1.0 : 1.5;
        return (baseCost + weightKg * perKgRate) * zoneMultiplier;
    }

    @Override
    public int estimateDeliveryDays(String sourcePincode, String destPincode) {
        boolean sameZone = sourcePincode.substring(0, 2).equals(destPincode.substring(0, 2));
        return sameZone ? 2 : 5;
    }

    @Override
    public String generateTrackingUrl(String trackingId) {
        return "https://bluedart.com/track/" + trackingId;
    }
}

public class DelhiveryShipping implements ShippingStrategy {

    @Override
    public double calculateCost(double weightKg, String destPincode) {
        // Delhivery uses slab-based pricing
        if (weightKg <= 0.5) return 40.0;
        if (weightKg <= 2.0) return 70.0;
        return 70.0 + (weightKg - 2.0) * 20.0;
    }

    @Override
    public int estimateDeliveryDays(String sourcePincode, String destPincode) {
        // Delhivery is slower but cheaper
        boolean sameZone = sourcePincode.substring(0, 2).equals(destPincode.substring(0, 2));
        return sameZone ? 3 : 7;
    }

    @Override
    public String generateTrackingUrl(String trackingId) {
        return "https://delhivery.com/track/package/" + trackingId;
    }
}

public class ExpressBeeShipping implements ShippingStrategy {

    private static final double FLAT_RATE = 100.0;

    @Override
    public double calculateCost(double weightKg, String destPincode) {
        // Flat rate up to 5kg, premium after
        return weightKg <= 5.0 ? FLAT_RATE : FLAT_RATE + (weightKg - 5.0) * 40.0;
    }

    @Override
    public int estimateDeliveryDays(String sourcePincode, String destPincode) {
        return 1; // next-day delivery always
    }

    @Override
    public String generateTrackingUrl(String trackingId) {
        return "https://expressbee.in/tracking?awb=" + trackingId;
    }
}
```

### Step 3: Context Uses All Methods

```java
public class OrderShippingService {

    private ShippingStrategy shippingStrategy;

    public OrderShippingService(ShippingStrategy shippingStrategy) {
        this.shippingStrategy = shippingStrategy;
    }

    public void setShippingStrategy(ShippingStrategy shippingStrategy) {
        this.shippingStrategy = shippingStrategy;
    }

    public ShippingSummary getShippingSummary(Order order) {
        double cost = shippingStrategy.calculateCost(
            order.getTotalWeightKg(), order.getDestPincode()
        );

        int days = shippingStrategy.estimateDeliveryDays(
            order.getWarehousePincode(), order.getDestPincode()
        );

        String trackingUrl = shippingStrategy.generateTrackingUrl(
            order.getTrackingId()
        );

        return new ShippingSummary(cost, days, trackingUrl);
    }
}
```

### Step 4: Client Selects Strategy

```java
public class Main {
    public static void main(String[] args) {
        Order order = new Order("560001", "400001", 3.5, "AWB12345");

        // Customer picks "Economy" → Delhivery
        OrderShippingService service = new OrderShippingService(new DelhiveryShipping());
        ShippingSummary summary = service.getShippingSummary(order);
        // cost=70.0 + 1.5*20 = ₹100, days=7, url=delhivery.com/track/package/AWB12345

        // Customer switches to "Express" → ExpressBee
        service.setShippingStrategy(new ExpressBeeShipping());
        summary = service.getShippingSummary(order);
        // cost=₹100 (flat, under 5kg), days=1, url=expressbee.in/tracking?awb=AWB12345
    }
}
```

### When Does Multi-Method Strategy Make Sense?

**Use multi-method when** the methods are **cohesive** — they represent different facets of the same domain that always change together:

| Domain | Methods That Vary Together |
|--------|---------------------------|
| Shipping carrier | calculateCost, estimateDelivery, getTrackingUrl |
| Auth provider | authenticate, refreshToken, revokeToken |
| Data export | serialize, getContentType, getFileExtension |
| Cloud storage | upload, download, generatePresignedUrl |
| Notification channel | send, formatMessage, checkDeliveryStatus |

**Don't use multi-method when** different consumers need different subsets of the methods — that's an ISP violation. Split into separate interfaces instead:

```java
// BAD — forcing implementors to care about unrelated concerns
public interface ShippingStrategy {
    double calculateCost(...);
    int estimateDeliveryDays(...);
    String generateTrackingUrl(...);
    void printLabel(...);          // Only warehouse needs this
    boolean isReturnSupported();   // Only returns-service needs this
}

// BETTER — split by consumer
public interface ShippingCostStrategy {
    double calculateCost(...);
    int estimateDeliveryDays(...);
}

public interface ShippingLabelStrategy {
    void printLabel(...);
}
```

### Why You Can't Use Lambdas Here

A `@FunctionalInterface` can only have **one abstract method**. The moment your strategy has 2+ methods, you need concrete classes. This is the dividing line:

| # of Methods | Approach |
|---|---|
| 1 | Lambda / method reference works fine |
| 2+ | Full class implementation required |

---

## 7. Strategy in the Spring Boot World

In Spring, strategies are natural — just make them beans and inject them via a `Map` or `List`.

```java
public interface NotificationStrategy {
    String getType();  // discriminator
    void send(String to, String message);
}

@Component
public class EmailNotification implements NotificationStrategy {
    public String getType() { return "EMAIL"; }
    public void send(String to, String message) { /* send email */ }
}

@Component
public class SmsNotification implements NotificationStrategy {
    public String getType() { return "SMS"; }
    public void send(String to, String message) { /* send SMS */ }
}
```

```java
@Service
public class NotificationService {

    private final Map<String, NotificationStrategy> strategyMap;

    // Spring injects ALL beans implementing NotificationStrategy
    public NotificationService(List<NotificationStrategy> strategies) {
        this.strategyMap = strategies.stream()
            .collect(Collectors.toMap(NotificationStrategy::getType, Function.identity()));
    }

    public void notify(String type, String to, String message) {
        NotificationStrategy strategy = strategyMap.get(type);
        if (strategy == null) {
            throw new IllegalArgumentException("Unsupported notification type: " + type);
        }
        strategy.send(to, message);
    }
}
```

**Why this matters:** Adding a new notification channel (e.g., Push) requires only a new `@Component` class — zero changes to `NotificationService`. True OCP compliance.

---

## 8. Real-World Use Cases

| Domain | Context | Strategies |
|--------|---------|------------|
| **E-commerce** | Payment processing | CreditCard, UPI, NetBanking, Wallet |
| **Sorting** | `Collections.sort()` / `List.sort()` | Different `Comparator<T>` implementations |
| **Compression** | File archiver | ZIP, GZIP, TAR, BZIP2 |
| **Authentication** | Auth service | OAuth, JWT, SAML, BasicAuth |
| **Pricing** | Discount engine | FlatDiscount, PercentageDiscount, BuyOneGetOne |
| **Logging** | Log dispatcher | ConsoleLogger, FileLogger, CloudLogger |
| **Serialization** | Data export | JSON, XML, CSV, Protobuf |
| **Inventory (Walmart-style)** | Replenishment calc | MinMaxStrategy, EOQStrategy, JITStrategy |

### Strategy in the JDK

- `java.util.Comparator<T>` — the classic strategy. `Collections.sort(list, comparator)`.
- `java.util.concurrent.RejectedExecutionHandler` — strategy for what ThreadPoolExecutor does when the queue is full.
- `javax.servlet.Filter` — each filter is a strategy for request/response processing.
- `java.util.logging.Handler` — strategy for where logs go (console, file, socket).

---

## 9. SOLID Principles Alignment

| Principle | How Strategy Helps |
|---|---|
| **S — Single Responsibility** | Each strategy class has exactly one algorithm. Context only delegates. |
| **O — Open/Closed** | Add new strategies without modifying existing code. |
| **L — Liskov Substitution** | Any concrete strategy is substitutable wherever the interface is expected. |
| **I — Interface Segregation** | Strategy interface is narrow (typically one method). |
| **D — Dependency Inversion** | Context depends on the Strategy abstraction, not concrete implementations. |

---

## 10. Strategy vs Other Patterns

### Strategy vs State

| Aspect | Strategy | State |
|--------|----------|-------|
| **Intent** | Choose an algorithm | Manage object lifecycle / transitions |
| **Who decides** | Client picks the strategy | State objects can trigger transitions internally |
| **Awareness** | Strategies don't know about each other | States often know about other states |
| **Swapping** | Client swaps explicitly | Transitions happen implicitly based on events |

### Strategy vs Template Method

| Aspect | Strategy | Template Method |
|--------|----------|-----------------|
| **Mechanism** | Composition (has-a) | Inheritance (is-a) |
| **Granularity** | Replaces the entire algorithm | Overrides specific steps within a fixed skeleton |
| **Flexibility** | Strategy can change at runtime | Fixed at compile time (tied to subclass) |
| **Coupling** | Loose (interface-based) | Tight (subclass knows parent's structure) |

### Strategy vs Factory

Not competing patterns — they complement each other. A **Factory** can decide *which* strategy to instantiate, while **Strategy** defines *how* the algorithm runs.

```java
public class PaymentStrategyFactory {
    public static PaymentStrategy getStrategy(String type) {
        return switch (type) {
            case "CREDIT_CARD" -> new CreditCardPayment("...");
            case "UPI"         -> new UpiPayment("...");
            default            -> throw new IllegalArgumentException("Unknown: " + type);
        };
    }
}
```

---

## 11. Advantages and Disadvantages

### Advantages

- **Runtime flexibility** — swap algorithms without recompilation.
- **Eliminates conditionals** — replaces if/else chains with polymorphism.
- **Testability** — each strategy is independently unit-testable.
- **OCP compliance** — extend by adding, not modifying.
- **Reusability** — strategies can be reused across different contexts.

### Disadvantages

- **Class explosion** — each algorithm needs its own class (mitigated by lambdas in Java 8+).
- **Client must know strategies** — the client needs to understand which strategy to pick (can be mitigated with a Factory).
- **Overhead for simple cases** — if you'll never have more than 2 algorithms and they won't change, a simple if/else might be pragmatically sufficient.
- **Communication overhead** — the Context must pass enough data to the Strategy. If different strategies need very different data, the interface can become awkward.

---

## 12. Interview-Ready Talking Points

**"Tell me about a design pattern you've used in production."**

> "At Walmart, our inventory system needed to calculate replenishment quantities using different algorithms depending on the product category — some used min/max thresholds, others used demand forecasting. We applied the Strategy pattern: each algorithm implemented a common `ReplenishmentStrategy` interface, and the orchestrator resolved the right strategy per SKU category at runtime. Adding a new algorithm meant adding a Spring bean — zero changes to existing code. This kept deployment risk low for a system processing millions of SKUs."

**Why Strategy over inheritance?**

> "Inheritance locks you in at compile time and creates fragile hierarchies. Strategy uses composition, so you can swap behavior at runtime and test each algorithm independently. It also avoids the combinatorial explosion problem — if you have N behaviors across M dimensions, inheritance needs N×M subclasses, while Strategy needs N+M classes."

**When would you NOT use Strategy?**

> "If there's genuinely only one algorithm and no foreseeable variation, it's over-engineering. Also, if the algorithms need radically different inputs, forcing them behind a common interface creates a leaky abstraction. In that case, consider a different decomposition."

---

## 13. Quick Implementation Checklist

1. Identify the algorithm that varies.
2. Define a `Strategy` interface with the algorithm's signature.
3. Extract each variant into a class implementing the interface.
4. Create a `Context` class that holds a `Strategy` reference and delegates to it.
5. Client code creates/selects the strategy and passes it to the context.
6. (Optional) Use a Factory to encapsulate strategy selection logic.
7. (Optional) In Spring — make strategies `@Component` beans and autowire them via a `List` or `Map`.
