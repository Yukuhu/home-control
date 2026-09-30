# Phase 2E, PR 1: The Server Edge — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** One exception mapping with 404 for every unknown device, the login behind one `LoginContext`, the unused
JSON content API gone, and an event stream with a heartbeat.

**Architecture:**
- `web.ErrorAdvice` maps core exceptions to a status and a plain-text body.
- `security.LoginContext` is resolved per request and passed to `LoginService`, services and stores, so nothing outside
  the web edge takes `HttpServletRequest`.
- `ContentController` is deleted.
- The renamed `web.EventStream` sends a `: keep-alive` comment every 25 seconds.

**Tech Stack:** Java 25, Spring Boot 4.1.1 (Spring MVC, `SseEmitter`, `HandlerMethodArgumentResolver`), JUnit 5,
AssertJ, Mockito, ArchUnit.

**Spec:** `docs/superpowers/specs/2026-09-30-phase-2e-web-edge-design.md` (sections 1–3 and 5, PR 1).

## Global Constraints

- No `/data` format changes. The session cookie `HOME_CONTROL_SESSION` and the session attribute
  `LoginService.SESSION_ATTRIBUTE` are unchanged, so existing logins survive.
- Paths and the `/events` event names (`state`, `rail`, `rails`) stay, apart from the deleted JSON API.
- Forget stays idempotent: an unknown id redirects to `/setup`.
- `scripts/gradle.sh build` is green after every task. The test count only rises (3,067 at `6fbbe9b`), except for
  `ContentControllerTest`, deleted with its class. Frozen ArchUnit violations only fall.
- Commits follow Conventional Commits; stage only the files you changed. The `ContentController` deletion is
  `refactor:` without `!`.
- Where a service took `HttpServletRequest`, it takes `LoginContext`; where a controller passed the request, it passes
  its `LoginContext` parameter (named `context` where a `login` field already exists).

## Review Focus

1. **A browser logged in before the upgrade.** Its session carries `LoginService.SESSION_ATTRIBUTE` with the credential
   version; it must count as logged in. Task 3 pins it in `RequestLoginContextTest`.
2. **A YouTube sign-in started before the upgrade.** Its OAuth state is bound to the HTTP session id; `sessionKey()` must
   be that id. Task 3 pins it.
3. **Forget with an unknown id.** It must stay a redirect to `/setup`, not a 404. Task 2 pins it.
4. **An htmx request that meets the advice.** Bodies stay plain text, which `app.js` shows in its toast. Task 1 pins
   the content type.
5. **A stream idle behind a proxy.** Heartbeats go out without any device or rail event. Task 6 pins it.

---

### Task 1: One exception mapping

**Files:**
- Create: `src/main/java/dev/andre/homecontrol/web/ErrorAdvice.java`
- Modify: `web/DeviceController.java:151-169` (the four handlers go; `text(...)` stays, it has other callers),
  `web/ContentPlayController.java:146-159` (the core handlers go; `IllegalArgumentException` and
  `NoSuchContentException` stay), `adapters/bluetooth/BluetoothSetupController.java:109-112` (its handler and the imports
  it alone used go)
- Test: create `src/test/java/dev/andre/homecontrol/web/ErrorAdviceTest.java`

**Interfaces:**
- Produces: `ErrorAdvice` (a `@RestControllerAdvice`) answering `DeviceNotFoundException` 404,
  `DeviceOfflineException` 409, `UnsupportedActionException`/`UnroutableException` 422,
  `ActionFailedException`/`ContentSourceException` 502, `LoginRequiredException` 401, each `text/plain` with the
  exception's message.

- [ ] **Step 1: Write the failing test** — a standalone MockMvc over a controller that throws, so the test needs no
  shared context:

