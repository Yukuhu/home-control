package dev.andre.homecontrol.e2e;

import dev.andre.homecontrol.core.CodePairing;
import dev.andre.homecontrol.core.CodePairingOutcome;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A {@link CodePairing} double for the browser tests: a TV that shows its code until a test calls
 * {@link #awaitCode()}, and checks a submitted code only when the test calls {@link #answer()}.
 */
public class FakeCodePairing implements CodePairing {

    private final AtomicInteger submits = new AtomicInteger();
    private volatile boolean inProgress;
    private volatile CountDownLatch answer = new CountDownLatch(0);

    /** The TV shows a code; a submit waits for {@link #answer()}. */
    public void awaitCode() {
        submits.set(0);
        answer = new CountDownLatch(1);
        inProgress = true;
    }

    /** The TV accepts the code it is waiting on. */
    public void answer() {
        answer.countDown();
    }

    /** No pairing waits any more, so a test that failed half way leaves no code form behind. */
    public void reset() {
        inProgress = false;
        answer.countDown();
    }

    public int submits() {
        return submits.get();
    }

    @Override
    public boolean inProgress() {
        return inProgress;
    }

    @Override
    public void begin(String host, String name) {
        inProgress = true;
    }

    @Override
    public CodePairingOutcome submit(String code) {
        submits.incrementAndGet();
        try {
            answer.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
        inProgress = false;
        return new CodePairingOutcome.Paired();
    }
}
