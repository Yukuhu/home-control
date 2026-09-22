package dev.andre.homecontrol.web;

import dev.andre.homecontrol.content.RailCache;
import dev.andre.homecontrol.content.RailStatus;
import dev.andre.homecontrol.core.*;
import dev.andre.homecontrol.device.DeviceManager;
import dev.andre.homecontrol.security.LoginService;
import dev.andre.homecontrol.sources.workflows.FakeWorkflowServer;
import dev.andre.homecontrol.sources.workflows.WorkflowStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The real HTTP, login, rail and playback boundary with only the receiver and upstream replaced. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(WorkflowEndToEndTest.RecordingDevices.class)
class WorkflowEndToEndTest {
    private static final String PASSWORD = "workflow household password";
    private static final String TOKEN = "secret-upstream-token-1";
    private static final String NEW_TOKEN = "secret-upstream-token-2";
    private static final String HEADER = "secret-header-value";
    private static final String URL_SECRET = "secret-source-query";
    private static final String TEMPLATE_SECRET = "secret-template-query";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws IOException {
        String directory = Files.createTempDirectory("workflow-web-e2e").toString();
        registry.add("shield.data-dir", () -> directory);
        registry.add("home-control.workflows.allow-loopback", () -> "true");
        registry.add("home-control.ssdp.enabled", () -> "false");
    }

    @TestConfiguration
    static class RecordingDevices {
        @Bean RecordingAdapter workflowRecordingAdapter() { return new RecordingAdapter(); }
    }

    static final class RecordingAdapter implements DeviceAdapter {
        record Recorded(String deviceId, Action action) {}
        final List<Recorded> actions = new CopyOnWriteArrayList<>();
        @Override public String id() { return "workflow-test"; }
        @Override public DeviceKind kind() { return DeviceKind.ANDROID_TV; }
        @Override public Set<Capability> capabilities(Device device) {
            return "true".equals(device.adapterSettings(id()).get("cast"))
                    ? Set.of(Capability.CAST_RECEIVER) : Set.of(Capability.REMOTE_KEYS);
        }
        @Override public DeviceHandle connect(Device device, Consumer<DeviceState> changed) {
            DeviceState state = new DeviceState(DeviceStatus.CONNECTED, true, "", 0, 0, false, Instant.now());
            changed.accept(state);
            return new DeviceHandle() {
                @Override public DeviceState state() { return state; }
                @Override public void execute(Action action) { actions.add(new Recorded(device.id(), action)); }
                @Override public void close() {}
            };
        }
        @Override public List<DiscoveredDevice> discovered() { return List.of(); }
        List<Action> recorded(String id) { return actions.stream().filter(a -> a.deviceId.equals(id)).map(Recorded::action).toList(); }
        void clear() { actions.clear(); }
    }

    @Autowired MockMvc mvc;
    @Autowired DeviceManager devices;
    @Autowired RecordingAdapter fakeDevices;
    @Autowired RailCache rails;
    @Autowired WorkflowStore workflows;
    @Autowired LoginService login;
    FakeWorkflowServer upstream;
    String deviceId = "workflow-cast";
    MockHttpSession session;

    @BeforeEach void setup() throws IOException {
        upstream = new FakeWorkflowServer();
        adopt(deviceId, true);
        adopt("workflow-no-cast", false);
        fakeDevices.clear();
    }

    @AfterEach void cleanup() {
        try {
            if (!workflows.all().isEmpty()) {
                var request = new MockHttpServletRequest();
                assertThat(login.authenticate(PASSWORD, request)).isTrue();
                for (var definition : workflows.all()) workflows.remove(definition.id(), definition.revision(), request);
            }
        } finally {
            devices.forget(deviceId);
            devices.forget("workflow-no-cast");
            fakeDevices.clear();
            upstream.close();
        }
    }