```java
package dev.andre.homecontrol.web;

import dev.andre.homecontrol.core.ActionFailedException;
import dev.andre.homecontrol.core.DeviceNotFoundException;
import dev.andre.homecontrol.core.DeviceOfflineException;
import dev.andre.homecontrol.core.UnsupportedActionException;
import dev.andre.homecontrol.core.content.ContentSourceException;
import dev.andre.homecontrol.core.playback.UnroutableException;
import dev.andre.homecontrol.security.LoginRequiredException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Every core exception becomes one status and a plain-text body a toast can show as it is. */
class ErrorAdviceTest {

    private static final Map<String, Supplier<RuntimeException>> THROWN = Map.of(
            "not-found", () -> new DeviceNotFoundException("No device with id ghost"),
            "offline", () -> new DeviceOfflineException("Shield is offline"),
            "unsupported", () -> new UnsupportedActionException("Shield cannot switch inputs"),
            "unroutable", () -> new UnroutableException("Shield: this device cannot open app links"),
            "failed", () -> new ActionFailedException("Shield refused the key"),
            "source", () -> new ContentSourceException("Jellyfin did not answer"),
            "login", LoginRequiredException::new);

    @RestController
    static class Throwing {
        @GetMapping("/throw/{kind}")
        String fail(@PathVariable String kind) {
            throw THROWN.get(kind).get();
        }
    }

    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new Throwing())
            .setControllerAdvice(new ErrorAdvice()).build();

    static Stream<Arguments> mappings() {
        return Stream.of(
                Arguments.of("not-found", 404, "No device with id ghost"),
                Arguments.of("offline", 409, "Shield is offline"),
                Arguments.of("unsupported", 422, "Shield cannot switch inputs"),
                Arguments.of("unroutable", 422, "Shield: this device cannot open app links"),
                Arguments.of("failed", 502, "Shield refused the key"),
                Arguments.of("source", 502, "Jellyfin did not answer"),
                Arguments.of("login", 401, "Log in first"));
    }

    @ParameterizedTest
    @MethodSource("mappings")
    void eachCoreExceptionHasItsStatusAndAPlainTextBody(String kind, int status, String body) throws Exception {
        mockMvc.perform(get("/throw/" + kind).header("HX-Request", "true"))
                .andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andExpect(content().string(body));
    }
}
```

  Check each exception's constructor before using it (`ContentSourceException` may take a cause or a kind); pass the
  message the test asserts.
- [ ] **Step 2:** run `scripts/gradle.sh test --tests 'dev.andre.homecontrol.web.ErrorAdviceTest'`. Expected: compile
  failure, `ErrorAdvice` does not exist.
- [ ] **Step 3: Implement.**

```java
package dev.andre.homecontrol.web;

import dev.andre.homecontrol.core.ActionFailedException;
import dev.andre.homecontrol.core.DeviceNotFoundException;
import dev.andre.homecontrol.core.DeviceOfflineException;
import dev.andre.homecontrol.core.UnsupportedActionException;
import dev.andre.homecontrol.core.content.ContentSourceException;
import dev.andre.homecontrol.core.playback.UnroutableException;
import dev.andre.homecontrol.security.LoginRequiredException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The one mapping from core exceptions to a status and a plain-text body, for every controller. The dashboard shows the
 * body as it is. A controller's own handler still wins, e.g. for an {@code IllegalArgumentException} that means bad
 * input there.
 */
@RestControllerAdvice
public class ErrorAdvice {

    @ExceptionHandler(DeviceNotFoundException.class)
    public ResponseEntity<String> notFound(DeviceNotFoundException e) {
        return text(HttpStatus.NOT_FOUND, e);
    }

    @ExceptionHandler(DeviceOfflineException.class)
    public ResponseEntity<String> offline(DeviceOfflineException e) {
        return text(HttpStatus.CONFLICT, e);
    }

    @ExceptionHandler({UnsupportedActionException.class, UnroutableException.class})
    public ResponseEntity<String> cannot(RuntimeException e) {
        return text(HttpStatus.UNPROCESSABLE_CONTENT, e);
    }

    @ExceptionHandler({ActionFailedException.class, ContentSourceException.class})
    public ResponseEntity<String> failed(RuntimeException e) {
        return text(HttpStatus.BAD_GATEWAY, e);
    }

    @ExceptionHandler(LoginRequiredException.class)
    public ResponseEntity<String> loginRequired(LoginRequiredException e) {
        return text(HttpStatus.UNAUTHORIZED, e);
    }

    private static ResponseEntity<String> text(HttpStatus status, RuntimeException e) {
        return ResponseEntity.status(status).contentType(MediaType.TEXT_PLAIN).body(e.getMessage());
    }
}
```

  Then delete the duplicated handlers named under **Files**, and their now-unused imports.
- [ ] **Step 4:** run `scripts/gradle.sh build`. Expected: green. The existing 404, 409, 422 and 502 tests
  (`DeviceControllerTest`, `ContentPlayControllerTest`, `ContentPlayErrorBodiesTest`, `BluetoothSetupControllerTest`)
  pass unchanged.
