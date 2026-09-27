# Shield Remote v0.3 Reliable Appliance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship v0.3 as a reliable CasaOS appliance whose pairing survives container replacement, whose startup never invents a replacement credential, and whose UI no longer exposes the unreliable app launcher.

**Architecture:** Keep the existing Spring Boot/Thymeleaf/htmx/SSE stack and make persistence a small explicit boundary around `/data`. Pairing is the only flow allowed to create a client credential; ordinary session startup performs a load-only join between the registry record and certificate alias. A CasaOS-specific Compose manifest supplies the durable bind mount and host networking, while the Remote v2 handshake advertises only the features the application still implements.

**Tech Stack:** Java 25, Spring Boot 4.1.1, Gradle 9.7.1, Thymeleaf, htmx, vanilla JavaScript, protobuf 4.36.0, PKCS12, SnakeYAML, JUnit 5, AssertJ, Mockito, Awaitility, Docker Compose, CasaOS.

**Spec:** `docs/superpowers/specs/2026-08-30-shield-remote-vnext-design.md` — implement section 4 only; sections 5 and 6 are later releases.

## Global Constraints

- Pairing through Android TV Remote v2 remains the only Shield-side setup; ADB and developer mode remain out of scope.
- The device solution must remain plug-and-play across NVIDIA Shield models and must not rely on package-specific deep-link databases.
- Preserve the live foreground-package indicator even though all app-launching controls and code are removed.
- Keep Spring Boot, Thymeleaf, htmx, SSE, and vanilla JavaScript; do not add a Node production build or a frontend framework.
- Continue rejecting commands that cannot be sent immediately; never queue or replay them.
- Keep the ordinary Compose bind `./data:/data` and add a separate CasaOS bind `/DATA/AppData/$AppID/data:/data`.
- CasaOS must use host networking, `ghcr.io/yukuhu/home-control:latest`, restart `unless-stopped`, and web UI port `8080`.
- Do not set `SHIELD_KEYSTORE_PASSWORD` in the CasaOS manifest; the stable application default is `shield` unless an advanced user supplies a stable override.
- A missing credential is `UNPAIRED`; a wrong keystore password or unreadable store is a named persistence failure, never an empty store.
- Forgetting a device removes only that device's registry record and certificate alias and tells the user that pairing will be required again.
- v0.4 gestures/PWA and v0.5 text input are explicitly outside this plan.

---

## File Structure Map

### Files to create

- `src/main/java/dev/andre/shield/storage/StorageException.java` — one runtime exception for persistence failures that must retain path and cause context.
- `src/main/java/dev/andre/shield/storage/DataDirectory.java` — writable-directory preflight used before pairing opens a socket or creates a credential.
- `src/test/java/dev/andre/shield/storage/DataDirectoryTest.java` — cross-platform preflight tests using a regular file as the blocked directory.
- `src/test/java/dev/andre/shield/web/SetupControllerTest.java` — setup-page rendering of actionable storage failures.
- `src/test/java/dev/andre/shield/deployment/CasaOsManifestTest.java` — parses and asserts the CasaOS runtime contract.
- `casaos/docker-compose.yml` — first-class CasaOS install/update manifest.
- `casaos/icon.svg` — repository-owned vector icon referenced by CasaOS metadata.

### Files to modify

- `src/main/java/dev/andre/shield/ShieldConfiguration.java` — expose `DataDirectory` as a Spring bean.
- `src/main/java/dev/andre/shield/ShieldProperties.java` — remove the obsolete `appsFile()` path.
- `src/main/java/dev/andre/shield/device/PairingService.java` — preflight storage, then create/reuse a credential only for deliberate pairing.
- `src/main/java/dev/andre/shield/device/DeviceSessionManager.java` — load credentials without creating them and delete aliases on forget.
- `src/main/java/dev/andre/shield/device/DeviceSession.java` — remove app-link launching.
- `src/main/java/dev/andre/shield/device/DeviceState.java` — add a named `unpaired()` factory.
- `src/main/java/dev/andre/shield/device/JsonFileDeviceRegistry.java` — classify malformed/unreadable registry data as a path-bearing storage failure.
- `src/main/java/dev/andre/shield/protocol/CertificateStore.java` — classify password/permission failures and add targeted alias deletion.
- `src/main/java/dev/andre/shield/protocol/RemoteConnection.java` — remove app-link sending and replace `622` with named truthful feature bits.
- `src/main/java/dev/andre/shield/web/RemoteController.java` — retain remote/key routes while removing catalog dependencies and routes.
- `src/main/java/dev/andre/shield/web/SetupController.java` — show storage failures on the setup page.
- `src/main/resources/templates/remote.html` — retain current-app state while removing launcher controls and fragment.
- `src/main/resources/templates/setup.html` — explain the destructive pairing consequence of Forget.
- `src/main/resources/static/app.js` — retain SSE current-app updates while deleting catalog synchronization.
- `src/main/resources/static/app.css` — delete launcher-only styles.
- `src/test/java/dev/andre/shield/device/DeviceSessionManagerTest.java` — cover load-only startup, no connection, and targeted forgetting.
- `src/test/java/dev/andre/shield/device/PairingServiceTest.java` — inject the preflight boundary and cover an unwritable data path/orphaned credential reuse.
- `src/test/java/dev/andre/shield/device/JsonFileDeviceRegistryTest.java` — verify unreadable data is not treated as empty.
- `src/test/java/dev/andre/shield/protocol/CertificateStoreTest.java` — cover password mismatch and alias deletion.
- `src/test/java/dev/andre/shield/protocol/FakePairingServer.java` — expose accepted-connection count so preflight tests prove no pairing socket opened.
- `src/test/java/dev/andre/shield/protocol/FakeRemoteServer.java` — observe client feature masks and stop implementing app-link capture.
- `src/test/java/dev/andre/shield/protocol/RemoteConnectionTest.java` — assert the exact v0.3 feature mask and remove launch coverage.
- `src/test/java/dev/andre/shield/web/DeviceStateStreamEndToEndTest.java` — seed a real credential before adopting a test device.
- `src/test/java/dev/andre/shield/web/RemoteControllerTest.java` — verify removed routes have no handler while key routes remain.
- `src/test/java/dev/andre/shield/web/RemotePageTest.java` — verify current-app rendering and absence of launcher UI.
- `README.md` — document the honest feature surface, both persistent layouts, CasaOS upgrade behavior, and keystore-password meaning.

### Files to delete

- `src/main/java/dev/andre/shield/apps/AppCatalog.java`
- `src/main/java/dev/andre/shield/apps/AppEntry.java`
- `src/main/resources/default-apps.yaml`
- `src/test/java/dev/andre/shield/apps/AppCatalogTest.java`

The protobuf schema remains wire-compatible: `RemoteAppLinkLaunchRequest` stays in `remotemessage.proto`, but no production method constructs or sends it.

---

### Task 1: Remove the Unreliable App Launcher Surface

