# Decorator Design Pattern — Detailed Notes

## 1. What Is It?

The Decorator pattern is a **structural design pattern** that lets you attach new behavior to an object dynamically by wrapping it inside another object. Instead of modifying existing code or creating subclasses for every combination, you wrap the original object with decorator layers — each adding one responsibility.

**GoF Definition:** "Attach additional responsibilities to an object dynamically. Decorators provide a flexible alternative to subclassing for extending functionality."

**Core Idea:** Wrap an object inside another object that has the same interface. The wrapper delegates to the original, adding behavior before/after the delegation. Multiple wrappers can be stacked like layers.

**Also Known As:** Wrapper.

---

## 2. When to Use

- You want to **add responsibilities to individual objects** at runtime, not to the entire class.
- You need to **combine behaviors flexibly** — N features shouldn't need 2^N subclasses.
- The class you want to extend is **final** or you don't control its source.
- You want to follow **Single Responsibility** — each decorator handles one concern.
- You need **stackable, composable** enhancements (logging + caching + retry + encryption — each as a separate layer).

**Don't use when:**
- The object's interface is very large (too many methods to delegate through).
- Order of decoration never matters and there's only 1-2 fixed combinations — a simple subclass might be enough.
- You need to remove a layer dynamically at runtime — Decorator stacks are typically set up once.

---

## 3. UML Structure

```
┌─────────────────────────────────┐
│       <<interface>>              │
│        Component                 │
├─────────────────────────────────┤
│ + operation(): Result            │
└──────────────┬──────────────────┘
               │ implements
       ┌───────┴────────┐
       ▼                ▼
┌──────────────┐  ┌─────────────────────────────┐
│  Concrete    │  │     <<abstract>>             │
│  Component   │  │     BaseDecorator            │
│              │  ├─────────────────────────────┤
│              │  │ - wrapped: Component          │
│              │  ├─────────────────────────────┤
│              │  │ + operation(): Result         │
│              │  │   → wrapped.operation()       │
└──────────────┘  └──────────────┬──────────────┘
                                 │ extends
                         ┌───────┴────────┐
                         ▼                ▼
                  ┌──────────────┐ ┌──────────────┐
                  │ DecoratorA   │ │ DecoratorB   │
                  │ (adds X)     │ │ (adds Y)     │
                  └──────────────┘ └──────────────┘
```

**Four Participants:**

| Role | Responsibility |
|------|----------------|
| **Component (Interface)** | Defines the contract that both the real object and decorators follow. |
| **ConcreteComponent** | The original object being decorated. Has the core behavior. |
| **BaseDecorator (Abstract)** | Holds a reference to a `Component`. Delegates all calls to it. Serves as the base for concrete decorators. |
| **ConcreteDecorator** | Extends `BaseDecorator`. Adds specific behavior before/after delegating to the wrapped component. |

**The Key Rule:** Both the original object and every decorator implement the **same interface**. This is what makes them stackable — the client can't tell the difference between a raw component and a decorated one.

---

## 4. Java Implementation

### 4.1 — The Subclass Explosion Problem (Without Decorator)

Imagine a notification system that can send via Email, and optionally add SMS, Slack, and logging:

```java
// Without Decorator — need a class for every combination
class EmailNotifier { }
class EmailWithSmsNotifier extends EmailNotifier { }
class EmailWithSlackNotifier extends EmailNotifier { }
class EmailWithSmsAndSlackNotifier extends EmailNotifier { }
class EmailWithSmsAndSlackAndLoggingNotifier extends EmailNotifier { }
// ... 2^N classes for N optional features
```

With 4 optional features, you'd need **16 subclasses**. With 6 features → **64 subclasses**. This is the combinatorial explosion Decorator solves.

### 4.2 — Solution With Decorator

**Step 1: Define the Component Interface**

```java
public interface Notifier {
    void send(String recipient, String message);
}
```

**Step 2: Implement the Concrete Component**