- [ ] **Step 5: Commit** `refactor: map core exceptions to a status and a plain-text body in one advice`.

---

### Task 2: An unknown device is 404 wherever it is named

**Files:**
- Modify: `device/Enrollment.java:331-334,369-370` (`merge`, `split`), `playback/DeepLinkTestService.java:66-67`,
  `web/DeepLinkTestController.java` (the pre-check, its `DeviceQueries` field and the `UnsupportedActionException` catch
  go), `web/SetupController.java:151-156` (the MAC pre-check goes)
- Test: the device tests for merge and split (`device/DevicesMergeTest.java` or the enrollment test that covers them),
  `playback/DeepLinkTestServiceTest.java`, `web/DeepLinkTestControllerTest.java`, `web/SetupControllerTest.java`,
  `web/PromptPairingSetupTest.java`

**Interfaces:**
- Consumes: Task 1's `ErrorAdvice`, which turns `DeviceNotFoundException` into 404 text.
- Produces: `DeviceEnrollment.merge`/`split` and `DeepLinkTestService.run` throw `DeviceNotFoundException("No device
  with id <id>")` for an unknown id.

- [ ] **Step 1: Write the failing tests.**
  - Device tests: `merge("ghost", <known>)`, `merge(<known>, "ghost")` and `split("ghost", "androidtv")` throw
    `DeviceNotFoundException` with the message "No device with id ghost".
  - `DeepLinkTestServiceTest`: `run("ghost")` throws `DeviceNotFoundException` "No device with id ghost".
  - `DeepLinkTestControllerTest`:
    - the test that pinned the race as a 200 "failed" fragment (`:49-57`) now stubs
      `tests.run("ghost")` to throw `DeviceNotFoundException` and expects 404 `text/plain` "No device with id ghost";
    - an offline device (`tests.run` throws `DeviceOfflineException("Shield is offline")`) still answers 200 with
      `<p class="deep-link-result failed">Shield is offline</p>`;
    - `UnsupportedActionException` still answers 422 with its message, now through the advice.
  - `SetupControllerTest`:
    - `POST /setup/merge` and `POST /setup/split` whose `enrollment` mock throws `DeviceNotFoundException` answer 404
      "No device with id ghost";
    - **review focus 3:** `POST /setup/forget` with `id=ghost` still redirects to `/setup`.
  - `PromptPairingSetupTest`: `POST /setup/devices/ghost/mac` whose `deviceSettings.setWakeOnLanMac` throws
    `DeviceNotFoundException` answers 404 (today the pre-check hides this; a vanished device gave 500).
- [ ] **Step 2:** run the five test classes. Expected: the device and deep-link service tests fail on
  `IllegalArgumentException`/`DeviceOfflineException`, the controller race test on 200.
- [ ] **Step 3: Implement.**
  - `Enrollment`: `.orElseThrow(() -> new DeviceNotFoundException(NO_DEVICE_PREFIX + id))` in both `merge` lookups and
    in `split`.
  - `DeepLinkTestService.run`: `.orElseThrow(() -> new DeviceNotFoundException("No device with id " + deviceId))`.
  - `DeepLinkTestController.test`:

```java
    @PostMapping(path = "/setup/devices/{id}/deep-link-test")
    public ResponseEntity<String> test(@PathVariable String id) {
        try {
            DeepLinkTestResult result = tests.run(id);
            return fragment(result.outcome().name().toLowerCase(Locale.ROOT).replace('_', '-'), result.message());
        } catch (DeviceOfflineException e) {
            // An offline device is a test result for the page, not an error toast.
            return fragment("failed", e.getMessage());
        }
    }
```

    Its constructor loses `DeviceQueries`. An unknown id and a device that cannot open app links reach `ErrorAdvice`.
  - `SetupController.wakeOnLanMac`: drop the `devices.device(id).isEmpty()` check; `setWakeOnLanMac` throws
    `DeviceNotFoundException` for an unknown id.
- [ ] **Step 4:** run `scripts/gradle.sh build`. Expected: green.
- [ ] **Step 5: Commit** `fix: answer 404 for an unknown device in merge, split, the deep-link test and the MAC form`.

---

### Task 3: `LoginContext`

