# Pub/Sub System — Low Level Design

## Problem Statement

Design a publish-subscribe messaging system where publishers send messages to named topics and subscribers receive them in order, with each subscriber consuming at its own pace, support for replay (seek), and clean unsubscribe — modelled after Kafka's pull-based, per-partition log.

---

## Design Patterns Used

| Pattern | Where |
|---|---|
| **Observer** | `Subscriber` interface — topics notify registered subscribers when new messages arrive |
| **Iterator / Pull** | Each `TopicSubscription` tracks its own offset; the per-subscriber worker pulls the next unread message rather than having messages pushed to a shared queue |

---

## Class Diagram

```
PubSubService
└── Map<String, Topic>   topicRegistry  (ConcurrentHashMap)

Topic
├── String                       name
├── List<Message>                log             (append-only, intrinsic lock)
├── List<TopicSubscription>      subscriptions   (CopyOnWriteArrayList)
└── AtomicInteger                subscriptionCounter

TopicSubscription  (broker-owned)
├── Subscriber       subscriber
├── AtomicInteger    offset        (broker controls this)
└── Thread           workerThread  (dedicated, daemon)

Subscriber  (Observer interface)
└── AlertSubscriber  (example implementation)

Message
└── payload, timestamp
```

---

## Core Design: Pull-Based Per-Subscriber Thread

Each subscription gets its **own dedicated daemon thread** that loops:
1. Lock on `subscription` monitor and `wait()` while `offset >= log.size()`.
2. Wake up (via `notifyAll` from `publish`), read `log[offset]`.
3. Call `subscriber.onMessage(message)` **outside** the lock.
4. `compareAndSet(offset, offset + 1)`.

This mirrors Kafka consumer groups: the broker (Topic) owns the offset; the subscriber never tracks it directly.

---

## Core Classes

### `PubSubService`

Top-level facade. Manages the topic registry and exposes the public API.

```java
PubSubService service = new PubSubService();
service.createTopic("orders");
service.publish("orders", new Message("order-1"));
TopicSubscription sub = service.subscribeAndReturn("orders", mySubscriber);
service.seek("orders", mySubscriber, 0);     // replay from the start
service.unsubscribe("orders", mySubscriber); // interrupt worker cleanly
service.shutdown();                          // interrupt all workers, join
```

---

### `Topic`

Owns the append-only log and all subscriptions. Two separate locks are used deliberately:

| Lock | Guarding | Why separate |
|---|---|---|
| `synchronized(log)` | Append + size/index reads | Prevents torn writes to the `ArrayList` |
| `synchronized(sub)` | `wait`/`notifyAll` per subscription | A slow subscriber's monitor must never block `publish` from waking other subscribers |

`publish` acquires `log` lock to append, then iterates subscriptions and `notifyAll` on each — these are two distinct objects, so they never nest.

---

### `TopicSubscription`

Broker-owned `(subscriber, offset)` pairing. The `AtomicInteger` offset allows lock-free reads in the worker's tight loop while `compareAndSet` prevents double-advance if a seek races with a delivery.

---

### Seek / Replay

```java
service.seek("orders", subscriber, 0); // rewind to beginning
```

Sets the subscriber's offset to any position (forward or backward) and wakes the worker immediately. No special opt-in needed — every subscriber gets replay for free.

---

### Unsubscribe

Interrupts the worker thread and removes the subscription. The worker's `InterruptedException` catch clears the interrupt flag and exits the loop cleanly.

---

## Delivery Guarantees

| Property | Behaviour |
|---|---|
| Order | Strictly sequential and in-order per subscriber — workers read `log[offset]` linearly |
| Isolation | A slow subscriber (blocking `onMessage`) only blocks its own worker thread, never others |
| Backpressure | Slow subscribers naturally fall behind; the log retains all messages (no eviction in this implementation) |
| At-least-once | If a subscriber's process dies mid-delivery, the offset hasn't advanced — replay is automatic on restart |

---

## Demo Walkthrough (`PubSubDemo.main`)

| Step | Scenario | Result |
|---|---|---|
| 1 | Two subscribers (fast + slow 300 ms/msg) subscribe to "orders" | Two separate named worker threads created |
| 2 | Publish order-1, order-2, order-3 | Fast subscriber finishes all 3 before slow processes order-1 |
| 3 | Wait 1 s | Slow subscriber catches up with its own backlog |
| 4 | Seek fast subscriber to offset 0 | Fast replays order-1, order-2, order-3 without republishing |
| 5 | Unsubscribe fast; publish order-4 | Fast subscriber's worker stops; only slow receives order-4 |
| 6 | `service.shutdown()` | All remaining worker threads interrupted and joined |

---

## Concurrency Notes

- **Two-lock discipline** — `log` lock and `subscription` monitors are never nested, preventing deadlock between `publish` and `runWorker`.
- **`while` not `if` on `wait()`** — guards against spurious wakeups and races where `notifyAll` fires before `wait` is entered.
- **Delivery outside monitor** — `onMessage` is called after releasing `synchronized(sub)`, so a slow subscriber can't block `publish`'s `notifyAll` from completing.
- **`CopyOnWriteArrayList` for subscriptions** — `publish` can iterate subscriptions while `subscribe`/`unsubscribe` modifies the list concurrently.
- **Daemon threads** — JVM exits cleanly even if `shutdown()` is skipped; no hung processes.

---

## Extension Points

- **Topic partitions** — split each `Topic` into N `Partition` objects; assign subscribers round-robin for horizontal scale.
- **Retention policy** — add a max-log-size or TTL eviction policy to the log; adjust offsets accordingly.
- **Consumer groups** — add a `groupId` to subscriptions and share an offset per group rather than per subscriber.
- **Dead-letter queue** — catch exceptions from `onMessage`; route failed messages to a separate error topic.
- **Persistence** — replace the in-memory `ArrayList` log with a memory-mapped file or WAL; offsets transfer directly.