```java
public class EmailNotifier implements Notifier {

    @Override
    public void send(String recipient, String message) {
        System.out.println("EMAIL → " + recipient + ": " + message);
        // actual email sending logic (SMTP, SES, etc.)
    }
}
```

**Step 3: Create the Base Decorator**

```java
public abstract class NotifierDecorator implements Notifier {

    protected final Notifier wrapped;

    public NotifierDecorator(Notifier wrapped) {
        this.wrapped = wrapped;
    }

    @Override
    public void send(String recipient, String message) {
        wrapped.send(recipient, message); // delegate to wrapped component
    }
}
```

> **Why abstract?** The base decorator exists to hold the `wrapped` reference and provide default delegation. Concrete decorators override `send()` and add their specific behavior.

> **Why `implements Notifier` and not `extends EmailNotifier`?** This is the core of Decorator — the decorator depends on the **interface**, not the concrete class. It can wrap ANY `Notifier`, including other decorators.

**Step 4: Implement Concrete Decorators**

```java
public class SmsDecorator extends NotifierDecorator {

    public SmsDecorator(Notifier wrapped) {
        super(wrapped);
    }

    @Override
    public void send(String recipient, String message) {
        super.send(recipient, message);  // delegate first (email goes out)
        sendSms(recipient, message);      // then add SMS
    }

    private void sendSms(String recipient, String message) {
        System.out.println("SMS  → " + recipient + ": " + message);
    }
}

public class SlackDecorator extends NotifierDecorator {

    private final String slackChannel;

    public SlackDecorator(Notifier wrapped, String slackChannel) {
        super(wrapped);
        this.slackChannel = slackChannel;
    }

    @Override
    public void send(String recipient, String message) {
        super.send(recipient, message);         // delegate first
        sendSlack(slackChannel, message);        // then add Slack
    }

    private void sendSlack(String channel, String message) {
        System.out.println("SLACK → #" + channel + ": " + message);
    }
}

public class LoggingDecorator extends NotifierDecorator {

    public LoggingDecorator(Notifier wrapped) {
        super(wrapped);
    }

    @Override
    public void send(String recipient, String message) {
        System.out.println("LOG  → Sending notification to " + recipient +
            " at " + LocalDateTime.now());
        super.send(recipient, message);  // delegate after logging
        System.out.println("LOG  → Notification sent successfully");
    }
}

public class RetryDecorator extends NotifierDecorator {

    private final int maxRetries;

    public RetryDecorator(Notifier wrapped, int maxRetries) {
        super(wrapped);
        this.maxRetries = maxRetries;
    }

    @Override
    public void send(String recipient, String message) {
        int attempt = 0;
        while (attempt < maxRetries) {
            try {
                super.send(recipient, message);
                return; // success — exit
            } catch (Exception e) {
                attempt++;
                System.out.println("RETRY → Attempt " + attempt + " failed: " + e.getMessage());
                if (attempt >= maxRetries) {
                    throw new RuntimeException("All " + maxRetries + " retries exhausted", e);
                }
            }
        }
    }
}
```

**Step 5: Client Code — Stacking Decorators**

```java
public class Main {
    public static void main(String[] args) {

        // Basic email only
        Notifier emailOnly = new EmailNotifier();
        emailOnly.send("ayus@email.com", "Order confirmed");
        // Output: EMAIL → ayus@email.com: Order confirmed

        System.out.println("---");

        // Email + SMS
        Notifier emailAndSms = new SmsDecorator(new EmailNotifier());
        emailAndSms.send("ayus@email.com", "Order shipped");
        // Output: EMAIL → ayus@email.com: Order shipped
        //         SMS  → ayus@email.com: Order shipped

        System.out.println("---");

        // Email + SMS + Slack + Logging (stacked)
        Notifier fullyDecorated = new LoggingDecorator(
                                    new RetryDecorator(
                                      new SlackDecorator(
                                        new SmsDecorator(
                                          new EmailNotifier()
                                        ), "orders"
                                      ), 3
                                    )
                                  );
        fullyDecorated.send("ayus@email.com", "Payment received");
        // Output:
        // LOG  → Sending notification to ayus@email.com at 2025-06-29T...
        // EMAIL → ayus@email.com: Payment received
        // SMS  → ayus@email.com: Payment received
        // SLACK → #orders: Payment received
        // LOG  → Notification sent successfully
    }
}
```

