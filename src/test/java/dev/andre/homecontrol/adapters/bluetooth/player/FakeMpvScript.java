package dev.andre.homecontrol.adapters.bluetooth.player;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;

/** Writes an executable "mpv" that runs FakeMpv in a child JVM with the given environment. */
public final class FakeMpvScript {

    private FakeMpvScript() {
    }

    public static Path create(Path directory, Map<String, String> environment) throws IOException {
        String java = ProcessHandle.current().info().command().orElseThrow();
        String classpath = System.getProperty("home-control.test.runtime-classpath", System.getProperty("java.class.path"));
        StringBuilder script = new StringBuilder("#!/bin/sh\n");
        environment.forEach((name, value) -> script.append("export ").append(name).append('=').append(quote(value)).append('\n'));
        // Container diagnostics must not become fake mpv protocol/version output.
        script.append("exec ").append(quote(java)).append(" -Xlog:os+container=off -XX:TieredStopAtLevel=1 -cp ").append(quote(classpath))
                .append(' ').append(FakeMpv.class.getName()).append(" \"$@\"\n");
        Files.createDirectories(directory);
        Path file = directory.resolve("mpv");
        Files.writeString(file, script.toString());
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
        return file;
    }

    /** The JSON lines FakeMpv wrote to FAKE_MPV_LOG. */
    public static List<JsonNode> log(Path logFile) throws IOException {
        JsonMapper json = JsonMapper.builder().build();
        return Files.exists(logFile)
                ? Files.readAllLines(logFile).stream().filter(line -> !line.isBlank()).map(json::readTree).toList()
                : List.of();
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }
}
