package com.learn.machine.coding;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/* ============================================================
   RATE LIMITER - Machine Coding
   Patterns used: Strategy (algorithm), Chain of Responsibility (pre-checks)
   Concurrency: per-key locking, correct under N concurrent allow() calls
   ============================================================ */

// ---------- Key & config ----------

// Composite key so each (client, endpoint) pair gets its own independent
// limiter state - a client's /search quota is separate from /checkout.
final class RateLimitKey {
    private final String clientId;
    private final String endpoint;

    public RateLimitKey(String clientId, String endpoint) {
        this.clientId = clientId;
        this.endpoint = endpoint;
    }

    public String getClientId() { return clientId; }
    public String getEndpoint() { return endpoint; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RateLimitKey)) return false;
        RateLimitKey other = (RateLimitKey) o;
        return clientId.equals(other.clientId) && endpoint.equals(other.endpoint);
    }

    @Override
    public int hashCode() { return Objects.hash(clientId, endpoint); }

    @Override
    public String toString() { return clientId + ":" + endpoint; }
}

class RateLimitConfig {
    private final int capacity;          // token bucket capacity / sliding window max requests
    private final double refillRatePerSec; // token bucket only
    private final long windowSizeMs;      // sliding window only

    public RateLimitConfig(int capacity, double refillRatePerSec, long windowSizeMs) {
        this.capacity = capacity;
        this.refillRatePerSec = refillRatePerSec;
        this.windowSizeMs = windowSizeMs;
    }

    public int getCapacity() { return capacity; }
    public double getRefillRatePerSec() { return refillRatePerSec; }
    public long getWindowSizeMs() { return windowSizeMs; }
}

// ---------- Algorithm strategy ----------

interface RateLimitAlgorithm {
    boolean tryAcquire();
}

// Bucket holds up to `capacity` tokens, refilled continuously at
// `refillRatePerSec`. Each allowed request consumes one token.
// Naturally supports short bursts up to capacity.
class TokenBucket implements RateLimitAlgorithm {
    private final int capacity;
    private final double refillRatePerSec;
    private double availableTokens;
    private long lastRefillTimestampNanos;
    private final Object lock = new Object();

    public TokenBucket(RateLimitConfig config) {
        this.capacity = config.getCapacity();
        this.refillRatePerSec = config.getRefillRatePerSec();
        this.availableTokens = capacity; // start full
        this.lastRefillTimestampNanos = System.nanoTime();
    }

    @Override
    public boolean tryAcquire() {
        synchronized (lock) {
            refill();
            if (availableTokens >= 1.0) {
                availableTokens -= 1.0;
                return true;
            }
            return false;
        }
    }

    private void refill() {
        long now = System.nanoTime();
        double elapsedSeconds = (now - lastRefillTimestampNanos) / 1_000_000_000.0;
        double tokensToAdd = elapsedSeconds * refillRatePerSec;
        if (tokensToAdd > 0) {
            availableTokens = Math.min(capacity, availableTokens + tokensToAdd);
            lastRefillTimestampNanos = now;
        }
    }
}

// Tracks timestamps of requests within the last windowSizeMs. A request
// is allowed only if fewer than `capacity` requests occurred in that
// rolling window. More memory than a fixed-window counter, but avoids
// the burst-at-boundary unfairness fixed windows have.
class SlidingWindowLog implements RateLimitAlgorithm {
    private final int capacity;
    private final long windowSizeMs;
    private final Deque<Long> requestTimestamps = new ArrayDeque<>();
    private final Object lock = new Object();

    public SlidingWindowLog(RateLimitConfig config) {
        this.capacity = config.getCapacity();
        this.windowSizeMs = config.getWindowSizeMs();
    }

    @Override
    public boolean tryAcquire() {
        long now = System.currentTimeMillis();
        synchronized (lock) {
            // Evict anything outside the current window before checking.
            while (!requestTimestamps.isEmpty() && requestTimestamps.peekFirst() <= now - windowSizeMs) {
                requestTimestamps.pollFirst();
            }
            if (requestTimestamps.size() < capacity) {
                requestTimestamps.addLast(now);
                return true;
            }
            return false;
        }
    }
}

// ---------- The rate limiter service ----------