**What just happened:** The `fullyDecorated` object is an `EmailNotifier` wrapped in `SmsDecorator`, wrapped in `SlackDecorator`, wrapped in `RetryDecorator`, wrapped in `LoggingDecorator`. Each layer adds one behavior. The client sees it as just a `Notifier`.

---

## 5. Execution Flow Visualized

When `fullyDecorated.send()` is called, here's the call stack:

```
LoggingDecorator.send()
  │  prints "LOG → Sending notification..."
  │
  └─→ RetryDecorator.send()
        │  try block
        │
        └─→ SlackDecorator.send()
              │
              └─→ SmsDecorator.send()
                    │
                    └─→ EmailNotifier.send()    ← core component
                    │       prints "EMAIL → ..."
                    │
                    │   prints "SMS → ..."       ← SmsDecorator's addition
              │
              │   prints "SLACK → ..."           ← SlackDecorator's addition
        │
        │   (RetryDecorator: success, no retry needed)
  │
  │  prints "LOG → Notification sent successfully"
```

**Think of it like function composition:** `log(retry(slack(sms(email(msg)))))` — each layer wraps the next.

---

## 6. Decorator Ordering Matters

The order in which you stack decorators changes the behavior:

```java
// Order A: Retry wraps Logging
// → Logging happens on EVERY retry attempt
Notifier a = new RetryDecorator(new LoggingDecorator(new EmailNotifier()), 3);

// Order B: Logging wraps Retry
// → Logging happens ONCE, retry is internal
Notifier b = new LoggingDecorator(new RetryDecorator(new EmailNotifier(), 3));
```

| Scenario | Retry wraps Logging | Logging wraps Retry |
|----------|--------------------|--------------------|
| 1st attempt fails | Logged ✓ | Not logged separately |
| 2nd attempt fails | Logged ✓ | Not logged separately |
| 3rd attempt succeeds | Logged ✓ | Logged once ✓ |
| **Total log entries** | 3 | 1 |

> **Rule of thumb:** Cross-cutting concerns like **logging and metrics** usually go on the **outermost** layer. Resilience concerns like **retry and circuit-breaker** go closer to the core.

---

## 7. Decorator in the JDK

The Decorator pattern is deeply embedded in the Java standard library.

### 7.1 — Java I/O Streams (The Classic Example)

Java's entire I/O system is built on Decorator:

```java
// BufferedInputStream decorates FileInputStream
// DataInputStream decorates BufferedInputStream
DataInputStream in = new DataInputStream(      // Decorator 2: read primitives
                        new BufferedInputStream(  // Decorator 1: add buffering
                            new FileInputStream("data.bin")  // Core component
                        )
                     );

int value = in.readInt();  // reads through all three layers
```

| Class | Role |
|-------|------|
| `InputStream` | Component interface |
| `FileInputStream` | ConcreteComponent (reads raw bytes from file) |
| `FilterInputStream` | BaseDecorator (holds wrapped `InputStream`) |
| `BufferedInputStream` | ConcreteDecorator (adds buffering) |
| `DataInputStream` | ConcreteDecorator (adds typed reads: `readInt`, `readDouble`) |
| `GZIPInputStream` | ConcreteDecorator (adds decompression) |

```java
// Reading a GZIP-compressed, buffered file
InputStream in = new GZIPInputStream(
                    new BufferedInputStream(
                        new FileInputStream("data.gz")
                    )
                 );
```

### 7.2 — Collections Utility Decorators

```java
// unmodifiableList wraps a list and disables mutation methods
List<String> original = new ArrayList<>(List.of("a", "b", "c"));
List<String> readOnly = Collections.unmodifiableList(original);
// readOnly.add("d") → throws UnsupportedOperationException

// synchronizedList wraps a list and adds thread safety
List<String> syncList = Collections.synchronizedList(new ArrayList<>());

// checkedList wraps a list and adds runtime type checking
List<String> checked = Collections.checkedList(new ArrayList<>(), String.class);
```

