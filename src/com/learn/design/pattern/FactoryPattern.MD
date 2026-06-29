# Factory Design Pattern — Detailed Notes

## 1. What Is It?

The Factory pattern is a **creational design pattern** that provides an interface for creating objects without exposing the instantiation logic to the client. Instead of calling `new ConcreteClass()` directly, the client asks a factory to create the object — the factory decides which concrete class to instantiate.

**Core Idea:** Move the `new` keyword out of client code. The client depends on an abstraction, and the factory handles the concrete class selection.

There are **three variants**, each solving a progressively harder problem:

| Variant | What It Does | Complexity |
|---------|-------------|------------|
| **Simple Factory** | A single method with conditional logic picks the class. Not a GoF pattern — it's a common idiom. | Low |
| **Factory Method** | A method in a superclass defines the interface for creation; subclasses override it to decide which class to instantiate. | Medium |
| **Abstract Factory** | A factory interface creates **families of related objects** without specifying their concrete classes. | High |

---

## 2. When to Use

- You don't know **ahead of time** which concrete class to instantiate — the decision depends on configuration, user input, or runtime context.
- You want to **decouple client code** from concrete classes — the client works with the interface, never with `new ConcreteClass()`.
- You have a **family of related classes** that share an interface, and the right one is chosen at runtime.
- Object creation involves **complex setup** (reading config, resolving dependencies, validation) that shouldn't be scattered across client code.
- You want a **single place to modify** when adding a new product type — OCP compliance.

**Don't use when:**
- There's only one implementation and it will never change — `new` is fine.
- Object creation is trivial (no logic, no dependencies) — a factory adds unnecessary indirection.
- You're in a Spring Boot context where DI already handles object creation — don't layer a manual factory on top of Spring's container.

---

## 3. Simple Factory (Not a GoF Pattern — But Common)

### 3.1 — Problem Without Any Factory

```java
public class NotificationService {

    public void sendNotification(String type, String recipient, String message) {
        if ("EMAIL".equals(type)) {
            EmailNotification email = new EmailNotification();
            email.configure(recipient);
            email.send(message);
        } else if ("SMS".equals(type)) {
            SmsNotification sms = new SmsNotification();
            sms.configure(recipient);
            sms.send(message);
        } else if ("PUSH".equals(type)) {
            PushNotification push = new PushNotification();
            push.configure(recipient);
            push.send(message);
        }
        // Adding Slack? Modify this class. OCP violated.
        // This same if-else is duplicated wherever notifications are created.
    }
}
```

**Problems:** Client is tightly coupled to every concrete class. The same if-else block gets duplicated across the codebase. Adding a new type requires modifying every place that creates notifications.

### 3.2 — Solution: Simple Factory

**Step 1: Define the Product Interface**

```java
public interface Notification {
    void configure(String recipient);
    void send(String message);
}
```

**Step 2: Implement Concrete Products**

```java
public class EmailNotification implements Notification {

    private String recipient;

    @Override
    public void configure(String recipient) {
        this.recipient = recipient;
    }

    @Override
    public void send(String message) {
        System.out.println("EMAIL → " + recipient + ": " + message);
    }
}

public class SmsNotification implements Notification {

    private String recipient;

    @Override
    public void configure(String recipient) {
        this.recipient = recipient;
    }

    @Override
    public void send(String message) {
        System.out.println("SMS → " + recipient + ": " + message);
    }
}

public class PushNotification implements Notification {

    private String recipient;

    @Override
    public void configure(String recipient) {
        this.recipient = recipient;
    }

    @Override
    public void send(String message) {
        System.out.println("PUSH → " + recipient + ": " + message);
    }
}
```

**Step 3: Create the Simple Factory**

```java
public class NotificationFactory {

    public static Notification createNotification(String type) {
        return switch (type.toUpperCase()) {
            case "EMAIL" -> new EmailNotification();
            case "SMS"   -> new SmsNotification();
            case "PUSH"  -> new PushNotification();
            default      -> throw new IllegalArgumentException("Unknown notification type: " + type);
        };
    }
}
```

**Step 4: Client Code**

```java
public class NotificationService {

    public void sendNotification(String type, String recipient, String message) {
        Notification notification = NotificationFactory.createNotification(type);
        notification.configure(recipient);
        notification.send(message);
    }
}
```

```java
public class Main {
    public static void main(String[] args) {
        NotificationService service = new NotificationService();
        service.sendNotification("EMAIL", "ayus@email.com", "Order confirmed");
        service.sendNotification("SMS", "+91-9876543210", "OTP: 4521");
        service.sendNotification("PUSH", "device-token-xyz", "New offer available");
    }
}
```

