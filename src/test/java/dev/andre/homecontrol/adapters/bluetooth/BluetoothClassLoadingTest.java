package dev.andre.homecontrol.adapters.bluetooth;

import dev.andre.homecontrol.ContextSmoke;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Starts the real application in a fresh JVM and reads which classes it loaded. */
class BluetoothClassLoadingTest {

    @TempDir
    Path temp;

    @Test
    void aDisabledModuleLoadsNoDbusClass() throws Exception {
        List<String> loaded = loadedClasses("false");
        assertThat(loaded).noneMatch(line -> line.contains(" org.freedesktop.dbus.")
                || line.contains(" org.bluez.") || line.contains(" com.github.hypfvieh."));
        // BluetoothProperties may load (@ConfigurationPropertiesScan registers every properties record); nothing else may.
        assertThat(loaded).noneMatch(line -> line.contains(" dev.andre.homecontrol.adapters.bluetooth.")
                && !line.contains(" dev.andre.homecontrol.adapters.bluetooth.BluetoothProperties "));
    }

    @Test
    void theProbeSeesTheModuleWhenEnabled() throws Exception {
        List<String> loaded = loadedClasses("true");
        assertThat(loaded).anyMatch(line -> line.contains(" dev.andre.homecontrol.adapters.bluetooth.bluez.DbusBluezClient "));
    }

    private List<String> loadedClasses(String enabled) throws Exception {
        String java = ProcessHandle.current().info().command().orElseThrow();
        String classpath = System.getProperty("home-control.test.runtime-classpath");
        assertThat(classpath).as("home-control.test.runtime-classpath is set by build.gradle.kts").isNotBlank();
        Path output = temp.resolve("child-" + enabled + ".log");
        Process child = new ProcessBuilder(java, "-Xlog:os+container=off", "-Xlog:class+load=info",
                "-cp", classpath, ContextSmoke.class.getName(),
                "--server.port=0",
                "--shield.data-dir=" + temp.resolve("data-" + enabled),
                "--shield.discovery-enabled=false",
                "--home-control.ssdp.enabled=false",
                "--home-control.bluetooth.enabled=" + enabled,
                "--home-control.bluetooth.dbus-address=unix:path=" + temp.resolve("no-bus.sock"),
                "--home-control.bluetooth.runtime-dir=" + temp.resolve("runtime-" + enabled))
                .redirectErrorStream(true)
                .redirectOutput(output.toFile())
                .start();
        assertThat(child.waitFor(3, TimeUnit.MINUTES)).as("the child application finished").isTrue();
        List<String> lines = Files.readAllLines(output);
        assertThat(lines).as("child output ends with CONTEXT-OK").anyMatch(line -> line.contains("CONTEXT-OK"));
        assertThat(child.exitValue()).isZero();
        return lines.stream().filter(line -> line.contains("[class,load]")).toList();
    }
}