    private void adopt(String id, boolean cast) {
        devices.adopt(new Device(id, id, DeviceKind.ANDROID_TV, "127.0.0.1",
                Map.of("workflow-test", Map.of("cast", Boolean.toString(cast))), Instant.now()));
    }

    private MockHttpServletRequestBuilder create(String mode) {
        var builder = post("/setup/workflows")
                .header("User-Agent", "Chromium")
                .header("Sec-CH-UA-Platform", "Linux")
                .param("name", mode.equals("SINGLE") ? "Single News" : "Generated News")
                .param("enabled", "true").param("mode", mode).param("kind", "VIDEO")
                .param("urlMode", "REPLACE").param("url", upstream.url("/feed?key=" + URL_SECRET).toString())
                .param("templateMode", "REPLACE")
                .param("template", upstream.url("/media/" + TEMPLATE_SECRET).toString() + "?id={A}&token={C}")
                .param("headersMode", "REPLACE").param("headers[0].name", "Authorization")
                .param("headers[0].value", HEADER).param("mimeType", "video/mp4")
                .param("variables[0].name", "A").param("variables[0].scope", mode.equals("SINGLE") ? "ROOT" : "ENTRY")
                .param("variables[0].pointer", "/id")
                .param("variables[1].name", "C").param("variables[1].scope", "ROOT")
                .param("variables[1].pointer", "/auth/token").param("variables[1].sensitive", "true")
                .param("loginPassword", PASSWORD).param("loginPasswordConfirmation", PASSWORD);
        if (mode.equals("SINGLE")) builder.param("title", "News");
        else builder.param("arrayPointer", "/channels").param("idPointer", "/id").param("titlePointer", "/title");
        return builder;
    }

    private String save(String mode) throws Exception {
        var result = mvc.perform(create(mode)).andExpect(status().is3xxRedirection()).andReturn();
        session = (MockHttpSession) result.getRequest().getSession(false);
        assertThat(session).isNotNull();
        assertThat(login.isAuthenticated(session)).isTrue();
        assertThat(upstream.count("/feed")).isZero();
        return result.getResponse().getRedirectedUrl().substring("/setup/workflows/".length());
    }