**What improved:**
- Client (`NotificationService`) no longer knows about `EmailNotification`, `SmsNotification`, or `PushNotification`.
- The if-else/switch exists in **one place** — the factory.
- Adding Slack? Add a new class + one case in the factory. Client code unchanged.

**What's still not ideal:**
- The factory itself has a switch — adding a new type still modifies `NotificationFactory` (violates OCP).
- It's a static method — hard to mock in tests.

---

### 3.3 — Simple Factory with Registry (OCP-Compliant)

Eliminate the switch entirely by using a registry map:

```java
public class NotificationFactory {

    private static final Map<String, Supplier<Notification>> registry = new HashMap<>();

    static {
        registry.put("EMAIL", EmailNotification::new);
        registry.put("SMS", SmsNotification::new);
        registry.put("PUSH", PushNotification::new);
    }

    // Open for extension — new types register themselves
    public static void register(String type, Supplier<Notification> supplier) {
        registry.put(type.toUpperCase(), supplier);
    }

    public static Notification createNotification(String type) {
        Supplier<Notification> supplier = registry.get(type.toUpperCase());
        if (supplier == null) {
            throw new IllegalArgumentException("Unknown notification type: " + type);
        }
        return supplier.get();
    }
}
```

```java
// Adding Slack — no modification to factory
NotificationFactory.register("SLACK", SlackNotification::new);

// Works immediately
Notification slack = NotificationFactory.createNotification("SLACK");
```

Now the factory is truly OCP-compliant — new types register themselves without touching existing code.

---

## 4. Factory Method (GoF Pattern)

### 4.1 — What Changes from Simple Factory

Simple Factory centralizes creation in **one static method**. Factory Method pushes the creation decision into **subclasses** — each subclass overrides a method to create its specific product.

**GoF Definition:** "Define an interface for creating an object, but let subclasses decide which class to instantiate. Factory Method lets a class defer instantiation to subclasses."

### 4.2 — UML Structure

```
┌─────────────────────────────────┐        ┌─────────────────────────────┐
│       <<abstract>>               │        │      <<interface>>           │
│        Creator                   │        │        Product               │
├─────────────────────────────────┤        ├─────────────────────────────┤
│ + createProduct(): Product       │───────→│ + operation(): void          │
│ + someBusinessLogic()            │        └──────────────┬──────────────┘
└──────────────┬──────────────────┘                        │ implements
               │ extends                            ┌──────┴───────┐
       ┌───────┴────────┐                           ▼              ▼
       ▼                ▼                    ┌──────────────┐ ┌──────────────┐
┌──────────────┐ ┌──────────────┐           │  ProductA    │ │  ProductB    │
│  CreatorA    │ │  CreatorB    │           └──────────────┘ └──────────────┘
├──────────────┤ ├──────────────┤
│ createProduct│ │ createProduct│
│ → ProductA   │ │ → ProductB   │
└──────────────┘ └──────────────┘
```

**Four Participants:**

| Role | Responsibility |
|------|----------------|
| **Product (Interface)** | Defines the contract for objects the factory method creates. |
| **ConcreteProduct** | Implements the Product interface. |
| **Creator (Abstract)** | Declares the `createProduct()` factory method. May contain business logic that uses the product. |
| **ConcreteCreator** | Overrides `createProduct()` to return a specific ConcreteProduct. |

### 4.3 — Java Implementation

**Example: Document Exporter System**

Different export formats (PDF, Excel, CSV) need different generators, but the export workflow (validate → generate → compress → upload) is the same.

**Step 1: Product Interface**

```java
public interface DocumentExporter {
    byte[] export(List<Map<String, Object>> data);
    String getFileExtension();
    String getContentType();
}
```

**Step 2: Concrete Products**

```java
public class PdfExporter implements DocumentExporter {

    @Override
    public byte[] export(List<Map<String, Object>> data) {
        System.out.println("Generating PDF document...");
        // actual PDF generation logic (iText, Apache PDFBox, etc.)
        return new byte[0]; // placeholder
    }

    @Override
    public String getFileExtension() { return ".pdf"; }

    @Override
    public String getContentType() { return "application/pdf"; }
}

public class ExcelExporter implements DocumentExporter {

    @Override
    public byte[] export(List<Map<String, Object>> data) {
        System.out.println("Generating Excel spreadsheet...");
        // actual Excel generation logic (Apache POI)
        return new byte[0];
    }

    @Override
    public String getFileExtension() { return ".xlsx"; }

    @Override
    public String getContentType() {
        return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    }
}

public class CsvExporter implements DocumentExporter {

    @Override
    public byte[] export(List<Map<String, Object>> data) {
        System.out.println("Generating CSV file...");
        StringBuilder sb = new StringBuilder();
        if (!data.isEmpty()) {
            sb.append(String.join(",", data.get(0).keySet())).append("\n");
            for (Map<String, Object> row : data) {
                sb.append(String.join(",",
                    row.values().stream().map(Object::toString).toList()
                )).append("\n");
            }
        }
        return sb.toString().getBytes();
    }

    @Override
    public String getFileExtension() { return ".csv"; }

    @Override
    public String getContentType() { return "text/csv"; }
}
```

