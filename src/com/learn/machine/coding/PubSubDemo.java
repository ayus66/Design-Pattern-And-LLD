package com.learn.machine.coding;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/* ============================================================
   PUB/SUB SYSTEM - Machine Coding (pull-based, Kafka-internals style)

   Departure from the original push/shared-executor design: each
   subscription gets its OWN dedicated worker thread that pulls
   sequentially from the topic's log and blocks (wait/notify) when
   caught up. The broker (Topic) owns each subscriber's offset -
   the subscriber itself never touches or tracks it. This gives:
     - strictly sequential, in-order delivery per subscriber
     - natural backpressure (a slow subscriber just doesn't advance)
     - free seek()/replay for every subscriber, no opt-in needed
   Trade-off: one live thread per subscription, not a bounded shared
   pool - doesn't scale to huge subscriber counts without adding a
   pool bound back in.
   ============================================================ */

// ---------- Message ----------

class Message {
    private final Instant timestamp;
    private final String payload;

    public Message(String payload) {
        this.payload = payload;
        this.timestamp = Instant.now();
    }

    public Instant getTimestamp() { return timestamp; }
    public String getPayload() { return payload; }
}

// ---------- Subscriber (Observer) ----------

interface Subscriber {
    void onMessage(Message message);
}

class AlertSubscriber implements Subscriber {
    private final String id;

    public AlertSubscriber(String id) {
        this.id = id;
    }

    @Override
    public void onMessage(Message message) {
        System.out.println("[Alert:" + id + "] " + message.getPayload());
    }
}

// ---------- TopicSubscription: broker-owned (subscriber, offset) pairing ----------

class TopicSubscription {
    private final Subscriber subscriber;
    private final AtomicInteger offset = new AtomicInteger(0);
    private volatile Thread workerThread;

    public TopicSubscription(Subscriber subscriber) {
        this.subscriber = subscriber;
    }

    public Subscriber getSubscriber() { return subscriber; }
    public AtomicInteger getOffset() { return offset; }
    public void setWorkerThread(Thread thread) { this.workerThread = thread; }
    public Thread getWorkerThread() { return workerThread; }
}

// ---------- Topic ----------

class Topic {
    private final String name;
    // Append-only log, guarded by its own intrinsic lock - separate from
    // any individual subscription's monitor, to avoid nesting locks in
    // a way that could deadlock (append() never holds the log lock while
    // trying to acquire a subscription's lock).
    private final List<Message> log = new ArrayList<>();
    private final List<TopicSubscription> subscriptions = new CopyOnWriteArrayList<>();
    // Distinguishes worker threads for different subscribers on the SAME
    // topic - without this, every worker on "orders" would be named
    // "sub-worker-orders", making a thread dump useless for telling them
    // apart during a real debugging session.
    private final AtomicInteger subscriptionCounter = new AtomicInteger();

    public Topic(String name) {
        this.name = name;
    }

    public TopicSubscription subscribe(Subscriber subscriber) {
        TopicSubscription subscription = new TopicSubscription(subscriber);
        subscriptions.add(subscription);
        int subId = subscriptionCounter.incrementAndGet();
        Thread worker = new Thread(() -> runWorker(subscription), "sub-worker-" + name + "-" + subId);
        worker.setDaemon(true); // don't block JVM exit if shutdown() is never called
        subscription.setWorkerThread(worker);
        worker.start();
        return subscription;
    }

    public void unsubscribe(Subscriber subscriber) {
        subscriptions.removeIf(sub -> {
            if (sub.getSubscriber() == subscriber) {
                sub.getWorkerThread().interrupt();
                return true;
            }
            return false;
        });
    }

    public void publish(Message message) {
        synchronized (log) {
            log.add(message);
        }
        // Wake every subscription's worker so it re-checks its offset
        // against the new log size. A notify() with nobody currently
        // waiting is harmless - the worker's while-loop condition check
        // catches the new message on its own next pass regardless.
        for (TopicSubscription sub : subscriptions) {
            synchronized (sub) {
                sub.notifyAll();
            }
        }
    }

    // Explicit Kafka-style seek - move a subscriber's offset to any
    // point (forward OR backward) and wake its worker immediately.
    public void seek(Subscriber subscriber, int newOffset) {
        for (TopicSubscription sub : subscriptions) {
            if (sub.getSubscriber() == subscriber) {
                sub.getOffset().set(newOffset);
                synchronized (sub) {
                    sub.notifyAll();
                }
                return;
            }
        }
    }