**Files:**
- Create: `security/LoginContext.java`, `security/RequestLoginContext.java`, `security/LoginContextResolver.java`,
  `security/LoginContextConfigurer.java`, `src/test/java/dev/andre/homecontrol/testsupport/FakeLoginContext.java`
- Modify: `security/LoginService.java` (five methods take `LoginContext`; `permitSecrets`; the private static
  `startSession` goes), `web/LoginController.java`, and every current caller of the five methods (they change again in
  Task 4)
- Test: create `security/RequestLoginContextTest.java`; modify `security/LoginServiceTest.java`

**Interfaces:**
- Produces:

```java
package dev.andre.homecontrol.security;

import java.util.function.BooleanSupplier;

/**
 * One browser's login, as controllers, services and stores see it. Controllers receive it as an argument; the
 * request-backed {@link RequestLoginContext} is the only code that touches the HTTP session for the login.
 */
public interface LoginContext {

    /** No login password is set, or this browser logged in with the current one. */
    boolean loggedIn();

    /** Throws {@link LoginRequiredException} unless {@link #loggedIn()}. */
    default void requireLogin() {
        if (!loggedIn()) {
            throw new LoginRequiredException();
        }
    }

    /** The same check for work that outlives the request, such as an event stream; false once the session ends. */
    BooleanSupplier whileLoggedIn();

    /** A key that stays the same for this browser session, starting a session if there is none. */
    String sessionKey();

    /** Logs this browser in with the given credential version under a new session id. For {@link LoginService}. */
    void startSession(String version);

    /** Ends this browser's session. For {@link LoginService}. */
    void endSession();
}
```

  `LoginService`:
  - `boolean authenticate(String password, LoginContext context)`
  - `void logout(LoginContext context)`
  - `void storeSecrets(Map<String, String> secrets, String newPassword, String confirmation, LoginContext context)`
  - `void setPassword(String password, String confirmation, LoginContext context)`
  - `void changePassword(String current, String next, String confirmation, LoginContext context)`
  - `void permitSecrets(LoginContext context, String newPassword, String confirmation)`: `requireLogin()` while a
    password is set, else `checkNewPassword(newPassword, confirmation)`.
  - `isAuthenticated(HttpServletRequest)` and `isAuthenticated(HttpSession)` stay, for the filters and
    `RequestLoginContext`.

  `testsupport.FakeLoginContext`: `static FakeLoginContext loggedIn()`, `static FakeLoginContext loggedOut()`,
  `boolean sessionStarted()`, `String startedVersion()`.

- [ ] **Step 1: Write the failing tests.** `RequestLoginContextTest` uses a real `LoginService`, built as
  `LoginServiceTest` builds it, over `MockHttpServletRequest`:
  - without a password, a fresh request is logged in;
  - with a password and no session, it is not, and `requireLogin()` throws `LoginRequiredException`;
  - `authenticate(password, context)` starts a session under a new id, and the context is logged in;
  - **review focus 1:** a session whose `LoginService.SESSION_ATTRIBUTE` holds the current version, set directly as the
    old code set it, is logged in;
  - after `changePassword` through a second context, the first context's `loggedIn()` and `whileLoggedIn()` are false;
  - `logout(context)` ends the session, and `whileLoggedIn()` turns false;
  - **review focus 2:** `sessionKey()` is the HTTP session id, is stable across calls, and starts a session when there
    is none.

  `LoginServiceTest` moves from requests to contexts and adds `permitSecrets`:
  - logged in → passes;
  - a password is set and the context is logged out → `LoginRequiredException`;
  - no password and a short first password → `PasswordRejectedException`;
  - no password and a valid pair → passes, and nothing is stored.
- [ ] **Step 2:** run `security.*`. Expected: compile failure, `LoginContext` does not exist.
- [ ] **Step 3: Implement.**

```java
package dev.andre.homecontrol.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import java.util.function.BooleanSupplier;

/** The login of the browser behind one request, kept in its HTTP session as {@link LoginService} always kept it. */
final class RequestLoginContext implements LoginContext {

    private final HttpServletRequest request;
    private final LoginService login;

    RequestLoginContext(HttpServletRequest request, LoginService login) {
        this.request = request;
        this.login = login;
    }

    @Override
    public boolean loggedIn() {
        return login.isAuthenticated(request);
    }

    @Override
    public BooleanSupplier whileLoggedIn() {
        HttpSession session = request.getSession(false);
        return () -> login.isAuthenticated(session);
    }

    @Override
    public String sessionKey() {
        return request.getSession(true).getId();
    }

    @Override
    public void startSession(String version) {
        HttpSession session = request.getSession(true);
        request.changeSessionId(); // no session fixation
        session.setAttribute(LoginService.SESSION_ATTRIBUTE, version);
    }

    @Override
    public void endSession() {
        HttpSession session = request.getSession(false);
        if (session != null) {
            try {
                session.invalidate();
            } catch (IllegalStateException _) {
                // nothing left to end
            }
        }
    }
}
```