**Step 3: Creator (Abstract Class with Factory Method)**

```java
public abstract class ReportGenerator {

    // ═══════════════════════════════════════
    // THIS is the Factory Method
    // Subclasses override this to decide which exporter to create
    // ═══════════════════════════════════════
    protected abstract DocumentExporter createExporter();

    // Template-like workflow that USES the product
    public final String generateReport(List<Map<String, Object>> data, String reportName) {
        // Step 1: Validate
        if (data == null || data.isEmpty()) {
            throw new IllegalArgumentException("No data to export");
        }

        // Step 2: Create exporter via factory method
        DocumentExporter exporter = createExporter();

        // Step 3: Generate
        byte[] content = exporter.export(data);

        // Step 4: Build filename
        String filename = reportName + exporter.getFileExtension();

        // Step 5: Upload (simulated)
        System.out.println("Uploading " + filename + " (" + exporter.getContentType() + ")");

        return filename;
    }
}
```

> **Key insight:** The `generateReport()` method defines the workflow (validate → create → export → upload) but doesn't know which exporter it's using. The `createExporter()` factory method is the "hole" that subclasses fill.

**Step 4: Concrete Creators**

```java
public class PdfReportGenerator extends ReportGenerator {

    @Override
    protected DocumentExporter createExporter() {
        return new PdfExporter();
    }
}

public class ExcelReportGenerator extends ReportGenerator {

    @Override
    protected DocumentExporter createExporter() {
        return new ExcelExporter();
    }
}

public class CsvReportGenerator extends ReportGenerator {

    @Override
    protected DocumentExporter createExporter() {
        return new CsvExporter();
    }
}
```

**Step 5: Client Code**

```java
public class Main {
    public static void main(String[] args) {
        List<Map<String, Object>> salesData = List.of(
            Map.of("product", "Laptop", "amount", 75000, "qty", 10),
            Map.of("product", "Mouse", "amount", 500, "qty", 200)
        );

        // Client picks the creator — doesn't know about PdfExporter, ExcelExporter, etc.
        ReportGenerator generator = new PdfReportGenerator();
        String file = generator.generateReport(salesData, "sales-q4");
        // Output: Generating PDF document...
        //         Uploading sales-q4.pdf (application/pdf)

        // Switch to Excel — just swap the creator
        generator = new ExcelReportGenerator();
        file = generator.generateReport(salesData, "sales-q4");
        // Output: Generating Excel spreadsheet...
        //         Uploading sales-q4.xlsx (application/vnd.openxml...)
    }
}
```

### 4.4 — "But We're Still Using `new` for the Creator!"

The Factory Method pattern removes `new` for the **product** (`PdfExporter`, `ExcelExporter`) — the client never sees those concrete classes. But the **creator** (`new PdfReportGenerator()`) is still instantiated directly. This is a valid concern.

**What Factory Method actually guarantees:**
- Client is decoupled from **products** — doesn't know `PdfExporter` exists.
- Client still knows about **creators** — but creators are stable (they rarely change once written).
- The `generateReport()` workflow is reusable — same validation, compression, upload logic regardless of format.

**But if you want to eliminate `new` for the creator too, you have three options:**

**Option A: Simple Factory for the Creator (Factory of Factories)**

```java
public class ReportGeneratorFactory {

    private static final Map<String, Supplier<ReportGenerator>> registry = Map.of(
        "PDF",   PdfReportGenerator::new,
        "EXCEL", ExcelReportGenerator::new,
        "CSV",   CsvReportGenerator::new
    );

    public static ReportGenerator getGenerator(String format) {
        Supplier<ReportGenerator> supplier = registry.get(format.toUpperCase());
        if (supplier == null) {
            throw new IllegalArgumentException("Unsupported format: " + format);
        }
        return supplier.get();
    }
}
```

```java
// Client — no new keyword at all
public class Main {
    public static void main(String[] args) {
        String format = "PDF"; // could come from config, API param, user input

        ReportGenerator generator = ReportGeneratorFactory.getGenerator(format);
        generator.generateReport(salesData, "sales-q4");
    }
}
```

**Option B: Spring DI — Let the Container Decide**

```java
@Component("PDF")
public class PdfReportGenerator extends ReportGenerator {
    @Override
    protected DocumentExporter createExporter() { return new PdfExporter(); }
}

@Component("EXCEL")
public class ExcelReportGenerator extends ReportGenerator {
    @Override
    protected DocumentExporter createExporter() { return new ExcelExporter(); }
}
```