**Files:**
- Delete: `src/main/java/dev/andre/shield/apps/AppCatalog.java`
- Delete: `src/main/java/dev/andre/shield/apps/AppEntry.java`
- Delete: `src/main/resources/default-apps.yaml`
- Delete: `src/test/java/dev/andre/shield/apps/AppCatalogTest.java`
- Modify: `src/main/java/dev/andre/shield/ShieldProperties.java`
- Modify: `src/main/java/dev/andre/shield/web/RemoteController.java`
- Modify: `src/main/resources/templates/remote.html`
- Modify: `src/main/resources/static/app.js`
- Modify: `src/main/resources/static/app.css`
- Modify: `src/test/java/dev/andre/shield/web/RemoteControllerTest.java`
- Modify: `src/test/java/dev/andre/shield/web/RemotePageTest.java`
- Modify: `README.md`

**Interfaces:**
- Consumes: `DeviceSessionManager.state()`, `DeviceSessionManager.activeDevice()`, `DeviceSessionManager.active()`, `DeviceSession.sendKey(RemoteKey)`.
- Produces: `RemoteController(DeviceSessionManager)`, `GET /`, and `POST /key/{key}` only; `POST /apps/current` and `POST /apps/{id}/launch` have no handler.

- [ ] **Step 1: Replace launcher-positive web tests with launcher-absence tests**

In `RemotePageTest`, replace `rendersTheRemoteWithItsDpadAndAppGrid` with the following test. Remove `AppEntry` and `List<AppEntry>` setup from this test, but leave the existing `@MockitoBean AppCatalog apps` in place until Step 3 so the pre-change controller can still be constructed.

```java
@Test
void rendersTheRemoteWithCurrentAppButWithoutLauncherControls() throws Exception {
    given(sessions.state()).willReturn(new DeviceState(
            DeviceStatus.CONNECTED, true, "com.netflix.ninja", 12, 100, false, Instant.now()));
    given(sessions.activeDevice()).willReturn(Optional.empty());

    mockMvc.perform(get("/"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("/key/DPAD_UP")))
            .andExpect(content().string(containsString("com.netflix.ninja")))
            .andExpect(content().string(not(containsString("/apps/"))))
            .andExpect(content().string(not(containsString("Add current app"))))
            .andExpect(content().string(not(containsString("id=\"app-list\""))));
}
```

Add these static imports:

```java
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
```

In `RemoteControllerTest`, delete the four launcher behavior tests and add this route-removal test. Keep the existing `@MockitoBean AppCatalog apps` until Step 3.

```java
@Test
void removedLauncherEndpointsHaveNoHandler() throws Exception {
    mockMvc.perform(post("/apps/current")).andExpect(status().isNotFound());
    mockMvc.perform(post("/apps/netflix/launch")).andExpect(status().isNotFound());
}
```

- [ ] **Step 2: Run the web tests and observe the red state**

Run:

```bash
./gradlew test --tests '*RemotePageTest' --tests '*RemoteControllerTest'
```

Expected: FAIL because the page still contains `/apps/current`/`app-list`, and the current-app route still has a handler.

- [ ] **Step 3: Remove the catalog from the controller and configuration**

Replace `RemoteController` with:

```java
package dev.andre.shield.web;

import dev.andre.shield.device.DeviceOfflineException;
import dev.andre.shield.device.DeviceSession;
import dev.andre.shield.device.DeviceSessionManager;
import dev.andre.shield.device.DeviceState;
import dev.andre.shield.protocol.RemoteKey;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.Locale;

@Controller
public class RemoteController {

    private final DeviceSessionManager sessions;

    public RemoteController(DeviceSessionManager sessions) {
        this.sessions = sessions;
    }

    @GetMapping("/")
    public String remote(Model model) {
        DeviceState state = sessions.state();
        model.addAttribute("state", state);
        model.addAttribute("device", sessions.activeDevice().orElse(null));
        return "remote";
    }

    @PostMapping("/key/{key}")
    public ResponseEntity<Void> key(@PathVariable String key) {
        RemoteKey remoteKey;
        try {
            remoteKey = RemoteKey.valueOf(key.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
        session().sendKey(remoteKey);
        return ResponseEntity.noContent().build();
    }

    private DeviceSession session() {
        return sessions.active()
                .orElseThrow(() -> new DeviceOfflineException("No device is paired"));
    }

    @ExceptionHandler(DeviceOfflineException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public void offline() {
    }
}
```

Delete `appsFile()` from `ShieldProperties`:

```java
public Path devicesFile() {
    return dataDir.resolve("devices.json");
}
```

Delete the four catalog files listed in this task. Then remove `AppCatalog`, `AppEntry`, and their mock/imports from both web test classes.

- [ ] **Step 4: Remove launcher markup and retain the current-app indicator**

In `remote.html`, make the header exactly:

```html
<header>
    <span id="status" class="badge" th:classappend="${state.connected()} ? 'ok' : 'off'"
          th:text="${state.status()}">DISCONNECTED</span>
    <span id="current-app" th:text="${state.currentApp()} ?: 'Nothing playing'">Nothing playing</span>
    <span id="volume" th:text="${state.muted()} ? 'muted' : ('vol ' + ${state.volumeLevel()})">vol</span>
    <a th:href="@{/setup}">Setup</a>
</header>
```

Delete the complete `<section id="app-list" ...>` block at the bottom of `<main>`. Keep every D-pad, navigation, media, and volume button unchanged.

Replace `app.js` with the catalog-free state handling below:

```javascript
// Live device state. The server pushes on every change; a new tab gets the current
// state immediately on connect.
const source = new EventSource("/events");

source.addEventListener("state", (event) => {
    const state = JSON.parse(event.data);

    const status = document.getElementById("status");
    status.textContent = state.status;
    status.classList.toggle("ok", state.status === "CONNECTED");
    status.classList.toggle("off", state.status !== "CONNECTED");

    document.getElementById("current-app").textContent =
        state.currentApp || "Nothing playing";
    document.getElementById("volume").textContent =
        state.muted ? "muted" : "vol " + state.volumeLevel;
});

function showOfflineToast() {
    const toast = document.getElementById("toast");
    toast.textContent = "The device is not connected";
    toast.hidden = false;
    setTimeout(() => (toast.hidden = true), 2000);
}

document.body.addEventListener("htmx:responseError", showOfflineToast);

// Keyboard control for desktop use.
const KEYS = {
    ArrowUp: "DPAD_UP", ArrowDown: "DPAD_DOWN", ArrowLeft: "DPAD_LEFT",
    ArrowRight: "DPAD_RIGHT", Enter: "DPAD_CENTER", Backspace: "BACK",
    " ": "PLAY_PAUSE", h: "HOME", m: "VOLUME_MUTE",
};

document.addEventListener("keydown", (event) => {
    if (event.target.tagName === "INPUT") return;
    const key = KEYS[event.key];
    if (!key) return;
    event.preventDefault();
    fetch("/key/" + key, { method: "POST" })
        .then((response) => {
            if (!response.ok) showOfflineToast();
        })
        .catch(showOfflineToast);
});
```