```java
package dev.andre.homecontrol.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/** Gives a controller parameter of type {@link LoginContext} the login of the browser behind the request. */
final class LoginContextResolver implements HandlerMethodArgumentResolver {

    private final LoginService login;

    LoginContextResolver(LoginService login) {
        this.login = login;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return LoginContext.class.equals(parameter.getParameterType());
    }

    @Override
    public LoginContext resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                        NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        return new RequestLoginContext(webRequest.getNativeRequest(HttpServletRequest.class), login);
    }
}
```

```java
package dev.andre.homecontrol.security;

import org.springframework.stereotype.Component;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/** Registers {@link LoginContextResolver}; a {@code WebMvcConfigurer}, so web slice tests get it too. */
@Component
class LoginContextConfigurer implements WebMvcConfigurer {

    private final LoginService login;

    LoginContextConfigurer(LoginService login) {
        this.login = login;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new LoginContextResolver(login));
    }
}
```

  `LoginService`:
  - `startSession(request, credential)` → `context.startSession(credential.version())`;
  - `logout` → `context.endSession(); changed();`;
  - `storeSecrets` → `if (!context.loggedIn()) throw new LoginRequiredException();` where it asked
    `isAuthenticated(request)`;
  - add:

```java
    /**
     * Before work that ends in {@link #storeSecrets}: this browser is logged in, or, while no password is set, the new
     * one is acceptable. Throws what {@code storeSecrets} would, before anything is fetched or stored.
     */
    public void permitSecrets(LoginContext context, String newPassword, String confirmation) {
        if (loginRequired()) {
            context.requireLogin();
        } else {
            checkNewPassword(newPassword, confirmation);
        }
    }
```

  `LoginController`: each handler that passed the request to `LoginService` takes a `LoginContext context` parameter
  and passes that. `isAuthenticated(request)` at `:43` becomes `context.loggedIn()`. The request stays for
  `getRemoteAddr()`.

  The sources still call `storeSecrets(..., HttpServletRequest)` and `isAuthenticated(HttpServletRequest)` until
  Task 4. So that this task stays green on its own, `LoginService` keeps
  `storeSecrets(Map, String, String, HttpServletRequest)` for now, delegating to the new method with
  `new RequestLoginContext(request, this)`. Task 4 moves the last caller and deletes it.

  `FakeLoginContext`:

```java
package dev.andre.homecontrol.testsupport;

import dev.andre.homecontrol.security.LoginContext;

import java.util.function.BooleanSupplier;

/** A browser's login without HTTP: logged in or not, and whether {@code LoginService} started a session. */
public final class FakeLoginContext implements LoginContext {

    private boolean loggedIn;
    private String startedVersion;

    private FakeLoginContext(boolean loggedIn) {
        this.loggedIn = loggedIn;
    }

    public static FakeLoginContext loggedIn() {
        return new FakeLoginContext(true);
    }

    public static FakeLoginContext loggedOut() {
        return new FakeLoginContext(false);
    }

    @Override
    public boolean loggedIn() {
        return loggedIn;
    }

    @Override
    public BooleanSupplier whileLoggedIn() {
        return () -> loggedIn;
    }

    @Override
    public String sessionKey() {
        return "session-1";
    }

    @Override
    public void startSession(String version) {
        startedVersion = version;
        loggedIn = true;
    }

    @Override
    public void endSession() {
        loggedIn = false;
    }

    public boolean sessionStarted() {
        return startedVersion != null;
    }

    public String startedVersion() {
        return startedVersion;
    }
}
```
- [ ] **Step 4:** run `scripts/gradle.sh build`. Expected: green.
- [ ] **Step 5: Commit** `refactor: keep a browser's login behind one LoginContext`.

---

### Task 4: Services and stores take `LoginContext`