class RateLimiter {
    // One algorithm instance per (client, endpoint) key - each key's state
    // is fully independent, so unrelated clients never contend on the same
    // lock. This is the deliberate alternative to one global lock.
    private final Map<RateLimitKey, RateLimitAlgorithm> limiters = new ConcurrentHashMap<>();
    private final Map<RateLimitKey, RateLimitConfig> configs;
    private final java.util.function.Function<RateLimitConfig, RateLimitAlgorithm> algorithmFactory;

    public RateLimiter(Map<RateLimitKey, RateLimitConfig> configs,
                       java.util.function.Function<RateLimitConfig, RateLimitAlgorithm> algorithmFactory) {
        this.configs = configs;
        this.algorithmFactory = algorithmFactory;
    }

    public boolean allow(String clientId, String endpoint) {
        RateLimitKey key = new RateLimitKey(clientId, endpoint);
        RateLimitConfig config = configs.get(key);
        if (config == null) {
            // No configured quota for this client+endpoint combination -
            // fail closed (deny) rather than silently allowing unlimited
            // traffic. State this trade-off explicitly if asked.
            return false;
        }
        RateLimitAlgorithm algorithm = limiters.computeIfAbsent(key, k -> algorithmFactory.apply(config));
        return algorithm.tryAcquire();
    }
}

// ---------- Chain of Responsibility: composable pre-checks ----------

interface RequestContext {
    String getClientId();
    String getEndpoint();
    String getIp();
    String getAuthToken();
}

class SimpleRequestContext implements RequestContext {
    private final String clientId, endpoint, ip, authToken;

    public SimpleRequestContext(String clientId, String endpoint, String ip, String authToken) {
        this.clientId = clientId;
        this.endpoint = endpoint;
        this.ip = ip;
        this.authToken = authToken;
    }

    public String getClientId() { return clientId; }
    public String getEndpoint() { return endpoint; }
    public String getIp() { return ip; }
    public String getAuthToken() { return authToken; }
}

abstract class RequestHandler {
    private RequestHandler next;

    public RequestHandler setNext(RequestHandler next) {
        this.next = next;
        return next; // allows fluent chaining: h1.setNext(h2).setNext(h3)
    }

    // Template method: subclasses implement the check; base class handles
    // passing control down the chain.
    public final boolean handle(RequestContext ctx) {
        if (!check(ctx)) {
            return false; // short-circuit - this link rejected the request
        }
        return next == null || next.handle(ctx);
    }

    protected abstract boolean check(RequestContext ctx);
}

class AuthCheckHandler extends RequestHandler {
    private final Set<String> validTokens;

    public AuthCheckHandler(Set<String> validTokens) {
        this.validTokens = validTokens;
    }

    @Override
    protected boolean check(RequestContext ctx) {
        boolean ok = validTokens.contains(ctx.getAuthToken());
        if (!ok) System.out.println("  [AuthCheck] rejected token=" + ctx.getAuthToken());
        return ok;
    }
}

class IpBlocklistHandler extends RequestHandler {
    private final Set<String> blockedIps;

    public IpBlocklistHandler(Set<String> blockedIps) {
        this.blockedIps = blockedIps;
    }

    @Override
    protected boolean check(RequestContext ctx) {
        boolean ok = !blockedIps.contains(ctx.getIp());
        if (!ok) System.out.println("  [IpBlocklist] rejected ip=" + ctx.getIp());
        return ok;
    }
}

class RateLimitHandler extends RequestHandler {
    private final RateLimiter rateLimiter;

