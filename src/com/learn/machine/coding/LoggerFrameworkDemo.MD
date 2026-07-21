# Logging Framework — Low Level Design

## Problem Statement

Design a hierarchical logging framework (Log4j-style) that supports multiple log levels, pluggable appenders and formatters, parent-child logger inheritance with configurable additivity, and asynchronous log dispatch via a bounded queue — all without blocking the calling thread.

---

## Design Patterns Used

| Pattern | Where |
|---|---|
| **Singleton** | `LogManager` — one global instance owns the logger tree and the async processor |
| **Strategy** | `LogFormatter` and `LogAppender` — `SimpleTextFormatter`, `ConsoleAppender`, `FileAppender` are interchangeable without changing `Logger` |
| **Composite / Hierarchy** | `Logger` — each logger holds a reference to its parent; log calls propagate up the tree (additivity) |

---

## Class Diagram

```
LogManager  (Singleton)
├── Map<String, Logger>   loggers  (ConcurrentHashMap)
├── Logger                rootLogger
└── AsyncLogProcessor     processor

Logger
├── Logger               parent
├── List<LogAppender>    appenders  (CopyOnWriteArrayList)
├── String               name
├── LogLevel             level      (nullable → inherits from parent)
└── boolean              additivity (default true)

AsyncLogProcessor
└── ExecutorService  executor  (single-thread, ArrayBlockingQueue, daemon)

LogMessage
└── level, message, loggerName, threadName, timestamp

LogFormatter  (Strategy interface)
└── SimpleTextFormatter

LogAppender  (Strategy interface)
├── ConsoleAppender
└── FileAppender
```

---

## Core Classes

### `LogManager`

The singleton entry point. Owns the entire logger tree and the single `AsyncLogProcessor`. Loggers are created lazily via `getLogger(name)` and cached in a `ConcurrentHashMap`.

```java
LogManager manager = LogManager.getInstance();
Logger log = manager.getLogger("com.app.service");
log.info("started");
manager.shutdown(); // drains the async queue before returning
```

| Behaviour | Detail |
|---|---|
| Parent resolution | Walks the dotted name up (`com.app.service` → `com.app` → `root`) to find the nearest registered ancestor |
| Thread safety | `computeIfAbsent` on `ConcurrentHashMap` — only one `Logger` instance ever created per name |

---

### `Logger`

Hierarchical, Log4j-style. The effective log level walks up to the first ancestor that has an explicit level set.

| Feature | Detail |
|---|---|
| Level inheritance | `getEffectiveLevel()` walks up to root if `level == null` |
| Additivity | When `true`, log records propagate to parent's appenders after the logger's own appenders run |
| Additivity = false | Breaks the propagation chain; that subtree's logs never reach higher appenders |
| Thread-safe appenders | `CopyOnWriteArrayList` — safe for concurrent readers with rare writes |

```java
Logger isolated = manager.getLogger("com.app.isolated");
isolated.setAdditivity(false);   // logs stop here, won't bubble up
isolated.addAppender(fileAppender);
```

---

### `AsyncLogProcessor`

Wraps a single-threaded `ThreadPoolExecutor` with an `ArrayBlockingQueue` bound. The caller's thread enqueues the log record and returns immediately; the daemon worker drains it.

| Feature | Detail |
|---|---|
| Bounded queue | Prevents unbounded memory growth under burst load |
| Rejection | `CallerRunsPolicy`-style drop with a stderr warning — no silent loss |
| Graceful shutdown | `shutdown()` + `awaitTermination(5s)` drains the backlog; force-kills if it stalls |
| Daemon thread | JVM can exit even if `shutdown()` is never called |

---

### `LogAppender` / `LogFormatter` (Strategy)

| Class | Responsibility |
|---|---|
| `SimpleTextFormatter` | Formats as `"timestamp [thread] LEVEL logger - message"` |
| `ConsoleAppender` | Writes to `System.out` |
| `FileAppender` | Appends to a file via `FileWriter`; `close()` flushes the handle |

Appenders hold a reference to their formatter and can be hot-swapped via `setFormatter`.

---

## Log Level Filtering

`DEBUG < INFO < WARN < ERROR < FATAL`

A log call is dropped before creating a `LogMessage` if `msgLevel.ordinal() < effectiveLevel.ordinal()` — zero allocation for filtered records.

---

## Demo Walkthrough (`LoggerFrameworkDemo.main`)

| Step | Scenario | Result |
|---|---|---|
| 1 | `com.app` at INFO — call `debug()` | Silently dropped (below threshold) |
| 2 | `com.app` — call `info/warn/error/fatal` | Written to console + `app.log` |
| 3 | `com.app.service` — no explicit level or appenders | Inherits INFO from parent; logs propagate to `com.app`'s file via additivity |
| 4 | `com.app.isolated` — additivity=false | Writes only to `isolated.log`; never reaches `app.log` |
| 5 | Burst of 50 messages to `burst` logger | All enqueued; `shutdown()` drains every record before returning |

---

## Concurrency Notes

- `Logger.appenders` is `CopyOnWriteArrayList` — snapshot on each dispatch, safe for concurrent logging across threads.
- `Logger.level` and `Logger.additivity` are `volatile` — visibility without locking for frequent reads, rare writes.
- The async queue serialises all actual I/O onto a single daemon thread; appenders themselves need no internal locking.
- `LogManager.loggers` uses `ConcurrentHashMap.computeIfAbsent` — exactly one `Logger` instance per name even under concurrent `getLogger` calls.

---

## Extension Points

- **New formatter** — implement `LogFormatter` (e.g., `JsonFormatter`, `XmlFormatter`) and pass it to any appender.
- **New appender** — implement `LogAppender` (e.g., `SyslogAppender`, `DatabaseAppender`, `ElasticAppender`).
- **Log filtering** — add a `LogFilter` strategy to `Logger` or `LogAppender` to suppress records by pattern or MDC key.
- **Rolling file** — extend `FileAppender` to rotate by size or date without touching `Logger` or `LogManager`.
- **Multiple processors** — replace the single `AsyncLogProcessor` with a per-appender executor to isolate a slow appender from fast ones.