Delete only these launcher selectors from `app.css`; retain the general `button:disabled` rule for later connection-state work:

```css
.add-current-app { padding: .35rem .6rem; font-size: .8rem; white-space: nowrap; }
.apps { display: grid; grid-template-columns: repeat(auto-fill, minmax(7rem, 1fr)); gap: .5rem; }
```

- [ ] **Step 5: Remove launcher claims from the README**

Change the opening sentence to:

```markdown
A small Spring Boot web app that controls an NVIDIA Shield (or any Android TV device)
on the same network: a browser remote with live device state.
```

Replace the Pairing storage paragraphs with:

```markdown
The app stores its Remote v2 client certificate in `data/keystore.p12` and its
paired-device registry in `data/devices.json`. **The certificate is the pairing
credential** — losing it means the Shield must be paired again. Keep the whole
data directory mounted persistently and keep any custom keystore password stable.

The current foreground package remains visible in the remote header as connection
context. Remote v2 does not expose a reliable way to derive a launchable deep link
from that package, so the remote intentionally does not offer app shortcuts.
```

Change the configuration-table description for `shield.data-dir` to `Where the keystore and device registry live`.

- [ ] **Step 6: Run focused tests and scan the live source surface**

Run:

```bash
./gradlew test --tests '*RemotePageTest' --tests '*RemoteControllerTest'
rg -n 'AppCatalog|AppEntry|appsFile|default-apps|/apps/|Add current app|app-list' src/main src/test README.md
```

Expected: both test classes PASS; `rg` exits with status 1 and prints no matches.

- [ ] **Step 7: Commit the launcher removal**

```bash
git add README.md \
  src/main/java/dev/andre/shield/ShieldProperties.java \
  src/main/java/dev/andre/shield/apps/AppCatalog.java \
  src/main/java/dev/andre/shield/apps/AppEntry.java \
  src/main/java/dev/andre/shield/web/RemoteController.java \
  src/main/resources/default-apps.yaml \
  src/main/resources/templates/remote.html \
  src/main/resources/static/app.js \
  src/main/resources/static/app.css \
  src/test/java/dev/andre/shield/apps/AppCatalogTest.java \
  src/test/java/dev/andre/shield/web/RemoteControllerTest.java \
  src/test/java/dev/andre/shield/web/RemotePageTest.java
git commit -m "feat: remove unreliable app launcher"
```

---

### Task 2: Advertise Only Implemented Remote v2 Features

**Files:**
- Modify: `src/main/java/dev/andre/shield/protocol/RemoteConnection.java`
- Modify: `src/main/java/dev/andre/shield/device/DeviceSession.java`
- Modify: `src/test/java/dev/andre/shield/protocol/FakeRemoteServer.java`
- Modify: `src/test/java/dev/andre/shield/protocol/RemoteConnectionTest.java`

**Interfaces:**
- Consumes: generated protobuf `RemoteConfigure`, `RemoteSetActive`, key/power/volume messages, and IME receive events.
- Produces: `RemoteConnection.sendKey(RemoteKey)` and a client feature mask of `102` (`2 | 4 | 32 | 64`); no domain or protocol app-launch method.

- [ ] **Step 1: Make the fake server record both client handshake masks**

In `FakeRemoteServer`, add fields and accessors:

```java
private final AtomicInteger clientConfigureFeatures = new AtomicInteger(-1);
private final AtomicInteger clientActiveFeatures = new AtomicInteger(-1);

public int clientConfigureFeatures() {
    return clientConfigureFeatures.get();
}

public int clientActiveFeatures() {
    return clientActiveFeatures.get();
}
```

Update the handshake branches in `handle`:

```java
if (message.hasRemoteConfigure()) {
    clientConfigureFeatures.set(message.getRemoteConfigure().getCode1());
    stream.write(RemoteMessage.newBuilder()
            .setRemoteSetActive(RemoteSetActive.newBuilder().setActive(622)).build());
} else if (message.hasRemoteSetActive()) {
    clientActiveFeatures.set(message.getRemoteSetActive().getActive());
    handshakeComplete.countDown();
```

Add `java.util.concurrent.atomic.AtomicInteger` if it is not already imported.

- [ ] **Step 2: Add the truthful-mask test and observe it fail**

Add this test to `RemoteConnectionTest`:

```java
@Test
void advertisesOnlyImplementedV03Features() {
    assertThat(device.clientConfigureFeatures()).isEqualTo(102);
    assertThat(device.clientActiveFeatures()).isEqualTo(102);
}
```

Run:

```bash
./gradlew test --tests '*RemoteConnectionTest.advertisesOnlyImplementedV03Features'
```

Expected: FAIL because both observed values are `622`.

- [ ] **Step 3: Replace the magic mask with named feature bits**

Replace `ACTIVE_CODE` in `RemoteConnection` with:

```java
private static final int FEATURE_KEY = 1 << 1;      // 2
private static final int FEATURE_IME_RECEIVE = 1 << 2; // 4, supplies current-app events
private static final int FEATURE_POWER = 1 << 5;    // 32
private static final int FEATURE_VOLUME = 1 << 6;   // 64
private static final int CLIENT_FEATURES = FEATURE_KEY
        | FEATURE_IME_RECEIVE
        | FEATURE_POWER
        | FEATURE_VOLUME;
```

Use `CLIENT_FEATURES` in both handshake responses:

```java
.setCode1(CLIENT_FEATURES)
```

```java
.setRemoteSetActive(RemoteSetActive.newBuilder().setActive(CLIENT_FEATURES))
```

This deliberately excludes voice bit `8` and app-link bit `512`.

- [ ] **Step 4: Remove the app-link protocol and domain methods**

Delete `RemoteConnection.launchAppLink(String)` and its `RemoteAppLinkLaunchRequest` import. Delete `DeviceSession.launchAppLink(String)`. In `RemoteConnectionTest`, delete `launchesAppLinks`. In `FakeRemoteServer`, delete the `appLinks` queue, `nextAppLink()`, and the `message.hasRemoteAppLinkLaunchRequest()` branch.

Do not edit `src/main/proto/remotemessage.proto`; retaining the message declaration preserves the upstream wire schema.

- [ ] **Step 5: Run protocol tests and scan production calls**

Run:

```bash
./gradlew test --tests '*RemoteConnectionTest' --tests '*DeviceSessionTest'
rg -n 'ACTIVE_CODE|launchAppLink|RemoteAppLinkLaunchRequest' src/main/java src/test/java
```

Expected: tests PASS; `rg` exits with status 1 and prints no matches.

- [ ] **Step 6: Commit the protocol cleanup**

```bash
git add src/main/java/dev/andre/shield/protocol/RemoteConnection.java \
  src/main/java/dev/andre/shield/device/DeviceSession.java \
  src/test/java/dev/andre/shield/protocol/FakeRemoteServer.java \
  src/test/java/dev/andre/shield/protocol/RemoteConnectionTest.java
git commit -m "fix: advertise only supported remote features"
```