```java
@Service
public class ReportService {

    private final Map<String, ReportGenerator> generators;

    // Spring injects all ReportGenerator beans, keyed by bean name
    public ReportService(Map<String, ReportGenerator> generators) {
        this.generators = generators;
    }

    public String export(String format, List<Map<String, Object>> data, String name) {
        ReportGenerator generator = generators.get(format.toUpperCase());
        if (generator == null) {
            throw new IllegalArgumentException("Unsupported format: " + format);
        }
        return generator.generateReport(data, name);
    }
}
```

```java
// Controller — no new keyword anywhere
@RestController
public class ReportController {

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    @GetMapping("/export")
    public String export(@RequestParam String format) {
        return reportService.export(format, getSalesData(), "sales-q4");
    }
}
```

**Option C: Dependency Injection via Constructor**

The simplest approach — push the decision to the caller:

```java
public class ReportService {

    private final ReportGenerator generator;

    // Whoever creates ReportService decides the generator
    public ReportService(ReportGenerator generator) {
        this.generator = generator;
    }

    public String export(List<Map<String, Object>> data, String name) {
        return generator.generateReport(data, name);
    }
}
```

```java
// The `new` lives at the composition root — one place, topmost layer
ReportService service = new ReportService(new PdfReportGenerator());
```

**Summary — Where Does `new` Live?**

| Approach | `new` keyword lives in... | Best when... |
|----------|--------------------------|--------------|
| Raw Factory Method | Client code | Simple apps, learning the pattern |
| Simple Factory for Creator | `ReportGeneratorFactory` (one place) | Format comes from runtime value |
| Spring DI | Container config (`@Component`) | Spring Boot apps |
| Constructor Injection | Composition root (topmost layer, e.g., `main()`) | Clean architecture, testability |

> **Bottom line:** The `new` keyword can never be fully eliminated — someone, somewhere creates the object. The goal is to push it to the **edges** (composition root, factory, DI container) and keep business logic free of it.

### 4.5 — Factory Method vs Simple Factory

| Aspect | Simple Factory | Factory Method |
|--------|---------------|----------------|
| **Creation logic** | Centralized in one static method (switch/map) | Distributed across subclasses |
| **Extension** | Modify the factory's switch (or use registry) | Add a new Creator subclass |
| **OCP** | Violated (unless using registry) | Respected — new subclass, no existing code changed |
| **Use when** | Product selection is data-driven (a string/enum decides) | Product selection is tied to a class hierarchy or framework extension point |
| **Complexity** | Lower — one class | Higher — parallel hierarchy (Creator + Product) |

> **When to pick which:** If the creation decision comes from a **runtime value** (a string from config, API param, DB), use Simple Factory. If the decision comes from **which subclass you are** (framework plugin model), use Factory Method.

---

## 5. Abstract Factory (GoF Pattern)

### 5.1 — What Problem It Solves

Factory Method creates **one product**. Abstract Factory creates **a family of related products** that must be used together. If you mix products from different families, things break.

**GoF Definition:** "Provide an interface for creating families of related or dependent objects without specifying their concrete classes."

### 5.2 — UML Structure

```
┌─────────────────────────────────────┐
│        <<interface>>                 │
│        UIComponentFactory            │
├─────────────────────────────────────┤
│ + createButton(): Button             │
│ + createTextField(): TextField       │
│ + createCheckbox(): Checkbox         │
└──────────────┬──────────────────────┘
               │ implements
       ┌───────┴────────┐
       ▼                ▼
┌──────────────┐ ┌──────────────┐       Each factory produces
│ MaterialUI   │ │ AppleUI      │       a FAMILY of related products
│ Factory      │ │ Factory      │       that work together
└──────┬───────┘ └──────┬───────┘
       │                │
       │ creates         │ creates
       ▼                ▼
  MaterialButton     AppleButton        ← same Button interface
  MaterialTextField  AppleTextField     ← same TextField interface
  MaterialCheckbox   AppleCheckbox      ← same Checkbox interface
```

### 5.3 — Java Implementation

**Example: Cross-Platform UI Component Factory**

A UI framework needs to render components in either Material Design (Android) or Apple HIG (iOS) style. Mixing a Material button with an Apple checkbox would look broken — the factory ensures consistency.

**Step 1: Product Interfaces (The Family)**

```java
public interface Button {
    void render();
    void onClick(Runnable action);
}

public interface TextField {
    void render();
    String getValue();
    void setValue(String text);
}

public interface Checkbox {
    void render();
    boolean isChecked();
    void setChecked(boolean checked);
}
```

**Step 2: Concrete Product Families**