All three return a `List<String>` — the caller doesn't know it's decorated. Same interface, enhanced behavior.

### 7.3 — Other JDK Examples

| Class | What It Decorates | What It Adds |
|-------|-------------------|-------------|
| `BufferedReader` | `Reader` | Buffering + `readLine()` |
| `OutputStreamWriter` | `OutputStream` | Byte-to-char encoding |
| `CheckedOutputStream` | `OutputStream` | Checksum computation |
| `HttpServletRequestWrapper` | `HttpServletRequest` | Custom request behavior in Servlet filters |
| `HttpServletResponseWrapper` | `HttpServletResponse` | Custom response behavior |

---

## 8. Decorator in the Spring Boot World

### 8.1 — HandlerInterceptor (Decorating Request Handling)

Spring's `HandlerInterceptor` works like a decorator chain around controller methods:

```java
@Component
public class LoggingInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) {
        System.out.println("REQUEST → " + request.getMethod() + " " + request.getRequestURI());
        return true; // continue to next interceptor / controller
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        System.out.println("RESPONSE → " + response.getStatus());
    }
}
```

### 8.2 — Custom Decorator with Spring Beans

```java
public interface PricingService {
    double calculatePrice(String productId, double basePrice);
}

@Component
@Primary
public class DiscountDecorator implements PricingService {

    private final PricingService delegate;

    // Spring injects the non-@Primary bean here
    public DiscountDecorator(@Qualifier("basePricingService") PricingService delegate) {
        this.delegate = delegate;
    }

    @Override
    public double calculatePrice(String productId, double basePrice) {
        double price = delegate.calculatePrice(productId, basePrice);
        return price * 0.9; // 10% discount
    }
}

@Component("basePricingService")
public class BasePricingService implements PricingService {

    @Override
    public double calculatePrice(String productId, double basePrice) {
        return basePrice; // no modification
    }
}
```

Any bean that injects `PricingService` gets `DiscountDecorator` (because `@Primary`), which internally delegates to `BasePricingService`. Adding tax? Create `TaxDecorator` that wraps `DiscountDecorator`.

### 8.3 — Servlet Filters as Decorators

```java
@Component
@Order(1)
public class AuthFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response,
                         FilterChain chain) throws IOException, ServletException {
        // Before — authentication check
        HttpServletRequest req = (HttpServletRequest) request;
        String token = req.getHeader("Authorization");
        if (token == null || !isValid(token)) {
            ((HttpServletResponse) response).setStatus(401);
            return;
        }

        chain.doFilter(request, response); // delegate to next filter / controller

        // After — response logging
        System.out.println("Authenticated request completed");
    }
}
```

Each filter wraps the next one in the chain — same as stacking decorators.

---

## 9. Real-World Use Cases

| Domain | Component | Decorators |
|--------|-----------|------------|
| **Notification** | EmailNotifier | SMS, Slack, Push, Logging, Retry |
| **Pricing** | BasePriceCalculator | Discount, Tax, CouponApplier, CurrencyConverter |
| **I/O Streams** | FileInputStream | Buffering, Compression, Encryption, Checksum |
| **HTTP Client** | BaseHttpClient | Logging, Retry, CircuitBreaker, Authentication, Caching |
| **Data Access** | BaseRepository | Caching (Redis), Auditing, Metrics, Read-Replica routing |
| **Serialization** | JsonSerializer | Compression, Encryption, Base64Encoding |
| **Inventory (Walmart-style)** | BaseStockCalculator | SafetyStockDecorator, SeasonalAdjustment, PromotionBuffer |
| **Message Processing** | RawMessageProcessor | Validation, Enrichment, Transformation, DeduplicationFilter |

---

## 10. Decorator vs Other Patterns

### Decorator vs Proxy