    public RateLimitHandler(RateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    @Override
    protected boolean check(RequestContext ctx) {
        boolean ok = rateLimiter.allow(ctx.getClientId(), ctx.getEndpoint());
        if (!ok) System.out.println("  [RateLimit] rejected client=" + ctx.getClientId()
                + " endpoint=" + ctx.getEndpoint());
        return ok;
    }
}

// ---------- Demo Harness ----------

public class RateLimiterDemo {
    public static void main(String[] args) throws InterruptedException {
        // ---- Configure quotas per (client, endpoint) at startup ----
        RateLimitKey searchKey = new RateLimitKey("clientA", "/search");
        RateLimitKey checkoutKey = new RateLimitKey("clientA", "/checkout");

        Map<RateLimitKey, RateLimitConfig> configs = new HashMap<>();
        configs.put(searchKey, new RateLimitConfig(5, 1.0, 1000));   // 5 burst, 1/sec refill
        configs.put(checkoutKey, new RateLimitConfig(2, 0.5, 1000)); // stricter: 2 burst

        // ---- Token bucket demo: burst then throttle ----
        RateLimiter tokenBucketLimiter = new RateLimiter(configs, TokenBucket::new);
        System.out.println("-- Token Bucket: /search allows a burst of 5 --");
        for (int i = 1; i <= 7; i++) {
            boolean allowed = tokenBucketLimiter.allow("clientA", "/search");
            System.out.println("Request " + i + ": " + (allowed ? "ALLOWED" : "REJECTED"));
        }

        // ---- Sliding window demo ----
        RateLimiter slidingLimiter = new RateLimiter(configs, SlidingWindowLog::new);
        System.out.println("\n-- Sliding Window: /checkout allows only 2 in window --");
        for (int i = 1; i <= 4; i++) {
            boolean allowed = slidingLimiter.allow("clientA", "/checkout");
            System.out.println("Request " + i + ": " + (allowed ? "ALLOWED" : "REJECTED"));
        }

        // ---- Edge case: unconfigured client+endpoint fails closed ----
        boolean unknownAllowed = tokenBucketLimiter.allow("clientB", "/unknown-endpoint");
        System.out.println("\nUnconfigured key result (should be REJECTED): "
                + (unknownAllowed ? "ALLOWED" : "REJECTED"));

        // ---- Chain of Responsibility demo ----
        System.out.println("\n-- Chain of Responsibility: auth -> IP filter -> rate limit --");
        RateLimiter chainLimiter = new RateLimiter(configs, TokenBucket::new);
        Set<String> validTokens = new HashSet<>(Collections.singletonList("token-123"));
        Set<String> blockedIps = new HashSet<>(Collections.singletonList("10.0.0.66"));

        RequestHandler chain = new AuthCheckHandler(validTokens);
        chain.setNext(new IpBlocklistHandler(blockedIps))
                .setNext(new RateLimitHandler(chainLimiter));

        RequestContext goodRequest = new SimpleRequestContext("clientA", "/search", "10.0.0.1", "token-123");
        RequestContext badAuthRequest = new SimpleRequestContext("clientA", "/search", "10.0.0.1", "bad-token");
        RequestContext blockedIpRequest = new SimpleRequestContext("clientA", "/search", "10.0.0.66", "token-123");

        System.out.println("Good request result: " + chain.handle(goodRequest));
        System.out.println("Bad auth result: " + chain.handle(badAuthRequest));
        System.out.println("Blocked IP result: " + chain.handle(blockedIpRequest));

        // ---- Concurrency proof: N threads hammering the same key ----
        System.out.println("\n-- Concurrency: 50 threads racing for 5 tokens --");
        Map<RateLimitKey, RateLimitConfig> raceConfig = new HashMap<>();
        RateLimitKey raceKey = new RateLimitKey("clientC", "/race");
        raceConfig.put(raceKey, new RateLimitConfig(5, 0.0, 1000)); // no refill during the race
        RateLimiter raceLimiter = new RateLimiter(raceConfig, TokenBucket::new);

        int threadCount = 50;
        AtomicInteger allowedCount = new AtomicInteger();
        Thread[] threads = new Thread[threadCount];
        for (int i = 0; i < threadCount; i++) {
            threads[i] = new Thread(() -> {
                if (raceLimiter.allow("clientC", "/race")) {
                    allowedCount.incrementAndGet();
                }
            });
        }
        for (Thread t : threads) t.start();
        for (Thread t : threads) t.join();
        System.out.println("Allowed out of " + threadCount + " concurrent requests (expect exactly 5): "
                + allowedCount.get());
    }
}

/* ============================================================
   TEST CASES TO HAVE READY (verbalize even if not all written)
   ============================================================
   1. tokenBucketAllowsBurstUpToCapacity()
   2. tokenBucketRefillsOverTime()
   3. slidingWindowRejectsAfterLimitWithinWindow()
   4. slidingWindowAllowsAgainAfterWindowExpires()
   5. unconfiguredKeyFailsClosed()
   6. chainShortCircuitsOnFailedAuthCheck()
   7. chainShortCircuitsOnBlockedIp()
   8. exactlyCapacityRequestsAllowedUnderConcurrentLoad()  -- concurrency proof
   9. separateClientsDontShareRateLimitState()
   ============================================================ */
