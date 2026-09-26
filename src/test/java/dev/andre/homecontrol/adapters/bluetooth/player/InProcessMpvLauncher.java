package dev.andre.homecontrol.adapters.bluetooth.player;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** In-process fake mpv launcher for unit tests: starts real {@link FakeMpv} instances, no subprocess. */
public final class InProcessMpvLauncher implements MpvLauncher {

    public volatile FakeMpv.Options options = FakeMpv.Options.defaults();
    public volatile IOException startFailure;
    public volatile Duration startDelay = Duration.ZERO;
    public volatile String version = FakeMpv.VERSION;

    public final List<List<String>> starts = new CopyOnWriteArrayList<>();
    public final List<List<String>> runs = new CopyOnWriteArrayList<>();
    public final List<FakeMpv> players = new CopyOnWriteArrayList<>();

    private final AtomicLong nextPid = new AtomicLong(1000);

    @Override
    public MpvProcess start(List<String> arguments) throws IOException {
        if (startFailure != null) {
            throw startFailure;
        }
        starts.add(List.copyOf(arguments));
        Path socket = Path.of(value(arguments, "--input-ipc-server="));
        String volumeArg = value(arguments, "--volume=");
        double volume = volumeArg == null ? 100 : Double.parseDouble(volumeArg);
        FakeStartedProcess process = new FakeStartedProcess(nextPid.getAndIncrement());
        Duration delay = startDelay;
        FakeMpv.Options currentOptions = options;
        Thread.ofVirtual().name("in-process-mpv-" + process.pid()).start(() -> {
            try {
                if (!delay.isZero()) {
                    Thread.sleep(delay.toMillis());
                }
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                process.exit.complete(1);
                return;
            }
            if (process.terminated) {
                process.exit.complete(143);
                return;
            }
            try (FakeMpv fake = FakeMpv.serve(socket, currentOptions, volume, line -> { })) {
                process.attach(fake);
                players.add(fake);
                fake.awaitQuit();
                // A real OS process's exit is detected with some latency (a reaper thread, waitpid);
                // this keeps the fake from "exiting" before an already-sent IPC event (e.g. end-file,
                // broadcast just before mpv's --idle=once quit) has been read and dispatched.
                Thread.sleep(50);
                process.exit.complete(0);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                process.exit.complete(1);
            } catch (Exception _) {
                process.exit.complete(1);
            }
        });
        return process;
    }

    @Override
    public String run(List<String> arguments, Duration timeout) throws IOException {
        if (startFailure != null) {
            throw startFailure;
        }
        runs.add(List.copyOf(arguments));
        if (arguments.contains("--version")) {
            return version + "\n";
        }
        if (arguments.contains("--audio-device=help")) {
            return FakeMpv.audioDeviceHelp(options.audioDevices());
        }
        return "";
    }

    @Override
    public void close() {
        players.forEach(FakeMpv::close);
    }

    public FakeMpv latest() {
        return players.isEmpty() ? null : players.getLast();
    }

    public long alive() {
        return players.stream().filter(fake -> !fake.hasQuit()).count();
    }

    private static String value(List<String> argv, String prefix) {
        return argv.stream().filter(argument -> argument.startsWith(prefix)).map(argument -> argument.substring(prefix.length()))
                .findFirst().orElse(null);
    }

    private static final class FakeStartedProcess implements MpvProcess {
        private final long pid;
        private final CompletableFuture<Integer> exit = new CompletableFuture<>();
        private volatile boolean terminated;
        private volatile FakeMpv fake;

        FakeStartedProcess(long pid) {
            this.pid = pid;
        }

        void attach(FakeMpv fake) {
            this.fake = fake;
        }

        @Override
        public long pid() {
            return pid;
        }

        @Override
        public boolean alive() {
            return !exit.isDone();
        }

        @Override
        public CompletableFuture<Integer> onExit() {
            return exit;
        }

        @Override
        public String recentErrors() {
            return "";
        }

        @Override
        public void terminate(Duration grace) {
            terminated = true;
            FakeMpv current = fake;
            if (current != null) {
                current.close();
            } else if (!exit.isDone()) {
                exit.complete(143);
            }
            try {
                exit.get(2, TimeUnit.SECONDS);
            } catch (Exception _) {
            }
        }
    }
}