| Aspect | Decorator | Proxy |
|--------|-----------|-------|
| **Intent** | Add new behavior dynamically | Control access to the object |
| **Stacking** | Multiple decorators are commonly stacked | Usually a single proxy layer |
| **Who creates** | Client builds the decorator chain | Proxy often creates/manages the real object internally |
| **Lifecycle** | Client provides the wrapped object | Proxy may lazy-load or cache the real object |
| **Examples** | BufferedInputStream, LoggingDecorator | Spring AOP proxy, LazyInitProxy, RemoteProxy |

> **Key insight:** Decorator **enhances** what the object does. Proxy **controls** whether/how the object is accessed. In practice, Spring's `@Transactional` proxy and AOP proxies are structurally identical to Decorator — the difference is intent.

### Decorator vs Strategy

| Aspect | Decorator | Strategy |
|--------|-----------|----------|
| **What changes** | The object's skin (wrapping layers) | The object's guts (algorithm) |
| **Mechanism** | Composition via wrapping (same interface) | Composition via delegation (different interface) |
| **Stacking** | Multiple decorators stack | One strategy at a time |
| **Client awareness** | Client builds the chain | Client picks the strategy |

### Decorator vs Chain of Responsibility

| Aspect | Decorator | Chain of Responsibility |
|--------|-----------|------------------------|
| **Execution** | ALL decorators execute (each layer runs) | Processing may STOP at any handler |
| **Control flow** | Always delegates to wrapped object | Handler decides whether to pass along |
| **Purpose** | Add behavior | Find the right handler |
| **Order** | Every layer runs in defined order | First matching handler wins |

### Decorator vs Inheritance

| Aspect | Decorator | Inheritance |
|--------|-----------|-------------|
| **When** | Runtime | Compile time |
| **Combinations** | N decorators for N features | 2^N subclasses for N features |
| **Flexibility** | Mix and match per instance | Fixed per class |
| **SRP** | Each decorator = one responsibility | Subclass may accumulate responsibilities |
| **Coupling** | Loose (interface-based) | Tight (knows parent internals) |

---

## 11. SOLID Principles Alignment

| Principle | How Decorator Helps |
|---|---|
| **S — Single Responsibility** | Each decorator adds exactly one concern (logging, retry, SMS, etc.). |
| **O — Open/Closed** | New behavior is added via new decorators, not by modifying existing classes. |
| **L — Liskov Substitution** | Decorated objects are substitutable wherever the Component interface is expected. |
| **I — Interface Segregation** | Component interface is narrow (ideally 1-3 methods). |
| **D — Dependency Inversion** | Decorators depend on the Component abstraction, not on ConcreteComponent. |

---

## 12. Common Pitfalls and How to Avoid Them

### 1. Identity Comparison Breaks

Decorators wrap the original object — identity checks fail:

```java
Notifier original = new EmailNotifier();
Notifier decorated = new LoggingDecorator(original);

System.out.println(original == decorated);           // false
System.out.println(original.equals(decorated));      // false (different objects)
System.out.println(decorated instanceof EmailNotifier); // false!
```

**Impact:** If any code does `instanceof` checks or identity comparisons, decoration breaks it.

**Solution:** Use interface type checks (`instanceof Notifier` → `true`) and avoid identity-based logic.

### 2. Large Interfaces Make Decoration Painful

If the Component interface has 15 methods, every decorator must delegate all 15 — even if it only enhances one:

```java
// Painful — 15 methods, only modifying 1
public class LoggingDecorator extends NotifierDecorator {
    // Must override or delegate all 15 methods
    // Only actually adding logic to send()
}
```

**Solution:** Keep Component interfaces small (ISP). If unavoidable, the `BaseDecorator` abstract class handles default delegation so concrete decorators only override what they need.

### 3. Constructor Complexity with Many Layers

Deep nesting gets hard to read:

```java
// Hard to read — which layer is which?
Notifier n = new LoggingDecorator(
                new RetryDecorator(
                    new SlackDecorator(
                        new SmsDecorator(
                            new EmailNotifier()
                        ), "orders"
                    ), 3
                )
             );
```