```java
// ─── Material Design Family ───
public class MaterialButton implements Button {
    @Override
    public void render() { System.out.println("[Material] Rendering raised button with ripple effect"); }
    @Override
    public void onClick(Runnable action) { action.run(); }
}

public class MaterialTextField implements TextField {
    private String value = "";
    @Override
    public void render() { System.out.println("[Material] Rendering outlined text field"); }
    @Override
    public String getValue() { return value; }
    @Override
    public void setValue(String text) { this.value = text; }
}

public class MaterialCheckbox implements Checkbox {
    private boolean checked = false;
    @Override
    public void render() { System.out.println("[Material] Rendering checkbox with checkmark animation"); }
    @Override
    public boolean isChecked() { return checked; }
    @Override
    public void setChecked(boolean checked) { this.checked = checked; }
}

// ─── Apple HIG Family ───
public class AppleButton implements Button {
    @Override
    public void render() { System.out.println("[Apple] Rendering rounded button with haptic feedback"); }
    @Override
    public void onClick(Runnable action) { action.run(); }
}

public class AppleTextField implements TextField {
    private String value = "";
    @Override
    public void render() { System.out.println("[Apple] Rendering bordered text field with clear button"); }
    @Override
    public String getValue() { return value; }
    @Override
    public void setValue(String text) { this.value = text; }
}

public class AppleCheckbox implements Checkbox {
    private boolean checked = false;
    @Override
    public void render() { System.out.println("[Apple] Rendering toggle switch"); }
    @Override
    public boolean isChecked() { return checked; }
    @Override
    public void setChecked(boolean checked) { this.checked = checked; }
}
```

**Step 3: Abstract Factory Interface**

```java
public interface UIComponentFactory {
    Button createButton();
    TextField createTextField();
    Checkbox createCheckbox();
}
```

**Step 4: Concrete Factories**

```java
public class MaterialUIFactory implements UIComponentFactory {

    @Override
    public Button createButton() { return new MaterialButton(); }

    @Override
    public TextField createTextField() { return new MaterialTextField(); }

    @Override
    public Checkbox createCheckbox() { return new MaterialCheckbox(); }
}

public class AppleUIFactory implements UIComponentFactory {

    @Override
    public Button createButton() { return new AppleButton(); }

    @Override
    public TextField createTextField() { return new AppleTextField(); }

    @Override
    public Checkbox createCheckbox() { return new AppleCheckbox(); }
}
```

**Step 5: Client Code**

```java
public class LoginPage {

    private final Button loginButton;
    private final TextField usernameField;
    private final TextField passwordField;
    private final Checkbox rememberMeCheckbox;

    // Client depends on factory interface — doesn't know Material vs Apple
    public LoginPage(UIComponentFactory factory) {
        this.loginButton = factory.createButton();
        this.usernameField = factory.createTextField();
        this.passwordField = factory.createTextField();
        this.rememberMeCheckbox = factory.createCheckbox();
    }

    public void render() {
        System.out.println("=== Login Page ===");
        usernameField.render();
        passwordField.render();
        rememberMeCheckbox.render();
        loginButton.render();
    }
}
```

```java
public class Main {
    public static void main(String[] args) {
        // Decide factory based on platform
        String platform = System.getProperty("os.name").toLowerCase();
        UIComponentFactory factory = platform.contains("mac")
            ? new AppleUIFactory()
            : new MaterialUIFactory();

        LoginPage loginPage = new LoginPage(factory);
        loginPage.render();

        // On Mac:
        // === Login Page ===
        // [Apple] Rendering bordered text field with clear button
        // [Apple] Rendering bordered text field with clear button
        // [Apple] Rendering toggle switch
        // [Apple] Rendering rounded button with haptic feedback

        // On others:
        // [Material] Rendering outlined text field
        // [Material] Rendering outlined text field
        // [Material] Rendering checkbox with checkmark animation
        // [Material] Rendering raised button with ripple effect
    }
}
```

**What Abstract Factory guarantees:** You can never accidentally mix a `MaterialButton` with an `AppleCheckbox`. The factory produces a consistent family.

---

## 6. All Three Compared

| Aspect | Simple Factory | Factory Method | Abstract Factory |
|--------|---------------|----------------|------------------|
| **Creates** | One product | One product | A family of related products |
| **Decision by** | Runtime value (string/enum) | Subclass identity | Factory implementation |
| **Extension** | Add case to switch (or register) | Add new Creator subclass | Add new Factory implementation |
| **Structure** | One class with a static method | Parallel class hierarchies (Creator↔Product) | Factory interface + Product interfaces |
| **GoF pattern?** | No (common idiom) | Yes | Yes |
| **Complexity** | Low | Medium | High |
| **Use when** | Selection is data-driven, simple | Creation is tied to a class hierarchy | Multiple related objects must be created consistently |