---

### Task 3: Add a Path-Bearing Persistence Boundary and Pairing Preflight

**Files:**
- Create: `src/main/java/dev/andre/shield/storage/StorageException.java`
- Create: `src/main/java/dev/andre/shield/storage/DataDirectory.java`
- Create: `src/test/java/dev/andre/shield/storage/DataDirectoryTest.java`
- Create: `src/test/java/dev/andre/shield/web/SetupControllerTest.java`
- Modify: `src/main/java/dev/andre/shield/ShieldConfiguration.java`
- Modify: `src/main/java/dev/andre/shield/device/PairingService.java`
- Modify: `src/main/java/dev/andre/shield/device/JsonFileDeviceRegistry.java`
- Modify: `src/main/java/dev/andre/shield/protocol/CertificateStore.java`
- Modify: `src/main/java/dev/andre/shield/web/SetupController.java`
- Modify: `src/test/java/dev/andre/shield/device/PairingServiceTest.java`
- Modify: `src/test/java/dev/andre/shield/device/JsonFileDeviceRegistryTest.java`
- Modify: `src/test/java/dev/andre/shield/protocol/CertificateStoreTest.java`
- Modify: `src/test/java/dev/andre/shield/protocol/FakePairingServer.java`

**Interfaces:**
- Consumes: `ShieldProperties.dataDir()`, `PairingService.begin(...)`, existing PKCS12 and JSON persistence.
- Produces: `StorageException(String, Throwable)`, `DataDirectory(Path)`, `void DataDirectory.verifyWritable()`, and `PairingService(CertificateStore, DeviceSessionManager, DataDirectory)`.

- [ ] **Step 1: Write the data-directory preflight test**

Create `DataDirectoryTest`:

```java
package dev.andre.shield.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DataDirectoryTest {

    @TempDir
    Path dir;

    @Test
    void createsAndVerifiesAWritableDirectoryWithoutLeavingAProbe() throws Exception {
        Path data = dir.resolve("data");

        new DataDirectory(data).verifyWritable();

        assertThat(data).isDirectory();
        try (var files = Files.list(data)) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    void namesTheBlockedPathWhenTheDirectoryCannotBeCreated() throws Exception {
        Path blocked = dir.resolve("not-a-directory");
        Files.writeString(blocked, "occupied");

        assertThatThrownBy(() -> new DataDirectory(blocked).verifyWritable())
                .isInstanceOf(StorageException.class)
                .hasMessageContaining(blocked.toString())
                .hasMessageContaining("bind-mounted")
                .hasCauseInstanceOf(Exception.class);
    }
}
```

- [ ] **Step 2: Add store-corruption tests**

Add to `CertificateStoreTest`:

```java
@Test
void aWrongPasswordIsAStorageFailureNotAMissingAlias() {
    Path file = dir.resolve("keystore.p12");
    new CertificateStore(file, "correct".toCharArray()).loadOrCreate("shield");

    assertThatThrownBy(() -> new CertificateStore(file, "wrong".toCharArray()).load("shield"))
            .isInstanceOf(StorageException.class)
            .hasMessageContaining(file.toString())
            .hasMessageContaining("password");
}
```

Add imports for `dev.andre.shield.storage.StorageException` and AssertJ's `assertThatThrownBy`.

Add to `JsonFileDeviceRegistryTest`:

```java
@Test
void malformedRegistryIsAStorageFailureNotAnEmptyRegistry() throws Exception {
    Path file = dir.resolve("devices.json");
    java.nio.file.Files.writeString(file, "{not-json");

    assertThatThrownBy(() -> new JsonFileDeviceRegistry(file).findAll())
            .isInstanceOf(StorageException.class)
            .hasMessageContaining(file.toString())
            .hasMessageContaining("permissions");
}
```

Add imports for `StorageException` and `assertThatThrownBy`.

- [ ] **Step 3: Add a pairing preflight regression test**

First add an observation-only connection count to `FakePairingServer`:

```java
private final AtomicInteger connections = new AtomicInteger();

public int connections() {
    return connections.get();
}
```

Increment it immediately after the fake accepts a socket:

```java
try (SSLSocket socket = (SSLSocket) serverSocket.accept()) {
    connections.incrementAndGet();
```

Import `java.util.concurrent.atomic.AtomicInteger`.

In `PairingServiceTest`, retain the `CertificateStore` in a field and pass a `DataDirectory` in setup:

```java
private CertificateStore certificates;

@BeforeEach
void setUp() throws Exception {
    fakeDevice = new FakePairingServer();
    ShieldProperties properties = new ShieldProperties(dir, "shield", false, 10, 1, 4);
    sessions = mock(DeviceSessionManager.class);
    certificates = new CertificateStore(properties.keystoreFile(), "shield".toCharArray());
    service = new PairingService(certificates, sessions, new DataDirectory(dir));
}
```

Add:

```java
@Test
void blocksPairingBeforeCreatingACredentialWhenDataDirectoryIsUnwritable() throws Exception {
    Path blocked = dir.resolve("blocked");
    Files.writeString(blocked, "occupied");
    Path keystore = blocked.resolve("keystore.p12");
    PairingService blockedService = new PairingService(
            new CertificateStore(keystore, "shield".toCharArray()),
            sessions,
            new DataDirectory(blocked));

    assertThatThrownBy(() -> blockedService.begin(
            "127.0.0.1", fakeDevice.port(), "Living Room Shield"))
            .isInstanceOf(StorageException.class)
            .hasMessageContaining(blocked.toString());

    assertThat(Files.exists(keystore)).isFalse();
    assertThat(blockedService.inProgress()).isFalse();
    assertThat(fakeDevice.connections()).isZero();
}
```

Add imports for `DataDirectory`, `StorageException`, and `java.nio.file.Files`.

- [ ] **Step 4: Add the setup-page error test**

Create `SetupControllerTest`:

```java
package dev.andre.shield.web;

import dev.andre.shield.device.DeviceSessionManager;
import dev.andre.shield.device.PairingService;
import dev.andre.shield.discovery.MdnsDiscovery;
import dev.andre.shield.storage.StorageException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.AccessDeniedException;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(SetupController.class)
class SetupControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    MdnsDiscovery discovery;

    @MockitoBean
    PairingService pairing;

    @MockitoBean
    DeviceSessionManager sessions;

    @Test
    void showsAnActionableStorageErrorBeforePairing() throws Exception {
        given(discovery.devices()).willReturn(List.of());
        given(sessions.activeDevice()).willReturn(Optional.empty());
        willThrow(new StorageException(
                "Shield data directory is not writable: /data; check that /data is bind-mounted and writable",
                new AccessDeniedException("/data")))
                .given(pairing).begin("192.168.1.50", null);

        mockMvc.perform(post("/setup/pair").param("host", "192.168.1.50"))
                .andExpect(status().isOk())
                .andExpect(view().name("setup"))
                .andExpect(content().string(containsString("/data")))
                .andExpect(content().string(containsString("bind-mounted and writable")));
    }
}
```

