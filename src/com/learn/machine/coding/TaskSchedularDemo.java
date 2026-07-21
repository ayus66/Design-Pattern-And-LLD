package com.learn.machine.coding;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/* ============================================================
   TASK ORCHESTRATOR - DAG Execution Engine
   Hevo Data SDE-3 Machine Coding
   link - https://leetcode.com/discuss/post/8310356/hevo-data-machine-coding-sde-3-dag-by-an-bheh/

   Covers: DAG validation (cycle detection), parallel execution,
   dependency resolution, per-task timeout, retry with exponential
   backoff, two-mode failure propagation (SKIPPED vs FAILED cascade),
   resume-from-snapshot.
   ============================================================ */

// ---------- States ----------

enum TaskState { PENDING, RUNNING, COMPLETED, FAILED, SKIPPED, TIMED_OUT }
enum OverallStatus { COMPLETED, PARTIAL_FAILURE }

// ---------- Task definition ----------

interface TaskAction {
    void run(int attempt) throws Exception;
}

class TaskDefinition {
    final String id;
    final int maxRetries;
    final long timeoutMillis;
    final boolean continueOnFailure;
    final TaskAction action;

    public TaskDefinition(String id, int maxRetries, long timeoutMillis,
                          boolean continueOnFailure, TaskAction action) {
        this.id = id;
        this.maxRetries = maxRetries;
        this.timeoutMillis = timeoutMillis;
        this.continueOnFailure = continueOnFailure;
        this.action = action;
    }
}

// ---------- DAG structure + validation ----------

class DagValidationException extends RuntimeException {
    public DagValidationException(String message) { super(message); }
}

class Dag {
    final Map<String, TaskDefinition> tasks = new LinkedHashMap<>();
    // from -> list of tasks that depend on "from" (its children)
    final Map<String, List<String>> dependents = new HashMap<>();
    // to -> list of tasks "to" depends on (its parents)
    final Map<String, List<String>> dependencies = new HashMap<>();

    public void addTask(TaskDefinition td) {
        tasks.put(td.id, td);
        dependents.putIfAbsent(td.id, new ArrayList<>());
        dependencies.putIfAbsent(td.id, new ArrayList<>());
    }

    public void addDependency(String from, String to) {
        dependents.computeIfAbsent(from, k -> new ArrayList<>()).add(to);
        dependencies.computeIfAbsent(to, k -> new ArrayList<>()).add(from);
    }

    // Validates that every referenced task exists, and that the graph
    // has no cycles - via Kahn's algorithm (iterative topological sort).
    // Deliberately the same in-degree-peeling mechanism the scheduler
    // itself uses at runtime, so there's one consistent mental model
    // instead of two different graph algorithms in the same codebase.
    public void validate() {
        for (Map.Entry<String, List<String>> e : dependencies.entrySet()) {
            if (!tasks.containsKey(e.getKey())) {
                throw new DagValidationException("Unknown task referenced: " + e.getKey());
            }
            for (String parent : e.getValue()) {
                if (!tasks.containsKey(parent)) {
                    throw new DagValidationException("Unknown dependency referenced: " + parent);
                }
            }
        }

        Map<String, Integer> inDegree = new HashMap<>();
        for (String id : tasks.keySet()) {
            inDegree.put(id, dependencies.getOrDefault(id, Collections.emptyList()).size());
        }

        Queue<String> queue = new ArrayDeque<>();
        for (Map.Entry<String, Integer> e : inDegree.entrySet()) {
            if (e.getValue() == 0) queue.add(e.getKey());
        }

        int processed = 0;
        while (!queue.isEmpty()) {
            String current = queue.poll();
            processed++;
            for (String child : dependents.getOrDefault(current, Collections.emptyList())) {
                int newDegree = inDegree.get(child) - 1;
                inDegree.put(child, newDegree);
                if (newDegree == 0) queue.add(child);
            }
        }

        if (processed != tasks.size()) {
            // Whichever tasks never reached in-degree 0 are stuck inside
            // (or downstream of) a cycle - report them for a useful error.
            List<String> stuck = new ArrayList<>();
            for (Map.Entry<String, Integer> e : inDegree.entrySet()) {
                if (e.getValue() != 0) stuck.add(e.getKey());
            }
            throw new DagValidationException("cycle detected involving task(s): " + stuck);
        }
    }
}

// ---------- Snapshot for resume ----------

class ExecutionSnapshot {
    final Map<String, TaskState> stateByTaskId;

    public ExecutionSnapshot(Map<String, TaskState> stateByTaskId) {
        this.stateByTaskId = stateByTaskId;
    }
}

// ---------- Result ----------