---

## 7. Factory in the JDK

| Class / Method | Variant | What It Creates |
|---------------|---------|-----------------|
| `Calendar.getInstance()` | Simple Factory | `GregorianCalendar` or locale-specific calendar |
| `NumberFormat.getInstance()` | Simple Factory | Locale-specific number formatter |
| `Collection.iterator()` | Factory Method | `ArrayList` returns `ArrayListIterator`, `HashSet` returns `HashSetIterator` |
| `DocumentBuilderFactory.newInstance()` | Abstract Factory | XML parser family (DocumentBuilder, Transformer, etc.) |
| `DriverManager.getConnection()` | Simple Factory | Database-specific `Connection` implementation |
| `ExecutorService` via `Executors.newFixedThreadPool()` | Simple Factory | Configured `ThreadPoolExecutor` |
| `List.of()`, `Map.of()` | Simple Factory | Immutable collection implementations |
| `Optional.of()`, `Optional.empty()` | Simple Factory | `Optional` instances |
| `Logger.getLogger()` | Simple Factory | Logger instance (may be cached) |

### `Collection.iterator()` — Factory Method in Action

```java
List<String> list = new ArrayList<>();
Iterator<String> it = list.iterator();  // returns ArrayList's internal Itr class

Set<String> set = new HashSet<>();
Iterator<String> it2 = set.iterator(); // returns HashSet's internal KeyIterator
```

Each collection **overrides** `iterator()` to return its own iterator type. Client code just calls `iterator()` on the `Collection` interface — Factory Method pattern.

---

## 8. Factory in the Spring Boot World

### 8.1 — Spring as a Giant Factory

Spring's `ApplicationContext` IS a factory — it creates and manages bean instances:

```java
// Spring decides which implementation to inject
@Service
public class OrderService {

    private final PaymentGateway gateway; // interface

    // Spring resolves the concrete implementation
    public OrderService(PaymentGateway gateway) {
        this.gateway = gateway;
    }
}
```

`BeanFactory` and `ApplicationContext` are literally factory interfaces in Spring's class hierarchy.

### 8.2 — Map-Based Factory with Spring (Simple Factory, OCP-Compliant)

This is the most production-common factory pattern in Spring Boot — identical to how we did Strategy in Spring:

```java
public interface PaymentProcessor {
    String getType();
    PaymentResult process(PaymentRequest request);
}

@Component
public class CreditCardProcessor implements PaymentProcessor {
    public String getType() { return "CREDIT_CARD"; }
    public PaymentResult process(PaymentRequest request) { /* ... */ return null; }
}

@Component
public class UpiProcessor implements PaymentProcessor {
    public String getType() { return "UPI"; }
    public PaymentResult process(PaymentRequest request) { /* ... */ return null; }
}

@Component
public class WalletProcessor implements PaymentProcessor {
    public String getType() { return "WALLET"; }
    public PaymentResult process(PaymentRequest request) { /* ... */ return null; }
}
```

```java
@Component
public class PaymentProcessorFactory {

    private final Map<String, PaymentProcessor> processorMap;

    // Spring injects ALL PaymentProcessor beans
    public PaymentProcessorFactory(List<PaymentProcessor> processors) {
        this.processorMap = processors.stream()
            .collect(Collectors.toMap(PaymentProcessor::getType, Function.identity()));
    }

    public PaymentProcessor getProcessor(String type) {
        PaymentProcessor processor = processorMap.get(type);
        if (processor == null) {
            throw new IllegalArgumentException("Unsupported payment type: " + type);
        }
        return processor;
    }
}
```

```java
@Service
public class PaymentService {

    private final PaymentProcessorFactory factory;

    public PaymentService(PaymentProcessorFactory factory) {
        this.factory = factory;
    }

    public PaymentResult pay(String type, PaymentRequest request) {
        PaymentProcessor processor = factory.getProcessor(type);
        return processor.process(request);
    }
}
```

**Adding a new payment type:** Create a new `@Component` implementing `PaymentProcessor`. It auto-registers. Zero changes to factory or service. True OCP.

### 8.3 — Factory Method in Spring with @Bean

```java
@Configuration
public class AppConfig {

    @Bean
    public RestTemplate restTemplate() {
        // Factory method — complex creation logic centralized
        RestTemplate template = new RestTemplate();
        template.setRequestFactory(new HttpComponentsClientHttpRequestFactory());
        template.setInterceptors(List.of(new LoggingInterceptor()));
        template.setErrorHandler(new CustomErrorHandler());
        return template;
    }

    @Bean
    @Profile("production")
    public DataSource productionDataSource() {
        // Different product for production
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl("jdbc:postgresql://prod-db:5432/app");
        ds.setMaximumPoolSize(20);
        return ds;
    }

    @Bean
    @Profile("development")
    public DataSource developmentDataSource() {
        // Different product for development
        return new EmbeddedDatabaseBuilder()
            .setType(EmbeddedDatabaseType.H2)
            .addScript("schema.sql")
            .build();
    }
}
```