- [ ] **Step 5: Run the new tests and observe the red state**

Run:

```bash
./gradlew test --tests '*DataDirectoryTest' --tests '*CertificateStoreTest' \
  --tests '*JsonFileDeviceRegistryTest' --tests '*PairingServiceTest' \
  --tests '*SetupControllerTest'
```

Expected: FAIL to compile because `DataDirectory` and `StorageException` do not exist and the pairing constructor has only two arguments.

- [ ] **Step 6: Implement the persistence types and Spring bean**

Create `StorageException`:

```java
package dev.andre.shield.storage;

public final class StorageException extends RuntimeException {

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

Create `DataDirectory`:

```java
package dev.andre.shield.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class DataDirectory {

    private final Path path;

    public DataDirectory(Path path) {
        this.path = path;
    }

    public void verifyWritable() {
        try {
            Files.createDirectories(path);
            Path probe = Files.createTempFile(path, ".shield-write-check-", ".tmp");
            Files.delete(probe);
        } catch (IOException e) {
            throw new StorageException(
                    "Shield data directory is not writable: " + path
                            + "; check that /data is bind-mounted and writable",
                    e);
        }
    }
}
```

Add this bean to `ShieldConfiguration`:

```java
@Bean
public DataDirectory dataDirectory(ShieldProperties properties) {
    return new DataDirectory(properties.dataDir());
}
```

Import `dev.andre.shield.storage.DataDirectory`.

- [ ] **Step 7: Preflight before certificate creation or network pairing**

In `PairingService`, add the field and replace the constructor:

```java
private final DataDirectory dataDirectory;

public PairingService(CertificateStore certificates, DeviceSessionManager sessions,
                      DataDirectory dataDirectory) {
    this.certificates = certificates;
    this.sessions = sessions;
    this.dataDirectory = dataDirectory;
}
```

At the start of the testable `begin` overload, keep cancellation first and put the preflight before identity derivation and `loadOrCreate`:

```java
public void begin(String host, int port, String name) throws IOException {
    cancel();
    dataDirectory.verifyWritable();
    String resolvedName = (name == null || name.isBlank()) ? host : name;
    String deviceId = deviceId(host);
    ClientCertificate credential = certificates.loadOrCreate(deviceId);
```

Import `dev.andre.shield.storage.DataDirectory`.

- [ ] **Step 8: Convert store failures to `StorageException`**

In `CertificateStore.load`, replace the catch body with:

```java
} catch (Exception e) {
    throw new StorageException(
            "Could not read keystore " + file
                    + "; check the keystore password and file permissions",
            e);
}
```

In `CertificateStore.save`, replace the catch body with:

```java
} catch (Exception e) {
    throw new StorageException(
            "Could not write keystore " + file + "; check file permissions",
            e);
}
```

Import `dev.andre.shield.storage.StorageException`.

In `JsonFileDeviceRegistry.findAll`, replace the catch body with:

```java
} catch (IOException | JacksonException e) {
    throw new StorageException(
            "Could not read device registry " + file
                    + "; check file permissions and JSON integrity",
            e);
}
```

In `writeAll`, replace the final throw with:

```java
throw new StorageException(
        "Could not write device registry " + file + "; check file permissions",
        e);
```

Import `dev.andre.shield.storage.StorageException`.

- [ ] **Step 9: Render storage failures through the setup controller**

Refactor `SetupController` to use this exact helper:

```java
private void populateSetupModel(Model model, boolean awaitingCode) {
    model.addAttribute("awaitingCode", awaitingCode);
    model.addAttribute("discovered", discovery.devices());
    model.addAttribute("paired", sessions.activeDevice().orElse(null));
}
```

Use it in `setup`:

```java
@GetMapping("/setup")
public String setup(Model model) {
    populateSetupModel(model, pairing.inProgress());
    return "setup";
}
```

Replace `pair` with:

```java
@PostMapping("/setup/pair")
public String pair(@RequestParam String host, @RequestParam(required = false) String name,
                   Model model) {
    try {
        pairing.begin(host, name);
        populateSetupModel(model, true);
    } catch (StorageException e) {
        model.addAttribute("error", e.getMessage());
        populateSetupModel(model, false);
    } catch (IOException e) {
        model.addAttribute("error", "Could not reach " + host + ": " + e.getMessage());
        populateSetupModel(model, false);
    }
    return "setup";
}
```

Replace `code` with:

```java
@PostMapping("/setup/code")
public String code(@RequestParam String code, Model model) {
    PairingResult result;
    try {
        result = pairing.submit(code);
    } catch (StorageException e) {
        model.addAttribute("error", e.getMessage());
        populateSetupModel(model, false);
        return "setup";
    }

    switch (result) {
        case PairingResult.Paired ignored -> {
            return "redirect:/";
        }
        case PairingResult.WrongCode ignored -> model.addAttribute("error",
                "That code was not accepted. The device will show a new one — start again.");
        case PairingResult.Failed failed -> model.addAttribute("error", failed.reason());
    }

    populateSetupModel(model, false);
    return "setup";
}
```

Import `dev.andre.shield.storage.StorageException`.

- [ ] **Step 10: Run the persistence and setup tests**

Run:

```bash
./gradlew test --tests '*DataDirectoryTest' --tests '*CertificateStoreTest' \
  --tests '*JsonFileDeviceRegistryTest' --tests '*PairingServiceTest' \
  --tests '*SetupControllerTest'
```

Expected: all selected tests PASS. The blocked-path pairing test must finish without waiting for the fake Shield because no connection is opened.

- [ ] **Step 11: Commit the persistence boundary**

```bash
git add src/main/java/dev/andre/shield/ShieldConfiguration.java \
  src/main/java/dev/andre/shield/storage/StorageException.java \
  src/main/java/dev/andre/shield/storage/DataDirectory.java \
  src/main/java/dev/andre/shield/device/PairingService.java \
  src/main/java/dev/andre/shield/device/JsonFileDeviceRegistry.java \
  src/main/java/dev/andre/shield/protocol/CertificateStore.java \
  src/main/java/dev/andre/shield/web/SetupController.java \
  src/test/java/dev/andre/shield/storage/DataDirectoryTest.java \
  src/test/java/dev/andre/shield/device/PairingServiceTest.java \
  src/test/java/dev/andre/shield/device/JsonFileDeviceRegistryTest.java \
  src/test/java/dev/andre/shield/protocol/CertificateStoreTest.java \
  src/test/java/dev/andre/shield/protocol/FakePairingServer.java \
  src/test/java/dev/andre/shield/web/SetupControllerTest.java
