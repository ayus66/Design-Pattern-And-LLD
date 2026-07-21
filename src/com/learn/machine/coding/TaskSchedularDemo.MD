# Task Orchestrator (DAG Execution Engine) — Low Level Design

## Problem Statement

Design a DAG-based task execution engine that validates the graph for cycles, runs independent tasks in parallel, resolves dependencies dynamically, enforces per-task timeouts, retries failed tasks with exponential backoff, propagates failure/skip signals downstream, and supports resuming a partial run from a snapshot.

> Source: Hevo Data SDE-3 Machine Coding
> Reference: https://leetcode.com/discuss/post/8310356/

---

## Design Patterns Used

| Pattern | Where |
|---|---|
| **Template Method (implicit)** | `executeWithRetries` defines the retry/timeout skeleton; `TaskAction` supplies the varying step |
| **Command** | `TaskAction` — encapsulates a unit of work as a functional interface, decoupling definition from execution |

---

## Class Diagram

```
TaskOrchestrator
├── ExecutorService    executor         (CachedThreadPool)
└── Object             schedulingLock   (guards dependency-count bookkeeping)

Dag
├── Map<String, TaskDefinition>   tasks
├── Map<String, List<String>>     dependents    (child edges)
└── Map<String, List<String>>     dependencies  (parent edges)

TaskDefinition
├── id, maxRetries, timeoutMillis
├── continueOnFailure
└── TaskAction  action

TaskAction  (Command interface)
└── void run(int attempt) throws Exception

ExecutionResult
├── Map<String, TaskState>   finalStates
├── OverallStatus            overallStatus
└── toSnapshot() → ExecutionSnapshot

ExecutionSnapshot
└── Map<String, TaskState>  stateByTaskId

TaskState:  PENDING → RUNNING → COMPLETED | FAILED | TIMED_OUT | SKIPPED
OverallStatus:  COMPLETED | PARTIAL_FAILURE
```

---

## Core Algorithm

### DAG Validation — Kahn's Algorithm

`Dag.validate()` uses the same in-degree-peeling topological sort that the scheduler uses at runtime. If `processed != tasks.size()`, tasks still with `inDegree > 0` are in (or downstream of) a cycle.

```
1. Build in-degree map for all tasks.
2. Enqueue all zero-in-degree tasks.
3. Dequeue → decrement children's in-degree → enqueue any newly-zero children.
4. If enqueued count < total tasks → cycle detected; report stuck tasks.
```

---

### Execution Flow

```
run(dag, snapshot)
  → validate() — throws DagValidationException on cycle
  → mark snapshot-COMPLETED tasks; count remaining toResolve
  → compute unresolved-dependency count per pending task
  → submitTask() for every PENDING task with remaining=0
       → executor thread: executeWithRetries(td)
            → loop until success or retries exhausted:
                 runWithTimeout(td, attempt)
                   → submit action to executor
                   → future.get(timeoutMillis) → success / TimeoutException / ExecutionException
                 on timeout → TIMED_OUT (terminal, no retry)
                 on exception → backoff (100ms × 2^(attempt-1)) → retry
                 on retries exhausted → FAILED
            → returns terminal TaskState
       → onTaskResolved() — counts down latch, cascades to children
  → latch.await() — blocks caller until all tasks resolved
  → buildResult()
```

---

## Failure Propagation

When a task terminates with a non-COMPLETED state, `onTaskResolved` computes what signal to send downstream using the task's `continueOnFailure` flag:

| Task state | `continueOnFailure` | Signal to children |
|---|---|---|
| FAILED / TIMED_OUT | `false` | **FAILED** — child becomes FAILED without running |
| FAILED / TIMED_OUT | `true` | **SKIPPED** — child is skipped, not marked as failed |
| COMPLETED | — | no negative signal; child runs normally when all parents complete |

`worseOf(a, b)`: `FAILED` outranks `SKIPPED` outranks `null`. A child with two parents — one sending FAILED and one sending SKIPPED — becomes FAILED.

