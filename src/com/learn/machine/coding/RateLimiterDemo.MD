# Rate Limiter — Low Level Design

## Problem Statement

Design a rate limiter that enforces per-client, per-endpoint quotas using swappable algorithms, composes pre-flight checks (auth, IP blocklist, rate limit) in a configurable chain, and remains correct under concurrent load with no shared lock across unrelated clients.

---

## Design Patterns Used

| Pattern | Where |
|---|---|
| **Strategy** | `RateLimitAlgorithm` — `TokenBucket` and `SlidingWindowLog` are interchangeable implementations injected via a factory |
| **Chain of Responsibility** | `RequestHandler` subclasses (`AuthCheckHandler` → `IpBlocklistHandler` → `RateLimitHandler`) — each link either passes or short-circuits |

---

## Class Diagram

```
RateLimiter
├── Map<RateLimitKey, RateLimitConfig>       configs
├── Map<RateLimitKey, RateLimitAlgorithm>    limiters  (ConcurrentHashMap)
└── Function<RateLimitConfig, RateLimitAlgorithm>  algorithmFactory

RateLimitKey  (clientId + endpoint — composite key)
RateLimitConfig  (capacity, refillRatePerSec, windowSizeMs)

RateLimitAlgorithm  (interface)
├── TokenBucket       — burst-friendly, continuous refill
└── SlidingWindowLog  — precise rolling window, higher memory

RequestHandler  (abstract, Chain of Responsibility)
├── AuthCheckHandler       — validates auth token
├── IpBlocklistHandler     — blocks known bad IPs
└── RateLimitHandler       — delegates to RateLimiter.allow()

RequestContext  (interface)
└── SimpleRequestContext   — clientId, endpoint, ip, authToken
```

---

## Core Classes

### `RateLimiter`

The central service. Each `(clientId, endpoint)` pair gets its own independent algorithm instance — unrelated clients never contend on the same lock.

```java
RateLimiter limiter = new RateLimiter(configs, TokenBucket::new);
limiter.allow("clientA", "/search");
```

| Behaviour | Detail |
|---|---|
| Per-key isolation | `computeIfAbsent` creates one algorithm instance per `RateLimitKey`, lazily |
| Unconfigured key | Returns `false` (fail closed) — no silent unlimited access |
| Algorithm swap | Pass `SlidingWindowLog::new` instead of `TokenBucket::new` — nothing else changes |

---

### `RateLimitKey`

Composite key of `(clientId, endpoint)` with `equals`/`hashCode` by both fields. This means a client's `/search` quota is completely separate from its `/checkout` quota.

---

### `RateLimitConfig`

Holds all tunable parameters. Fields used depend on the algorithm:

| Field | Used by |
|---|---|
| `capacity` | Both algorithms — max burst / max requests in window |
| `refillRatePerSec` | `TokenBucket` only |
| `windowSizeMs` | `SlidingWindowLog` only |

---

### `RateLimitAlgorithm`

```java
boolean tryAcquire();
```

#### `TokenBucket`

- Starts full (`availableTokens = capacity`).
- On every `tryAcquire`: refills tokens proportional to elapsed time (`elapsedSeconds × refillRatePerSec`), capped at `capacity`.
- Allows one request per token; supports **bursts up to capacity**.
- All state changes guarded by a per-instance `lock`.

```
availableTokens = min(capacity, availableTokens + elapsedSeconds × refillRatePerSec)
```

#### `SlidingWindowLog`

- Stores a `Deque<Long>` of request timestamps.
- On every `tryAcquire`: evicts timestamps older than `now − windowSizeMs`, then checks if `size < capacity`.
- More **precise** than a fixed-window counter (no burst-at-boundary issue), but uses more memory (one entry per request vs one counter).
- All state changes guarded by a per-instance `lock`.

---

### `RequestHandler` (Chain of Responsibility)

Abstract base with a **template method** pattern:

```java
public final boolean handle(RequestContext ctx) {
    if (!check(ctx)) return false;          // short-circuit if this link rejects
    return next == null || next.handle(ctx); // pass down otherwise
}
protected abstract boolean check(RequestContext ctx);
```