**Files:**
- Modify (main):
  - `sources/jellyfin/JellyfinSetupService.java` (`connect`; `passLoginGate` → `login.permitSecrets`)
  - `sources/sports/calendar/SportsCalendars.java` (`add`; its first-password pre-check → `permitSecrets`)
  - `sources/sports/thesportsdb/SportsCompetitions.java` (`usePersonalKey`; the same)
  - `sources/tmdb/TmdbSetupService.java` (`connect`)
  - `sources/workflows/WorkflowStore.java` (`create`, `update`, `setEnabled`, `remove`, `removeInvalid`; the private
    `requireLogin` goes, and `if (login.loginRequired()) requireLogin(request)` becomes `context.requireLogin()`)
  - `sources/workflows/WorkflowTestService.java` (`test`; the private `authenticate` goes)
  - `sources/workflows/WorkflowSetupController.java` (its `authenticate(request)` becomes `context.requireLogin()`)
  - `sources/youtube/YouTubeSetupService.java`:
    - `connect(ConnectRequest, LoginContext)`;
    - `connectBrowser(ConnectRequest, URI callback, LoginContext)`;
    - `authorizeBrowser(URI callback, LoginContext)`, whose OAuth state binds to `context.sessionKey()`;
    - `completeBrowser(LoginContext, String state, String code, String error)` passes `context.sessionKey()`. A browser
      without a session gets a fresh key, which matches no pending sign-in, so it is refused as before.
  - `sources/youtube/YouTubeOAuthCallback.java`: `uri(HttpServletRequest)` becomes `uri(URI contextUrl)`, which returns
    `contextUrl` with the path `PATH` and no query.
  - `sources/youtube/YouTubeSetupController.java`: add
    `static URI callback(HttpServletRequest request) { return YouTubeOAuthCallback.uri(ServletUriComponentsBuilder.fromContextPath(request).build().toUri()); }`
    and pass it to the service.
  - `sources/youtube/YouTubeSetupAdvice.java`: uses `YouTubeSetupController.callback(request)`.
  - `JellyfinSetupController`, `TmdbSetupController`, `SportsSetupController`, `TheSportsDbSetupController`: take
    `LoginContext` where they took the request for these services.
- Modify (test): `src/test/java/dev/andre/homecontrol/ArchitectureTest.java` (`servletTypesStayAtTheWebEdge` loses
  `freeze(...)`); delete `src/test/archunit-store/082d893a-9cb9-4f73-8942-7df9e9c9f02a` and its line in
  `src/test/archunit-store/stored.rules`
- Test: every service test that mocked `HttpServletRequest` for these methods moves to `FakeLoginContext`:
  - `JellyfinSetupServiceTest`, `SportsCalendarsTest`, `SportsCompetitionsTest`, `TmdbSetupServiceTest`;
  - `WorkflowStoreTest`, `WorkflowTestServiceTest`, `WorkflowIntegrationFixture`;
  - `YouTubeSetupServiceTest`, `YouTubeBrowserAuthorizationTest`;
  - the controller tests that stub `login.isAuthenticated(...)` keep working through `RequestLoginContext`.

**Interfaces:**
- Consumes: Task 3's `LoginContext`, `LoginService.permitSecrets`, `FakeLoginContext`.
- Produces: no class outside `web`, `security`, `@Controller` and `@ControllerAdvice` uses `jakarta.servlet`.

- [ ] **Step 1: Write the failing test.** Make the rule strict: in `ArchitectureTest`, replace
  `freeze(noClasses()...because("services and stores take values, not requests"))` by the rule without `freeze(`,
  delete its store file and its `stored.rules` line.
- [ ] **Step 2:** run `scripts/gradle.sh test --tests 'dev.andre.homecontrol.ArchitectureTest'`. Expected: FAIL,
  `servletTypesStayAtTheWebEdge` reports 24 violations in the eight classes.
- [ ] **Step 3: Implement** the signature changes above, and move each service test's mocked request to
  `FakeLoginContext.loggedIn()`/`loggedOut()`. Where a test verified "a first password logs this browser in", assert
  `context.sessionStarted()`. Delete Task 3's temporary `LoginService.storeSecrets(..., HttpServletRequest)`.
- [ ] **Step 4:** run `scripts/gradle.sh build`. Expected: green, `ArchitectureTest` included; the store has no entry
  for the rule.