---

### Resume from Snapshot

```java
ExecutionResult result = orchestrator.execute(dag);
// fix the transient issue, then:
ExecutionResult resumed = orchestrator.resumeFrom(dag, result.toSnapshot());
```

Tasks already `COMPLETED` in the snapshot are treated as done; their children's unresolved-dependency counts are pre-decremented accordingly. Only the remaining non-completed tasks are submitted.

---

## Core Classes

### `TaskOrchestrator`

The engine. Uses a single `CachedThreadPool` for both task execution and `runWithTimeout`'s inner `Future` submission.

| Method | Purpose |
|---|---|
| `execute(dag)` | Full run from scratch |
| `resumeFrom(dag, snapshot)` | Partial re-run; skips already-COMPLETED tasks |
| `shutdown()` | Shuts down the executor; call after all runs complete |

---

### `executeWithRetries`

Exponential backoff: `100ms × 2^(attempt-1)` → 100, 200, 400, 800 ms…

Timeouts are terminal (no retry) — a deliberate simplification worth flagging in an interview; a production version might retry timeouts up to `maxRetries` too.

---

### `runWithTimeout`

Submits the action to the executor and calls `future.get(timeoutMillis)`. On `TimeoutException`, cancels the future (sends interrupt to the action's thread) and rethrows. On `ExecutionException`, unwraps and rethrows the original checked exception.

---

### `onTaskResolved`

The cascade engine. Called once per task reaching a terminal state (including tasks that are cascade-resolved without running). Holds `schedulingLock` only for the bookkeeping (remaining-count decrement + next-state decision) — never during actual execution or observer notification.

---

## Demo Test Cases

| Test | DAG Shape | What's Verified |
|---|---|---|
| 1 | A→B,C; B→D; C→E; D,E→F; F→G | C retries once (backoff); F always fails → F,G=FAILED; resume re-runs only F,G |
| 2 | X→Y→Z (linear chain) | All three complete; overall=COMPLETED |
| 3 | P→Q→R→P (cycle) | `DagValidationException` thrown before any execution |
| 4 | Wide diamond with B(continueOnFailure=true), D(false) | B→E=SKIPPED; D→G,H,I=FAILED; C,F=COMPLETED; overall=PARTIAL_FAILURE |

---

## Concurrency Notes

- **`CountDownLatch`** — one count per non-snapshot task; `latch.await()` blocks the caller until the last task resolves.
- **`schedulingLock`** — only the dependency-count decrement and next-state branching are serialised; the actual task execution is fully parallel.
- **Defensive executor submission** — uncaught `RuntimeException` inside `submit(Runnable)` is swallowed silently by the `Future`; explicit try/catch logs the error and still counts down the latch so the run doesn't hang forever.
- **`ConcurrentHashMap` for state/remaining/worst** — multiple tasks finishing simultaneously update independent keys without contention.
- **Interrupt propagation** — `runWithTimeout` cancels the future with `mayInterruptIfRunning=true`; `executeWithRetries` catches `InterruptedException` on backoff sleep and returns `FAILED` cleanly.

---

## Extension Points

- **Task priorities** — add a `priority` field to `TaskDefinition`; use a `PriorityBlockingQueue` in the executor to run higher-priority tasks first.
- **Timeout per attempt vs total** — add a `totalTimeoutMillis` alongside `timeoutMillis`; track wall-clock start in `executeWithRetries`.
- **Progress callbacks** — add a `TaskListener` (Observer) for `onStart`/`onRetry`/`onComplete`/`onFail` events without modifying `TaskOrchestrator`.
- **Distributed execution** — replace `ExecutorService` with a remote task queue (SQS, Celery); workers pull tasks and report back via the same `onTaskResolved` callback.
- **Conditional branching** — add an `OutputContext` to `TaskAction` and a `Predicate<OutputContext>` on edges; `onTaskResolved` evaluates the predicate before marking a child as ready.