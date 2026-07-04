# Vending Machine — Low Level Design

## Problem Statement

Design a vending machine that manages an inventory of items, accepts coins, tracks balance, dispenses items, and returns change — while enforcing valid operation sequences through state transitions.

---

## Design Patterns Used

| Pattern | Where |
|---|---|
| **Singleton** | `VendingMachine` — single instance represents the physical machine |
| **State** | `VendingMachineState` and its subclasses model each phase of a transaction |

---

## State Machine Diagram

```
          selectItem (valid)
┌────────┐ ──────────────────► ┌────────────────┐
│  Idle  │                     │  ItemSelected  │
└────────┘ ◄────────────────── └────────────────┘
              refund / cancel        │ insertCoin (balance >= price)
                                     ▼
                               ┌──────────┐
                               │ HasMoney │
                               └──────────┘
                                     │ dispense()
                                     ▼
                               ┌────────────┐
                               │ Dispensing │ ──► back to Idle
                               └────────────┘
```

---

## Class Diagram

```
VendingMachine (Singleton)
├── Inventory
│   ├── Map<String, Item>     (code → Item)
│   └── Map<String, Integer>  (code → stock count)
├── VendingMachineState  (current state)
│   ├── IdleState
│   ├── ItemSelectedState
│   ├── HasMoneyState
│   └── DispensingState
├── balance: int
└── selectedItemCode: String

Item
├── code: String
├── name: String
└── price: int

Coin (enum)
├── PENNY   → 1
├── NICKEL  → 5
├── DIME    → 10
└── QUARTER → 25
```

---

## Core Classes

### `VendingMachine` (Singleton)

The central controller. Delegates every user action to the current `VendingMachineState`.

```java
VendingMachine vm = VendingMachine.getInstance();
```

Key methods:

| Method | Description |
|---|---|
| `selectItem(code)` | Delegates to current state |
| `insertCoin(coin)` | Adds coin value to balance via state |
| `dispense()` | Triggers item dispensing via state |
| `refund()` | Returns balance and resets via state |
| `dispenseItem()` | Internal — reduces stock, prints item, refunds change, resets |
| `reset()` | Clears `selectedItemCode` and `balance` |

> **Note:** `getInstance()` has a bug — it always creates a new instance when `INSTANCE == null` instead of assigning it. The singleton guarantee is broken on first call.

---

### `VendingMachineState` (abstract)

Each subclass represents one phase of a transaction and overrides four operations:

```java
void insertCoin(Coin coin);
void selectItem(String code);
void dispense();
void refund();
```

#### State Behaviour Matrix

| Action | Idle | ItemSelected | HasMoney | Dispensing |
|---|---|---|---|---|
| `insertCoin` | "Select item first" | Adds balance; transitions to `HasMoney` if sufficient | "Already full" | "Please wait" |
| `selectItem` | Sets item, → `ItemSelectedState` | "Already selected" | "Already selected" | "Please wait" |
| `dispense` | "No item selected" | "Insert more money" | Transitions to `DispensingState`, calls `dispenseItem()` | (no-op) |
| `refund` | "No money" | Refunds balance, → `IdleState` | Refunds balance, → `IdleState` | "Not allowed" |

---

### `Inventory`

Maintains two parallel maps keyed by item code:
- `itemMap` — item metadata
- `stockMap` — current quantity

`isAvailable(code)` returns `true` only when stock > 0. `reduceStock` decrements by 1 on each dispense.

---

### `Coin` (enum)

| Coin | Value (cents) |
|---|---|
| PENNY | 1 |
| NICKEL | 5 |
| DIME | 10 |
| QUARTER | 25 |

---

## Demo Walkthrough (`VendingMachineDemo.main`)

**Inventory:** Coke (A1, 25¢, qty 3), Pepsi (A2, 25¢, qty 2), Water (B1, 10¢, qty 5)

| Step | Action | Balance | State | Output |
|---|---|---|---|---|
| 1 | `selectItem("A1")` | 0 | ItemSelected | "Item selected: A1" |
| 2a | `insertCoin(DIME)` | 10 | ItemSelected | "Coin Inserted: 10" |
| 2b | `insertCoin(DIME)` | 20 | ItemSelected | "Coin Inserted: 10" |
| 2c | `insertCoin(NICKEL)` | 25 | **HasMoney** | "Sufficient money received." |
| 3 | `dispense()` | 0 | **Idle** | "Dispensed: Coke" |
| 4 | `selectItem("B1")` | 0 | ItemSelected | "Item selected: B1" |
| 5 | `insertCoin(QUARTER)` | 25 | **HasMoney** | "Sufficient money received." |
| 6 | `dispense()` | 0 | **Idle** | "Dispensed: Water" + "Refunding: 15" |

---

## Extension Points

- **New state** — extend `VendingMachineState` (e.g., `OutOfServiceState`) and call `machine.setState(...)`.
- **New coin denomination** — add an entry to the `Coin` enum with its cent value.
- **New fee/pricing model** — move price logic into a strategy interface injected into `VendingMachine`.
- **Persistent inventory** — replace `Inventory`'s `HashMap`s with a database-backed store without changing any state logic.