Spring's `@Profile` is essentially a Factory Method — the "subclass" (profile-specific config) decides which concrete product (DataSource) to create.

---

## 9. Factory + Other Patterns

### 9.1 — Factory + Strategy

Factory decides **which** strategy; Strategy defines **how** it runs:

```java
@Component
public class ShippingStrategyFactory {

    private final Map<String, ShippingStrategy> strategyMap;

    public ShippingStrategyFactory(List<ShippingStrategy> strategies) {
        this.strategyMap = strategies.stream()
            .collect(Collectors.toMap(ShippingStrategy::getCarrier, Function.identity()));
    }

    public ShippingStrategy getStrategy(String carrier) {
        return strategyMap.getOrDefault(carrier,
            strategyMap.get("DEFAULT"));
    }
}
```

### 9.2 — Factory + Decorator

Factory creates the **base object**; Decorator wraps it with enhancements:

```java
public class NotifierFactory {

    public static Notifier createNotifier(NotificationConfig config) {
        Notifier notifier = new EmailNotifier(); // base

        if (config.isSmsEnabled()) {
            notifier = new SmsDecorator(notifier);
        }
        if (config.isSlackEnabled()) {
            notifier = new SlackDecorator(notifier, config.getSlackChannel());
        }
        if (config.isRetryEnabled()) {
            notifier = new RetryDecorator(notifier, config.getMaxRetries());
        }

        // Always add logging as outermost layer
        notifier = new LoggingDecorator(notifier);
        return notifier;
    }
}
```

### 9.3 — Factory + Observer

Factory creates the **subject** and wires up its **observers** based on configuration:

```java
public class OrderServiceFactory {

    public static OrderService create(AppConfig config) {
        OrderService service = new OrderService();

        service.addObserver(new EmailNotificationObserver());
        service.addObserver(new InventoryObserver());

        if (config.isAnalyticsEnabled()) {
            service.addObserver(new AnalyticsObserver());
        }
        if (config.isFraudDetectionEnabled()) {
            service.addObserver(new FraudDetectionObserver());
        }

        return service;
    }
}
```

---

## 10. Real-World Use Cases

| Domain | Factory Type | What It Creates |
|--------|-------------|-----------------|
| **Document Export** | Factory Method | PDF / Excel / CSV exporters |
| **Payment Processing** | Simple Factory (registry) | CreditCard / UPI / Wallet processors |
| **Database Connection** | Simple Factory | MySQL / PostgreSQL / Oracle connections |
| **Cross-Platform UI** | Abstract Factory | Material / Apple / Windows component families |
| **Cloud Provider** | Abstract Factory | AWS / GCP / Azure storage + compute + messaging families |
| **Message Broker** | Simple Factory | Kafka / RabbitMQ / SQS producers |
| **Logging** | Simple Factory | Console / File / Cloud loggers |
| **Serialization** | Simple Factory | JSON / XML / Protobuf serializers |
| **Auth Provider** | Factory Method | OAuth / JWT / SAML authenticators |
| **Inventory (Walmart-style)** | Simple Factory | Different inventory calculators per warehouse type |

---

## 11. SOLID Principles Alignment

| Principle | How Factory Helps |
|---|---|
| **S — Single Responsibility** | Factory handles creation logic; client handles business logic. Separate concerns. |
| **O — Open/Closed** | Registry-based factory: new product = new class + register. No existing code modified. |
| **L — Liskov Substitution** | Client works with Product interface — any concrete product is substitutable. |
| **I — Interface Segregation** | Product interfaces stay narrow and focused. |
| **D — Dependency Inversion** | Client depends on Product interface, not on concrete classes. Factory depends on interface, not implementations. |

---

## 12. Common Pitfalls and How to Avoid Them

### 1. Over-Factoring

```java
// OVER-ENGINEERING — factory for a single implementation
public class UserRepositoryFactory {
    public static UserRepository create() {
        return new JpaUserRepository(); // only one implementation, ever
    }
}

// JUST USE IT DIRECTLY
@Autowired
private UserRepository userRepository; // Spring handles it
```

**Rule:** If there's only one implementation and no foreseeable variation, a factory adds unnecessary indirection.

### 2. Fat Factory with Business Logic

