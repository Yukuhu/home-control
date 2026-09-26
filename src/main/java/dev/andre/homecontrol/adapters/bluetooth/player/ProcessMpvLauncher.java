package dev.andre.homecontrol.adapters.bluetooth.player;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Starts real mpv processes and ends them all on close. */
public final class ProcessMpvLauncher implements MpvLauncher {

    private static final int ERROR_LINES = 20;
    private static final int MAX_OUTPUT_BYTES = 256 * 1024;

    private final String mpvPath;
    private final Set<LocalProcess> live = ConcurrentHashMap.newKeySet();

    public ProcessMpvLauncher(String mpvPath) {
        this.mpvPath = mpvPath;
    }

    @Override
    public MpvProcess start(List<String> arguments) throws IOException {
        Process process;
        try {
            process = new ProcessBuilder(command(arguments)).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        } catch (IOException e) {
            throw new MpvNotInstalledException(mpvPath, e);
        }
        LocalProcess started = new LocalProcess(process);
        live.add(started);
        process.onExit().thenRun(() -> live.remove(started));
        return started;
    }

    @Override
    public String run(List<String> arguments, Duration timeout) throws IOException {
        Process process;
        try {
            process = new ProcessBuilder(command(arguments)).redirectErrorStream(true).start();
        } catch (IOException e) {
            throw new MpvNotInstalledException(mpvPath, e);
        }
        process.getOutputStream().close();
        CompletableFuture<String> output = new CompletableFuture<>();
        Thread.ofVirtual().name("mpv-run").start(() -> {
            try (InputStream in = process.getInputStream()) {
                String text = new String(in.readNBytes(MAX_OUTPUT_BYTES), StandardCharsets.UTF_8);
                in.transferTo(OutputStream.nullOutputStream());
                output.complete(text);
            } catch (IOException e) {
                output.completeExceptionally(e);
            }
        });
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new IOException("mpv did not finish within " + timeout.toMillis() + " ms");
            }
            return StreamRedaction.redact(output.get(2, TimeUnit.SECONDS));
        } catch (InterruptedException _) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("interrupted while running mpv");
        } catch (ExecutionException | TimeoutException e) {
            throw new IOException("could not read mpv's output", e);
        }
    }

    @Override
    public void close() {
        new ArrayList<>(live).forEach(process -> process.terminate(Duration.ofSeconds(1)));
    }

    private List<String> command(List<String> arguments) {
        List<String> command = new ArrayList<>(arguments.size() + 1);
        command.add(mpvPath);
        command.addAll(arguments);
        return command;
    }

    private static final class LocalProcess implements MpvProcess {

        private final Process process;
        private final Deque<String> errors = new ArrayDeque<>();

        LocalProcess(Process process) {
            this.process = process;
            try {
                process.getOutputStream().close();
            } catch (IOException _) {
                // The process may have already exited and closed its input stream.
            }
            Thread.ofVirtual().name("mpv-stderr-" + process.pid()).start(this::drainErrors);
        }

        private void drainErrors() {
            try (BufferedReader reader = process.errorReader(StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String safe = StreamRedaction.redact(line);
                    synchronized (errors) {
                        errors.addLast(safe.length() > 300 ? safe.substring(0, 300) : safe);
                        while (errors.size() > ERROR_LINES) {
                            errors.removeFirst();
                        }
                    }
                }
            } catch (IOException _) {
                // process ended
            }
        }

        @Override
        public long pid() {
            return process.pid();
        }

        @Override
        public boolean alive() {
            return process.isAlive();
        }

        @Override
        public CompletableFuture<Integer> onExit() {
            return process.onExit().thenApply(Process::exitValue);
        }

        @Override
        public String recentErrors() {
            synchronized (errors) {
                return String.join(" | ", errors);
            }
        }

        @Override
        public void terminate(Duration grace) {
            if (!process.isAlive()) {
                return;
            }
            process.destroy();
            try {
                if (!process.waitFor(grace.toMillis(), TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(2, TimeUnit.SECONDS);
                }
            } catch (InterruptedException _) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }
    }
}
