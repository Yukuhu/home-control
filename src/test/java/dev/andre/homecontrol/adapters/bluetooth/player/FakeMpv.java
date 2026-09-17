package dev.andre.homecontrol.adapters.bluetooth.player;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;

/**
 * Speaks enough of mpv's JSON IPC, with --idle=once semantics, for the player: in-process for unit
 * tests, or as a fake mpv executable through FakeMpvScript (environment variables configure it).
 */
public final class FakeMpv implements AutoCloseable {

    public record Options(double durationSeconds, String metadataTitle, String failUrlsContaining, List<String> audioDevices) {

        public static Options defaults() {
            return new Options(187.0, null, null, List.of());
        }

        public Options withDuration(double seconds) {
            return new Options(seconds, metadataTitle, failUrlsContaining, audioDevices);
        }

        public Options withMetadataTitle(String title) {
            return new Options(durationSeconds, title, failUrlsContaining, audioDevices);
        }

        public Options failingFor(String urlPart) {
            return new Options(durationSeconds, metadataTitle, urlPart, audioDevices);
        }

        /** Each entry is {@code id=description}. */
        public Options withAudioDevices(String... devices) {
            return new Options(durationSeconds, metadataTitle, failUrlsContaining, List.of(devices));
        }

        static Options fromEnvironment(Map<String, String> env) {
            String duration = env.getOrDefault("FAKE_MPV_DURATION", "187");
            String devices = env.getOrDefault("FAKE_MPV_DEVICES", "");
            return new Options("none".equals(duration) ? 0 : Double.parseDouble(duration), env.get("FAKE_MPV_TITLE"),
                    env.get("FAKE_MPV_FAIL_URLS_CONTAINING"), devices.isBlank() ? List.of() : List.of(devices.split(";")));
        }
    }

    public static final String VERSION = "mpv v0.41.0-fake Copyright © 2000-2025 mpv/MPlayer/mplayer2 projects";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ServerSocketChannel server;
    private final Path socket;
    private final Options options;
    private final Consumer<String> log;
    private final List<List<String>> commands = new CopyOnWriteArrayList<>();
    private final List<SocketChannel> clients = new CopyOnWriteArrayList<>();
    private final CountDownLatch quit = new CountDownLatch(1);

    // playback state, guarded by this
    private String path;
    private boolean paused;
    private double positionAtMark;
    private long markNanos = System.nanoTime();
    private double volume;
    private boolean muted;
    private boolean everLoaded;
    private boolean closing;

    private FakeMpv(ServerSocketChannel server, Path socket, Options options, double volume, Consumer<String> log) {
        this.server = server;
        this.socket = socket;
        this.options = options;
        this.volume = volume;
        this.log = log;
    }

    public static FakeMpv serve(Path socket, Options options, double initialVolume, Consumer<String> log) throws IOException {
        Files.deleteIfExists(socket);
        ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        server.bind(UnixDomainSocketAddress.of(socket));
        FakeMpv fake = new FakeMpv(server, socket, options, initialVolume, log);
        Thread.ofVirtual().name("fake-mpv-accept").start(fake::acceptLoop);
        Thread.ofVirtual().name("fake-mpv-clock").start(fake::clockLoop);
        return fake;
    }

    public static String audioDeviceHelp(List<String> devices) {
        StringBuilder help = new StringBuilder("List of detected audio devices:\n  'auto' (Autoselect device)\n");
        for (String device : devices) {
            String[] parts = device.split("=", 2);
            help.append("  '").append(parts[0]).append("' (").append(parts.length > 1 ? parts[1] : parts[0]).append(")\n");
        }
        return help.toString();
    }

    public List<List<String>> commands() {
        return List.copyOf(commands);
    }

    public boolean hasQuit() {
        return quit.getCount() == 0;
    }

    public void awaitQuit() throws InterruptedException {
        quit.await();
    }

    public synchronized double volume() {
        return volume;
    }

    public synchronized boolean muted() {
        return muted;
    }

    public synchronized boolean paused() {
        return paused;
    }

    public synchronized String path() {
        return path;
    }

    /** Ends the current file as if the stream finished. */
    public void finishTrack() {
        List<ObjectNode> events = new ArrayList<>();
        boolean quitAfter;
        synchronized (this) {
            quitAfter = end("eof", events);
        }
        events.forEach(this::broadcast);
        if (quitAfter) {
            close();
        }
    }