git commit -m "feat: validate persistent storage before pairing"
```

---

### Task 4: Make Registered-Device Startup Load-Only and Forgetting Complete

**Files:**
- Modify: `src/main/java/dev/andre/shield/device/DeviceState.java`
- Modify: `src/main/java/dev/andre/shield/device/DeviceSessionManager.java`
- Modify: `src/main/java/dev/andre/shield/protocol/CertificateStore.java`
- Modify: `src/main/resources/templates/setup.html`
- Modify: `src/test/java/dev/andre/shield/device/DeviceSessionManagerTest.java`
- Modify: `src/test/java/dev/andre/shield/device/PairingServiceTest.java`
- Modify: `src/test/java/dev/andre/shield/protocol/CertificateStoreTest.java`
- Modify: `src/test/java/dev/andre/shield/web/DeviceStateStreamEndToEndTest.java`
- Modify: `src/test/java/dev/andre/shield/web/RemotePageTest.java`

**Interfaces:**
- Consumes: `Optional<ClientCertificate> CertificateStore.load(String)`, `ClientCertificate CertificateStore.loadOrCreate(String)` only from deliberate pairing/test setup, `Device.certificateAlias()`.
- Produces: `void CertificateStore.delete(String alias)`, `DeviceState.unpaired()`, and load-only `DeviceSessionManager.startRegisteredDevices()` behavior.

- [ ] **Step 1: Add certificate alias-deletion coverage**

Add to `CertificateStoreTest`:

```java
@Test
void deletesOnlyTheRequestedAlias() {
    Path file = dir.resolve("keystore.p12");
    CertificateStore store = new CertificateStore(file, "secret".toCharArray());
    store.loadOrCreate("living-room");
    ClientCertificate bedroom = store.loadOrCreate("bedroom");

    store.delete("living-room");

    CertificateStore reopened = new CertificateStore(file, "secret".toCharArray());
    assertThat(reopened.load("living-room")).isEmpty();
    assertThat(reopened.load("bedroom")).get()
            .extracting(ClientCertificate::certificate)
            .isEqualTo(bedroom.certificate());
}
```

- [ ] **Step 2: Add registry-only startup and complete-forget tests**

Add these tests to `DeviceSessionManagerTest`:

```java
@Test
void registryOnlyDeviceIsUnpairedWithoutConnectionOrCredentialCreation() throws Exception {
    try (FakeRemoteServer remote = new FakeRemoteServer()) {
        DeviceRegistry registry = new JsonFileDeviceRegistry(dir.resolve("devices.json"));
        registry.save(new Device("shield-missing-key", "Shield", "127.0.0.1", remote.port(),
                null, Instant.now()));
        ShieldProperties properties = new ShieldProperties(dir, "shield", false, 10, 1, 4);
        CertificateStore certificates = new CertificateStore(
                properties.keystoreFile(), "shield".toCharArray());

        try (DeviceSessionManager manager = new DeviceSessionManager(
                registry, certificates, properties, event -> { })) {
            manager.startRegisteredDevices();

            assertThat(manager.state().status()).isEqualTo(DeviceStatus.UNPAIRED);
            assertThat(remote.connections()).isZero();
            assertThat(certificates.load("shield-missing-key")).isEmpty();
            assertThat(Files.exists(properties.keystoreFile())).isFalse();
        }
    }
}

@Test
void forgetDeletesTheRegistryRecordAndOnlyItsCredential() {
    DeviceRegistry registry = new JsonFileDeviceRegistry(dir.resolve("devices.json"));
    Device forgotten = new Device("shield-forgotten", "Shield", "127.0.0.1", 6466,
            null, Instant.now());
    registry.save(forgotten);
    ShieldProperties properties = new ShieldProperties(dir, "shield", false, 10, 1, 4);
    CertificateStore certificates = new CertificateStore(
            properties.keystoreFile(), "shield".toCharArray());
    certificates.loadOrCreate(forgotten.certificateAlias());
    certificates.loadOrCreate("keep-this-alias");

    try (DeviceSessionManager manager = new DeviceSessionManager(
            registry, certificates, properties, event -> { })) {
        manager.forget(forgotten.id());
    }

    assertThat(registry.findById(forgotten.id())).isEmpty();
    assertThat(certificates.load(forgotten.certificateAlias())).isEmpty();
    assertThat(certificates.load("keep-this-alias")).isPresent();
}
```

Import `java.nio.file.Files`.

Update `startsASessionOnlyForTheActiveDevice` so it seeds the active alias before manager construction:

```java
ShieldProperties properties = new ShieldProperties(dir, "shield", false, 10, 1, 4);
CertificateStore certificates = new CertificateStore(
        properties.keystoreFile(), "shield".toCharArray());