    private String body(String path) throws Exception {
        return mvc.perform(get(path).session(session)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private void noSecrets(String body) {
        assertThat(body).doesNotContain(TOKEN, NEW_TOKEN, HEADER, URL_SECRET, TEMPLATE_SECRET);
    }

    private String loadedItem(String id) {
        await().untilAsserted(() -> assertThat(rails.snapshot("workflows", id).orElseThrow().status()).isEqualTo(RailStatus.READY));
        return rails.snapshot("workflows", id).orElseThrow().items().getFirst().id();
    }

    private Action.CastLoad onlyCast(String id) {
        assertThat(fakeDevices.recorded(id)).hasSize(1);
        return (Action.CastLoad) fakeDevices.recorded(id).getFirst();
    }

    @Test void firstSaveSingleTilePreviewTestAndPlay() throws Exception {
        upstream.respond("/feed", 200, "{\"id\":\"news\",\"auth\":{\"token\":\"" + TOKEN + "\"}}");
        String id = save("SINGLE");
        String itemId = loadedItem(id);
        assertThat(itemId).isEqualTo(id);
        assertThat(upstream.count("/feed")).isZero();
        noSecrets(body("/setup/workflows/" + id));
        noSecrets(body("/rails"));
        String preview = mvc.perform(get("/devices/" + deviceId + "/route-preview")
                        .param("source", "workflows").param("item", itemId).session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.route.key").value("workflow-cast"))
                .andReturn().getResponse().getContentAsString();
        noSecrets(preview);
        assertThat(upstream.count("/feed")).isZero();
        assertThat(fakeDevices.recorded(deviceId)).isEmpty();

        String tested = mvc.perform(post("/setup/workflows/" + id + "/test")
                        .param("expectedRevision", "1").session(session))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        noSecrets(tested);
        assertThat(tested).contains("Fetch JSON", "News", "Masked media address");
        assertThat(upstream.count("/feed")).isEqualTo(1);
        assertThat(fakeDevices.recorded(deviceId)).isEmpty();

        mvc.perform(post("/devices/" + deviceId + "/play-attempt")
                        .param("source", "workflows").param("item", itemId).session(session))
                .andExpect(status().isOk());
        assertThat(upstream.count("/feed")).isEqualTo(2);
        var cast = onlyCast(deviceId);
        assertThat(cast.receiverAppId()).isEqualTo("CC1AD845");
        assertThat(cast.load().toString()).contains("id=news", "token=" + TOKEN, "video/mp4", "News");
    }

    @Test void generatedTileKeepsIdentityAcrossReorderAndFreshToken() throws Exception {
        upstream.respond("/feed", 200, "{\"auth\":{\"token\":\"" + TOKEN + "\"},\"channels\":[{\"id\":\"news\",\"title\":\"News\"},{\"id\":\"music\",\"title\":\"Music\"}]}");
        String id = save("GENERATED");
        String itemId = loadedItem(id);
        assertThat(upstream.count("/feed")).isEqualTo(1);
        upstream.respond("/feed", 200, "{\"auth\":{\"token\":\"" + NEW_TOKEN + "\"},\"channels\":[{\"id\":\"music\",\"title\":\"Music\"},{\"id\":\"news\",\"title\":\"News\"}]}");
        String preview = mvc.perform(get("/devices/" + deviceId + "/route-preview")
                        .param("source", "workflows").param("item", itemId).session(session))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        noSecrets(preview);
        assertThat(upstream.count("/feed")).isEqualTo(1);
        mvc.perform(post("/devices/" + deviceId + "/play-attempt")
                        .param("source", "workflows").param("item", itemId).session(session))
                .andExpect(status().isOk());
        assertThat(upstream.count("/feed")).isEqualTo(2);
        assertThat(onlyCast(deviceId).load().toString()).contains("id=news", "token=" + NEW_TOKEN).doesNotContain("token=" + TOKEN);
    }

    @Test void fetchFailureDisappearedEntryAndWrongDeviceNeverCast() throws Exception {
        upstream.respond("/feed", 200, "{\"auth\":{\"token\":\"" + TOKEN + "\"},\"channels\":[{\"id\":\"news\",\"title\":\"News\"}]}");
        String id = save("GENERATED");
        String itemId = loadedItem(id);
        mvc.perform(post("/devices/workflow-no-cast/play-attempt")
                        .param("source", "workflows").param("item", itemId).session(session))
                .andExpect(status().isUnprocessableContent());
        assertThat(upstream.count("/feed")).isEqualTo(1);
        assertThat(fakeDevices.recorded("workflow-no-cast")).isEmpty();

        upstream.respond("/feed", 503, "{\"secret\":\"" + TOKEN + "\"}");
        String failed = mvc.perform(post("/devices/" + deviceId + "/play-attempt")
                        .param("source", "workflows").param("item", itemId).session(session))
                .andExpect(status().isBadGateway()).andReturn().getResponse().getContentAsString();
        noSecrets(failed);
        assertThat(fakeDevices.recorded(deviceId)).isEmpty();
        upstream.respond("/feed", 200, "{\"auth\":{\"token\":\"" + NEW_TOKEN + "\"},\"channels\":[{\"id\":\"other\",\"title\":\"Other\"}]}");
        String gone = mvc.perform(post("/devices/" + deviceId + "/play-attempt")
                        .param("source", "workflows").param("item", itemId).session(session))
                .andExpect(status().isBadGateway()).andReturn().getResponse().getContentAsString();
        noSecrets(gone);
        assertThat(fakeDevices.recorded(deviceId)).isEmpty();
        assertThat(upstream.count("/feed")).isEqualTo(3);
    }
}