`setNext` returns the next handler for fluent chaining:

```java
new AuthCheckHandler(tokens)
    .setNext(new IpBlocklistHandler(blockedIps))
    .setNext(new RateLimitHandler(limiter));
```

| Handler | Rejects when |
|---|---|
| `AuthCheckHandler` | `authToken` not in `validTokens` set |
| `IpBlocklistHandler` | `ip` is in `blockedIps` set |
| `RateLimitHandler` | `RateLimiter.allow()` returns `false` |

Order matters: auth and IP checks run before the rate limiter so rejected requests don't consume quota.

---

## Algorithm Comparison

| | Token Bucket | Sliding Window Log |
|---|---|---|
| **Burst support** | Yes — up to `capacity` tokens | No — hard cap per window |
| **Memory** | O(1) — just token count + timestamp | O(n) — one entry per request |
| **Precision** | Approximate (continuous refill) | Exact rolling window |
| **Best for** | APIs that tolerate short bursts | Strict per-window enforcement |
| **Config used** | `capacity`, `refillRatePerSec` | `capacity`, `windowSizeMs` |

---

## Demo Walkthrough (`RateLimiterDemo.main`)

**Config:** `clientA /search` → capacity 5, refill 1/sec; `clientA /checkout` → capacity 2

| Step | Scenario | Result |
|---|---|---|
| 1 | Token Bucket: 7 requests to `/search` (capacity 5, no time to refill) | Requests 1–5 ALLOWED, 6–7 REJECTED |
| 2 | Sliding Window: 4 requests to `/checkout` (capacity 2) | Requests 1–2 ALLOWED, 3–4 REJECTED |
| 3 | Unconfigured key `clientB /unknown-endpoint` | REJECTED (fail closed) |
| 4 | Chain — good request (valid token, clean IP) | ALLOWED |
| 5 | Chain — bad auth token | REJECTED at `AuthCheckHandler`, chain short-circuits |
| 6 | Chain — blocked IP (valid token) | REJECTED at `IpBlocklistHandler`, chain short-circuits |
| 7 | Concurrency — 50 threads, capacity 5, no refill | Exactly 5 ALLOWED, 45 REJECTED |

---

## Concurrency Notes

- **Per-key locking** — each `TokenBucket` and `SlidingWindowLog` has its own internal `lock`. Two requests for different `(client, endpoint)` pairs never contend.
- **`computeIfAbsent`** on `ConcurrentHashMap` guarantees exactly one algorithm instance is created per key even under concurrent `allow()` calls.
- **No global lock** — `RateLimiter` itself is lock-free; only the algorithm instances hold locks, and only for the duration of `tryAcquire`.
- The concurrency demo (50 threads, capacity 5) proves the token bucket allows **exactly** 5 — no over-counting from races.

---

## Test Cases (Key Scenarios)

| # | Test |
|---|---|
| 1 | `tokenBucketAllowsBurstUpToCapacity` |
| 2 | `tokenBucketRefillsOverTime` |
| 3 | `slidingWindowRejectsAfterLimitWithinWindow` |
| 4 | `slidingWindowAllowsAgainAfterWindowExpires` |
| 5 | `unconfiguredKeyFailsClosed` |
| 6 | `chainShortCircuitsOnFailedAuthCheck` |
| 7 | `chainShortCircuitsOnBlockedIp` |
| 8 | `exactlyCapacityRequestsAllowedUnderConcurrentLoad` |
| 9 | `separateClientsDontShareRateLimitState` |

---

## Extension Points

- **New algorithm** — implement `RateLimitAlgorithm` (e.g., `FixedWindowCounter`, `LeakyBucket`) and pass its constructor reference as `algorithmFactory`.
- **New pre-check** — extend `RequestHandler` (e.g., `PayloadSizeHandler`, `GeoBlockHandler`) and insert it into the chain.
- **Dynamic config reload** — replace the `configs` `HashMap` with a live-reloadable store; `RateLimiter` will pick up new configs on the next `allow()` call for that key.
- **Distributed rate limiting** — replace per-instance in-memory state with a Redis-backed store; `tryAcquire` becomes a Lua script atomic operation.