class ExecutionResult {
    final Map<String, TaskState> finalStates;
    final OverallStatus overallStatus;

    public ExecutionResult(Map<String, TaskState> finalStates, OverallStatus overallStatus) {
        this.finalStates = finalStates;
        this.overallStatus = overallStatus;
    }

    public void printSummary() {
        System.out.println("\n=== Execution Summary ===");
        for (Map.Entry<String, TaskState> e : finalStates.entrySet()) {
            System.out.println(e.getKey() + ": " + e.getValue());
        }
        System.out.println("Overall: " + overallStatus);
    }

    public ExecutionSnapshot toSnapshot() {
        return new ExecutionSnapshot(new HashMap<>(finalStates));
    }
}

// ---------- The orchestrator ----------

/*
 run(dag)
  → validate DAG
  → mark snapshot-completed tasks done, count remaining
  → submitTask() for every zero-dependency task
       → executor thread: executeWithRetries()
            → loop: runWithTimeout() each attempt
                 → submits action to ANOTHER executor thread
                 → waits up to timeoutMillis
                 → success / timeout / exception+backoff+retry
            → returns terminal TaskState
       → onTaskResolved() — cascades to children, submits newly-ready ones
  → latch.await() blocks until every task has resolved
  → buildResult()
* */

class TaskOrchestrator {
    private final ExecutorService executor = Executors.newCachedThreadPool();
    // Guards the dependency-counting/cascade bookkeeping below. Task
    // *execution* itself is fully parallel; only the scheduling decisions
    // (has a task's last dependency just resolved?) need to be atomic,
    // since multiple sibling tasks can finish at the same instant.
    private final Object schedulingLock = new Object();

    public ExecutionResult execute(Dag dag) {
        return run(dag, null);
    }

    public ExecutionResult resumeFrom(Dag dag, ExecutionSnapshot snapshot) {
        return run(dag, snapshot);
    }

    private ExecutionResult run(Dag dag, ExecutionSnapshot snapshot) {
        dag.validate();

        Map<String, TaskState> state = new ConcurrentHashMap<>();
        Map<String, Boolean> attempted = new ConcurrentHashMap<>();
        Map<String, AtomicInteger> remaining = new ConcurrentHashMap<>();
        Map<String, TaskState> worst = new ConcurrentHashMap<>();

        int toResolve = 0;
        for (String id : dag.tasks.keySet()) {
            TaskState snap = (snapshot != null) ? snapshot.stateByTaskId.get(id) : null;
            if (snap == TaskState.COMPLETED) {
                state.put(id, TaskState.COMPLETED);
                attempted.put(id, true);
                System.out.println("Skipping " + id + " (already COMPLETED)");
            } else {
                state.put(id, TaskState.PENDING);
                attempted.put(id, false);
                toResolve++;
            }
        }

        if (toResolve == 0) {
            return buildResult(dag, state);
        }

        CountDownLatch latch = new CountDownLatch(toResolve);

        // Remaining count only counts dependencies NOT already satisfied
        // via the snapshot - a resumed run should treat those exactly
        // like a COMPLETED dependency that already reported in.
        for (String id : dag.tasks.keySet()) {
            if (state.get(id) == TaskState.COMPLETED) continue;
            long unresolvedDeps = dag.dependencies.getOrDefault(id, Collections.emptyList())
                    .stream()
                    .filter(dep -> state.get(dep) != TaskState.COMPLETED)
                    .count();
            remaining.put(id, new AtomicInteger((int) unresolvedDeps));
        }

        for (String id : dag.tasks.keySet()) {
            if (state.get(id) == TaskState.PENDING && remaining.get(id).get() == 0) {
                submitTask(dag, dag.tasks.get(id), state, attempted, remaining, worst, latch);
            }
        }

        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        return buildResult(dag, state);
    }

    private void submitTask(Dag dag, TaskDefinition td, Map<String, TaskState> state,
                            Map<String, Boolean> attempted, Map<String, AtomicInteger> remaining,
                            Map<String, TaskState> worst, CountDownLatch latch) {
        state.put(td.id, TaskState.RUNNING);
        attempted.put(td.id, true);
        executor.submit(() -> {
            try {
                TaskState result = executeWithRetries(td);
                state.put(td.id, result);
                onTaskResolved(dag, td, state, attempted, remaining, worst, latch);
            } catch (RuntimeException e) {
                // Defensive: without this, an unexpected bug here would
                // silently swallow the exception (submit(Runnable)'s
                // Future is never inspected) and the latch would simply
                // never reach zero, hanging the whole run with no error.
                System.out.println("INTERNAL ERROR resolving " + td.id + ": " + e);
                e.printStackTrace();
                latch.countDown();
            }
        });
    }