- [ ] **Step 5: Commit** `refactor: let services and stores take a LoginContext instead of the request`.

---

### Task 5: The unused JSON content API goes

**Files:**
- Delete: `web/ContentController.java`, `src/test/java/dev/andre/homecontrol/web/ContentControllerTest.java`, and any
  view record only `ContentController` used (check each with `grep` before deleting)
- Modify: `testsupport/SharedWebSliceTest.java:62` (the controller list), and the end-to-end tests that used the API:
  - `StreamingLaunchersEndToEndTest`, `JellyfinEndToEndTest`, `YouTubeEndToEndTest`, `SportsEndToEndTest`;
  - `BluetoothJellyfinEndToEndTest`, `SpeakerJellyfinEndToEndTest`, `CrossOriginEndToEndTest`.
- Test: create `web/ContentApiRemovedTest.java` (a `WebSliceTest`)

**Interfaces:**
- Consumes: nothing new.
- Produces: `GET /sources`, `GET /sources/{s}/rails/{r}`, `POST /sources/{s}/rails/{r}/refresh`, `GET /search` and
  `POST /search?source=` answer 404.

- [ ] **Step 1: Write the failing test.**

```java
package dev.andre.homecontrol.web;

import dev.andre.homecontrol.testsupport.WebSliceTest;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The JSON content API had no caller; the dashboard reads rails and search results as HTML. */
class ContentApiRemovedTest extends WebSliceTest {

    @Test
    void theJsonContentApiIsGone() throws Exception {
        mockMvc.perform(get("/sources")).andExpect(status().isNotFound());
        mockMvc.perform(get("/sources/tmdb/rails/trending")).andExpect(status().isNotFound());
        mockMvc.perform(post("/sources/tmdb/rails/trending/refresh")).andExpect(status().isNotFound());
        mockMvc.perform(get("/search").param("q", "matrix")).andExpect(status().isNotFound());
        mockMvc.perform(post("/search").param("source", "youtube").param("q", "bunny")).andExpect(status().isNotFound());
    }
}
```

  Use the slice's `mockMvc` field and its CSRF/origin conventions as the neighbouring `WebSliceTest`s do.
- [ ] **Step 2:** run it. Expected: FAIL, `/sources` answers 200.
- [ ] **Step 3: Implement.** Delete the controller and its test. In the end-to-end tests:

  | Old | New |
  | --- | --- |
  | `GET /sources` for a 401 from a stranger | `GET /rails` |
  | `GET /sources/{s}/rails/{r}` | `GET /rails/{s}/{r}`, asserting the same titles in the HTML |
  | `POST /sources/{s}/rails/{r}/refresh` (202) | `POST /rails/{s}/{r}/refresh`, with that endpoint's status |
  | `GET /search?q=` | `GET /search/results?q=`, asserting the same titles in the HTML |
  | `GET /sources` listing sources | the same fact from the HTML the dashboard reads (`GET /rails` or `/`) |
  | `CrossOriginEndToEndTest`'s `/search?source=youtube&q=bunny` | dropped; `/search/results/youtube` is already listed |

  Each test keeps what it proves: a rail's items, a search hit, the login gate, a disconnect removing a source.
- [ ] **Step 4:** run `scripts/gradle.sh build`. Expected: green.
- [ ] **Step 5: Commit** `refactor: remove the unused JSON content API`.

---

### Task 6: The event stream sends a heartbeat, under names for what it carries

**Files:**
- Rename (`git mv`):
  - `web/DeviceStateBroadcaster.java` → `web/EventStream.java`;
  - `web/StateController.java` → `web/EventStreamController.java`;
  - the tests `DeviceStateBroadcasterTest` → `EventStreamTest`, `DeviceStateBroadcasterFailuresTest` →
    `EventStreamFailuresTest`, `StateControllerTest` → `EventStreamControllerTest`.
- Create: `web/EventStreamProperties.java`
- Modify:
  - `HomeControlConfiguration.java` (`@EnableConfigurationProperties` gains `EventStreamProperties`);
  - `src/main/resources/application.yaml` (`home-control.events.heartbeat-interval: 25s`, with a comment);
  - `docs/user/configuration.md` (a row for the property);
  - `testsupport/WebSliceTest.java`, `testsupport/SharedWebSliceTest.java` (the renamed types).
- Test: `web/EventStreamTest.java`

