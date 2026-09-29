package dev.andre.homecontrol.device;

import dev.andre.homecontrol.core.Action;
import dev.andre.homecontrol.core.Capability;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.core.DeviceStatus;
import dev.andre.homecontrol.core.RemoteKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class DevicesTest {

    @TempDir
    Path dir;

    @Test
    void assembleWiresOneConnectionMapForQueriesCommandsAndEnrollment() {
        StubAdapter stub = new StubAdapter("stub", DeviceKind.ANDROID_TV, false, false, Capability.REMOTE_KEYS);
        List<Object> published = new CopyOnWriteArrayList<>();
        Devices devices = Devices.assemble(new JsonFileDeviceRegistry(dir.resolve("devices.json")), List.of(stub),
                published::add);
        Action pressHome = new Action.PressKey(RemoteKey.HOME);

        devices.enrollment().adopt(new Device("tv", "TV", DeviceKind.ANDROID_TV, "10.0.0.5",
                Map.of("stub", Map.of()), Instant.EPOCH));
        devices.commands().execute("tv", pressHome);

        assertThat(devices.queries().state("tv").status()).isEqualTo(DeviceStatus.CONNECTED);
        assertThat(stub.handles.get("tv").executed).containsExactly(pressHome);
        devices.connections().closeAll();
        assertThat(stub.handles.get("tv").closed).isTrue();
    }
}