**Solution: Builder Pattern for Decorator Chains**

```java
public class NotifierBuilder {

    private Notifier notifier;

    public NotifierBuilder(Notifier base) {
        this.notifier = base;
    }

    public NotifierBuilder withSms() {
        this.notifier = new SmsDecorator(notifier);
        return this;
    }

    public NotifierBuilder withSlack(String channel) {
        this.notifier = new SlackDecorator(notifier, channel);
        return this;
    }

    public NotifierBuilder withRetry(int maxRetries) {
        this.notifier = new RetryDecorator(notifier, maxRetries);
        return this;
    }

    public NotifierBuilder withLogging() {
        this.notifier = new LoggingDecorator(notifier);
        return this;
    }

    public Notifier build() {
        return notifier;
    }
}
```

```java
// Clean, readable, self-documenting
Notifier notifier = new NotifierBuilder(new EmailNotifier())
    .withSms()
    .withSlack("orders")
    .withRetry(3)
    .withLogging()
    .build();
```

### 4. Debugging Difficulty

When something goes wrong, the stack trace shows multiple decorator layers, making it hard to pinpoint the issue.

**Solution:** Add class name in logs:

```java
@Override
public void send(String recipient, String message) {
    log.debug("[{}] delegating to [{}]", this.getClass().getSimpleName(),
        wrapped.getClass().getSimpleName());
    super.send(recipient, message);
}
```

### 5. Order-Dependent Bugs

As shown in Section 6, swapping decorator order changes behavior silently. No compile-time error tells you the order is wrong.

**Solution:** Document expected ordering. Use the Builder pattern to enforce correct order by controlling which decorators can be added at each step.

---

## 13. Multi-Method Decorator

When the Component interface has multiple methods, each decorator can enhance all, some, or just one:

```java
public interface DataService {
    String read(String key);
    void write(String key, String value);
    void delete(String key);
}
```

```java
public class BaseDataService implements DataService {

    private final Map<String, String> store = new HashMap<>();

    @Override
    public String read(String key) { return store.get(key); }

    @Override
    public void write(String key, String value) { store.put(key, value); }

    @Override
    public void delete(String key) { store.remove(key); }
}
```

```java
public abstract class DataServiceDecorator implements DataService {

    protected final DataService wrapped;

    public DataServiceDecorator(DataService wrapped) {
        this.wrapped = wrapped;
    }

    @Override
    public String read(String key) { return wrapped.read(key); }

    @Override
    public void write(String key, String value) { wrapped.write(key, value); }

    @Override
    public void delete(String key) { wrapped.delete(key); }
}
```

**Caching Decorator — enhances only `read()` and `write()`:**

```java
public class CachingDecorator extends DataServiceDecorator {

    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public CachingDecorator(DataService wrapped) {
        super(wrapped);
    }

    @Override
    public String read(String key) {
        // Check cache first
        String cached = cache.get(key);
        if (cached != null) {
            System.out.println("CACHE HIT → " + key);
            return cached;
        }
        // Cache miss — delegate and cache the result
        System.out.println("CACHE MISS → " + key);
        String value = super.read(key);
        if (value != null) {
            cache.put(key, value);
        }
        return value;
    }

    @Override
    public void write(String key, String value) {
        cache.put(key, value);        // update cache
        super.write(key, value);       // delegate to store
    }

    @Override
    public void delete(String key) {
        cache.remove(key);             // evict from cache
        super.delete(key);             // delegate to store
    }
}
```

**Audit Decorator — enhances only `write()` and `delete()`:**

```java
public class AuditDecorator extends DataServiceDecorator {

    public AuditDecorator(DataService wrapped) {
        super(wrapped);
    }

    @Override
    public void write(String key, String value) {
        System.out.println("AUDIT → WRITE key=" + key + " at " + LocalDateTime.now());
        super.write(key, value);
    }

    @Override
    public void delete(String key) {
        System.out.println("AUDIT → DELETE key=" + key + " at " + LocalDateTime.now());
        super.delete(key);
    }

    // read() is NOT overridden — passes through via BaseDecorator
}
```

