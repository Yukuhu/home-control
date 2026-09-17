package dev.andre.homecontrol.e2e;

import dev.andre.homecontrol.content.RailCache;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.device.DeviceManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.util.Map;

/**
 * The real application on a random port with fake devices and a fake content source.
 *
 * <p>{@code @DirtiesContext} at the class level: every subclass here has an identical
 * {@code @SpringBootTest}/{@code @Import} configuration and inherits the very same
 * {@link #isolatedDataDirectory} method, so without this Spring's context cache treats them as
 * interchangeable and reuses ONE application context (and so one data directory, one
 * {@link FakeContentSource}, one {@link FakeDeviceAdapter}) across every subclass — silently
 * defeating the per-class isolation {@link #isolatedDataDirectory} is here to provide, and
 * letting e.g. a healed rail in one test class leak into another's assertions. Evicting the
 * context after each class forces a fresh one (and a fresh temp directory) per subclass.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(E2eFakesConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class E2eApplicationTest {

    @DynamicPropertySource
    static void isolatedDataDirectory(DynamicPropertyRegistry registry) throws IOException {
        String dir = Files.createTempDirectory("home-control-e2e").toString();
        registry.add("shield.data-dir", () -> dir);
    }

    @LocalServerPort
    protected int port;

    @Autowired
    protected DeviceManager devices;

    @Autowired
    protected FakeDeviceAdapter fakeDevices;

    @Autowired
    protected FakeContentSource fakeContent;

    @Autowired
    protected RailCache rails;

    protected String traceName;

    @BeforeEach
    void devicesAndContent(TestInfo info) {
        traceName = info.getTestClass().map(Class::getSimpleName).orElse("e2e") + "-"
                + info.getTestMethod().map(m -> m.getName()).orElse("test");
        adopt("living", "Living Room", "APP_LINK,REMOTE_KEYS", "");
        adopt("bedroom", "Bedroom", "APP_LINK,CAST_RECEIVER,REMOTE_KEYS", "OpenAppLink");
        adopt("speaker", "Speaker", "", "");
        fakeContent.breakFlaky();
        fakeDevices.clear();
    }

    @AfterEach
    void cleanUp() {
        for (String id : new String[] {"living", "bedroom", "speaker"}) {
            devices.forget(id);
        }
        fakeDevices.clear();
    }

    protected void adopt(String id, String name, String caps, String fail) {
        devices.adopt(new Device(id, name, DeviceKind.ANDROID_TV, "127.0.0.1",
                Map.of("e2e-fake", Map.of("caps", caps, "fail", fail)), Instant.now()));
    }

    protected String baseUrl() {
        return "http://localhost:" + port;
    }

    protected BrowserSession open(String browser) {
        return Browsers.open(browser, baseUrl(), traceName);
    }
}
