package dev.andre.homecontrol.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Failed logins per client address and in total, in a sliding window. In memory; a restart resets it.
 * A password check first {@link #reserve reserves} an attempt, which counts as a failure until it
 * {@link #succeeded succeeds} or is {@link #release released}, so a parallel burst cannot run more
 * checks than the limit allows.
 */
public class LoginRateLimiter {

    private static final int MAX_TRACKED_ADDRESSES = 10_000;
    /** Sweeping every address on every call would make a flood of failures quadratic. */
    private static final Duration SWEEP_INTERVAL = Duration.ofSeconds(1);

    private final Clock clock;
    private final int perAddress;
    private final int total;
    private final Duration window;
    private final Map<String, Deque<Instant>> failuresByAddress = new HashMap<>();
    private final Deque<Instant> allFailures = new ArrayDeque<>();
    private Instant lastSweep = Instant.MIN;

    public LoginRateLimiter(Clock clock, int perAddress, int total, Duration window) {
        this.clock = clock;
        this.perAddress = perAddress;
        this.total = total;
        this.window = window;
    }

    /** How long this address must wait before the next attempt, or empty if it may try now. */
    public synchronized Optional<Duration> blockedFor(String address) {
        Instant now = clock.instant();
        prune(now, address);
        if (allFailures.size() >= total) {
            return Optional.of(Duration.between(now, allFailures.peekFirst().plus(window)));
        }
        Deque<Instant> failures = failuresByAddress.get(address);
        if (failures != null && failures.size() >= perAddress) {
            return Optional.of(Duration.between(now, failures.peekFirst().plus(window)));
        }
        return Optional.empty();
    }

    /**
     * Atomically checks the limit and, if the address may try, records the attempt as a failure.
     * Returns how long to wait when it may not; nothing is recorded then.
     */
    public synchronized Optional<Duration> reserve(String address) {
        Optional<Duration> blocked = blockedFor(address);
        if (blocked.isEmpty()) {
            failed(address);
        }
        return blocked;
    }

    /** Takes back a reservation whose password was never checked (the check was busy, or refused before it). */
    public synchronized void release(String address) {
        Deque<Instant> failures = failuresByAddress.get(address);
        if (failures == null || failures.isEmpty()) {
            return;
        }
        allFailures.removeLastOccurrence(failures.removeLast());
        if (failures.isEmpty()) {
            failuresByAddress.remove(address);
        }
    }

    public synchronized void failed(String address) {
        Instant now = clock.instant();
        prune(now, address);
        if (failuresByAddress.size() >= MAX_TRACKED_ADDRESSES && !failuresByAddress.containsKey(address)) {
            sweep(now.minus(window));
            if (failuresByAddress.size() >= MAX_TRACKED_ADDRESSES) {
                failuresByAddress.clear(); // the global cap still bounds guessing
            }
        }
        failuresByAddress.computeIfAbsent(address, ignored -> new ArrayDeque<>()).addLast(now);
        allFailures.addLast(now);
    }

    /** Clears the address; its last attempt (the successful reservation) also stops counting in total. */
    public synchronized void succeeded(String address) {
        Deque<Instant> failures = failuresByAddress.remove(address);
        if (failures != null && !failures.isEmpty()) {
            allFailures.removeLastOccurrence(failures.peekLast());
        }
    }

    synchronized int trackedAddresses() {
        return failuresByAddress.size();
    }

    private void prune(Instant now, String address) {
        Instant cutoff = now.minus(window);
        expire(allFailures, cutoff);
        Deque<Instant> failures = failuresByAddress.get(address);
        if (failures != null) {
            expire(failures, cutoff);
            if (failures.isEmpty()) {
                failuresByAddress.remove(address);
            }
        }
        if (!now.isBefore(lastSweep.plus(SWEEP_INTERVAL))) {
            sweep(cutoff);
            lastSweep = now;
        }
    }

    private void sweep(Instant cutoff) {
        failuresByAddress.values().forEach(failures -> expire(failures, cutoff));
        failuresByAddress.values().removeIf(Deque::isEmpty);
    }

    private static void expire(Deque<Instant> failures, Instant cutoff) {
        while (!failures.isEmpty() && !failures.peekFirst().isAfter(cutoff)) {
            failures.removeFirst();
        }
    }
}