**Stacking:**

```java
DataService service = new AuditDecorator(
                        new CachingDecorator(
                            new BaseDataService()
                        )
                      );

service.write("user:1", "Ayus");
// AUDIT → WRITE key=user:1 at 2025-06-29T...
// (CachingDecorator caches + delegates)
// (BaseDataService stores)

service.read("user:1");
// CACHE HIT → user:1  (no audit — AuditDecorator doesn't touch read)
```

---

## 14. Advantages and Disadvantages

### Advantages

- **Runtime flexibility** — add/remove behavior per instance without affecting other instances.
- **Avoids subclass explosion** — N decorators instead of 2^N subclasses.
- **SRP compliance** — each decorator encapsulates one concern.
- **Composable** — stack decorators in any combination, any order.
- **OCP compliance** — new behavior = new decorator, no existing code modified.

### Disadvantages

- **Deep nesting** — many layers make construction and debugging harder (mitigated by Builder).
- **Identity breaks** — `instanceof` and `equals` don't see through wrappers.
- **Method delegation overhead** — every call passes through N layers (usually negligible, but measurable in ultra-hot paths).
- **Large interfaces** — decorating a 15-method interface means 15 delegation methods in the base decorator.
- **Order sensitivity** — stacking order affects behavior silently.

---

## 15. Interview-Ready Talking Points

**"Tell me about the Decorator pattern and where you've used it."**

> "Decorator lets you wrap an object with additional behavior without modifying it, by implementing the same interface. At Walmart, our notification system started with just email confirmations. When we needed to add SMS, Slack alerts for the ops team, and structured logging, we used the Decorator pattern — each was a separate wrapper implementing the same `Notifier` interface. Stacking them was just: `new LoggingDecorator(new SlackDecorator(new SmsDecorator(new EmailNotifier())))`. Adding push notifications later was just a new decorator — zero changes to existing code."

**"How is Decorator different from Proxy?"**

> "Structurally they're identical — both wrap an object behind the same interface. The difference is intent: Decorator **adds new behavior** (logging, caching, retry), while Proxy **controls access** (lazy loading, security checks, remote calls). Decorator is client-assembled — the client builds the chain. Proxy is usually transparent — the client doesn't know it's there. Spring's `@Transactional` is technically a proxy, but Spring AOP's `@Around` advice acts very much like a decorator."

**"When would you NOT use Decorator?"**

> "If the component interface is very large — say 20 methods — the base decorator needs to delegate all 20, which is tedious and error-prone. Also, if you'll only ever have one fixed combination of behaviors, a single subclass is simpler. And if identity checks (`instanceof`, `equals`) are critical in your codebase, decoration breaks them because the wrapper is a different object."

**"How does it relate to Java I/O?"**

> "Java's entire I/O system is built on Decorator. `InputStream` is the component interface. `FileInputStream` is the concrete component. `FilterInputStream` is the base decorator. `BufferedInputStream`, `DataInputStream`, `GZIPInputStream` are concrete decorators. You stack them: `new DataInputStream(new BufferedInputStream(new FileInputStream(...)))`. Each layer adds one capability — buffering, typed reads, decompression — and they're freely composable."

---

## 16. Quick Implementation Checklist

1. Identify the **core behavior** that needs optional enhancements.
2. Define a **Component interface** with the operations to be decorated.
3. Implement the **ConcreteComponent** with the base behavior.
4. Create a **BaseDecorator** abstract class that holds a `Component` reference and delegates all calls.
5. Implement **ConcreteDecorators** — each overrides specific methods, adding behavior before/after `super.method()`.
6. Client code **constructs the chain** by nesting decorators.
7. (Optional) Use a **Builder** to make chain construction readable.
8. (Optional) In Spring — use `@Primary` + `@Qualifier` or `Filter`/`HandlerInterceptor` for HTTP-level decoration.
