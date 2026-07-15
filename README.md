# SOLID Principles

SOLID is a set of five object-oriented design principles that help developers write clean, maintainable, scalable, and flexible software.

---

# 1. Single Responsibility Principle (SRP)

## Definition

The **Single Responsibility Principle (SRP)** states that there should never be more than one reason for a class to change. In other words, every class should have only one responsibility.

### Importance

- **Maintainability:** Classes with a single, well-defined responsibility are easier to understand and modify.
- **Testability:** It is easier to write unit tests for classes with a single focus.
- **Flexibility:** Changes to one responsibility do not affect unrelated parts of the system.

---

# 2. Open–Closed Principle (OCP)

## Definition

The **Open–Closed Principle (OCP)** states that software entities should be **open for extension but closed for modification**.

### Importance

- **Extensibility:** New features can be added without modifying existing code.
- **Stability:** Reduces the risk of introducing bugs when making changes.
- **Flexibility:** Adapts to changing requirements more easily.

---

# 3. Liskov Substitution Principle (LSP)

## Definition

The **Liskov Substitution Principle (LSP)** states that functions using pointers or references to base classes must be able to use objects of derived classes without knowing it.

### Importance

- **Polymorphism:** Enables reusable and flexible code through polymorphic behavior.
- **Reliability:** Ensures subclasses adhere to the contract defined by the superclass.
- **Predictability:** Replacing a superclass object with a subclass object should not break the program.

---

# 4. Interface Segregation Principle (ISP)

## Definition

The **Interface Segregation Principle (ISP)** states that clients should not be forced to depend on interface methods they do not use.

### Importance

- **Decoupling:** Reduces dependencies between classes, making code more modular and maintainable.
- **Flexibility:** Allows more focused and targeted interface implementations.
- **Avoids Unnecessary Dependencies:** Clients only depend on the methods they actually need.

---

# 5. Dependency Inversion Principle (DIP)

## Definition

The **Dependency Inversion Principle (DIP)** states that high-level modules should depend on abstractions rather than concrete implementations.

### Importance

- **Loose Coupling:** Reduces dependencies between modules, making code more flexible and easier to test.
- **Flexibility:** Allows implementation changes without affecting client code.
- **Maintainability:** Makes software easier to understand, extend, and modify.

---

# Summary

| Principle | Description |
|-----------|-------------|
| **SRP** | A class should have only one responsibility. |
| **OCP** | Software should be open for extension but closed for modification. |
| **LSP** | Subclasses should be replaceable for their base classes without affecting correctness. |
| **ISP** | Clients should not be forced to implement interfaces they don't use. |
| **DIP** | Depend on abstractions, not concrete implementations. |

---

## Benefits of SOLID Principles

- Improves code readability
- Increases maintainability
- Encourages reusable components
- Simplifies unit testing
- Reduces coupling between modules
- Makes applications easier to extend and scale
