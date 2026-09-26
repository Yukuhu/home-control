package dev.andre.homecontrol.sources.workflows;

import dev.andre.homecontrol.core.content.ContentChangedEvent;
import dev.andre.homecontrol.security.Argon2PasswordHasher;
import dev.andre.homecontrol.security.LoginRequiredException;
import dev.andre.homecontrol.security.LoginService;
import dev.andre.homecontrol.storage.SecretKeySource;
import dev.andre.homecontrol.storage.SecretStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowStoreTest {
    private static final String PASSWORD = "long-enough-password";
    private static final URI SOURCE = URI.create("https://api.example/feed?private-marker=secret-value");

    @TempDir Path dir;
    SecretStore secrets;
    LoginService login;
    WorkflowStore workflows;
    MockHttpServletRequest request;
    List<Object> events;

    @BeforeEach void setUp() {
        SecureRandom random = new SecureRandom();
        secrets = new SecretStore(dir.resolve("secrets.json"),
                new SecretKeySource(null, dir.resolve("secret.key"), random), random);
        login = new LoginService(secrets, new Argon2PasswordHasher(random), random);
        request = new MockHttpServletRequest();
        events = new ArrayList<>();
        workflows = new WorkflowStore(secrets, login, new WorkflowCodec(), events::add, random);
    }

    private WorkflowDraft draft() { return WorkflowFixtures.single(SOURCE); }

    private WorkflowDefinition first() {
        return workflows.create(draft(), PASSWORD, PASSWORD, request);
    }

    @Test void firstSaveValidatesPasswordAndEncryptsWithoutFetching() throws Exception {
        assertThatThrownBy(() -> workflows.create(draft(), "short", "short", request))
                .hasMessageContaining("10 characters");
        assertThat(secrets.hasSecrets()).isFalse();
        assertThat(events).isEmpty();

        WorkflowDefinition saved = first();
        assertThat(saved.id()).matches("w-[0-9a-f]{12}");
        assertThat(saved.revision()).isEqualTo(1);
        assertThat(login.isAuthenticated(request)).isTrue();
        assertThat(workflows.all()).containsExactly(saved);
        assertThat(events).containsExactly(new ContentChangedEvent("workflows"));
        String file = Files.readString(dir.resolve("secrets.json"));
        assertThat(file).doesNotContain("api.example", "private-marker", "secret-value", "saved-secret", "media.example");
    }

    @Test void editsRequireAuthenticationAndRejectStaleRevisionsWithoutEvents() {
        WorkflowDefinition saved = first();
        int eventCount = events.size();
        var preparedArg80_0 = saved.id();
        var preparedArg80_1 = saved.revision();
        var preparedArg80_2 = draft();
        var preparedArg80_3 = new MockHttpServletRequest();
        assertThatThrownBy(() -> workflows.update(preparedArg80_0, preparedArg80_1, preparedArg80_2, preparedArg80_3))
                .isInstanceOf(LoginRequiredException.class);
        var preparedArg82_0 = saved.id();
        var preparedArg82_1 = saved.revision();
        var preparedArg82_3 = new MockHttpServletRequest();
        assertThatThrownBy(() -> workflows.setEnabled(preparedArg82_0, preparedArg82_1, false, preparedArg82_3))
                .isInstanceOf(LoginRequiredException.class);
        var preparedArg84_0 = saved.id();
        var preparedArg84_1 = saved.revision();
        var preparedArg84_2 = new MockHttpServletRequest();
        assertThatThrownBy(() -> workflows.remove(preparedArg84_0, preparedArg84_1, preparedArg84_2))
                .isInstanceOf(LoginRequiredException.class);

        WorkflowDefinition edited = workflows.update(saved.id(), saved.revision(), draft(), request);
        assertThat(edited.revision()).isEqualTo(2);
        assertThatThrownBy(() -> workflows.update(saved.id(), saved.revision(), draft(), request))
                .hasMessageContaining("changed");
        assertThat(workflows.find(saved.id())).contains(edited);
        assertThat(events).hasSize(eventCount + 1);
    }

    @Test void disablingAdvancesRevisionAndBlocksDispatch() {
        WorkflowDefinition saved = first();
        workflows.setEnabled(saved.id(), 1, false, request);
        WorkflowDefinition disabled = workflows.find(saved.id()).orElseThrow();
        assertThat(disabled.revision()).isEqualTo(2);
        assertThat(disabled.draft().enabled()).isFalse();
        assertThatThrownBy(() -> workflows.ifCurrent(saved.id(), 1, () -> {})).hasMessageContaining("changed");
        assertThatThrownBy(() -> workflows.ifCurrent(saved.id(), 2, () -> {})).hasMessageContaining("changed");
        assertThatThrownBy(() -> workflows.setEnabled(saved.id(), 1, true, request)).hasMessageContaining("changed");
    }

    @Test void restartReloadsOnlyWorkflowSecretsAndIsolatesCorruptValues() {
        WorkflowDefinition saved = first();
        String corruptId = "w-aaaaaaaaaaaa";
        String futureId = "w-bbbbbbbbbbbb";
        String mismatchId = "w-cccccccccccc";
        String malformedKey = "workflow.damaged-record";
        String future = new WorkflowCodec().encode(new WorkflowDefinition(1, futureId, 1, draft()))
                .replace("\"schemaVersion\":1", "\"schemaVersion\":99");
        secrets.putSecrets(Map.of("workflow." + corruptId, "{bad", "workflow." + futureId, future,
                "workflow." + mismatchId, new WorkflowCodec().encode(saved), malformedKey, "broken",
                "jellyfin.token", "another-source"));

        SecretStore reopened = new SecretStore(dir.resolve("secrets.json"),
                new SecretKeySource(null, dir.resolve("secret.key"), new SecureRandom()), new SecureRandom());
        WorkflowStore restored = new WorkflowStore(reopened,
                new LoginService(reopened, new Argon2PasswordHasher(new SecureRandom()), new SecureRandom()),
                new WorkflowCodec(), event -> {}, new SecureRandom());
        assertThat(restored.all()).containsExactly(saved);
        assertThat(restored.find(corruptId)).isEmpty();
        assertThat(restored.problems()).containsKeys(corruptId, futureId, mismatchId, "damaged-record")
                .hasSize(4);
        assertThat(restored.problems().values()).allSatisfy(message ->
                assertThat(message).doesNotContain("api.example", "saved-secret", "media.example", "{bad"));
    }

    @Test void readsUseTheStartupSnapshotWithoutFilesystemAccess() throws Exception {
        WorkflowDefinition saved = first();
        Files.delete(dir.resolve("secrets.json"));
        assertThat(workflows.all()).containsExactly(saved);
        assertThat(workflows.find(saved.id())).contains(saved);
        assertThat(workflows.problems()).isEmpty();
    }

    @Test void corruptDefinitionCanOnlyBeRemovedByAuthenticatedExplicitRecovery() {
        String key = "workflow.damaged-record";
        login.storeSecrets(Map.of(key, "{corrupt-payload", "jellyfin.token", "keep-me"), PASSWORD, PASSWORD, request);
        workflows = new WorkflowStore(secrets, login, new WorkflowCodec(), events::add, new SecureRandom());
        assertThat(workflows.problems()).containsKey("damaged-record");
        var preparedArg144_0 = draft();
        var preparedArg144_3 = new MockHttpServletRequest();
        assertThatThrownBy(() -> workflows.create(preparedArg144_0, null, null, preparedArg144_3))
                .isInstanceOf(LoginRequiredException.class);
        var preparedArg146_1 = new MockHttpServletRequest();
        assertThatThrownBy(() -> workflows.removeInvalid("damaged-record", preparedArg146_1))
                .isInstanceOf(LoginRequiredException.class);
        assertThat(secrets.secret(key)).contains("{corrupt-payload");
        assertThatThrownBy(() -> workflows.removeInvalid("not-displayed", request)).hasMessageContaining("Unknown workflow");
        workflows.removeInvalid("damaged-record", request);
        assertThat(secrets.secret(key)).isEmpty();
        assertThat(secrets.secret("jellyfin.token")).contains("keep-me");
        assertThat(workflows.problems()).isEmpty();
        assertThat(events).containsExactly(new ContentChangedEvent("workflows"));
    }

    @Test void deletingTheLastWorkflowKeepsOtherSourcesAndLogin() {
        WorkflowDefinition saved = first();
        secrets.putSecrets(Map.of("jellyfin.token", "other-source-token"));
        workflows.remove(saved.id(), saved.revision(), request);
        assertThat(workflows.find(saved.id())).isEmpty();
        assertThat(secrets.secret("jellyfin.token")).contains("other-source-token");
        assertThat(login.loginRequired()).isTrue();
        assertThatThrownBy(() -> workflows.update(saved.id(), saved.revision(), saved.draft(), request))
                .hasMessageContaining("changed");
    }

    @Test void capacityCountsCorruptKeysAndDoesNotPublishFailedCreate() {
        Map<String, String> stored = new HashMap<>();
        for (int i = 0; i < 50; i++) stored.put("workflow.w-%012x".formatted(i), "bad");
        login.storeSecrets(stored, PASSWORD, PASSWORD, request);
        workflows = new WorkflowStore(secrets, login, new WorkflowCodec(), events::add, new SecureRandom());
        assertThatThrownBy(() -> workflows.create(draft(), null, null, request)).hasMessageContaining("limit");
        assertThat(workflows.problems()).hasSize(50);
        assertThat(events).isEmpty();
    }

    @Test void randomIdCollisionIsRetried() {
        String occupiedId = "w-010101010101";
        login.storeSecrets(Map.of("workflow." + occupiedId, "bad"), PASSWORD, PASSWORD, request);
        SecureRandom collisionsThenUnique = new SecureRandom() {
            int calls;
            @Override public void nextBytes(byte[] bytes) { Arrays.fill(bytes, (byte) (++calls == 1 ? 1 : 2)); }
        };
        workflows = new WorkflowStore(secrets, login, new WorkflowCodec(), events::add, collisionsThenUnique);
        assertThat(workflows.create(draft(), null, null, request).id()).isEqualTo("w-020202020202");
        assertThat(secrets.secret("workflow." + occupiedId)).contains("bad");
    }

    @Test void failedEncodingLeavesSavedRevisionAndEventsAlone() {
        WorkflowDefinition saved = first();
        int eventCount = events.size();
        WorkflowDraft invalid = new WorkflowDraft(draft().name(), true, draft().mode(), draft().kind(),
                draft().fetch(), draft().listing(), draft().tile(), draft().variables(),
                new WorkflowDraft.Cast("https://media.example/too-bad/{Missing}", "video/mp4"));
        assertThatThrownBy(() -> workflows.update(saved.id(), saved.revision(), invalid, request));
        assertThat(workflows.find(saved.id())).contains(saved);
        assertThat(events).hasSize(eventCount);
    }

    @Test void failedEncryptedWritesLeaveSnapshotAndEventsAlone() {
        WorkflowDefinition saved = first();
        int eventCount = events.size();
        SecretStore refusing = new SecretStore(dir.resolve("secrets.json"),
                new SecretKeySource(null, dir.resolve("secret.key"), new SecureRandom()), new SecureRandom()) {
            @Override public synchronized void putSecrets(Map<String, String> values) {
                throw new IllegalStateException("simulated atomic write failure");
            }
            @Override public synchronized void removeSecrets(Collection<String> names) {
                throw new IllegalStateException("simulated atomic write failure");
            }
        };
        WorkflowStore refusingWorkflows = new WorkflowStore(refusing,
                new LoginService(refusing, new Argon2PasswordHasher(new SecureRandom()), new SecureRandom()),
                new WorkflowCodec(), events::add, new SecureRandom());
        assertThatThrownBy(() -> refusingWorkflows.update(saved.id(), 1, draft(), request))
                .hasMessageContaining("simulated atomic write failure");
        assertThatThrownBy(() -> refusingWorkflows.remove(saved.id(), 1, request))
                .hasMessageContaining("simulated atomic write failure");
        assertThat(refusingWorkflows.find(saved.id())).contains(saved);
        assertThat(refusing.secret("workflow." + saved.id())).isPresent();
        assertThat(events).hasSize(eventCount);
    }

    @Test void anEarlierEditPreventsStaleDispatchAndActiveDispatchDelaysItsEdit() throws Exception {
        WorkflowDefinition saved = first();
        WorkflowDefinition edited = workflows.update(saved.id(), 1, draft(), request);
        AtomicBoolean called = new AtomicBoolean();
        assertThatThrownBy(() -> workflows.ifCurrent(saved.id(), 1, () -> called.set(true)))
                .hasMessageContaining("changed");
        assertThat(called).isFalse();

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch editStarted = new CountDownLatch(1);
        AtomicBoolean editFinished = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var dispatch = executor.submit(() -> workflows.ifCurrent(saved.id(), edited.revision(), () -> {
                entered.countDown();
                try { assertThat(release.await(5, TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
            }));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            var edit = executor.submit(() -> {
                editStarted.countDown();
                try { workflows.update(saved.id(), edited.revision(), draft(), request); editFinished.set(true); }
                catch (Throwable e) { failure.set(e); }
            });
            try {
                assertThat(editStarted.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> edit.get(100, TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
                assertThat(editFinished).isFalse();
            } finally {
                release.countDown();
            }
            dispatch.get(5, TimeUnit.SECONDS);
            edit.get(5, TimeUnit.SECONDS);
        }
        assertThat(failure.get()).isNull();
        assertThat(editFinished).isTrue();
        assertThat(workflows.find(saved.id()).orElseThrow().revision()).isEqualTo(3);
    }

    @Test void dispatchForOneWorkflowDoesNotBlockAnUnrelatedEdit() throws Exception {
        SecureRandom ids = new SecureRandom() {
            int calls;
            @Override public void nextBytes(byte[] bytes) { Arrays.fill(bytes, (byte) ++calls); }
        };
        workflows = new WorkflowStore(secrets, login, new WorkflowCodec(), events::add, ids);
        WorkflowDefinition first = first();
        WorkflowDefinition second = workflows.create(draft(), null, null, request);
        assertThat(first.id()).isEqualTo("w-010101010101");
        assertThat(second.id()).isEqualTo("w-020202020202");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var dispatch = executor.submit(() -> workflows.ifCurrent(first.id(), 1, () -> {
                entered.countDown();
                try { assertThat(release.await(5, TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
            }));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            try {
                WorkflowDefinition edited = executor.submit(() ->
                        workflows.update(second.id(), 1, draft(), request)).get(2, TimeUnit.SECONDS);
                assertThat(edited.revision()).isEqualTo(2);
            } finally {
                release.countDown();
            }
            dispatch.get(5, TimeUnit.SECONDS);
        }
    }
}