    private TaskState executeWithRetries(TaskDefinition td) {
        int attempt = 0;
        while (true) {
            attempt++;
            try {
                System.out.println("Executing " + td.id + " (attempt " + attempt + ")...");
                runWithTimeout(td, attempt);
                System.out.println(td.id + " -> COMPLETED");
                return TaskState.COMPLETED;
            } catch (TimeoutException te) {
                // Simplification: a timeout is terminal immediately, no
                // retry attempted. Worth flagging this trade-off out loud
                // if asked - a production version might retry timeouts too.
                System.out.println(td.id + " -> TIMED_OUT");
                return TaskState.TIMED_OUT;
            } catch (Exception e) {
                if (attempt > td.maxRetries) {
                    System.out.println(td.id + " -> FAILED (no retries left, continueOnFailure="
                            + td.continueOnFailure + ")");
                    return TaskState.FAILED;
                }
                long backoffMs = 100L * (1L << (attempt - 1)); // 100, 200, 400...
                System.out.println("  " + td.id + " -> FAILED (attempt " + attempt + "), retrying in "
                        + backoffMs + "ms...");
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return TaskState.FAILED;
                }
            }
        }
    }

    private void runWithTimeout(TaskDefinition td, int attempt) throws Exception {
        Future<?> future = executor.submit(() -> {
            try {
                td.action.run(attempt);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        try {
            future.get(td.timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            future.cancel(true);
            throw te;
        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause();
            if (cause instanceof RuntimeException && cause.getCause() instanceof Exception) {
                throw (Exception) cause.getCause();
            }
            throw new Exception(cause);
        }
    }

    // Called once a task's own final state is set - either because it
    // actually ran (COMPLETED/FAILED/TIMED_OUT), or because this method
    // itself just cascade-resolved it (SKIPPED/FAILED-by-cascade).
    // Propagates the outcome to direct children and, if a child's last
    // dependency has just resolved, either submits it for real execution
    // or cascade-resolves it too (recursively).
    private void onTaskResolved(Dag dag, TaskDefinition resolvedTask, Map<String, TaskState> state,
                                Map<String, Boolean> attempted, Map<String, AtomicInteger> remaining,
                                Map<String, TaskState> worst, CountDownLatch latch) {
        latch.countDown();
        TaskState contribution = contributionOf(resolvedTask.id, dag, state, attempted);

        List<String> readyToRun = new ArrayList<>();
        List<String> cascadeFailed = new ArrayList<>();
        List<String> cascadeSkipped = new ArrayList<>();

        for (String childId : dag.dependents.getOrDefault(resolvedTask.id, Collections.emptyList())) {
            synchronized (schedulingLock) {
                if (state.get(childId) != TaskState.PENDING) continue; // already resolved, safety guard

                TaskState prevWorst = worst.get(childId);
                TaskState newWorst = worseOf(prevWorst, contribution);
                // ConcurrentHashMap rejects null values - a COMPLETED
                // parent with no prior negative contribution means
                // newWorst is legitimately null ("no bad signal yet"),
                // so simply don't store anything in that case; absence
                // from the map already means "no negative contribution"
                // via worst.get() returning null.
                if (newWorst != null) {
                    worst.put(childId, newWorst);
                }

                int rem = remaining.get(childId).decrementAndGet();
                if (rem == 0) {
                    if (newWorst == TaskState.FAILED) {
                        state.put(childId, TaskState.FAILED);
                        cascadeFailed.add(childId);
                    } else if (newWorst == TaskState.SKIPPED) {
                        state.put(childId, TaskState.SKIPPED);
                        cascadeSkipped.add(childId);
                    } else {
                        readyToRun.add(childId);
                    }
                }
            }
        }

        for (String id : cascadeFailed) {
            System.out.println(id + " -> FAILED (upstream dependency failed)");
            onTaskResolved(dag, dag.tasks.get(id), state, attempted, remaining, worst, latch);
        }
        for (String id : cascadeSkipped) {
            System.out.println(id + " -> SKIPPED (upstream failure, continueOnFailure)");
            onTaskResolved(dag, dag.tasks.get(id), state, attempted, remaining, worst, latch);
        }
        for (String id : readyToRun) {
            submitTask(dag, dag.tasks.get(id), state, attempted, remaining, worst, latch);
        }
    }

    // What does this resolved task pass down to its children?
    private TaskState contributionOf(String taskId, Dag dag, Map<String, TaskState> state,
                                     Map<String, Boolean> attempted) {
        TaskState s = state.get(taskId);
        if (s == TaskState.COMPLETED) return null; // no negative signal

        boolean wasAttempted = attempted.getOrDefault(taskId, false);
        if (wasAttempted) {
            // This task actually ran and terminally failed/timed out -
            // its OWN continueOnFailure flag decides the label for its
            // direct children.
            TaskDefinition td = dag.tasks.get(taskId);
            return td.continueOnFailure ? TaskState.SKIPPED : TaskState.FAILED;
        }
        // Never executed - it was itself cascade-resolved, so pass its
        // resolved state straight through unchanged.
        return s;
    }

    // FAILED outranks SKIPPED outranks "no negative signal" (null).
    private TaskState worseOf(TaskState a, TaskState b) {
        if (a == TaskState.FAILED || b == TaskState.FAILED) return TaskState.FAILED;
        if (a == TaskState.SKIPPED || b == TaskState.SKIPPED) return TaskState.SKIPPED;
        return null;
    }

    private ExecutionResult buildResult(Dag dag, Map<String, TaskState> state) {
        Map<String, TaskState> ordered = new LinkedHashMap<>();
        for (String id : dag.tasks.keySet()) {
            ordered.put(id, state.get(id));
        }
        boolean allCompleted = ordered.values().stream().allMatch(s -> s == TaskState.COMPLETED);
        OverallStatus overall = allCompleted ? OverallStatus.COMPLETED : OverallStatus.PARTIAL_FAILURE;
        return new ExecutionResult(ordered, overall);
    }

    public void shutdown() {
        executor.shutdown();
    }
}


public class TaskSchedularDemo {
    public static void main(String[] args) throws InterruptedException {
        testCase1_RetryAndFailurePropagationThenResume();
        testCase2_LinearChainAllPass();
        testCase3_CycleDetection();
        testCase4_MixedSkippedAndFailedCascade();
    }

    // ---- Test Case 1: A->B,C; B->D; C->E; D,E->F; F->G ----
    // C fails attempt 1, succeeds attempt 2 (retry+backoff).
    // F has maxRetries=0 and always fails -> FAILED, cascades FAILED to G
    // (continueOnFailure=false). Then resume from snapshot re-runs only
    // F and G.
    private static void testCase1_RetryAndFailurePropagationThenResume() {
        System.out.println("========== TEST CASE 1 ==========");
        Dag dag = new Dag();

        AtomicInteger cAttempts = new AtomicInteger(0);
        dag.addTask(new TaskDefinition("A", 2, 5000, false, quickAction()));
        dag.addTask(new TaskDefinition("B", 1, 3000, false, quickAction()));
        dag.addTask(new TaskDefinition("C", 1, 3000, true, failsThenSucceeds(cAttempts, 1)));
        dag.addTask(new TaskDefinition("D", 0, 10000, false, quickAction()));
        dag.addTask(new TaskDefinition("E", 0, 10000, false, quickAction()));
        dag.addTask(new TaskDefinition("F", 0, 30000, false, alwaysFails()));
        dag.addTask(new TaskDefinition("G", 2, 5000, true, quickAction()));

        dag.addDependency("A", "B");
        dag.addDependency("A", "C");
        dag.addDependency("B", "D");
        dag.addDependency("C", "E");
        dag.addDependency("D", "F");
        dag.addDependency("E", "F");
        dag.addDependency("F", "G");

        TaskOrchestrator orchestrator = new TaskOrchestrator();
        ExecutionResult result = orchestrator.execute(dag);
        result.printSummary();
        // Expect: A,B,C,D,E COMPLETED; F,G FAILED; Overall PARTIAL_FAILURE

        System.out.println("\n---- Resuming from snapshot ----");
        // Rebuild the DAG fresh - F's action this time succeeds, simulating
        // "the transient issue that caused F to fail has been fixed".
        Dag dagForResume = new Dag();
        dagForResume.addTask(new TaskDefinition("A", 2, 5000, false, quickAction()));
        dagForResume.addTask(new TaskDefinition("B", 1, 3000, false, quickAction()));
        dagForResume.addTask(new TaskDefinition("C", 1, 3000, true, quickAction()));
        dagForResume.addTask(new TaskDefinition("D", 0, 10000, false, quickAction()));
        dagForResume.addTask(new TaskDefinition("E", 0, 10000, false, quickAction()));
        dagForResume.addTask(new TaskDefinition("F", 1, 30000, false, quickAction())); // now succeeds
        dagForResume.addTask(new TaskDefinition("G", 2, 5000, true, quickAction()));
        dagForResume.addDependency("A", "B");
        dagForResume.addDependency("A", "C");
        dagForResume.addDependency("B", "D");
        dagForResume.addDependency("C", "E");
        dagForResume.addDependency("D", "F");
        dagForResume.addDependency("E", "F");
        dagForResume.addDependency("F", "G");

        ExecutionResult resumed = orchestrator.resumeFrom(dagForResume, result.toSnapshot());
        resumed.printSummary();
        // Expect: only F and G actually execute; everything COMPLETED

        orchestrator.shutdown();
    }

    // ---- Test Case 2: X -> Y -> Z, everything succeeds ----
    private static void testCase2_LinearChainAllPass() {
        System.out.println("\n========== TEST CASE 2 ==========");
        Dag dag = new Dag();
        dag.addTask(new TaskDefinition("X", 0, 5000, false, quickAction()));
        dag.addTask(new TaskDefinition("Y", 0, 5000, false, quickAction()));
        dag.addTask(new TaskDefinition("Z", 0, 5000, false, quickAction()));
        dag.addDependency("X", "Y");
        dag.addDependency("Y", "Z");

        TaskOrchestrator orchestrator = new TaskOrchestrator();
        ExecutionResult result = orchestrator.execute(dag);
        result.printSummary();
        // Expect: X, Y, Z all COMPLETED; Overall COMPLETED
        orchestrator.shutdown();
    }

    // ---- Test Case 3: P->Q->R->P (cycle) ----
    private static void testCase3_CycleDetection() {
        System.out.println("\n========== TEST CASE 3 ==========");
        Dag dag = new Dag();
        dag.addTask(new TaskDefinition("P", 0, 5000, false, quickAction()));
        dag.addTask(new TaskDefinition("Q", 0, 5000, false, quickAction()));
        dag.addTask(new TaskDefinition("R", 0, 5000, false, quickAction()));
        dag.addDependency("P", "Q");
        dag.addDependency("Q", "R");
        dag.addDependency("R", "P");

        TaskOrchestrator orchestrator = new TaskOrchestrator();
        try {
            orchestrator.execute(dag);
            System.out.println("UNEXPECTED: no exception thrown");
        } catch (DagValidationException e) {
            System.out.println("DAG validation failed: " + e.getMessage());
        }
        orchestrator.shutdown();
    }

    // ---- Test Case 4: wide DAG, mixed SKIPPED vs FAILED cascade ----
    private static void testCase4_MixedSkippedAndFailedCascade() {
        System.out.println("\n========== TEST CASE 4 ==========");
        Dag dag = new Dag();
        dag.addTask(new TaskDefinition("A", 0, 5000, false, quickAction()));
        dag.addTask(new TaskDefinition("B", 0, 3000, true, alwaysFails()));   // continueOnFailure=true
        dag.addTask(new TaskDefinition("C", 0, 3000, false, quickAction()));
        dag.addTask(new TaskDefinition("D", 0, 3000, false, alwaysFails())); // continueOnFailure=false
        dag.addTask(new TaskDefinition("E", 0, 5000, false, quickAction()));
        dag.addTask(new TaskDefinition("F", 0, 5000, false, quickAction()));
        dag.addTask(new TaskDefinition("G", 0, 5000, false, quickAction()));
        dag.addTask(new TaskDefinition("H", 0, 10000, false, quickAction()));
        dag.addTask(new TaskDefinition("I", 0, 5000, false, quickAction()));

        dag.addDependency("A", "B");
        dag.addDependency("A", "C");
        dag.addDependency("A", "D");
        dag.addDependency("B", "E");
        dag.addDependency("C", "F");
        dag.addDependency("D", "G");
        dag.addDependency("E", "H");
        dag.addDependency("F", "H");
        dag.addDependency("G", "H");
        dag.addDependency("H", "I");

        TaskOrchestrator orchestrator = new TaskOrchestrator();
        ExecutionResult result = orchestrator.execute(dag);
        result.printSummary();
        // Expect exactly: A,C,F COMPLETED; B,D,G,H,I FAILED; E SKIPPED;
        // Overall PARTIAL_FAILURE
        orchestrator.shutdown();
    }

    // ---- Reusable task actions for the demo ----

    private static TaskAction quickAction() {
        return attempt -> Thread.sleep(50);
    }

    private static TaskAction alwaysFails() {
        return attempt -> {
            Thread.sleep(50);
            throw new RuntimeException("simulated permanent failure");
        };
    }

    private static TaskAction failsThenSucceeds(AtomicInteger counter, int failUntilAttempt) {
        return attempt -> {
            Thread.sleep(50);
            int n = counter.incrementAndGet();
            if (n <= failUntilAttempt) {
                throw new RuntimeException("simulated transient failure");
            }
        };
    }
}
