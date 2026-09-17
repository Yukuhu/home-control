package dev.andre.homecontrol.adapters.bluetooth.player;

import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** One speaker's player: at most one mpv process, started per play, the URL sent over IPC. */
public final class MpvPlayer implements AutoCloseable {

    private record Running(MpvProcess process, MpvIpc ipc) {
        boolean alive() {
            return process.alive() && ipc.open();
        }
    }

    private final MpvLauncher launcher;
    private final Path socket;
    private final Duration startTimeout;
    private final Duration loadTimeout;
    private final Duration commandTimeout;
    private volatile Running running;

    public MpvPlayer(MpvLauncher launcher, Path socket, Duration startTimeout, Duration loadTimeout, Duration commandTimeout) {
        this.launcher = launcher;
        this.socket = socket;
        this.startTimeout = startTimeout;
        this.loadTimeout = loadTimeout;
        this.commandTimeout = commandTimeout;
    }

    /** Unix socket paths are limited to 108 bytes; device ids are free text. */
    public static Path socketFor(Path runtimeDir, String deviceId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(deviceId.getBytes(StandardCharsets.UTF_8));
            return runtimeDir.resolve("mpv-" + HexFormat.of().formatHex(digest).substring(0, 12) + ".sock");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public synchronized void play(URI url, String audioDevice, int volume, boolean muted) throws IOException, MpvException {
        stop();
        Files.createDirectories(socket.getParent());
        try {
            Files.setPosixFilePermissions(socket.getParent(), PosixFilePermissions.fromString("rwx------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // best effort
        }
        Files.deleteIfExists(socket);
        MpvProcess process = launcher.start(MpvCommandLine.arguments(socket, audioDevice, volume));
        CompletableFuture<Void> loaded = new CompletableFuture<>();
        MpvIpc ipc;
        try {
            ipc = MpvIpc.connect(socket, startTimeout, process::alive, new MpvIpc.EventListener() {
                @Override
                public void onEvent(JsonNode event) {
                    switch (event.path("event").asString("")) {
                        case "file-loaded" -> loaded.complete(null);
                        case "end-file" -> loaded.completeExceptionally(MpvException.loadFailed(
                                event.path("file_error").asString(event.path("reason").asString("stopped"))));
                        default -> {
                        }
                    }
                }

                @Override
                public void onClosed() {
                    loaded.completeExceptionally(new IOException("mpv closed its control socket"));
                }
            });
        } catch (IOException e) {
            process.terminate(Duration.ofSeconds(1));
            throw withErrors(e, process);
        }
        running = new Running(process, ipc);
        process.onExit().thenRun(() -> loaded.completeExceptionally(new IOException("mpv exited")));
        try {
            if (muted) {
                ipc.command(commandTimeout, "set_property", "mute", true);
            }
            ipc.command(commandTimeout, "loadfile", url.toString(), "replace");
            loaded.get(loadTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            stop();
            throw new IOException("the stream did not start within " + loadTimeout.toSeconds() + " s");
        } catch (ExecutionException e) {
            stop();
            if (e.getCause() instanceof MpvException refused) {
                throw refused;
            }
            throw withErrors(e.getCause() instanceof IOException io ? io : new IOException(e.getCause()), process);
        } catch (IOException | MpvException e) {
            stop();
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            stop();
            throw new InterruptedIOException("interrupted while starting playback");
        }
    }

    public boolean active() {
        Running current = running;
        return current != null && current.alive();
    }

    public void pause(boolean paused) throws IOException, MpvException {
        require().ipc().command(commandTimeout, "set_property", "pause", paused);
    }

    public void volume(int percent) throws IOException, MpvException {
        require().ipc().command(commandTimeout, "set_property", "volume", Math.clamp(percent, 0, 100));
    }

    public void mute(boolean muted) throws IOException, MpvException {
        require().ipc().command(commandTimeout, "set_property", "mute", muted);
    }

    /**
     * Empty when nothing is loaded; a dead player is cleaned up. Deliberately not {@code synchronized}
     * (several IPC round trips): a concurrent {@link #play(URI, String, int, boolean)} may replace
     * {@code running} while this reads a stale snapshot, so any failure here stops that snapshot only
     * if it is still current — never the new player a concurrent play might already have installed.
     */
    public Optional<PlayerStatus> status() {
        Running current = running;
        if (current == null) {
            return Optional.empty();
        }
        if (!current.alive()) {
            stopIfCurrent(current);
            return Optional.empty();
        }
        try {
            if (flag(current, "idle-active")) {
                return Optional.empty();
            }
            return Optional.of(new PlayerStatus(flag(current, "pause"), flag(current, "paused-for-cache"),
                    number(current, "time-pos").orElse(0.0),
                    number(current, "duration").filter(duration -> duration > 0).orElse(null),
                    metadataTitle(current),
                    (int) Math.round(number(current, "volume").orElse(0.0)),
                    flag(current, "mute")));
        } catch (IOException e) {
            stopIfCurrent(current);
            return Optional.empty();
        }
    }

    /** Stops {@code expected} only if it is still the running player: never tears down a newer one. */
    private synchronized void stopIfCurrent(Running expected) {
        if (running == expected) {
            stop();
        }
    }

    public synchronized void stop() {
        Running current = running;
        running = null;
        if (current == null) {
            return;
        }
        try {
            current.ipc().command(commandTimeout, "quit");
        } catch (IOException | MpvException ignored) {
            // already gone, or it closed the socket while quitting
        }
        current.ipc().close();
        current.process().terminate(Duration.ofSeconds(2));
        try {
            Files.deleteIfExists(socket);
        } catch (IOException ignored) {
        }
    }

    @Override
    public void close() {
        stop();
    }

    private Running require() throws IOException {
        Running current = running;
        if (current == null || !current.alive()) {
            throw new IOException("nothing is playing");
        }
        return current;
    }

    private boolean flag(Running current, String property) throws IOException {
        try {
            return current.ipc().command(commandTimeout, "get_property", property).asBoolean(false);
        } catch (MpvException unavailable) {
            return false;
        }
    }

    private Optional<Double> number(Running current, String property) throws IOException {
        try {
            JsonNode value = current.ipc().command(commandTimeout, "get_property", property);
            return value.isNumber() ? Optional.of(value.asDouble(0)) : Optional.empty();
        } catch (MpvException unavailable) {
            return Optional.empty();
        }
    }

    private String metadataTitle(Running current) throws IOException {
        try {
            JsonNode metadata = current.ipc().command(commandTimeout, "get_property", "metadata");
            String icy = null;
            for (Map.Entry<String, JsonNode> entry : metadata.properties()) {
                String value = entry.getValue().asString("").strip();
                if (value.isEmpty()) {
                    continue;
                }
                if (entry.getKey().equalsIgnoreCase("title")) {
                    return value;
                }
                if (entry.getKey().equalsIgnoreCase("icy-title")) {
                    icy = value;
                }
            }
            return icy;
        } catch (MpvException unavailable) {
            return null;
        }
    }

    private static IOException withErrors(IOException e, MpvProcess process) {
        String errors = process.recentErrors();
        return errors == null || errors.isBlank() ? e
                : new IOException(StreamRedaction.redact(e.getMessage()) + " (mpv: " + errors + ")", e);
    }
}