certificates.loadOrCreate("shield-current");
try (DeviceSessionManager manager = new DeviceSessionManager(registry,
        certificates, properties, event -> { })) {
```

- [ ] **Step 3: Add the orphaned-credential reuse regression**

Add to `PairingServiceTest`:

```java
@Test
void deliberateRepairReusesAnOrphanedCredentialForTheSameHost() throws Exception {
    ClientCertificate orphaned = certificates.loadOrCreate("127-0-0-1");

    service.begin("127.0.0.1", fakeDevice.port(), "Living Room Shield");
    PairingResult result = service.submit(fakeDevice.awaitDisplayedCode());

    assertThat(result).isInstanceOf(PairingResult.Paired.class);
    assertThat(certificates.load("127-0-0-1")).get()
            .extracting(ClientCertificate::certificate)
            .isEqualTo(orphaned.certificate());
}
```

Import `dev.andre.shield.protocol.ClientCertificate`.

- [ ] **Step 4: Add setup copy coverage and observe the red lifecycle tests**

In `RemotePageTest.rendersTheSetupPageWithDiscoveredDevicesAndManualEntry`, add:

```java
.andExpect(content().string(containsString("removes the stored pairing credential")))
.andExpect(content().string(containsString("pair again")));
```

Run:

```bash
./gradlew test --tests '*CertificateStoreTest.deletesOnlyTheRequestedAlias' \
  --tests '*DeviceSessionManagerTest' --tests '*PairingServiceTest.deliberateRepairReusesAnOrphanedCredentialForTheSameHost' \
  --tests '*RemotePageTest.rendersTheSetupPageWithDiscoveredDevicesAndManualEntry'
```

Expected: FAIL because alias deletion does not exist, startup creates a credential/connects, and the setup warning is absent. The orphaned-credential reuse test may already pass and protects the credential-present/registry-absent branch from regression.

- [ ] **Step 5: Implement targeted certificate deletion**

In `CertificateStore`, extract the common writer and update `save`:

```java
public synchronized void save(String alias, ClientCertificate credential) {
    try {
        KeyStore keyStore = openOrEmpty();
        keyStore.setKeyEntry(alias, credential.keyPair().getPrivate(), password,
                new Certificate[]{credential.certificate()});
        write(keyStore);
    } catch (Exception e) {
        throw new StorageException(
                "Could not write keystore " + file + "; check file permissions",
                e);
    }
}

public synchronized void delete(String alias) {
    try {
        KeyStore keyStore = openOrEmpty();
        if (!keyStore.containsAlias(alias)) {
            return;
        }
        keyStore.deleteEntry(alias);
        write(keyStore);
    } catch (Exception e) {
        throw new StorageException(
                "Could not delete credential " + alias + " from keystore " + file
                        + "; check the keystore password and file permissions",
                e);
    }
}

private void write(KeyStore keyStore) throws Exception {
    Files.createDirectories(file.toAbsolutePath().getParent());
    try (OutputStream out = Files.newOutputStream(file)) {
        keyStore.store(out, password);
    }
}
```

- [ ] **Step 6: Implement explicit unpaired state and load-only startup**

Add to `DeviceState`:

```java
public static DeviceState unpaired() {
    return new DeviceState(DeviceStatus.UNPAIRED, false, null, 0, 0, false, Instant.now());
}
```

Replace `DeviceSessionManager.state()` with:

```java
public DeviceState state() {
    Optional<Device> device = registry.first();
    if (device.isEmpty()) {
        return DeviceState.initial();
    }
    DeviceSession session = sessions.get(device.get().id());
    return session == null ? DeviceState.unpaired() : session.state();
}
```

Replace `startSession` with:

```java
private void startSession(Device device) {
    DeviceSession existing = sessions.remove(device.id());
    if (existing != null) {
        existing.close();
    }

    Optional<ClientCertificate> credential = certificates.load(device.certificateAlias());
    if (credential.isEmpty()) {
        events.publishEvent(new DeviceStateChangedEvent(DeviceState.unpaired()));
        return;
    }

    DeviceSession session = new DeviceSession(device,
            credential.get(),
            properties,
            state -> events.publishEvent(new DeviceStateChangedEvent(state)));
    sessions.put(device.id(), session);
    session.start();
}
```

Import `dev.andre.shield.protocol.ClientCertificate`.

Replace `forget` with:

```java
public void forget(String id) {
    DeviceSession session = sessions.remove(id);
    if (session != null) {
        session.close();
    }
    registry.delete(id);
    certificates.delete(id);
}
```

This class must contain no `loadOrCreate` call after the change.

- [ ] **Step 7: Seed the end-to-end test through the deliberate credential API**

In `DeviceStateStreamEndToEndTest`, inject the store:

```java
@Autowired
CertificateStore certificates;
```

Immediately before `sessions.adopt(...)`, add:

```java
certificates.loadOrCreate("shield-sse");
```

Import `dev.andre.shield.protocol.CertificateStore`. This keeps the production invariant visible: `adopt` receives a device only after pairing has persisted its alias.

- [ ] **Step 8: Make the Forget consequence explicit in the setup page**

Inside `<section th:if="${paired}">`, between the paired-device `<p>` and `<form>`, add:

```html
<p class="hint">
    Forgetting removes the stored pairing credential. You will need to pair again
    before this device can be controlled.
</p>
```

Change the button label to:

```html
<button type="submit">Forget and require re-pairing</button>
```

- [ ] **Step 9: Run lifecycle, web, and end-to-end tests**

Run:

```bash
./gradlew test --tests '*CertificateStoreTest' --tests '*DeviceSessionManagerTest' \
  --tests '*PairingServiceTest' --tests '*RemotePageTest' \
  --tests '*DeviceStateStreamEndToEndTest'
rg -n 'loadOrCreate' src/main/java/dev/andre/shield/device/DeviceSessionManager.java
```

Expected: all selected tests PASS; `rg` exits with status 1 and prints no matches.

- [ ] **Step 10: Commit the credential lifecycle**

```bash
git add src/main/java/dev/andre/shield/device/DeviceState.java \
  src/main/java/dev/andre/shield/device/DeviceSessionManager.java \
  src/main/java/dev/andre/shield/protocol/CertificateStore.java \
  src/main/resources/templates/setup.html \
  src/test/java/dev/andre/shield/device/DeviceSessionManagerTest.java \
  src/test/java/dev/andre/shield/device/PairingServiceTest.java \
  src/test/java/dev/andre/shield/protocol/CertificateStoreTest.java \
  src/test/java/dev/andre/shield/web/DeviceStateStreamEndToEndTest.java \
  src/test/java/dev/andre/shield/web/RemotePageTest.java
git commit -m "fix: preserve pairing identity across restarts"
```

---

### Task 5: Add the Persistent CasaOS Deployment

**Files:**
- Create: `casaos/docker-compose.yml`
- Create: `casaos/icon.svg`
- Create: `src/test/java/dev/andre/shield/deployment/CasaOsManifestTest.java`
- Modify: `README.md`

**Interfaces:**
- Consumes: container `/data`, web port `8080`, image `ghcr.io/yukuhu/home-control:latest`, and CasaOS `$AppID` interpolation.
- Produces: a directly importable CasaOS Compose manifest with top-level app id `dev.andre.shield-remote`, main service `shield-remote`, host networking, and durable `/DATA/AppData/$AppID/data` storage.

Use the current official CasaOS source format as the packaging reference:

- <https://github.com/IceWhaleTech/CasaOS-AppStore/blob/main/docs/quick-start/overview.md>
- <https://github.com/IceWhaleTech/CasaOS-AppStore/blob/main/Apps/HomeAssistant/docker-compose.yml>
- <https://github.com/IceWhaleTech/CasaOS-AppStore/blob/main/Apps/Gopeed/docker-compose.yml>

- [ ] **Step 1: Write the manifest contract test**

Create `CasaOsManifestTest`:

```java
package dev.andre.shield.deployment;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CasaOsManifestTest {

    @Test
    void declaresPersistentHostNetworkedShieldRemote() throws Exception {
        Map<String, Object> manifest;
        try (InputStream input = Files.newInputStream(Path.of("casaos/docker-compose.yml"))) {
            manifest = new Yaml().load(input);
        }

        Map<String, Object> service = map(map(manifest, "services"), "shield-remote");
        assertThat(service.get("image")).isEqualTo("ghcr.io/yukuhu/home-control:latest");
        assertThat(service.get("network_mode")).isEqualTo("host");
        assertThat(service.get("restart")).isEqualTo("unless-stopped");
        assertThat(service).doesNotContainKeys("ports", "environment");

        List<Map<String, Object>> volumes = maps(service, "volumes");
        assertThat(volumes).singleElement().satisfies(volume -> {
            assertThat(volume.get("type")).isEqualTo("bind");
            assertThat(volume.get("source")).isEqualTo("/DATA/AppData/$AppID/data");
            assertThat(volume.get("target")).isEqualTo("/data");
        });

        Map<String, Object> serviceMetadata = map(service, "x-casaos");
        assertThat(maps(serviceMetadata, "ports")).singleElement().satisfies(port ->
                assertThat(port.get("container")).isEqualTo("8080"));
        assertThat(maps(serviceMetadata, "volumes")).singleElement().satisfies(volume ->
                assertThat(volume.get("container")).isEqualTo("/data"));

        Map<String, Object> metadata = map(manifest, "x-casaos");
        assertThat(metadata.get("id")).isEqualTo("dev.andre.shield-remote");
        assertThat(metadata.get("main")).isEqualTo("shield-remote");
        assertThat(metadata.get("index")).isEqualTo("/");
        assertThat(metadata.get("port_map")).isEqualTo("8080");
        assertThat(metadata.get("scheme")).isEqualTo("http");
        assertThat(metadata.get("category")).isEqualTo("Home");
        assertThat(metadata.get("architectures")).isEqualTo(List.of("amd64", "arm64"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Map<String, Object> parent, String key) {
        return (Map<String, Object>) parent.get(key);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Map<String, Object> parent, String key) {
        return (List<Map<String, Object>>) parent.get(key);
    }
}
```

- [ ] **Step 2: Run the deployment test and observe the missing file**

Run:

```bash
./gradlew test --tests '*CasaOsManifestTest'
```

Expected: FAIL with `NoSuchFileException: casaos/docker-compose.yml`.

- [ ] **Step 3: Create the CasaOS manifest**

Create `casaos/docker-compose.yml`:

```yaml
name: shield-remote

services:
  shield-remote:
    image: ghcr.io/yukuhu/home-control:latest
    container_name: shield-remote
    network_mode: host
    restart: unless-stopped
    volumes:
      - type: bind
        source: /DATA/AppData/$AppID/data
        target: /data
    x-casaos:
      ports:
        - container: "8080"
          description:
            en_US: Shield Remote web interface
      volumes:
        - container: /data
          description:
            en_US: Pairing credential and paired-device registry

x-casaos:
  id: dev.andre.shield-remote
  main: shield-remote
  index: /
  port_map: "8080"
  scheme: http
  icon: https://raw.githubusercontent.com/Yukuhu/home-control/main/casaos/icon.svg
  title:
    en_US: Shield Remote
  category: Home
  tagline:
    en_US: A local web remote for NVIDIA Shield and Android TV
  description:
    en_US: Pair once through Remote v2 and control an NVIDIA Shield from any browser on the LAN.
  author: Yukuhu
  developer: Yukuhu
  architectures:
    - amd64
    - arm64
  version: "0.3.0"
  repo: https://github.com/Yukuhu/home-control
```

Do not add a runtime `ports:` mapping: host networking exposes the application's own port directly and is required for mDNS multicast.

- [ ] **Step 4: Add a repository-owned CasaOS icon**

Create `casaos/icon.svg`:

```svg
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512" role="img" aria-labelledby="title">
  <title id="title">Shield Remote</title>
  <rect width="512" height="512" rx="112" fill="#14161a"/>
  <path d="M256 72 400 124v112c0 96-58 164-144 204-86-40-144-108-144-204V124Z" fill="#3d7dff"/>
  <circle cx="256" cy="252" r="100" fill="#23262d"/>
  <path d="M236 176h40v56h56v40h-56v56h-40v-56h-56v-40h56Z" fill="#e8e8ea"/>
</svg>
```

- [ ] **Step 5: Document CasaOS installation and the one-time migration limitation**

Add this section after `Running a prebuilt image` in `README.md`:

````markdown
## CasaOS

Import the CasaOS manifest directly from:

```text
https://raw.githubusercontent.com/Yukuhu/home-control/main/casaos/docker-compose.yml
```

The manifest uses host networking so mDNS discovery works and persists `/data` at
`/DATA/AppData/$AppID/data` on the CasaOS host. The important files are:

- `/DATA/AppData/$AppID/data/keystore.p12` — the Remote v2 client credential;
- `/DATA/AppData/$AppID/data/devices.json` — the paired-device registry.

An older CasaOS deployment that had no volume mapping cannot recover data from an
already discarded anonymous container. Pair once after installing this manifest;
future container replacements and image updates will reuse the bind-mounted pairing.

The default keystore password is stable and intentionally omitted from the CasaOS
manifest. It protects the local PKCS12 file; it is not a web login or network
authentication. If you set `SHIELD_KEYSTORE_PASSWORD` yourself, keep the same value
for every redeployment.
````

Immediately after the ordinary `docker compose up --build` example, add:

```markdown
The bundled `compose.yaml` stores persistent state in `./data` beside the Compose
file. Preserve that directory when updating or recreating the ordinary Docker
deployment.
```

- [ ] **Step 6: Run automated manifest and Compose validation**

Run:

```bash
./gradlew test --tests '*CasaOsManifestTest'
AppID=shield-remote docker compose -f casaos/docker-compose.yml config
```

Expected: the JUnit test PASSes; Compose prints a normalized service with `network_mode: host`, source `/DATA/AppData/shield-remote/data`, target `/data`, and no `SHIELD_KEYSTORE_PASSWORD`.

- [ ] **Step 7: Commit the CasaOS deployment**

```bash
git add casaos/docker-compose.yml casaos/icon.svg \
  src/test/java/dev/andre/shield/deployment/CasaOsManifestTest.java README.md
git commit -m "feat: add persistent CasaOS deployment"
```

---

## Final Automated Verification

- [ ] Run the complete clean test suite and build the distributable jar:

```bash
./gradlew clean test bootJar
```

Expected: `BUILD SUCCESSFUL`; every test passes and `build/libs/` contains the boot jar.

- [ ] Validate both Compose paths without starting containers:

```bash
docker compose -f compose.yaml config
AppID=shield-remote docker compose -f casaos/docker-compose.yml config
```

Expected: ordinary Compose resolves `./data:/data`; CasaOS resolves `/DATA/AppData/shield-remote/data:/data`; both use host networking and restart unless stopped.

- [ ] Run invariant scans:

```bash
rg -n 'AppCatalog|AppEntry|appsFile|default-apps|launchAppLink|ACTIVE_CODE|/apps/|Add current app|app-list' src/main src/test README.md casaos
rg -n 'loadOrCreate' src/main/java/dev/andre/shield/device/DeviceSessionManager.java
git diff --check
git status --short
```

Expected: the first two `rg` commands print nothing and exit 1; `git diff --check` prints nothing; `git status --short` is clean apart from pre-existing untracked `.superpowers/`, which must not be staged or deleted.

## CasaOS Hardware Acceptance Gate

This gate requires the user's CasaOS host and a real Shield; it is the release gate after automated verification.

- [ ] Import `casaos/docker-compose.yml` into CasaOS and confirm the service uses host networking and the host path `/DATA/AppData/<resolved AppID>/data`.
- [ ] Pair once from `/setup`; confirm both `keystore.p12` and `devices.json` appear under that host path.
- [ ] Record the modification times or checksums of both files, then use CasaOS's normal redeploy/update action to replace the container without deleting AppData.
- [ ] Open the remote after replacement and confirm it reconnects without the Shield displaying a new pairing code.
- [ ] Confirm the two persisted files still exist and were not replaced merely by container recreation.
- [ ] Use **Forget and require re-pairing**; confirm the registry record and matching certificate alias are removed, the UI reports `UNPAIRED`/setup state, and controlling that Shield again requires pairing.

If the first install replaces an older CasaOS container that never had a volume mapping, one final pairing is expected. Only the subsequent replacement must reconnect without pairing.