```java
// BAD — factory doing validation, transformation, AND creation
public class OrderFactory {
    public static Order create(OrderRequest request) {
        validate(request);               // not creation
        enrichWithPricing(request);       // not creation
        applyDiscounts(request);          // not creation
        Order order = new Order(request); // creation
        sendNotification(order);          // not creation
        return order;
    }
}

// BETTER — factory only creates; other concerns live elsewhere
public class OrderFactory {
    public static Order create(OrderRequest request) {
        return new Order(
            request.getCustomerId(),
            request.getProductIds(),
            request.getTotalAmount()
        );
    }
}
```

**Rule:** Factories create objects. Validation, enrichment, and side-effects belong in services.

### 3. Switch Statements That Never End

```java
// Grows forever — violates OCP
public static Notification create(String type) {
    return switch (type) {
        case "EMAIL" -> new EmailNotification();
        case "SMS"   -> new SmsNotification();
        case "PUSH"  -> new PushNotification();
        case "SLACK" -> new SlackNotification();
        case "WHATSAPP" -> new WhatsAppNotification();
        case "TELEGRAM" -> new TelegramNotification();
        // ... 15 more cases
    };
}
```

**Solution:** Use the registry pattern (Section 3.3) or Spring's map-based autowiring (Section 8.2).

### 4. Returning Null Instead of Throwing

```java
// BAD — null propagates silently, NPE crashes later
public static Notification create(String type) {
    return switch (type) {
        case "EMAIL" -> new EmailNotification();
        default -> null; // silent failure
    };
}

// BETTER — fail fast with a clear message
default -> throw new IllegalArgumentException("Unknown notification type: " + type);
```

---

## 13. Advantages and Disadvantages

### Advantages

- **Decouples client from concrete classes** — client works with interfaces only.
- **Centralizes creation logic** — one place to modify, test, and monitor.
- **SRP compliance** — creation logic separated from business logic.
- **OCP compliance** — registry/subclass-based factories support new types without modification.
- **Testability** — inject mock factories for unit tests.

### Disadvantages

- **Added complexity** — parallel class hierarchies (Factory Method), or extra interfaces (Abstract Factory).
- **Over-engineering risk** — don't build a factory for a single concrete class.
- **Indirection** — harder to trace "where was this object actually created?"
- **Abstract Factory rigidity** — adding a new product type (e.g., `createDropdown()`) requires changing the factory interface and ALL concrete factories.

---

## 14. Interview-Ready Talking Points

**"Explain the Factory pattern and its variants."**

> "There are three levels. Simple Factory is a single method with a switch or registry that picks the concrete class — good when the decision is data-driven. Factory Method pushes the decision into subclasses — each Creator subclass overrides a creation method, which is useful when creation is tied to a class hierarchy or framework extension point. Abstract Factory creates families of related objects — like a UI toolkit that produces buttons, text fields, and checkboxes that must all be from the same design system."

**"Where have you used it in production?"**

> "At Walmart, our warehouse management system had different processing strategies per facility type — cross-dock, sortation center, consolidation hub. Each had different conveyor configurations, slot allocation algorithms, and label formats. We used a Spring-based registry factory: each processor was a `@Component` with a `getType()` discriminator. The factory autowired all processors into a map. When a shipment arrived, we resolved the processor by facility type. Adding a new facility type was just a new bean — no factory or routing changes."

**"When would you NOT use Factory?"**

> "Three cases: when there's only one implementation and no foreseeable variation — just use `new`. When you're in Spring and DI already handles the wiring — don't layer a manual factory on top of the container. And when creation is trivial — no dependencies, no configuration, no conditional logic. A factory should earn its keep by solving a real creation complexity problem."

**"Factory Method vs Abstract Factory — when which?"**

> "Factory Method creates one product and the creation decision is made by subclassing — think `Collection.iterator()` where each collection subclass returns its own iterator. Abstract Factory creates a family of products that must be used together — think a UI toolkit where buttons, text fields, and checkboxes must all be Material or all be Apple, never mixed."

---

## 15. Quick Implementation Checklist

**Simple Factory:**
1. Define the Product interface.
2. Implement ConcreteProducts.
3. Create a factory class with a static `create(type)` method.
4. (Optional) Use a `Map<String, Supplier<Product>>` registry for OCP compliance.
5. In Spring — autowire all implementations into a `Map` via `List<Product>` + `getType()`.

**Factory Method:**
1. Define the Product interface and ConcreteProducts.
2. Create an abstract Creator class with the `createProduct()` factory method.
3. Add business logic in Creator that uses the product (template workflow).
4. Create ConcreteCreator subclasses that override `createProduct()`.

**Abstract Factory:**
1. Define multiple Product interfaces (the family).
2. Implement ConcreteProducts for each family variant.
3. Define the AbstractFactory interface with a `create` method for each product type.
4. Implement ConcreteFactories — one per family variant.
5. Client receives the factory, creates all products from it — consistency guaranteed.