    @Override
    public void close() {
        synchronized (this) {
            if (closing) {
                return;
            }
            closing = true;
        }
        try {
            server.close();
        } catch (IOException ignored) {
        }
        for (SocketChannel client : clients) {
            try {
                client.close();
            } catch (IOException ignored) {
            }
        }
        try {
            Files.deleteIfExists(socket);
        } catch (IOException ignored) {
        }
        log.accept("{\"type\":\"exit\"}");
        quit.countDown();
    }

    private void acceptLoop() {
        try {
            while (!hasQuit()) {
                SocketChannel client = server.accept();
                clients.add(client);
                Thread.ofVirtual().name("fake-mpv-client").start(() -> serveClient(client));
            }
        } catch (IOException closed) {
            // server closed
        }
    }

    private void serveClient(SocketChannel client) {
        try {
            MpvIpc.readLines(client, line -> handle(client, line));
        } catch (IOException closed) {
            // client gone
        }
        clients.remove(client);
    }

    private void clockLoop() {
        while (!hasQuit()) {
            boolean finished;
            synchronized (this) {
                finished = path != null && options.durationSeconds() > 0 && position() >= options.durationSeconds();
            }
            if (finished) {
                finishTrack();
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void handle(SocketChannel client, String line) {
        JsonNode request;
        try {
            request = JSON.readTree(line);
        } catch (JacksonException e) {
            return;
        }
        List<String> command = new ArrayList<>();
        request.path("command").forEach(argument -> command.add(argument.isString() ? argument.asString("") : argument.toString()));
        commands.add(List.copyOf(command));
        log.accept(JSON.writeValueAsString(Map.of("type", "command", "command", command)));
        ObjectNode response = JSON.createObjectNode();
        List<ObjectNode> events = new ArrayList<>();
        boolean quitAfter = false;
        synchronized (this) {
            switch (command.isEmpty() ? "" : command.getFirst()) {
                case "get_property" -> getProperty(command.size() > 1 ? command.get(1) : "", response);
                case "set_property" -> setProperty(command.size() > 1 ? command.get(1) : "", request.path("command").path(2), response);
                case "loadfile" -> {
                    response.put("error", "success");
                    quitAfter = load(command.size() > 1 ? command.get(1) : "", events);
                }
                case "stop" -> {
                    response.put("error", "success");
                    quitAfter = end("stop", events);
                }
                case "quit" -> {
                    response.put("error", "success");
                    quitAfter = true;
                }
                default -> response.put("error", "invalid parameter");
            }
        }
        response.put("request_id", request.path("request_id").asLong(0));
        send(client, response);
        events.forEach(this::broadcast);
        if (quitAfter) {
            close();
        }
    }

    private void getProperty(String name, ObjectNode response) {
        boolean loaded = path != null;
        switch (name) {
            case "pause" -> response.put("data", paused);
            case "volume" -> response.put("data", volume);
            case "mute" -> response.put("data", muted);
            case "idle-active" -> response.put("data", !loaded);
            case "paused-for-cache" -> {
                if (!loaded) {
                    response.put("error", "property unavailable");
                    return;
                }
                response.put("data", false);
            }
            case "time-pos" -> {
                if (!loaded) {
                    response.put("error", "property unavailable");
                    return;
                }
                response.put("data", position());
            }
            case "duration" -> {
                if (!loaded || options.durationSeconds() <= 0) {
                    response.put("error", "property unavailable");
                    return;
                }
                response.put("data", options.durationSeconds());
            }
            case "metadata" -> {
                if (!loaded) {
                    response.put("error", "property unavailable");
                    return;
                }
                ObjectNode metadata = response.putObject("data");
                if (options.metadataTitle() != null) {
                    metadata.put("title", options.metadataTitle());
                }
            }
            default -> {
                response.put("error", "property not found");
                return;
            }
        }
        response.put("error", "success");
    }

    private void setProperty(String name, JsonNode value, ObjectNode response) {
        switch (name) {
            case "pause" -> {
                if (!value.isBoolean()) {
                    response.put("error", "invalid parameter");
                    return;
                }
                positionAtMark = position();
                markNanos = System.nanoTime();
                paused = value.asBoolean(false);
            }
            case "volume" -> {
                if (!value.isNumber() || value.asDouble(-1) < 0 || value.asDouble(-1) > 100) {
                    response.put("error", "invalid parameter");
                    return;
                }
                volume = value.asDouble(0);
            }
            case "mute" -> {
                if (!value.isBoolean()) {
                    response.put("error", "invalid parameter");
                    return;
                }
                muted = value.asBoolean(false);
            }
            default -> {
                response.put("error", "property not found");
                return;
            }
        }
        response.put("error", "success");
    }

    /** Returns whether the player quits (--idle=once: a failed first file ends the playlist). */
    private boolean load(String url, List<ObjectNode> events) {
        events.add(event("start-file"));
        if (options.failUrlsContaining() != null && url.contains(options.failUrlsContaining())) {
            ObjectNode end = event("end-file");
            end.put("reason", "error");
            end.put("file_error", "loading failed");
            events.add(end);
            path = null;
            return true;
        }
        path = url;
        paused = false;
        positionAtMark = 0;
        markNanos = System.nanoTime();
        everLoaded = true;
        events.add(event("file-loaded"));
        events.add(event("playback-restart"));
        return false;
    }

    private boolean end(String reason, List<ObjectNode> events) {
        if (path != null) {
            ObjectNode end = event("end-file");
            end.put("reason", reason);
            events.add(end);
        }
        path = null;
        return everLoaded;
    }

    private double position() {
        if (path == null) {
            return 0;
        }
        double position = paused ? positionAtMark : positionAtMark + (System.nanoTime() - markNanos) / 1e9;
        return options.durationSeconds() > 0 ? Math.min(position, options.durationSeconds()) : position;
    }

    private static ObjectNode event(String name) {
        ObjectNode event = JSON.createObjectNode();
        event.put("event", name);
        event.put("playlist_entry_id", 1);
        return event;
    }

    private void broadcast(ObjectNode event) {
        clients.forEach(client -> send(client, event));
    }

    private static void send(SocketChannel client, ObjectNode message) {
        byte[] bytes = (JSON.writeValueAsString(message) + "\n").getBytes(StandardCharsets.UTF_8);
        synchronized (client) {
            try {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    client.write(buffer);
                }
            } catch (IOException gone) {
                // client closed
            }
        }
    }

    /** Fake mpv executable. Environment: FAKE_MPV_LOG, FAKE_MPV_DEVICES, FAKE_MPV_DURATION, FAKE_MPV_TITLE,
     *  FAKE_MPV_FAIL_URLS_CONTAINING, FAKE_MPV_START_DELAY_MS, FAKE_MPV_EXIT_AT_START ("code:stderr text"), FAKE_MPV_IGNORE_TERM=1. */
    public static void main(String[] args) throws Exception {
        Map<String, String> env = System.getenv();
        List<String> argv = List.of(args);
        Path logFile = env.containsKey("FAKE_MPV_LOG") ? Path.of(env.get("FAKE_MPV_LOG")) : null;
        Consumer<String> log = line -> appendLine(logFile, line);
        if (argv.contains("--version")) {
            System.out.println(VERSION);
            return;
        }
        Options options = Options.fromEnvironment(env);
        if (argv.contains("--audio-device=help")) {
            System.out.print(audioDeviceHelp(options.audioDevices()));
            return;
        }
        log.accept(JSON.writeValueAsString(Map.of("type", "start", "pid", ProcessHandle.current().pid(), "args", argv)));
        String exitAtStart = env.get("FAKE_MPV_EXIT_AT_START");
        if (exitAtStart != null) {
            String[] parts = exitAtStart.split(":", 2);
            System.err.println(parts.length > 1 ? parts[1] : "fake failure");
            System.exit(Integer.parseInt(parts[0]));
        }
        if ("1".equals(env.get("FAKE_MPV_IGNORE_TERM"))) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    Thread.sleep(60_000);
                } catch (InterruptedException ignored) {
                }
            }));
        }
        Thread.sleep(Long.parseLong(env.getOrDefault("FAKE_MPV_START_DELAY_MS", "0")));
        Path socket = Path.of(value(argv, "--input-ipc-server="));
        String volume = value(argv, "--volume=");
        try (FakeMpv fake = serve(socket, options, volume == null ? 100 : Double.parseDouble(volume), log)) {
            fake.awaitQuit();
        }
        System.exit(0);
    }

    private static String value(List<String> argv, String prefix) {
        return argv.stream().filter(argument -> argument.startsWith(prefix)).map(argument -> argument.substring(prefix.length()))
                .findFirst().orElse(null);
    }

    private static void appendLine(Path file, String line) {
        if (file == null) {
            return;
        }
        synchronized (FakeMpv.class) {
            try {
                Files.writeString(file, line + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException ignored) {
            }
        }
    }
}