**Interfaces:**
- Consumes: Task 3's `LoginContext.whileLoggedIn()`.
- Produces:
  - `EventStreamProperties(Duration heartbeatInterval)`, bound from `home-control.events`, default `25s`;
  - `EventStream(EventStreamProperties)`, with `subscribe`, `revalidate`, the event listeners and `onContextClosed`
    as before;
  - `void sendHeartbeat(SseEmitter emitter) throws IOException`, package-private so a test can observe it.

- [ ] **Step 1: Write the failing tests** in `EventStreamTest`:

```java
    @Test
    void aHeartbeatReachesEveryOpenStreamWithoutAnyEvent() {
        List<SseEmitter> beats = new CopyOnWriteArrayList<>();
        EventStream stream = new EventStream(new EventStreamProperties(Duration.ofMillis(50))) {
            @Override
            void sendHeartbeat(SseEmitter emitter) {
                beats.add(emitter);
            }
        };
        SseEmitter first = stream.subscribe(() -> true);
        SseEmitter second = stream.subscribe(() -> true);

        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(beats).contains(first, second));
        stream.shutdown();
    }

    @Test
    void aStreamWhoseHeartbeatFailsIsDropped() {
        AtomicInteger beats = new AtomicInteger();
        EventStream stream = new EventStream(new EventStreamProperties(Duration.ofMillis(50))) {
            @Override
            void sendHeartbeat(SseEmitter emitter) throws IOException {
                beats.incrementAndGet();
                throw new IOException("the tab is gone");
            }
        };
        stream.subscribe(() -> true);

        await().atMost(Duration.ofSeconds(2)).until(() -> beats.get() >= 1);
        int afterFirst = beats.get();
        await().pollDelay(Duration.ofMillis(200)).atMost(Duration.ofSeconds(1)).untilAsserted(
                () -> assertThat(beats.get()).isEqualTo(afterFirst));
        stream.shutdown();
    }
```

  The other tests of the three renamed classes keep their assertions, with `new EventStream(new
  EventStreamProperties(Duration.ofSeconds(25)))` where they built `new DeviceStateBroadcaster()`.
- [ ] **Step 2:** run `web.*`. Expected: compile failure, `EventStream` does not exist.
- [ ] **Step 3: Implement.**

```java
package dev.andre.homecontrol.web;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/** {@code home-control.events.*}: how often an idle event stream gets a comment line, so proxies keep it open. */
@ConfigurationProperties("home-control.events")
public record EventStreamProperties(@DefaultValue("25s") Duration heartbeatInterval) {
}
```

  In `EventStream`:
  - the fan-out becomes `Executors.newSingleThreadScheduledExecutor(...)` with the same daemon thread name;
  - the constructor schedules
    `fanOut.scheduleWithFixedDelay(() -> broadcast(this::sendHeartbeat), interval, interval, MILLISECONDS)` and keeps
    the `ScheduledFuture`, so the heartbeat runs on the fan-out thread, in order with events;
  - `onContextClosed` cancels it before completing the emitters;
  - `sendHeartbeat` is `emitter.send(SseEmitter.event().comment("keep-alive"))`.

  `EventStreamController.events(LoginContext context)` subscribes with `context.whileLoggedIn()`. Its constructor
  keeps `ObjectProvider<LoginService>` only to register `onChange(stream::revalidate)`, and `stillAllowed` goes. The
  class Javadoc names what the stream carries: device state, rail updates and rail lists.
- [ ] **Step 4:** run `scripts/gradle.sh build`. Expected: green, `EventStreamShutdownEndToEndTest` included.
- [ ] **Step 5: Commit** `feat: keep the event stream open behind proxies with a heartbeat`.

---

### Task 7: The architecture guide

**Files:**
- Modify: `docs/dev/architecture.md`:
  - the `web` row: `ErrorAdvice`, `EventStream`;
  - the `security` row: `LoginContext`;
  - the rules table: the `jakarta.servlet` row becomes `strict`.
- Modify: `docs/dev/testing.md`, where it lists shared helpers: `FakeLoginContext`.

- [ ] **Step 1:** write the edits.
- [ ] **Step 2:** run `scripts/gradle.sh build`, `scripts/gradle.sh compileE2eJava` and
  `scripts/e2e.sh -Pe2eBrowsers=chromium`. Expected: green.
- [ ] **Step 3: Commit** `docs: describe the web edge's error mapping, login context and event stream`.
