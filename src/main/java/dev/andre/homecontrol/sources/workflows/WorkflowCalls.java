package dev.andre.homecontrol.sources.workflows;

import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static dev.andre.homecontrol.sources.workflows.WorkflowDraft.Call;
import static dev.andre.homecontrol.sources.workflows.WorkflowException.Stage;

/** Runs calls as soon as the calls they depend on are done, within one run's deadline and share of fetches. */
final class WorkflowCalls {
    private static final Executor VIRTUAL = task -> Thread.ofVirtual().name("workflow-call").start(task);
    private final WorkflowHttpClient http;

    WorkflowCalls(WorkflowHttpClient http) {
        this.http = http;
    }

    /** One refresh, Play or Test: when it must end and how many of its fetches may run at once. */
    static final class Run {
        private final long deadline;
        private final Semaphore share;

        Run(Duration budget, int parallel) {
            deadline = System.nanoTime() + budget.toNanos();
            share = new Semaphore(parallel);
        }

        long deadline() { return deadline; }

        long remaining() { return Math.max(0, deadline - System.nanoTime()); }
    }

    /** The values the calls produced, and each call's parsed response. Neither leaves the run. */
    record Outcome(Map<String, WorkflowJson.Value> values, Map<String, JsonNode> responses) {
        @Override public String toString() { return "Outcome[redacted]"; }
    }

    Outcome run(List<Call> calls, WorkflowPlan plan, Map<String, WorkflowJson.Value> known, Run run, String entryTitle) {
        var values = new ConcurrentHashMap<>(known);
        var responses = new ConcurrentHashMap<String, JsonNode>();
        if (calls.isEmpty()) return new Outcome(Map.copyOf(values), Map.of());
        var stopped = new AtomicBoolean();
        var failed = new CompletableFuture<Void>();
        Map<String, CompletableFuture<Void>> started = new LinkedHashMap<>();
        Set<String> names = Set.copyOf(plan.variables());
        for (Call call : calls) {
            // Calls come in list order, so every dependency in this set has already been started.
            CompletableFuture<?>[] before = plan.dependencies(call.name()).stream()
                    .map(started::get).filter(Objects::nonNull).toArray(CompletableFuture[]::new);
            CompletableFuture<Void> future = CompletableFuture.allOf(before)
                    .thenRunAsync(() -> execute(call, names, values, responses, run, stopped, entryTitle), VIRTUAL);
            future.whenComplete((ignored, error) -> {
                if (error != null) {
                    stopped.set(true);
                    failed.completeExceptionally(error);
                }
            });
            started.put(call.name(), future);
        }
        var all = CompletableFuture.allOf(started.values().toArray(CompletableFuture[]::new));
        try {
            CompletableFuture.anyOf(all, failed).get(run.remaining(), TimeUnit.NANOSECONDS);
        } catch (ExecutionException e) {
            throw safe(e.getCause());
        } catch (TimeoutException _) {
            stopped.set(true);
            throw new WorkflowException(Stage.FETCH, "the workflow took too long; try again later");
        } catch (InterruptedException _) {
            stopped.set(true);
            Thread.currentThread().interrupt();
            throw new WorkflowException(Stage.FETCH, "request interrupted");
        }
        return new Outcome(Map.copyOf(values), Map.copyOf(responses));
    }

    private void execute(Call call, Set<String> names, Map<String, WorkflowJson.Value> values,
                         Map<String, JsonNode> responses, Run run, AtomicBoolean stopped, String entryTitle) {
        try {
            if (stopped.get()) throw new WorkflowException(Stage.FETCH, "not run because another call failed");
            acquire(run);
            try {
                if (stopped.get()) throw new WorkflowException(Stage.FETCH, "not run because another call failed");
                var request = WorkflowRunner.request(call, names, values);
                JsonNode response = WorkflowJson.parse(http.fetch(request, run.deadline()));
                values.putAll(WorkflowJson.values(call.variables(), response));
                responses.put(call.name(), response);
            } finally {
                run.share.release();
            }
        } catch (WorkflowException failure) {
            throw failure.inCall(call.name(), entryTitle);
        }
    }

    private static void acquire(Run run) {
        try {
            if (!run.share.tryAcquire(run.remaining(), TimeUnit.NANOSECONDS)) {
                throw new WorkflowException(Stage.FETCH, "busy; try again later");
            }
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            throw new WorkflowException(Stage.FETCH, "request interrupted");
        }
    }

    /** Only locally authored messages leave a run; anything else becomes a generic failure. */
    private static WorkflowException safe(Throwable error) {
        Throwable cause = error;
        while (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
        return cause instanceof WorkflowException known ? known : new WorkflowException(Stage.FETCH, "request failed");
    }
}