    public void shutdownAllWorkers() {
        for (TopicSubscription sub : subscriptions) {
            sub.getWorkerThread().interrupt();
        }
        for (TopicSubscription sub : subscriptions) {
            try {
                sub.getWorkerThread().join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private int getLogSize() {
        synchronized (log) {
            return log.size();
        }
    }

    private Message getMessageAt(int offset) {
        synchronized (log) {
            return log.get(offset);
        }
    }

    private void runWorker(TopicSubscription sub) {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                synchronized (sub) {
                    // Loop, not if - the classic wait/notify idiom.
                    // Re-checking the condition after waking up guards
                    // against spurious wakeups AND against a notify()
                    // that arrived while this thread wasn't waiting yet.
                    while (sub.getOffset().get() >= getLogSize()) {
                        sub.wait();
                    }
                }
                // Deliver OUTSIDE the synchronized(sub) block - a slow
                // onMessage() call must not hold this subscription's
                // monitor, or publish()'s notifyAll() on it would block
                // until delivery finishes, defeating the isolation this
                // whole per-subscriber-thread design exists to provide.
                int curOffset = sub.getOffset().get();
                Message message = getMessageAt(curOffset);
                sub.getSubscriber().onMessage(message);
                sub.getOffset().compareAndSet(curOffset, curOffset + 1);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // clean exit on unsubscribe/shutdown
        }
    }
}

// ---------- PubSubService ----------

class PubSubService {
    private final Map<String, Topic> topicRegistry = new ConcurrentHashMap<>();

    public void createTopic(String name) {
        topicRegistry.computeIfAbsent(name, Topic::new);
    }

    public void publish(String topicName, Message message) {
        Topic topic = topicRegistry.get(topicName);
        if (topic == null) {
            System.err.println("[PubSubService] publish to unknown topic: " + topicName);
            return;
        }
        topic.publish(message);
    }


    public TopicSubscription subscribeAndReturn(String topicName, Subscriber subscriber) {
        Topic topic = topicRegistry.get(topicName);
        if (topic == null) {
            throw new IllegalArgumentException("No such topic: " + topicName);
        }
        return topic.subscribe(subscriber);
    }

    public void unsubscribe(String topicName, Subscriber subscriber) {
        Topic topic = topicRegistry.get(topicName);
        if (topic != null) {
            topic.unsubscribe(subscriber);
        }
    }

    // Kafka-style seek - jump a subscriber's offset anywhere, forward
    // (skip ahead) or backward (replay).
    public void seek(String topicName, Subscriber subscriber, int offset) {
        Topic topic = topicRegistry.get(topicName);
        if (topic == null) {
            throw new IllegalArgumentException("No such topic: " + topicName);
        }
        topic.seek(subscriber, offset);
    }

    public void shutdown() {
        for (Topic topic : topicRegistry.values()) {
            topic.shutdownAllWorkers();
        }
    }
}

// ---------- Demo Harness ----------

public class PubSubDemo {
    public static void main(String[] args) throws InterruptedException {
        PubSubService service = new PubSubService();
        service.createTopic("orders");

        Subscriber fast = new AlertSubscriber("fast");
        Subscriber slow = message -> {
            try {
                Thread.sleep(300); // simulate a slow consumer
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            System.out.println("[Alert:slow] " + message.getPayload());
        };

        System.out.println("-- Sequential, in-order delivery + slow-subscriber isolation --");
        TopicSubscription fastSub = service.subscribeAndReturn("orders", fast);
        TopicSubscription slowSub = service.subscribeAndReturn("orders", slow);
        System.out.println("fast worker thread: " + fastSub.getWorkerThread().getName());
        System.out.println("slow worker thread: " + slowSub.getWorkerThread().getName()
                + " (should be a DIFFERENT name than fast's, despite same topic)");
        service.publish("orders", new Message("order-1"));
        service.publish("orders", new Message("order-2"));
        service.publish("orders", new Message("order-3"));
        Thread.sleep(200);
        System.out.println("(fast subscriber should have finished all 3 well before slow catches up)");
        Thread.sleep(800); // let slow subscriber finish its backlog

        System.out.println("\n-- Seek proof: rewind 'fast' back to offset 0, replay from the start --");
        service.seek("orders", fast, 0);
        Thread.sleep(200);

        System.out.println("\n-- Unsubscribe proof: worker thread should stop cleanly --");
        service.unsubscribe("orders", fast);
        service.publish("orders", new Message("order-4 - fast should NOT see this"));
        Thread.sleep(200);

        service.shutdown();
        System.out.println("\nDone - all worker threads shut down.");
    }
}