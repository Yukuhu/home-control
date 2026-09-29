# Workflow chains, step 1: several calls per workflow — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A workflow holds up to eight GET calls, shared or per entry, which run in parallel or in order depending on
the variables they use. The stored format becomes schema version 2, and v1 definitions migrate when read.

**Architecture:** `WorkflowDraft` becomes a list of calls with a separate entry source (`Listing`). `WorkflowPlan`
works out each call's dependencies and phase from the variables its templates use. `WorkflowCalls` runs a set of
calls on virtual threads within one run's deadline and share of fetches. `WorkflowRunner` uses both for refresh and
Play. The HTTP client gains per-run deadlines and waits for a free fetch slot within them. The editor shows calls as
cards.

**Tech Stack:** Java 25, Spring Boot 4.1.1, Jackson 3 (`tools.jackson.*`), Apache HttpClient 5, Thymeleaf, plain ES
modules, JUnit 5, AssertJ, Mockito, Playwright (browser tests).

**Spec:** `docs/superpowers/specs/2026-09-29-workflow-chains-design.md`. This plan implements its build step 1:
**Model and runner**. Steps 2 (date values and filters) and 3 (Try and the picker) get their own plans after this one
is merged. Their fields are optional additions to schema version 2, so they need no third version.

## Global Constraints

- Build and test only through `scripts/gradle.sh <args>` (no local JDK). A task is done when its named tests pass;
  the plan is done when `scripts/gradle.sh build` and `scripts/e2e.sh -Pe2eBrowsers=chromium --tests '*WorkflowE2eTest'`
  are green.
- Everything stays in `dev.andre.homecontrol.sources.workflows` (plus the editor template, `static/js/workflows.js`,
  docs and the ADR). No other module changes. `ArchitectureTest` must stay green; never refreeze
  `src/test/archunit-store`.
- Schema version 2 is written; versions 1 and 2 are read. An unknown version is refused without overwriting.
- Limits, verbatim from the spec: at most **8 calls**, **16 headers per call**, **64 variables** per workflow;
  **200 entries**, or **50** when a per-entry call runs at refresh; **16,384 characters** per stored definition;
  **1,024 characters** per substituted header value; call deadline **10 s**; run deadline **Play 20 s**, **refresh
  60 s**; **8** workflow fetches at once in total, **3** per refresh, **4** per Play or Test.
- Call names match `[a-z][a-z0-9_]{0,23}`. Variable names keep `[A-Za-z][A-Za-z0-9_]{0,31}` and are unique across the
  whole workflow.
- Only GET. A variable may fill a call URL's path segments and query values, never its scheme, host or port.
- Error text names the call, the entry's public title and a safe reason. It never contains a URL, header value,
  response excerpt or variable value.
- `toString()` of every record that can hold a URL, header value or response prints no secrets.
- Commit messages follow Conventional Commits. Stage only the files you changed (`git add <paths>`).
- A deliberate SonarCloud exception uses the narrowest `@SuppressWarnings("java:S…")` with a one-line reason.

## Review Focus

1. **A v1 header value containing braces** (e.g. `X-Filter: {"a":1}`) is migrated by doubling the braces and still
   sent byte for byte. Pinned in Task 3 (`migrationKeepsBracesInV1HeadersLiteral`) and Task 6
   (`migratedHeaderWithBracesIsSentUnchanged`).
2. **Two independent calls failing at the same moment** produce one error naming one of them, and the run does not
   wait for the slower one. Pinned in Task 5 (`aFailureEndsTheRunWithoutWaitingForASlowIndependentCall`).
3. **A per-entry call returning an artwork address that is not public HTTPS** gives the tile the placeholder, and
   Test says so. Pinned in Task 6 (`unsafeArtworkFromAnEntryCallFallsBackToThePlaceholder`).
4. **A value that looks like a host or path** (`a/b@evil.example?x`) substituted into a call URL stays inside one
   path segment, and the request goes to the configured host. Pinned in Task 2
   (`callUrlTemplatesKeepTheirHostAndEncodeEveryValue`) and Task 5 (`valuesFromAResponseNeverChangeTheHost`).
5. **Renaming a call whose URL is on Keep** still keeps the saved URL, found through `savedName`. Pinned in Task 8
   (`keepFindsTheSavedCallAfterARename`).

---

## File structure

| File | Responsibility |
|---|---|
| `WorkflowProperties.java` (modify) | New defaults (10 s call, 8 fetches); `playTimeout`, `refreshTimeout`. |
| `WorkflowHttpClient.java` (modify) | `Request` type; `fetch(Request, deadline)` and `checkMedia(URI, deadline)` that wait for a slot. |
| `WorkflowTemplate.java` (modify) | Label and stage per use (media URL or call URL); `references()`. |
| `WorkflowHeaderTemplate.java` (create) | Header value templates with `{{`/`}}` escapes and checked substitution. |
| `WorkflowException.java` (modify) | `detail()`, `call()`, `inCall(call, entryTitle)`. |
| `WorkflowDraft.java` (rewrite) | Schema v2 records: `Call`, `Listing`, `Field`, `Variable`, `Header`, `Tile`, `Cast`. |
| `WorkflowDefinition.java` (modify) | `SCHEMA_VERSION = 2`. |
| `WorkflowMigration.java` (create) | v1 JSON records and their conversion to v2. |
| `WorkflowCodec.java` (modify) | Reads v1 and v2, writes v2. |
| `WorkflowValidator.java` (rewrite) | v2 structure and limits (`validateStored`); save rules (`validate`). |
| `WorkflowPlan.java` (create) | Variable owners, dependencies, ordering rules, phases, what each run executes. |
| `WorkflowJson.java` (modify) | `entries(Listing, root, limit)`, `values(variables, context)`. |
| `WorkflowCalls.java` (create) | Runs calls on virtual threads by dependency within a `Run`. |
| `WorkflowRunner.java` (rewrite) | Refresh and Play with calls; per-entry fallbacks; building a call's request. |
| `WorkflowTestService.java` (rewrite) | Test through the runner; call-named failures; per-entry warnings. |
| `WorkflowForm.java` (rewrite) | Call rows with per-call Keep/Replace; entry source; display sources. |
| `WorkflowSetupController.java` (modify) | Binding of nested call rows; error targeting for calls. |
| `WorkflowStore.java` (modify) | Schema constant; `withEnabled`. |
| `WorkflowConfiguration.java` (modify) | Runner gets properties. |
| `templates/workflow-editor.html` (modify) | Call cards, entry source section, display sources. |
| `static/js/workflows.js` (rewrite) | Nested row indexing, add/remove/move, Keep locks, visibility. |
| `docs/adr/0003-workflow-chains.md` (create) | Schema v2 and showing responses in the editor. |
| `docs/user/sources.md`, `docs/user/configuration.md` (modify) | User guide and new settings. |

Tests live beside the code in `src/test/java/dev/andre/homecontrol/sources/workflows/`; browser tests in
`src/e2e/java/dev/andre/homecontrol/e2e/WorkflowE2eTest.java`.

---

### Task 1: HTTP client deadlines, waiting for a slot, new defaults

**Files:**
- Modify: `src/main/java/dev/andre/homecontrol/sources/workflows/WorkflowProperties.java`
- Modify: `src/main/java/dev/andre/homecontrol/sources/workflows/WorkflowHttpClient.java`
- Modify: `src/main/resources/application.yaml` only if it sets `home-control.workflows.request-timeout` or `max-concurrent-fetches` (check with `grep -n -A8 'workflows:' src/main/resources/application.yaml`; remove values equal to the old defaults so the new defaults apply)
- Test: `src/test/java/dev/andre/homecontrol/sources/workflows/WorkflowHttpClientTest.java`, `WorkflowUrlPolicyTest.java`

**Interfaces:**
- Produces: `WorkflowProperties.playTimeout()`, `WorkflowProperties.refreshTimeout()` (`Duration`); the 7-argument
  constructor still exists. `WorkflowHttpClient.Request(String url, List<WorkflowDraft.Header> headers)`;
  `byte[] fetch(Request)` (fails at once when no slot is free, as today); `byte[] fetch(Request, long deadlineNanos)`
  and `void checkMedia(URI, long deadlineNanos)` (wait for a slot until the deadline; each fetch ends at the earlier
  of the deadline and `requestTimeout`). Deadlines are `System.nanoTime()` values.

- [ ] **Step 1: Write the failing tests**

In `WorkflowHttpClientTest`, replace every `WorkflowDraft.Fetch` with `WorkflowHttpClient.Request` (same two
arguments) — it is the only change to existing tests. Add a helper and three tests:

```java
    private static WorkflowHttpClient client(Duration timeout, int permits) {
        return new WorkflowHttpClient(new WorkflowProperties(true, true, Duration.ofSeconds(1), timeout, permits, 2_097_152, 3),
                new WorkflowUrlPolicy(true, host -> new InetAddress[]{InetAddress.ofLiteral("127.0.0.1")}));
    }

    private static long in(Duration wait) { return System.nanoTime() + wait.toNanos(); }

    @Test void aFetchWithADeadlineWaitsForAFreeSlot() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var server = new FakeWorkflowServer(); var client = client(Duration.ofSeconds(5), 1)) {
            server.block("/slow", false, entered, release);
            server.respond("/next", 200, "{}");
            var first = CompletableFuture.supplyAsync(() -> client.fetch(request(server.url("/slow"))),
                    Executors.newVirtualThreadPerTaskExecutor());
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            var second = CompletableFuture.supplyAsync(() -> client.fetch(request(server.url("/next")), in(Duration.ofSeconds(3))),
                    Executors.newVirtualThreadPerTaskExecutor());
            release.countDown();
            assertThat(second.get(3, TimeUnit.SECONDS)).asString().isEqualTo("{}");
            assertThat(first.get(3, TimeUnit.SECONDS)).isNotNull();
        } finally { release.countDown(); }
    }

    @Test void aFetchWithADeadlineReportsBusyWhenNoSlotFreesInTime() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var server = new FakeWorkflowServer(); var client = client(Duration.ofSeconds(5), 1)) {
            server.block("/slow", false, entered, release);
            server.respond("/next", 200, "{}");
            CompletableFuture.runAsync(() -> client.fetch(request(server.url("/slow"))), Executors.newVirtualThreadPerTaskExecutor());
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            long started = System.nanoTime();
            var next = request(server.url("/next"));
            assertThatThrownBy(() -> client.fetch(next, in(Duration.ofMillis(200))))
                    .isInstanceOf(WorkflowException.class).hasMessageContaining("busy");
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isGreaterThanOrEqualTo(Duration.ofMillis(150));
            assertThat(server.count("/next")).isZero();
        } finally { release.countDown(); }
    }

    @Test void aRunDeadlineEarlierThanTheCallTimeoutEndsTheFetch() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var server = new FakeWorkflowServer(); var client = client(Duration.ofSeconds(5), 2)) {
            server.block("/slow", true, entered, release);
            long started = System.nanoTime();
            var slow = request(server.url("/slow"));
            assertThatThrownBy(() -> client.fetch(slow, in(Duration.ofMillis(300))))
                    .isInstanceOf(WorkflowException.class).hasMessageContaining("timed out");
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
        } finally { release.countDown(); }
    }
```

Add the imports `java.util.concurrent.Executors` and `java.util.concurrent.CompletableFuture` if missing.

In `WorkflowUrlPolicyTest.bindsSafeDefaultsAndAllowsExplicitLocalOverrides`, change the expected defaults and
check the new settings:

```java
        assertThat(defaults).isEqualTo(new WorkflowProperties(true, false, Duration.ofSeconds(5), Duration.ofSeconds(10), 8, 2_097_152, 3));
        assertThat(defaults.playTimeout()).isEqualTo(Duration.ofSeconds(20));
        assertThat(defaults.refreshTimeout()).isEqualTo(Duration.ofSeconds(60));
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.sources.workflows.WorkflowHttpClientTest' --tests 'dev.andre.homecontrol.sources.workflows.WorkflowUrlPolicyTest'`
Expected: compilation fails (`WorkflowHttpClient.Request`, `fetch(Request, long)` and `playTimeout()` do not exist).

- [ ] **Step 3: Implement**

`WorkflowProperties.java`:

```java
package dev.andre.homecontrol.sources.workflows;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties("home-control.workflows")
public record WorkflowProperties(@DefaultValue("true") boolean enabled,
                                 @DefaultValue("false") boolean allowLoopback,
                                 @DefaultValue("5s") Duration connectTimeout,
                                 @DefaultValue("10s") Duration requestTimeout,
                                 @DefaultValue("8") int maxConcurrentFetches,
                                 @DefaultValue("2097152") int maxBytes,
                                 @DefaultValue("3") int maxRedirects,
                                 @DefaultValue("20s") Duration playTimeout,
                                 @DefaultValue("60s") Duration refreshTimeout) {
    @ConstructorBinding
    public WorkflowProperties {
        validateTimeout(connectTimeout);
        validateTimeout(requestTimeout);
        validateTimeout(playTimeout);
        validateTimeout(refreshTimeout);
        if (maxConcurrentFetches <= 0 || maxBytes <= 0 || maxBytes == Integer.MAX_VALUE || maxRedirects <= 0) {
            throw new IllegalArgumentException("Workflow limits must be positive and finite");
        }
    }

    /** The settings without run deadlines, which take their defaults. */
    public WorkflowProperties(boolean enabled, boolean allowLoopback, Duration connectTimeout, Duration requestTimeout,
                              int maxConcurrentFetches, int maxBytes, int maxRedirects) {
        this(enabled, allowLoopback, connectTimeout, requestTimeout, maxConcurrentFetches, maxBytes, maxRedirects,
                Duration.ofSeconds(20), Duration.ofSeconds(60));
    }

    private static void validateTimeout(Duration value) {
        try {
            if (value == null || value.isNegative() || value.isZero() || value.toNanos() <= 0) {
                throw new IllegalArgumentException("Workflow timeouts must be positive and finite");
            }
        } catch (ArithmeticException _) {
            throw new IllegalArgumentException("Workflow timeouts must be positive and finite");
        }
    }
}
```

`WorkflowHttpClient.java` — add the `Request` record next to `FetchResponse`, and change the public methods,
`bounded` and `Operation`:

```java
    /** One GET with its URL and header values already expanded. Printing it shows neither. */
    public record Request(String url, List<WorkflowDraft.Header> headers) {
        public Request {
            headers = headers == null ? null : java.util.Collections.unmodifiableList(new java.util.ArrayList<>(headers));
        }

        @Override public String toString() { return "Request"; }
    }

    /** Fails at once with "busy" when every fetch slot is taken. */
    public byte[] fetch(Request request) {
        return bounded(Stage.FETCH, System.nanoTime() + properties.requestTimeout().toNanos(), false,
                () -> fetchBody(request));
    }

    /** Waits for a fetch slot until {@code deadline}; the fetch ends at the deadline or the request timeout. */
    public byte[] fetch(Request request, long deadline) {
        return bounded(Stage.FETCH, deadline, true, () -> fetchBody(request));
    }

    public void checkMedia(URI uri) {
        checkMedia(uri, System.nanoTime() + properties.requestTimeout().toNanos(), false);
    }

    public void checkMedia(URI uri, long deadline) {
        checkMedia(uri, deadline, true);
    }

    private void checkMedia(URI uri, long deadline, boolean wait) {
        bounded(Stage.BUILD, deadline, wait, () -> {
            URI checked = policy.parse(uri == null ? null : uri.toString());
            current.get().check();
            policy.addresses(checked.getHost());
            current.get().check();
            return null;
        });
    }
```

`fetchBody(WorkflowDraft.Fetch fetch)` becomes `fetchBody(Request fetch)`; its body is unchanged (it reads
`fetch.url()` and `fetch.headers()`).

In `bounded`, admission and the operation's deadline change:

```java
    private <T> T bounded(Stage stage, long deadline, boolean wait, Callable<T> work) {
        if (closed.get()) throw failure(stage, CLIENT_CLOSED);
        acquire(stage, deadline, wait);
        long callDeadline = System.nanoTime() + properties.requestTimeout().toNanos();
        var operation = new Operation<T>(stage, deadline - callDeadline < 0 ? deadline : callDeadline);
        operations.add(operation);
        // … the rest of the method is unchanged …
    }

    private void acquire(Stage stage, long deadline, boolean wait) {
        try {
            long remaining = deadline - System.nanoTime();
            boolean admitted = wait ? remaining > 0 && permits.tryAcquire(remaining, TimeUnit.NANOSECONDS)
                    : permits.tryAcquire();
            if (!admitted) throw failure(stage, "busy; try again later");
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            throw failure(stage, "request interrupted");
        }
    }
```

In `Operation`, replace the field initializer with a constructor parameter:

```java
        private final long deadline;
        // …
        Operation(Stage stage, long deadline) { this.stage = stage; this.deadline = deadline; }
```

Remove the now unused `WorkflowDraft.Fetch` references from this class. `WorkflowDraft.Fetch` itself is removed in
Task 3. Until then, `WorkflowRunner` and `WorkflowTestService` call `http.fetch(draft.fetch())`. Make them compile by
wrapping each call: `http.fetch(new WorkflowHttpClient.Request(draft.fetch().url(), draft.fetch().headers()))`. In
`WorkflowRunnerTest` and `WorkflowTestServiceTest`, change `fetch(definition.draft().fetch())` in `when`/`verify` to
`fetch(any(WorkflowHttpClient.Request.class))`, keeping the rest of each test.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.sources.workflows.*'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/andre/homecontrol/sources/workflows/WorkflowProperties.java \
        src/main/java/dev/andre/homecontrol/sources/workflows/WorkflowHttpClient.java \
        src/main/java/dev/andre/homecontrol/sources/workflows/WorkflowRunner.java \
        src/main/java/dev/andre/homecontrol/sources/workflows/WorkflowTestService.java \
        src/test/java/dev/andre/homecontrol/sources/workflows/
git commit -m "feat: let a workflow fetch wait for a free slot until its run's deadline"
```

(Add `src/main/resources/application.yaml` if you changed it.)

---

### Task 2: Call URL templates and header templates

**Files:**
- Modify: `src/main/java/dev/andre/homecontrol/sources/workflows/WorkflowTemplate.java`
- Create: `src/main/java/dev/andre/homecontrol/sources/workflows/WorkflowHeaderTemplate.java`
- Test: `src/test/java/dev/andre/homecontrol/sources/workflows/WorkflowTemplateTest.java`,
  `src/test/java/dev/andre/homecontrol/sources/workflows/WorkflowHeaderTemplateTest.java` (create)

**Interfaces:**
- Produces: `new WorkflowTemplate(String template, Set<String> names, String label, WorkflowException.Stage stage)`;
  the two-argument constructor keeps label `"media URL"` and stage `BUILD`, so its messages are unchanged.
  `Set<String> WorkflowTemplate.references()`. `new WorkflowHeaderTemplate(String template)`,
  `Set<String> references()`, `String expand(Map<String, WorkflowJson.Value>)`, `static String literal(String)`.

- [ ] **Step 1: Write the failing tests**

Add to `WorkflowTemplateTest`:

```java
    @Test void callUrlTemplatesKeepTheirHostAndEncodeEveryValue() {
        var template = new WorkflowTemplate("https://api.example/items/{id}/details?lang={lang}",
                Set.of("id", "lang"), "call URL", WorkflowException.Stage.FETCH);
        assertThat(template.references()).containsExactlyInAnyOrder("id", "lang");
        var url = template.expand(Map.of("id", new WorkflowJson.Value("a/b@evil.example?x", false),
                "lang", new WorkflowJson.Value("de & en", false)));
        assertThat(url.getHost()).isEqualTo("api.example");
        assertThat(url.getRawPath()).isEqualTo("/items/a%2Fb%40evil.example%3Fx/details");
        assertThat(url.getRawQuery()).isEqualTo("lang=de%20%26%20en");
    }

    @Test void callUrlErrorsUseTheirLabelAndStage() {
        assertThatThrownBy(() -> new WorkflowTemplate("https://{host}/x", Set.of("host"), "call URL",
                WorkflowException.Stage.WORKFLOW))
                .isInstanceOf(WorkflowException.class)
                .hasMessage("Workflow: call URL placeholder must be in a path or query value");
        var template = new WorkflowTemplate("https://api.example/{id}", Set.of("id"), "call URL", WorkflowException.Stage.FETCH);
        assertThatThrownBy(() -> template.expand(Map.of("id", new WorkflowJson.Value("..", false))))
                .hasMessage("Fetch JSON: dot path segment in call URL");
    }

    @Test void mediaTemplateMessagesAreUnchanged() {
        assertThatThrownBy(() -> new WorkflowTemplate("https://media.example/{B}", Set.of("A")))
                .hasMessage("Build media URL: unknown media URL placeholder: B");
    }
```

Create `WorkflowHeaderTemplateTest`:

```java
package dev.andre.homecontrol.sources.workflows;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowHeaderTemplateTest {
    private static Map<String, WorkflowJson.Value> token(String value) {
        return Map.of("token", new WorkflowJson.Value(value, true));
    }

    @Test void substitutesVariablesAndKeepsDoubledBracesLiteral() {
        var template = new WorkflowHeaderTemplate("Bearer {token} {{json}}");
        assertThat(template.references()).containsExactly("token");
        assertThat(template.expand(token("abc"))).isEqualTo("Bearer abc {json}");
    }

    @Test void literalEscapingSendsAnyTextUnchanged() {
        String raw = "{\"a\":1} }{ {{";
        assertThat(new WorkflowHeaderTemplate(WorkflowHeaderTemplate.literal(raw)).expand(Map.of())).isEqualTo(raw);
    }

    @Test void rejectsMalformedPlaceholders() {
        for (String bad : List.of("{", "}", "{1a}", "{a", "{a b}", "x}y", "{}")) {
            assertThatThrownBy(() -> new WorkflowHeaderTemplate(bad)).as(bad)
                    .isInstanceOf(WorkflowException.class).hasMessage("Workflow: invalid header placeholder");
        }
    }

    @Test void rejectsSubstitutedLineBreaksControlCharactersAndLongValues() {
        var template = new WorkflowHeaderTemplate("Bearer {token}");
        for (String value : List.of("a\r\nX-Injected: 1", "a\u0000b", "a\u007fb", "x".repeat(1025))) {
            assertThatThrownBy(() -> template.expand(token(value)))
                    .isInstanceOf(WorkflowException.class)
                    .hasMessage("Fetch JSON: header value from token is not allowed");
        }
    }

    @Test void acceptsTabsAndTheLongestValue() {
        var template = new WorkflowHeaderTemplate("{token}");
        assertThat(template.expand(token("a\tb"))).isEqualTo("a\tb");
        assertThat(template.expand(token("x".repeat(1024)))).hasSize(1024);
    }

    @Test void aMissingValueIsAnError() {
        assertThatThrownBy(() -> new WorkflowHeaderTemplate("{token}").expand(Map.of()))
                .hasMessage("Fetch JSON: unresolved header placeholder");
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.sources.workflows.WorkflowTemplateTest' --tests 'dev.andre.homecontrol.sources.workflows.WorkflowHeaderTemplateTest'`
Expected: compilation fails (four-argument constructor, `references()`, `WorkflowHeaderTemplate` missing).

- [ ] **Step 3: Implement**

In `WorkflowTemplate`, add two fields and make every message use them. `tokenize`, `validUri` and `fail` become
instance methods, and the constructor sets `label` and `stage` first:

```java
    private final String label;
    private final WorkflowException.Stage stage;

    public WorkflowTemplate(String template, Set<String> variableNames) {
        this(template, variableNames, "media URL", WorkflowException.Stage.BUILD);
    }

    public WorkflowTemplate(String template, Set<String> variableNames, String label, WorkflowException.Stage stage) {
        this.label = label;
        this.stage = stage;
        if (template == null || template.isBlank() || template.length() > MAX_URL) fail("invalid " + label + " template length");
        this.template = template;
        this.tokens = tokenize(template, variableNames);
        // … unchanged: authority, pathStart, queryStart, validatePlaceholderPositions(), checked URI …
    }

    /** The variable names this template uses. */
    public Set<String> references() {
        return tokens.stream().filter(Token::variable).map(Token::text).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private void fail(String detail) {
        throw new WorkflowException(stage, detail);
    }
```

Replace each literal `media URL` in the messages with `label`:
`"invalid " + label + " placeholder"`, `"unknown " + label + " placeholder: " + name`,
`label + " placeholder must be in a path or query value"`, `label + " placeholder must be in a query value"`,
`"unresolved " + label + " placeholder"`, `"expanded " + label + " exceeds limit"`, `"invalid " + label + " template"`,
`"dot path segment in " + label`. In `validUri`, the `URISyntaxException` branch throws
`new WorkflowException(stage, "invalid " + label + " template")`. `encodeComponent` stays static.

Create `WorkflowHeaderTemplate.java`:

```java
package dev.andre.homecontrol.sources.workflows;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static dev.andre.homecontrol.sources.workflows.WorkflowException.Stage;

/** A request header value with {name} placeholders. {{ and }} stand for literal braces. Values are checked, not encoded. */
public final class WorkflowHeaderTemplate {
    private static final Pattern NAME = Pattern.compile("[A-Za-z]\\w{0,31}");
    private static final int MAX_VALUE = 1_024;
    private final List<Token> tokens;

    private record Token(String text, boolean variable) {}

    public WorkflowHeaderTemplate(String template) {
        if (template == null) throw new WorkflowException(Stage.WORKFLOW, "invalid header placeholder");
        tokens = tokenize(template);
    }

    private static List<Token> tokenize(String template) {
        List<Token> parts = new ArrayList<>();
        StringBuilder literal = new StringBuilder();
        int i = 0;
        while (i < template.length()) {
            char c = template.charAt(i);
            if (template.startsWith("{{", i)) {
                literal.append('{');
                i += 2;
            } else if (template.startsWith("}}", i)) {
                literal.append('}');
                i += 2;
            } else if (c == '{') {
                int end = template.indexOf('}', i);
                String name = end < 0 ? "" : template.substring(i + 1, end);
                if (!NAME.matcher(name).matches()) throw new WorkflowException(Stage.WORKFLOW, "invalid header placeholder");
                if (!literal.isEmpty()) {
                    parts.add(new Token(literal.toString(), false));
                    literal.setLength(0);
                }
                parts.add(new Token(name, true));
                i = end + 1;
            } else if (c == '}') {
                throw new WorkflowException(Stage.WORKFLOW, "invalid header placeholder");
            } else {
                literal.append(c);
                i++;
            }
        }
        if (!literal.isEmpty()) parts.add(new Token(literal.toString(), false));
        return List.copyOf(parts);
    }

    public Set<String> references() {
        return tokens.stream().filter(Token::variable).map(Token::text).collect(Collectors.toUnmodifiableSet());
    }

    public String expand(Map<String, WorkflowJson.Value> values) {
        StringBuilder out = new StringBuilder();
        for (Token token : tokens) {
            if (!token.variable()) {
                out.append(token.text());
                continue;
            }
            WorkflowJson.Value value = values.get(token.text());
            if (value == null || value.text() == null) throw new WorkflowException(Stage.FETCH, "unresolved header placeholder");
            String text = value.text();
            if (text.length() > MAX_VALUE || text.chars().anyMatch(WorkflowHeaderTemplate::control)) {
                throw new WorkflowException(Stage.FETCH, "header value from " + token.text() + " is not allowed");
            }
            out.append(text);
        }
        return out.toString();
    }

    /** The template whose expansion is {@code literal} itself. */
    public static String literal(String literal) {
        return literal.replace("{", "{{").replace("}", "}}");
    }

    private static boolean control(int c) {
        return (c < 0x20 && c != '\t') || c == 0x7f;
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.sources.workflows.*'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/andre/homecontrol/sources/workflows/WorkflowTemplate.java \
        src/main/java/dev/andre/homecontrol/sources/workflows/WorkflowHeaderTemplate.java \
        src/test/java/dev/andre/homecontrol/sources/workflows/WorkflowTemplateTest.java \
        src/test/java/dev/andre/homecontrol/sources/workflows/WorkflowHeaderTemplateTest.java
git commit -m "feat: add templates for workflow call URLs and header values"
```

---

### Task 3: Schema version 2 with migration from version 1

The in-memory model becomes version 2. Behaviour stays that of version 1: this task runs only the first call. Tasks
5 and 6 add multi-call execution.

**Files:**
- Rewrite: `src/main/java/dev/andre/homecontrol/sources/workflows/WorkflowDraft.java`
- Modify: `WorkflowDefinition.java`, `WorkflowCodec.java`, `WorkflowJson.java`, `WorkflowRunner.java`,
  `WorkflowTestService.java`, `WorkflowForm.java`, `WorkflowStore.java`, `WorkflowException.java`
- Create: `src/main/java/dev/andre/homecontrol/sources/workflows/WorkflowMigration.java`
- Rewrite: `src/main/java/dev/andre/homecontrol/sources/workflows/WorkflowValidator.java`
- Test: `WorkflowFixtures.java` (rewrite), `WorkflowMigrationTest.java` (create), and every test in the package that
  builds a `WorkflowDraft` (list below)

**Interfaces:**
- Consumes: `WorkflowTemplate(template, names, label, stage)`, `WorkflowHeaderTemplate`, `WorkflowHttpClient.Request`.
- Produces:
  - `WorkflowDraft(String name, boolean enabled, Mode mode, ContentKind kind, List<Call> calls, Listing listing, Tile tile, Cast cast)`
  - `WorkflowDraft.withEnabled(boolean)`
  - `enum CallScope { SHARED, ENTRY }`
  - `Call(String name, CallScope scope, String url, List<Header> headers, List<Variable> variables)`
  - `Listing(String call, String arrayPointer, String idPointer, String titlePointer, Field subtitle, Field artwork, List<Variable> variables)`
  - `Field(String pointer, String variable)`, where exactly one is non-null
  - `Variable(String name, String pointer, boolean sensitive)`
  - `Header`, `Tile` and `Cast` are unchanged
  - `WorkflowDefinition.SCHEMA_VERSION` (= 2)
  - `WorkflowValidator.validateStored(WorkflowDraft)` (structure, used by the codec) and
    `WorkflowValidator.validate(WorkflowDraft)` (save rules, used by the controller)
  - `WorkflowJson.entries(Listing, JsonNode, int limit)` and `WorkflowJson.values(List<Variable>, JsonNode)`
  - `static WorkflowHttpClient.Request WorkflowRunner.request(Call, Set<String> names, Map<String, WorkflowJson.Value>)`
  - `WorkflowException.detail()`

- [ ] **Step 1: Write the failing tests**

Rewrite `WorkflowFixtures.java` (keep `CHANNELS` and `REORDERED_CHANNELS` as they are):

```java
    public static WorkflowDraft single(URI source) {
        return new WorkflowDraft("News", true, Mode.SINGLE, ContentKind.VIDEO,
                List.of(new Call("main", CallScope.SHARED, source.toString(),
                        List.of(new Header("Authorization", "Bearer saved-secret")),
                        List.of(new Variable("A", "/id", false), new Variable("C", "/token", true)))),
                null, new Tile("News", null, null),
                new Cast("https://media.example/play?id={A}&token={C}", "video/mp4"));
    }

    public static WorkflowDraft generated() {
        return new WorkflowDraft("News", true, Mode.GENERATED, ContentKind.VIDEO,
                List.of(new Call("main", CallScope.SHARED, "https://api.example/catalog",
                        List.of(new Header("Authorization", "Bearer saved-secret")),
                        List.of(new Variable("C", "/token", true)))),
                new Listing("main", "/items", "/id", "/title", null, null, List.of(new Variable("A", "/id", false))),
                null, new Cast("https://media.example/play?id={A}&token={C}", "video/mp4"));
    }

    /** A single-tile workflow with the given calls and a media URL that uses none of their values. */
    public static WorkflowDraft singleWith(List<Call> calls) {
        return new WorkflowDraft("Calls", true, Mode.SINGLE, ContentKind.VIDEO, calls, null,
                new Tile("Calls", null, null), new Cast("https://media.example/play", "video/mp4"));
    }
```

Create `WorkflowMigrationTest.java`:

```java
package dev.andre.homecontrol.sources.workflows;

import org.junit.jupiter.api.Test;

import static dev.andre.homecontrol.sources.workflows.WorkflowDraft.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowMigrationTest {
    private final WorkflowCodec codec = new WorkflowCodec();

    private static final String GENERATED_V1 = """
            {"schemaVersion":1,"id":"w-0123456789ab","revision":7,"draft":{
              "name":"News","enabled":true,"mode":"GENERATED","kind":"VIDEO",
              "fetch":{"url":"https://api.example/catalog","headers":[{"name":"X-Filter","value":"{\\"a\\":1}"}]},
              "listing":{"arrayPointer":"/items","idPointer":"/id","titlePointer":"/title",
                         "subtitlePointer":"/sub","artworkPointer":null},
              "tile":null,
              "variables":[{"name":"A","scope":"ENTRY","pointer":"/id","sensitive":false},
                           {"name":"C","scope":"ROOT","pointer":"/token","sensitive":true}],
              "cast":{"template":"https://media.example/play?id={A}&token={C}","mimeType":"video/mp4"}}}
            """;

    @Test void generatedV1BecomesOneSharedCallThatIsTheEntrySource() {
        var definition = codec.decode(GENERATED_V1);
        assertThat(definition.schemaVersion()).isEqualTo(2);
        assertThat(definition.id()).isEqualTo("w-0123456789ab");
        assertThat(definition.revision()).isEqualTo(7);
        var draft = definition.draft();
        assertThat(draft.calls()).singleElement().satisfies(call -> {
            assertThat(call.name()).isEqualTo("main");
            assertThat(call.scope()).isEqualTo(CallScope.SHARED);
            assertThat(call.url()).isEqualTo("https://api.example/catalog");
            assertThat(call.variables()).containsExactly(new Variable("C", "/token", true));
        });
        assertThat(draft.listing()).isEqualTo(new Listing("main", "/items", "/id", "/title",
                new Field("/sub", null), null, java.util.List.of(new Variable("A", "/id", false))));
    }

    @Test void migrationKeepsBracesInV1HeadersLiteral() {
        var header = codec.decode(GENERATED_V1).draft().calls().getFirst().headers().getFirst();
        assertThat(header.value()).isEqualTo("{{\"a\":1}}");
        assertThat(new WorkflowHeaderTemplate(header.value()).expand(java.util.Map.of())).isEqualTo("{\"a\":1}");
    }

    @Test void encodingWritesVersionTwoAndReadsItBack() {
        var migrated = codec.decode(GENERATED_V1);
        String encoded = codec.encode(migrated);
        assertThat(encoded).contains("\"schemaVersion\":2").doesNotContain("\"fetch\"");
        assertThat(codec.decode(encoded)).isEqualTo(migrated);
    }

    @Test void singleV1WithoutMappingsStaysReadable() {
        String v1 = """
                {"schemaVersion":1,"id":"w-0123456789ab","revision":1,"draft":{
                  "name":"Radio","enabled":true,"mode":"SINGLE","kind":"TRACK",
                  "fetch":{"url":"https://api.example/ping","headers":[]},"listing":null,
                  "tile":{"title":"Radio","subtitle":null,"artwork":null},"variables":[],
                  "cast":{"template":"https://media.example/radio.mp3","mimeType":"audio/mpeg"}}}
                """;
        var draft = codec.decode(v1).draft();
        assertThat(draft.calls()).singleElement().extracting(Call::name).isEqualTo("main");
        assertThatThrownBy(() -> WorkflowValidator.validate(draft))
                .hasMessage("Workflow: call main: nothing uses this call");
    }

    @Test void unknownVersionsAreRefused() {
        assertThatThrownBy(() -> codec.decode(GENERATED_V1.replace("\"schemaVersion\":1", "\"schemaVersion\":3")))
                .hasMessage("Workflow: unsupported definition schema");
    }
}
```

`singleV1WithoutMappingsStaysReadable` needs Task 4's unused-call rule. Mark it `@org.junit.jupiter.api.Disabled("Task 4")`
now and remove the annotation in Task 4.

Add these validator tests to `WorkflowDefinitionTest`. Its old tests exercise v1-only shapes, so rewrite those per the
list in Step 3.

```java
    @Test void callNamesAreLowerCaseUniqueAndAtMostEight() {
        var draft = WorkflowFixtures.generated();
        for (String name : List.of("", "Main", "1st", "a-b", "a".repeat(25))) {
            invalid(withCalls(draft, List.of(call(name, "https://api.example/x"))));
        }
        invalid(withCalls(draft, List.of(call("main", "https://api.example/a"), call("main", "https://api.example/b"))));
        List<Call> eight = new ArrayList<>(draft.calls());
        for (int i = 1; i < 8; i++) eight.add(call("c" + i, "https://api.example/" + i));
        WorkflowValidator.validateStored(withCalls(draft, eight));
        eight.add(call("c8", "https://api.example/8"));
        invalid(withCalls(draft, eight));
        invalid(withCalls(draft, List.of()));
    }

    @Test void variableNamesAreUniqueAcrossTheWholeWorkflow() {
        var draft = WorkflowFixtures.generated();
        var clash = new Call("second", CallScope.SHARED, "https://api.example/b", List.of(),
                List.of(new Variable("A", "/a", false)));
        var calls = new ArrayList<>(draft.calls());
        calls.add(clash);
        assertThatThrownBy(() -> WorkflowValidator.validateStored(withCalls(draft, calls)))
                .hasMessage("Workflow: duplicate mapping name: A");
    }

    @Test void atMostSixtyFourVariables() {
        var draft = WorkflowFixtures.single(URI.create("https://api.example/catalog"));
        List<Variable> many = new ArrayList<>();
        for (int i = 0; i < 64; i++) many.add(new Variable("V" + i, "/id", false));
        var first = draft.calls().getFirst();
        WorkflowValidator.validateStored(withCalls(draft, List.of(
                new Call("main", CallScope.SHARED, first.url(), first.headers(), many))));
        many.add(new Variable("V64", "/id", false));
        invalid(withCalls(draft, List.of(new Call("main", CallScope.SHARED, first.url(), first.headers(), many))));
    }

    @Test void perEntryCallsNeedGeneratedTiles() {
        var draft = WorkflowFixtures.single(URI.create("https://api.example/catalog"));
        var first = draft.calls().getFirst();
        invalid(withCalls(draft, List.of(new Call("main", CallScope.ENTRY, first.url(), first.headers(), first.variables()))));
    }

    @Test void theEntrySourceMustBeASharedCall() {
        var draft = WorkflowFixtures.generated();
        var listing = draft.listing();
        invalid(withListing(draft, new Listing("missing", listing.arrayPointer(), listing.idPointer(),
                listing.titlePointer(), null, null, listing.variables())));
    }

    @Test void aDisplayFieldReadsEitherAPointerOrAVariable() {
        var draft = WorkflowFixtures.generated();
        var l = draft.listing();
        invalid(withListing(draft, new Listing(l.call(), l.arrayPointer(), l.idPointer(), l.titlePointer(),
                new Field("/sub", "A"), null, l.variables())));
        invalid(withListing(draft, new Listing(l.call(), l.arrayPointer(), l.idPointer(), l.titlePointer(),
                new Field(null, null), null, l.variables())));
        invalid(withListing(draft, new Listing(l.call(), l.arrayPointer(), l.idPointer(), l.titlePointer(),
                null, new Field(null, "Unknown"), l.variables())));
        WorkflowValidator.validateStored(withListing(draft, new Listing(l.call(), l.arrayPointer(), l.idPointer(),
                l.titlePointer(), new Field("/sub", null), new Field(null, "A"), l.variables())));
    }

    @Test void headerValuesMustBeValidTemplates() {
        var draft = WorkflowFixtures.single(URI.create("https://api.example/catalog"));
        var first = draft.calls().getFirst();
        invalid(withCalls(draft, List.of(new Call("main", CallScope.SHARED, first.url(),
                List.of(new Header("X-Bad", "{unclosed")), first.variables()))));
    }

    private static Call call(String name, String url) {
        return new Call(name, CallScope.SHARED, url, List.of(), List.of());
    }

    private static WorkflowDraft withCalls(WorkflowDraft d, List<Call> calls) {
        return new WorkflowDraft(d.name(), d.enabled(), d.mode(), d.kind(), calls, d.listing(), d.tile(), d.cast());
    }

    private static WorkflowDraft withListing(WorkflowDraft d, Listing listing) {
        return new WorkflowDraft(d.name(), d.enabled(), d.mode(), d.kind(), d.calls(), listing, d.tile(), d.cast());
    }
```

`invalid(draft)` in that class must call `WorkflowValidator.validateStored(draft)` and expect a `WorkflowException`.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.sources.workflows.WorkflowMigrationTest'`
Expected: compilation fails (`Call`, `Listing(String, …)`, `validateStored` do not exist).

- [ ] **Step 3: Implement**

`WorkflowDraft.java`:

```java
package dev.andre.homecontrol.sources.workflows;

import dev.andre.homecontrol.core.playback.ContentKind;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A workflow's editable settings: its calls, where tiles come from, and the Cast action. Schema version 2. */
public record WorkflowDraft(String name, boolean enabled, Mode mode, ContentKind kind,
                            List<Call> calls, Listing listing, Tile tile, Cast cast) {
    public enum Mode { SINGLE, GENERATED }
    public enum CallScope { SHARED, ENTRY }

    public WorkflowDraft {
        calls = copy(calls);
    }

    public WorkflowDraft withEnabled(boolean enabled) {
        return new WorkflowDraft(name, enabled, mode, kind, calls, listing, tile, cast);
    }

    @Override public String toString() {
        return "WorkflowDraft[mode=" + mode + "]";
    }

    private static <T> List<T> copy(List<T> list) {
        return list == null ? null : Collections.unmodifiableList(new ArrayList<>(list));
    }

    /** A header value is a {@link WorkflowHeaderTemplate}. */
    public record Header(String name, String value) {
        @Override public String toString() { return "Header"; }
    }

    /** One value read from a JSON response. */
    public record Variable(String name, String pointer, boolean sensitive) {
        @Override public String toString() { return "Variable"; }
    }

    /** One GET. Its URL is a call-URL {@link WorkflowTemplate}; its variables read its response. */
    public record Call(String name, CallScope scope, String url, List<Header> headers, List<Variable> variables) {
        public Call {
            headers = copy(headers);
            variables = copy(variables);
        }

        @Override public String toString() { return "Call[" + name + "]"; }
    }

    /**
     * Where generated tiles come from: an array in one shared call's response. Its pointers read each entry, and
     * its variables are the entry values.
     */
    public record Listing(String call, String arrayPointer, String idPointer, String titlePointer,
                          Field subtitle, Field artwork, List<Variable> variables) {
        public Listing {
            variables = copy(variables);
        }

        @Override public String toString() { return "Listing"; }
    }

    /** A generated tile's subtitle or artwork: read from the entry by pointer, or taken from a variable. */
    public record Field(String pointer, String variable) {}

    public record Tile(String title, String subtitle, String artwork) {
        @Override public String toString() { return "Tile"; }
    }

    public record Cast(String template, String mimeType) {
        @Override public String toString() { return "Cast"; }
    }
}
```

`WorkflowDefinition.java`: add `public static final int SCHEMA_VERSION = 2;` above the `toString`.

`WorkflowException.java`:

```java
public final class WorkflowException extends RuntimeException {
    public enum Stage { FETCH, PARSE, SELECT, MAP, BUILD, WORKFLOW }

    private final Stage stage;
    private final String detail;
    private final String call;

    public WorkflowException(Stage stage, String safeDetail) {
        this(stage, label(stage), safeDetail, null);
    }

    private WorkflowException(Stage stage, String context, String detail, String call) {
        super(context + ": " + detail);
        this.stage = stage;
        this.detail = detail;
        this.call = call;
    }

    public Stage stage() { return stage; }

    /** The message without its context, e.g. {@code server returned HTTP 404}. */
    public String detail() { return detail; }

    /** The call that failed, or null when the failure was not inside a call. */
    public String call() { return call; }

    /** The same failure, told as part of a call and, for a per-entry call, the entry's public title. */
    public WorkflowException inCall(String callName, String entryTitle) {
        if (call != null) return this;
        String context = "Call " + callName + (entryTitle == null ? "" : " · entry \"" + entryTitle + "\"");
        return new WorkflowException(stage, context, detail, callName);
    }

    private static String label(Stage stage) { /* unchanged */ }
}
```

`inCall` is used from Task 5 on. It is added here because `detail()` belongs with it.

`WorkflowMigration.java`:

```java
package dev.andre.homecontrol.sources.workflows;

import dev.andre.homecontrol.core.playback.ContentKind;

import java.util.List;

import static dev.andre.homecontrol.sources.workflows.WorkflowDraft.*;

/** Reads a schema version 1 definition and turns it into version 2: its one fetch becomes the shared call "main". */
final class WorkflowMigration {
    static final String MAIN = "main";

    private WorkflowMigration() {}

    record V1Definition(int schemaVersion, String id, long revision, V1Draft draft) {
        @Override public String toString() { return "V1Definition"; }
    }

    record V1Draft(String name, boolean enabled, Mode mode, ContentKind kind, V1Fetch fetch, V1Listing listing,
                   Tile tile, List<V1Variable> variables, Cast cast) {
        @Override public String toString() { return "V1Draft"; }
    }

    record V1Fetch(String url, List<Header> headers) {
        @Override public String toString() { return "V1Fetch"; }
    }

    record V1Listing(String arrayPointer, String idPointer, String titlePointer, String subtitlePointer,
                     String artworkPointer) {}

    record V1Variable(String name, String scope, String pointer, boolean sensitive) {
        @Override public String toString() { return "V1Variable"; }
    }

    static WorkflowDefinition toV2(V1Definition v1) {
        if (v1 == null || v1.draft() == null || v1.draft().fetch() == null || v1.draft().variables() == null
                || v1.draft().fetch().headers() == null) {
            throw new WorkflowException(WorkflowException.Stage.WORKFLOW, "definition could not be parsed");
        }
        V1Draft d = v1.draft();
        // v1 header values were literal text; in v2 braces are template syntax, so double them.
        List<Header> headers = d.fetch().headers().stream()
                .map(h -> new Header(h.name(), h.value() == null ? null : WorkflowHeaderTemplate.literal(h.value())))
                .toList();
        Call main = new Call(MAIN, CallScope.SHARED, d.fetch().url(), headers, variables(d.variables(), "ROOT"));
        Listing listing = d.listing() == null ? null : new Listing(MAIN, d.listing().arrayPointer(),
                d.listing().idPointer(), d.listing().titlePointer(), pointer(d.listing().subtitlePointer()),
                pointer(d.listing().artworkPointer()), variables(d.variables(), "ENTRY"));
        return new WorkflowDefinition(WorkflowDefinition.SCHEMA_VERSION, v1.id(), v1.revision(),
                new WorkflowDraft(d.name(), d.enabled(), d.mode(), d.kind(), List.of(main), listing, d.tile(), d.cast()));
    }

    private static List<Variable> variables(List<V1Variable> all, String scope) {
        return all.stream().filter(v -> v != null && scope.equals(v.scope()))
                .map(v -> new Variable(v.name(), v.pointer(), v.sensitive())).toList();
    }

    private static Field pointer(String pointer) {
        return pointer == null ? null : new Field(pointer, null);
    }
}
```

A v1 variable with a null or unknown scope is dropped. v1's validator never stored one, and if one appears, the
v2 validator reports the template's unknown placeholder instead.

`WorkflowCodec.java` — `decode` reads both versions, and both directions validate with `validateStored`:

```java
    public WorkflowDefinition decode(String encoded) {
        checkLength(encoded);
        try {
            JsonNode node = JSON.readTree(encoded);
            if (node == null || !node.isObject() || !node.path("schemaVersion").isInt()) throw unsupported();
            WorkflowDefinition definition = switch (node.path("schemaVersion").intValue()) {
                case 1 -> WorkflowMigration.toV2(JSON.treeToValue(node, WorkflowMigration.V1Definition.class));
                case WorkflowDefinition.SCHEMA_VERSION -> JSON.treeToValue(node, WorkflowDefinition.class);
                default -> throw unsupported();
            };
            validate(definition);
            return definition;
        } catch (WorkflowException e) {
            throw e;
        } catch (JacksonException | IllegalArgumentException _) {
            throw new WorkflowException(WorkflowException.Stage.WORKFLOW, "definition could not be parsed");
        }
    }

    private static WorkflowException unsupported() {
        return new WorkflowException(WorkflowException.Stage.WORKFLOW, "unsupported definition schema");
    }

    private static void validate(WorkflowDefinition definition) {
        if (definition == null || definition.schemaVersion() != WorkflowDefinition.SCHEMA_VERSION) throw unsupported();
        // … id and revision checks unchanged …
        WorkflowValidator.validateStored(definition.draft());
    }
```

`WorkflowValidator.java` — full rewrite. Messages keep the words the controller's field mapping looks for
(`mapping`, `fetch URL`, `header`, `entry … pointer`):

```java
package dev.andre.homecontrol.sources.workflows;

import dev.andre.homecontrol.core.playback.ContentKind;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import static dev.andre.homecontrol.sources.workflows.WorkflowDraft.*;

/** Save-time syntax, reference and size checks. Address resolution belongs to the outbound fetch policy. */
public final class WorkflowValidator {
    static final int MAX_CALLS = 8;
    static final int MAX_HEADERS = 16;
    static final int MAX_VARIABLES = 64;
    private static final String INVALID_PREFIX = "invalid ";
    private static final Pattern CALL_NAME = Pattern.compile("[a-z][a-z0-9_]{0,23}");
    private static final Pattern NAME = Pattern.compile("[A-Za-z]\\w{0,31}");
    /** In a JSON pointer, {@code ~} only ever starts {@code ~0} or {@code ~1}. */
    private static final Pattern BAD_POINTER_ESCAPE = Pattern.compile("~(?![01])");
    private static final Pattern MIME = Pattern.compile("[A-Za-z0-9!#$&^_.+*-]+/[A-Za-z0-9!#$&^_.+*-]+");
    private static final Pattern HEADER_NAME = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");
    private static final Set<String> DENIED_HEADERS = Set.of("host", "cookie", "connection", "content-length",
            "transfer-encoding", "te", "trailer", "upgrade", "keep-alive", "expect", "accept-encoding", "proxy");

    private WorkflowValidator() {}

    /** Everything a stored definition satisfies. The codec checks this when reading and writing. */
    public static void validateStored(WorkflowDraft draft) {
        if (draft == null) fail("definition has no draft");
        text(draft.name(), 120, "name");
        if (draft.mode() == null) fail("mode is required");
        if (draft.kind() != ContentKind.VIDEO && draft.kind() != ContentKind.TRACK) fail("kind must be video or audio");
        Set<String> names = variableNames(draft);
        calls(draft, names);
        presentation(draft, names);
        cast(draft.cast(), names);
    }

    /** What a Save additionally requires. */
    public static void validate(WorkflowDraft draft) {
        validateStored(draft);
    }

    private static Set<String> variableNames(WorkflowDraft draft) {
        if (draft.calls() == null || draft.calls().isEmpty()) fail("at least one call is required");
        if (draft.calls().size() > MAX_CALLS) fail("too many calls");
        Set<String> names = new HashSet<>();
        for (Call call : draft.calls()) {
            if (call == null || call.variables() == null) fail("mappings are required");
            for (Variable variable : call.variables()) variable(variable, names);
        }
        if (draft.listing() != null) {
            if (draft.listing().variables() == null) fail("mappings are required");
            for (Variable variable : draft.listing().variables()) variable(variable, names);
        }
        if (names.size() > MAX_VARIABLES) fail("too many mappings");
        return names;
    }

    private static void variable(Variable variable, Set<String> names) {
        if (variable == null || variable.name() == null || !NAME.matcher(variable.name()).matches()) fail("invalid mapping name");
        if (!names.add(variable.name())) fail("duplicate mapping name: " + variable.name());
        pointer(variable.pointer(), "mapping " + variable.name(), true);
    }

    private static void calls(WorkflowDraft draft, Set<String> variables) {
        Set<String> names = new HashSet<>();
        for (Call call : draft.calls()) {
            if (call.name() == null || !CALL_NAME.matcher(call.name()).matches()) fail("invalid call name");
            if (!names.add(call.name())) fail("duplicate call name: " + call.name());
            String context = "call " + call.name() + ": ";
            if (call.scope() == null || (draft.mode() == Mode.SINGLE && call.scope() != CallScope.SHARED)) {
                fail(context + "invalid scope");
            }
            try {
                new WorkflowTemplate(call.url(), variables, "call URL", WorkflowException.Stage.WORKFLOW);
            } catch (WorkflowException _) {
                fail(context + INVALID_PREFIX + "fetch URL");
            }
            headers(call, context);
        }
    }

    private static void headers(Call call, String context) {
        if (call.headers() == null) fail(context + "headers are required");
        if (call.headers().size() > MAX_HEADERS) fail(context + "too many headers");
        Set<String> names = new HashSet<>();
        for (Header header : call.headers()) {
            if (header == null || header.name() == null || !HEADER_NAME.matcher(header.name()).matches()) {
                fail(context + "invalid header name");
            }
            String lower = header.name().toLowerCase(Locale.ROOT);
            if (DENIED_HEADERS.contains(lower) || lower.startsWith("proxy-")) fail(context + "header is not allowed: " + header.name());
            if (!names.add(lower)) fail(context + "duplicate header: " + header.name());
            if (header.value() == null || header.value().chars().anyMatch(c -> c == '\r' || c == '\n' || c == 0)) {
                fail(context + "invalid header value: " + header.name());
            }
            try {
                new WorkflowHeaderTemplate(header.value());
            } catch (WorkflowException _) {
                fail(context + "invalid header value: " + header.name());
            }
        }
    }

    private static void presentation(WorkflowDraft draft, Set<String> variables) {
        if (draft.mode() == Mode.SINGLE) {
            if (draft.listing() != null) fail("single mode cannot have entry selection");
            if (draft.tile() == null) fail("single mode requires a tile");
            text(draft.tile().title(), 120, "tile title");
            optionalText(draft.tile().subtitle(), 240, "tile subtitle");
            if (draft.tile().artwork() != null && WorkflowJson.artwork(draft.tile().artwork()) == null) fail("invalid artwork URL");
            return;
        }
        if (draft.tile() != null) fail("generated mode cannot have a saved tile");
        Listing listing = draft.listing();
        if (listing == null) fail("generated mode requires entry selection");
        boolean shared = draft.calls().stream()
                .anyMatch(call -> call.name().equals(listing.call()) && call.scope() == CallScope.SHARED);
        if (!shared) fail("the entry source must be a shared call");
        pointer(listing.arrayPointer(), "array", true);
        pointer(listing.idPointer(), "entry ID", true);
        pointer(listing.titlePointer(), "entry title", true);
        display(listing.subtitle(), "entry subtitle", variables);
        display(listing.artwork(), "entry artwork", variables);
    }

    private static void display(Field field, String label, Set<String> variables) {
        if (field == null) return;
        if ((field.pointer() == null) == (field.variable() == null)) fail(INVALID_PREFIX + label);
        if (field.pointer() != null) pointer(field.pointer(), label, true);
        else if (!variables.contains(field.variable())) fail("unknown " + label + " variable");
    }

    private static void cast(Cast cast, Set<String> names) {
        if (cast == null) fail("Cast action is required");
        new WorkflowTemplate(cast.template(), names);
        String mime = cast.mimeType();
        if (mime == null || mime.length() > 100 || !MIME.matcher(mime).matches()) fail("invalid media type");
    }

    private static void pointer(String value, String field, boolean required) {
        if (value == null) {
            if (required) fail(field + " pointer is required");
            return;
        }
        if (value.length() > 512 || (!value.isEmpty() && !value.startsWith("/"))) fail(INVALID_PREFIX + field + " pointer");
        if (BAD_POINTER_ESCAPE.matcher(value).find()) fail(INVALID_PREFIX + field + " pointer escape");
    }

    private static void text(String value, int max, String field) {
        if (value == null || value.isBlank() || value.length() > max) fail(INVALID_PREFIX + field);
    }

    private static void optionalText(String value, int max, String field) {
        if (value != null && value.length() > max) fail(INVALID_PREFIX + field);
    }

    private static void fail(String detail) {
        throw new WorkflowException(WorkflowException.Stage.WORKFLOW, detail);
    }
}
```

The old v1 `url()` check (scheme, host, userinfo, fragment, dot segments on the literal) is now done by the
`WorkflowTemplate` constructor, which validates the template with every placeholder replaced by `x`.

`WorkflowJson.java`:
- Replace `entries(WorkflowDraft, JsonNode)` and `generatedEntries` with
  `public static List<Entry> entries(Listing listing, JsonNode root, int limit)`. Its body is the old
  `generatedEntries` body, with the size check now reading:
  `if (array.size() > limit) fail(WorkflowException.Stage.SELECT, "the list has " + array.size() + " entries; the limit is " + limit);`
- Make `subtitle(listing, node)` and `artwork(listing, node)` read `listing.subtitle()` and `listing.artwork()`. They
  read a pointer only when the field is non-null and `pointer()` is non-null; otherwise they return null (a
  variable-based field is filled by the runner).
- Replace `values(List<Variable>, JsonNode root, JsonNode entry)` with:

```java
    /** Reads each variable from {@code context}, which is a call's response or one entry of the list. */
    public static Map<String, Value> values(List<Variable> variables, JsonNode context) {
        Map<String, Value> values = new HashMap<>();
        for (Variable variable : variables) {
            JsonNode node = select(context, variable.pointer());
            if (node == null || node.isNull() || !(node.isTextual() || node.isNumber() || node.isBoolean())) {
                fail(WorkflowException.Stage.MAP, "mapping " + variable.name() + " has no scalar value");
            }
            values.put(variable.name(), new Value(node.isTextual() ? node.asText() : node.toString(), variable.sensitive()));
        }
        return Map.copyOf(values);
    }
```

`WorkflowRunner.java` — interim version that runs only the first call, which is how every v1 workflow behaves:

```java
package dev.andre.homecontrol.sources.workflows;

import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static dev.andre.homecontrol.sources.workflows.WorkflowDraft.*;

/** One response belongs to one call; neither raw JSON nor resolved secrets enter the catalog. */
public final class WorkflowRunner {
    static final int ENTRY_LIMIT = 200;
    private final WorkflowHttpClient http;

    public WorkflowRunner(WorkflowHttpClient http) { this.http = http; }

    public record CatalogEntry(String key, String title, String subtitle, URI artwork) {}

    public record ResolvedMedia(URI url, String mimeType, String title) {
        @Override public String toString() { return "ResolvedMedia[redacted]"; }
    }

    public List<CatalogEntry> catalog(WorkflowDefinition definition) {
        var draft = definition.draft();
        var root = fetch(draft.calls().getFirst());
        return WorkflowJson.entries(draft.listing(), root, ENTRY_LIMIT).stream()
                .map(entry -> new CatalogEntry(entry.key(), entry.title(), entry.subtitle(), entry.artwork())).toList();
    }

    public ResolvedMedia resolve(WorkflowDefinition definition, String entryKey) {
        var draft = definition.draft();
        var call = draft.calls().getFirst();
        var root = fetch(call);
        Map<String, WorkflowJson.Value> values = new HashMap<>(WorkflowJson.values(call.variables(), root));
        String title = draft.mode() == Mode.SINGLE ? draft.tile().title() : null;
        if (draft.mode() == Mode.GENERATED) {
            var selected = WorkflowJson.entries(draft.listing(), root, ENTRY_LIMIT).stream()
                    .filter(entry -> entry.key().equals(entryKey)).findFirst()
                    .orElseThrow(() -> new WorkflowException(WorkflowException.Stage.SELECT,
                            "entry disappeared; refresh this workflow"));
            values.putAll(WorkflowJson.values(draft.listing().variables(), selected.node()));
            title = selected.title();
        }
        URI url = new WorkflowTemplate(draft.cast().template(), values.keySet()).expand(values);
        http.checkMedia(url);
        return new ResolvedMedia(url, draft.cast().mimeType(), title);
    }

    private JsonNode fetch(Call call) {
        return WorkflowJson.parse(http.fetch(request(call, Set.of(), Map.of())));
    }

    /** The GET a call makes, with the values known so far substituted into its URL and headers. */
    static WorkflowHttpClient.Request request(Call call, Set<String> names, Map<String, WorkflowJson.Value> values) {
        URI url = new WorkflowTemplate(call.url(), names, "call URL", WorkflowException.Stage.FETCH).expand(values);
        List<Header> headers = call.headers().stream()
                .map(header -> new Header(header.name(), new WorkflowHeaderTemplate(header.value()).expand(values)))
                .toList();
        return new WorkflowHttpClient.Request(url.toString(), headers);
    }
}
```

`resolve` builds the media template with the names it actually read. In v1, an entry-scoped variable was always
present at Play, so this matches.

`WorkflowTestService.java` — replace `http.fetch(saved.draft().fetch())` with
`http.fetch(WorkflowRunner.request(draft.calls().getFirst(), java.util.Set.of(), java.util.Map.of()))`. Replace
`WorkflowJson.entries(saved.draft(), root)` with:
`draft.mode() == Mode.SINGLE ? List.of(new WorkflowJson.Entry("single", draft.tile().title(), draft.tile().subtitle(), WorkflowJson.artwork(draft.tile().artwork()), root)) : WorkflowJson.entries(draft.listing(), root, WorkflowRunner.ENTRY_LIMIT)`.
Where it computes values per entry, use
`values = new HashMap<>(WorkflowJson.values(first.variables(), root))` plus, in generated mode,
`values.putAll(WorkflowJson.values(draft.listing().variables(), entry.node()))`. The masked list iterates
`first.variables()` then `draft.listing().variables()`. Build the template with `values.keySet()`. The artwork
warning checks `draft.listing().artwork() != null && draft.listing().artwork().pointer() != null` and reads that
pointer. `WorkflowJson.Entry`'s constructor must be callable from the package (it is a public record).

`WorkflowForm.java` — interim mapping onto one call named `main`. The fields and the editor stay as they are:
- `toDraft(saved)`:
  - The saved call is `saved.draft().calls().getFirst()` for Keep of URL and headers.
  - It builds
    `new Call("main", CallScope.SHARED, source, requestHeaders, variables.stream().filter(r -> r.scope == Scope.ROOT).map(r -> new Variable(r.name, r.pointer, r.sensitive)).toList())`.
  - Replacement header values are passed through `WorkflowHeaderTemplate.literal(row.value)`. The v1 editor promises
    literal values, and Task 8 replaces this form.
  - `listing()` returns
    `new Listing("main", arrayPointer, idPointer, titlePointer, includeSubtitlePointer ? new Field(subtitlePointer, null) : null, includeArtworkPointer ? new Field(artworkPointer, null) : null, entryRows)`,
    where `entryRows` are the rows with `scope == Scope.ENTRY`.
- Keep the form's own `enum Scope { ROOT, ENTRY }` by moving it into `WorkflowForm` (it was
  `WorkflowDraft.Scope`); `VariableRow.scope` uses `WorkflowForm.Scope`.
- `from(saved)`: the rows are the first call's variables with `Scope.ROOT`, then the listing's variables with
  `Scope.ENTRY`. The subtitle and artwork checkboxes and pointers come from `listing.subtitle()` and
  `listing.artwork()` pointers.

`WorkflowSetupController.java` — only the reference to `WorkflowDraft.Scope` (if any) changes to
`WorkflowForm.Scope`. In `WorkflowStore.java`, replace the three `new WorkflowDefinition(1, …` with
`WorkflowDefinition.SCHEMA_VERSION`, and build `changedDraft` in `setEnabled` as `draft.withEnabled(enabled)`.

Update the tests that build v1 shapes. The mechanical rules:
- `new WorkflowDefinition(1,` → `new WorkflowDefinition(WorkflowDefinition.SCHEMA_VERSION,` in every test file
  (`WorkflowDefinitionTest`, `WorkflowIntegrationFixture`, `WorkflowModuleSwitchTest`, `WorkflowRunnerTest`,
  `WorkflowSetupControllerErrorsTest`, `WorkflowSetupControllerTest`, `WorkflowStoreTest`, `WorkflowTestServiceTest`).
- `WorkflowStoreTest` line ~117: `.replace("\"schemaVersion\":1", "\"schemaVersion\":99")` →
  `.replace("\"schemaVersion\":2", "\"schemaVersion\":99")`.
- `WorkflowDefinitionTest`: the check that `schemaVersion` 2 is refused becomes a check that 3 is refused. Its tests
  of v1-only shapes (`rejectsMissingModeSpecificFields`, `rejectsInvalidVariableNamesDuplicatesAndEntryScopeInSingleMode`,
  `validatesNameTitleSubtitleAndMappingCountBoundaries`, `validatesPointersAndHeaderCountBoundaries` and the header
  tests) keep their boundaries but build drafts through the helpers `withCalls`/`withListing`. Variables lose their
  scope; "entry scope in single mode" becomes `perEntryCallsNeedGeneratedTiles`. The mapping-count boundary moves from
  32/33 to 64/65 (`atMostSixtyFourVariables`).
- `WorkflowJsonTest`, `WorkflowPointerAndPreviewTest`: `values(vars, root, entry)` → `values(vars, context)`, with
  `context` being the node the old scope selected. `entries(draft, root)` → `entries(draft.listing(), root, 200)`. The
  "too many entries" assertion expects `"Choose entries: the list has 201 entries; the limit is 200"`.
- `WorkflowRunnerTest`, `WorkflowTestServiceTest`, `WorkflowSetupControllerTest`, `WorkflowStoreTest`:
  - `draft.fetch()` → `draft.calls().getFirst()`;
  - `.fetch().url()` → `.calls().getFirst().url()`;
  - `.fetch().headers()` → `.calls().getFirst().headers()`.

  A saved header whose value the test compares after a Replace is now `WorkflowHeaderTemplate.literal(value)`, which
  equals `value` unless it contains braces.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.sources.workflows.*' --tests 'dev.andre.homecontrol.web.*Workflow*'`
Expected: PASS (`singleV1WithoutMappingsStaysReadable` skipped).

Then run: `scripts/gradle.sh test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/andre/homecontrol/sources/workflows/ src/test/java/dev/andre/homecontrol/sources/workflows/
git commit -m "feat: store workflows as schema version 2 with a list of calls and migrate version 1"
```

---

### Task 4: WorkflowPlan — dependencies, ordering rules and phases

**Files:**
- Create: `src/main/java/dev/andre/homecontrol/sources/workflows/WorkflowPlan.java`
- Modify: `WorkflowValidator.java`
- Test: `src/test/java/dev/andre/homecontrol/sources/workflows/WorkflowPlanTest.java` (create), `WorkflowFixtures.java`,
  `WorkflowMigrationTest.java` (remove `@Disabled`)

**Interfaces:**
- Consumes: `WorkflowTemplate.references()`, `WorkflowHeaderTemplate.references()`, the v2 records.
- Produces:
  - `WorkflowPlan.of(WorkflowDraft)`, which throws `WorkflowException(WORKFLOW, "call <name>: …")`
  - `enum WorkflowPlan.Phase { REFRESH, PLAY, REFRESH_AND_PLAY, UNUSED }` with `label()`
  - `Phase phase(String call)`
  - `List<Call> refreshCalls(CallScope)` and `List<Call> playCalls(CallScope)`, in list order
  - `Set<String> dependencies(String call)`
  - `List<String> variables()`, in declaration order
  - `boolean sensitive(String variable)`
  - `Set<String> refreshEntryValues()` and `Set<String> playEntryValues()`, the listing variables each run needs
  - `WorkflowValidator.validateStored` builds the plan; `validate` also rejects unused calls
  - Test fixture `WorkflowFixtures.chain(URI base)`

- [ ] **Step 1: Write the failing tests**

Add to `WorkflowFixtures`:

```java
    /**
     * A list call, a per-entry artwork lookup for the tiles, and a per-entry stream call for Play:
     * list → {token} and entries with {id}; images/{id} → {art}; stream/{id}?token={token} → {path}.
     */
    public static WorkflowDraft chain(URI base) {
        String root = base.toString().replaceAll("/$", "");
        return new WorkflowDraft("Chain", true, Mode.GENERATED, ContentKind.VIDEO,
                List.of(new Call("list", CallScope.SHARED, root + "/list", List.of(),
                                List.of(new Variable("token", "/token", true))),
                        new Call("images", CallScope.ENTRY, root + "/images/{id}", List.of(),
                                List.of(new Variable("art", "/url", false))),
                        new Call("stream", CallScope.ENTRY, root + "/stream/{id}?token={token}",
                                List.of(new Header("Authorization", "Bearer {token}")),
                                List.of(new Variable("path", "/path", true)))),
                new Listing("list", "/items", "/id", "/title", null, new Field(null, "art"),
                        List.of(new Variable("id", "/id", false))),
                null, new Cast("https://media.example/play/{path}?t={token}", "video/mp4"));
    }
```

Create `WorkflowPlanTest.java`:

```java
package dev.andre.homecontrol.sources.workflows;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import static dev.andre.homecontrol.sources.workflows.WorkflowDraft.*;
import static dev.andre.homecontrol.sources.workflows.WorkflowPlan.Phase.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowPlanTest {
    private static final URI BASE = URI.create("https://api.example");

    @Test void phasesFollowWhatUsesEachCall() {
        var plan = WorkflowPlan.of(WorkflowFixtures.chain(BASE));
        assertThat(plan.phase("list")).isEqualTo(REFRESH_AND_PLAY);
        assertThat(plan.phase("images")).isEqualTo(REFRESH);
        assertThat(plan.phase("stream")).isEqualTo(PLAY);
        assertThat(REFRESH_AND_PLAY.label()).isEqualTo("Runs at refresh and Play");
    }

    @Test void eachRunExecutesOnlyTheCallsItNeeds() {
        var plan = WorkflowPlan.of(WorkflowFixtures.chain(BASE));
        assertThat(plan.refreshCalls(CallScope.SHARED)).extracting(Call::name).containsExactly("list");
        assertThat(plan.refreshCalls(CallScope.ENTRY)).extracting(Call::name).containsExactly("images");
        assertThat(plan.playCalls(CallScope.SHARED)).extracting(Call::name).containsExactly("list");
        assertThat(plan.playCalls(CallScope.ENTRY)).extracting(Call::name).containsExactly("stream");
        assertThat(plan.dependencies("stream")).containsExactly("list");
        assertThat(plan.dependencies("list")).isEmpty();
        assertThat(plan.refreshEntryValues()).containsExactly("id");
        assertThat(plan.playEntryValues()).containsExactly("id");
        assertThat(plan.variables()).containsExactly("token", "art", "path", "id");
        assertThat(plan.sensitive("token")).isTrue();
        assertThat(plan.sensitive("art")).isFalse();
    }

    @Test void singleTileCallsAllRunAtPlay() {
        var plan = WorkflowPlan.of(WorkflowFixtures.single(BASE));
        assertThat(plan.phase("main")).isEqualTo(PLAY);
        assertThat(plan.refreshCalls(CallScope.SHARED)).isEmpty();
    }

    @Test void aCallCannotUseAValueFromACallFurtherDown() {
        var chain = WorkflowFixtures.chain(BASE);
        var reordered = new ArrayList<>(chain.calls());
        reordered.add(0, reordered.remove(2)); // stream before list
        assertThatThrownBy(() -> WorkflowPlan.of(with(chain, reordered)))
                .isInstanceOf(WorkflowException.class)
                .hasMessage("Workflow: call stream: uses {id}, which is defined by a call further down");
    }

    @Test void aSharedCallCannotUseAnEntryValue() {
        var chain = WorkflowFixtures.chain(BASE);
        var calls = new ArrayList<>(chain.calls());
        var stream = calls.get(2);
        calls.set(2, new Call(stream.name(), CallScope.SHARED, stream.url(), stream.headers(), stream.variables()));
        assertThatThrownBy(() -> WorkflowPlan.of(with(chain, calls)))
                .hasMessage("Workflow: call stream: uses the entry value {id}; make it a per-entry call");
    }

    @Test void aCallCannotUseItsOwnValues() {
        var draft = WorkflowFixtures.singleWith(List.of(new Call("self", CallScope.SHARED,
                "https://api.example/x?t={t}", List.of(), List.of(new Variable("t", "/t", false)))));
        assertThatThrownBy(() -> WorkflowPlan.of(draft))
                .hasMessage("Workflow: call self: uses {t}, which is defined by a call further down");
    }

    @Test void aHeaderPlaceholderMustNameAValue() {
        var draft = WorkflowFixtures.singleWith(List.of(new Call("main", CallScope.SHARED, "https://api.example/x",
                List.of(new Header("Authorization", "Bearer {nothing}")), List.of())));
        assertThatThrownBy(() -> WorkflowPlan.of(draft))
                .hasMessage("Workflow: call main: uses {nothing}, which no call defines");
    }

    @Test void aTileFieldCannotShowASensitiveValue() {
        var chain = WorkflowFixtures.chain(BASE);
        var l = chain.listing();
        var leaking = new Listing(l.call(), l.arrayPointer(), l.idPointer(), l.titlePointer(),
                new Field(null, "token"), l.artwork(), l.variables());
        assertThatThrownBy(() -> WorkflowPlan.of(new WorkflowDraft(chain.name(), true, chain.mode(), chain.kind(),
                chain.calls(), leaking, null, chain.cast())))
                .hasMessage("Workflow: entry subtitle variable {token} is marked sensitive");
    }

    @Test void saveRejectsAnUnusedCallButAStoredOneRunsAtPlay() {
        var chain = WorkflowFixtures.chain(BASE);
        var calls = new ArrayList<>(chain.calls());
        calls.add(new Call("spare", CallScope.SHARED, "https://api.example/spare", List.of(), List.of()));
        var draft = with(chain, calls);
        WorkflowValidator.validateStored(draft);
        assertThatThrownBy(() -> WorkflowValidator.validate(draft)).hasMessage("Workflow: call spare: nothing uses this call");
        var plan = WorkflowPlan.of(draft);
        assertThat(plan.phase("spare")).isEqualTo(UNUSED);
        assertThat(plan.playCalls(CallScope.SHARED)).extracting(Call::name).containsExactly("list", "spare");
    }

    private static WorkflowDraft with(WorkflowDraft d, List<Call> calls) {
        return new WorkflowDraft(d.name(), d.enabled(), d.mode(), d.kind(), calls, d.listing(), d.tile(), d.cast());
    }
}
```

Remove `@Disabled` from `WorkflowMigrationTest.singleV1WithoutMappingsStaysReadable`.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.sources.workflows.WorkflowPlanTest'`
Expected: compilation fails (`WorkflowPlan` does not exist).

- [ ] **Step 3: Implement**

`WorkflowPlan.java`:

```java
package dev.andre.homecontrol.sources.workflows;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import static dev.andre.homecontrol.sources.workflows.WorkflowDraft.*;

/**
 * Which call needs which, when each call runs, and what a refresh or a Play executes. A call may use only values of
 * calls above it, so the dependencies can never form a cycle.
 */
public final class WorkflowPlan {
    public enum Phase {
        REFRESH("Runs at refresh"),
        PLAY("Runs at Play"),
        REFRESH_AND_PLAY("Runs at refresh and Play"),
        UNUSED("Nothing uses this call");

        private final String label;

        Phase(String label) { this.label = label; }

        public String label() { return label; }
    }

    private final WorkflowDraft draft;
    /** Variable name → the call that defines it, in declaration order. Entry values belong to the entry source. */
    private final Map<String, String> owners = new LinkedHashMap<>();
    private final Map<String, Boolean> sensitive = new HashMap<>();
    private final Set<String> listingVariables = new HashSet<>();
    private final Set<String> entryVariables = new HashSet<>();
    private final Map<String, Set<String>> uses = new HashMap<>();
    private final Map<String, Set<String>> dependencies = new HashMap<>();
    private final Set<String> castUses;
    private final Set<String> refresh;
    private final Set<String> play;
    private final Set<String> playRun;

    private WorkflowPlan(WorkflowDraft draft) {
        this.draft = draft;
        index();
        Map<String, Integer> position = new HashMap<>();
        for (int i = 0; i < draft.calls().size(); i++) position.put(draft.calls().get(i).name(), i);
        for (Call call : draft.calls()) link(call, position);
        castUses = new WorkflowTemplate(draft.cast().template(), owners.keySet()).references();
        checkDisplayFields();
        refresh = draft.mode() == Mode.GENERATED ? closure(refreshRoots()) : Set.of();
        play = closure(playRoots());
        Set<String> unused = new HashSet<>();
        for (Call call : draft.calls()) if (!refresh.contains(call.name()) && !play.contains(call.name())) unused.add(call.name());
        Set<String> playRoots = new HashSet<>(play);
        playRoots.addAll(unused);
        playRun = closure(playRoots);
    }

    public static WorkflowPlan of(WorkflowDraft draft) {
        return new WorkflowPlan(draft);
    }

    private void index() {
        for (Call call : draft.calls()) {
            for (Variable variable : call.variables()) {
                owners.put(variable.name(), call.name());
                sensitive.put(variable.name(), variable.sensitive());
                if (call.scope() == CallScope.ENTRY) entryVariables.add(variable.name());
            }
        }
        if (draft.listing() != null) {
            for (Variable variable : draft.listing().variables()) {
                owners.put(variable.name(), draft.listing().call());
                sensitive.put(variable.name(), variable.sensitive());
                listingVariables.add(variable.name());
                entryVariables.add(variable.name());
            }
        }
    }

    private void link(Call call, Map<String, Integer> position) {
        Set<String> used = new HashSet<>(new WorkflowTemplate(call.url(), owners.keySet(), "call URL",
                WorkflowException.Stage.WORKFLOW).references());
        for (Header header : call.headers()) used.addAll(new WorkflowHeaderTemplate(header.value()).references());
        Set<String> needed = new HashSet<>();
        // Sorted, so the same draft always reports the same first problem.
        for (String name : new java.util.TreeSet<>(used)) {
            String owner = owners.get(name);
            if (owner == null) fail(call, "uses {" + name + "}, which no call defines");
            if (position.get(owner) >= position.get(call.name())) fail(call, "uses {" + name + "}, which is defined by a call further down");
            if (entryVariables.contains(name) && call.scope() != CallScope.ENTRY) {
                fail(call, "uses the entry value {" + name + "}; make it a per-entry call");
            }
            needed.add(owner);
        }
        uses.put(call.name(), Set.copyOf(used));
        dependencies.put(call.name(), Set.copyOf(needed));
    }

    private void checkDisplayFields() {
        if (draft.listing() == null) return;
        check(draft.listing().subtitle(), "entry subtitle");
        check(draft.listing().artwork(), "entry artwork");
    }

    private void check(Field field, String label) {
        if (field == null || field.variable() == null) return;
        if (Boolean.TRUE.equals(sensitive.get(field.variable()))) {
            throw new WorkflowException(WorkflowException.Stage.WORKFLOW,
                    label + " variable {" + field.variable() + "} is marked sensitive");
        }
    }

    private Set<String> displayVariables() {
        Set<String> names = new HashSet<>();
        if (draft.listing() == null) return names;
        for (Field field : new Field[]{draft.listing().subtitle(), draft.listing().artwork()}) {
            if (field != null && field.variable() != null) names.add(field.variable());
        }
        return names;
    }

    private Set<String> refreshRoots() {
        Set<String> roots = new HashSet<>();
        roots.add(draft.listing().call());
        for (String name : displayVariables()) roots.add(owners.get(name));
        return roots;
    }

    private Set<String> playRoots() {
        Set<String> roots = new HashSet<>();
        for (String name : castUses) roots.add(owners.get(name));
        if (draft.mode() == Mode.GENERATED) roots.add(draft.listing().call());
        return roots;
    }

    private Set<String> closure(Set<String> roots) {
        Set<String> all = new HashSet<>();
        Deque<String> todo = new ArrayDeque<>(roots);
        while (!todo.isEmpty()) {
            String call = todo.pop();
            if (all.add(call)) todo.addAll(dependencies.get(call));
        }
        return Set.copyOf(all);
    }

    public Phase phase(String call) {
        boolean atRefresh = refresh.contains(call);
        boolean atPlay = play.contains(call);
        if (atRefresh && atPlay) return Phase.REFRESH_AND_PLAY;
        if (atRefresh) return Phase.REFRESH;
        return atPlay ? Phase.PLAY : Phase.UNUSED;
    }

    /** The calls a refresh runs, in list order. */
    public List<Call> refreshCalls(CallScope scope) {
        return select(scope, refresh::contains);
    }

    /** The calls a Play runs, in list order. A stored definition's unused call also runs, as its v1 original did. */
    public List<Call> playCalls(CallScope scope) {
        return select(scope, playRun::contains);
    }

    private List<Call> select(CallScope scope, Predicate<String> included) {
        return draft.calls().stream().filter(call -> call.scope() == scope && included.test(call.name())).toList();
    }

    public Set<String> dependencies(String call) {
        return dependencies.get(call);
    }

    /** Every variable name, in declaration order. */
    public List<String> variables() {
        return List.copyOf(owners.keySet());
    }

    public boolean sensitive(String variable) {
        return Boolean.TRUE.equals(sensitive.get(variable));
    }

    /** The entry values a refresh reads: those its per-entry calls and tile fields use. */
    public Set<String> refreshEntryValues() {
        Set<String> needed = new HashSet<>(displayVariables());
        for (Call call : refreshCalls(CallScope.ENTRY)) needed.addAll(uses.get(call.name()));
        needed.retainAll(listingVariables);
        return Set.copyOf(needed);
    }

    /** The entry values a Play reads: those its per-entry calls and the media URL use. */
    public Set<String> playEntryValues() {
        Set<String> needed = new HashSet<>(castUses);
        for (Call call : playCalls(CallScope.ENTRY)) needed.addAll(uses.get(call.name()));
        needed.retainAll(listingVariables);
        return Set.copyOf(needed);
    }

    private static void fail(Call call, String detail) {
        throw new WorkflowException(WorkflowException.Stage.WORKFLOW, "call " + call.name() + ": " + detail);
    }
}
```

In `WorkflowValidator`, `validateStored` ends with `WorkflowPlan.of(draft);` and returns the plan. `validate` then
rejects unused calls:

```java
    public static WorkflowPlan validateStored(WorkflowDraft draft) {
        // … existing checks …
        return WorkflowPlan.of(draft);
    }

    public static void validate(WorkflowDraft draft) {
        WorkflowPlan plan = validateStored(draft);
        for (Call call : draft.calls()) {
            if (plan.phase(call.name()) == WorkflowPlan.Phase.UNUSED) fail("call " + call.name() + ": nothing uses this call");
        }
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.sources.workflows.*'`
Expected: PASS. If a controller test now fails because its fixture has an unused call, it is a test whose draft the
v2 rules consider unused (for example, a single-tile draft whose template uses no variables). Give its template a
variable of the call.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/andre/homecontrol/sources/workflows/WorkflowPlan.java \
        src/main/java/dev/andre/homecontrol/sources/workflows/WorkflowValidator.java \
        src/test/java/dev/andre/homecontrol/sources/workflows/
git commit -m "feat: work out when each workflow call runs from the values it uses"
```

---

### Task 5: WorkflowCalls — running calls by dependency within one run

**Files:**
- Create: `src/main/java/dev/andre/homecontrol/sources/workflows/WorkflowCalls.java`
- Test: `src/test/java/dev/andre/homecontrol/sources/workflows/WorkflowCallsTest.java` (create)

**Interfaces:**
- Consumes: `WorkflowPlan.dependencies/variables`, `WorkflowRunner.request(call, names, values)`,
  `WorkflowHttpClient.fetch(Request, long)`, `WorkflowJson.parse/values`, `WorkflowException.inCall`.
- Produces:
  - `new WorkflowCalls(WorkflowHttpClient)`
  - `WorkflowCalls.Run(Duration budget, int parallel)` with `deadline()` and `remaining()`
  - `WorkflowCalls.Outcome(Map<String, WorkflowJson.Value> values, Map<String, JsonNode> responses)`
  - `Outcome run(List<Call> calls, WorkflowPlan plan, Map<String, WorkflowJson.Value> known, Run run, String entryTitle)`.
    A dependency outside `calls` must already be in `known`. The first failure ends the run with a
    `WorkflowException` whose `call()` is set.

- [ ] **Step 1: Write the failing tests**

```java
package dev.andre.homecontrol.sources.workflows;

import com.sun.net.httpserver.HttpExchange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static dev.andre.homecontrol.sources.workflows.WorkflowDraft.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Timeout(20)
class WorkflowCallsTest {
    private static WorkflowHttpClient client() {
        return new WorkflowHttpClient(new WorkflowProperties(true, true, Duration.ofSeconds(1), Duration.ofSeconds(5), 8, 2_097_152, 3),
                new WorkflowUrlPolicy(true, host -> new InetAddress[]{InetAddress.ofLiteral("127.0.0.1")}));
    }

    private static Call call(String name, String url, Variable... variables) {
        return new Call(name, CallScope.SHARED, url, List.of(), List.of(variables));
    }

    private static void reply(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @Test void independentCallsRunAtTheSameTime() throws Exception {
        var entered = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        try (var server = new FakeWorkflowServer(); var http = client()) {
            for (String path : List.of("/a", "/b")) {
                server.route(path, exchange -> {
                    entered.countDown();
                    try { release.await(); } catch (InterruptedException _) { Thread.currentThread().interrupt(); }
                    reply(exchange, "{\"v\":\"" + path + "\"}");
                });
            }
            var draft = WorkflowFixtures.singleWith(List.of(
                    call("a", server.url("/a").toString(), new Variable("a", "/v", false)),
                    call("b", server.url("/b").toString(), new Variable("b", "/v", false))));
            var plan = WorkflowPlan.of(draft);
            var outcome = CompletableFuture.supplyAsync(() -> new WorkflowCalls(http).run(draft.calls(), plan, Map.of(),
                    new WorkflowCalls.Run(Duration.ofSeconds(10), 4), null), Executors.newVirtualThreadPerTaskExecutor());
            assertThat(entered.await(3, TimeUnit.SECONDS)).as("both calls started before either finished").isTrue();
            release.countDown();
            var values = outcome.get(5, TimeUnit.SECONDS).values();
            assertThat(values.get("a").text()).isEqualTo("/a");
            assertThat(values.get("b").text()).isEqualTo("/b");
        } finally { release.countDown(); }
    }

    @Test void aDependentCallRunsAfterItsSourceAndUsesItsValue() throws Exception {
        try (var server = new FakeWorkflowServer(); var http = client()) {
            server.respond("/token", 200, "{\"token\":\"t-1\"}");
            server.respond("/list", 200, "{\"n\":3}");
            var draft = WorkflowFixtures.singleWith(List.of(
                    call("token", server.url("/token").toString(), new Variable("token", "/token", true)),
                    new Call("list", CallScope.SHARED, server.url("/list") + "?t={token}",
                            List.of(new Header("Authorization", "Bearer {token}")), List.of(new Variable("n", "/n", false)))));
            var outcome = new WorkflowCalls(http).run(draft.calls(), WorkflowPlan.of(draft), Map.of(),
                    new WorkflowCalls.Run(Duration.ofSeconds(10), 4), null);
            assertThat(outcome.values().get("n").text()).isEqualTo("3");
            var list = server.requests("/list").getFirst();
            assertThat(list.rawQuery()).isEqualTo("t=t-1");
            assertThat(list.header("Authorization")).isEqualTo("Bearer t-1");
            assertThat(outcome.responses()).containsKeys("token", "list");
            assertThat(outcome.toString()).doesNotContain("t-1");
        }
    }

    @Test void valuesFromAResponseNeverChangeTheHost() throws Exception {
        try (var server = new FakeWorkflowServer(); var http = client()) {
            server.respond("/token", 200, "{\"id\":\"x/y@evil.example?z\"}");
            server.respond("/item", 200, "{}");
            var draft = WorkflowFixtures.singleWith(List.of(
                    call("token", server.url("/token").toString(), new Variable("id", "/id", false)),
                    call("item", server.url("/item") + "?q={id}")));
            new WorkflowCalls(http).run(draft.calls(), WorkflowPlan.of(draft), Map.of(),
                    new WorkflowCalls.Run(Duration.ofSeconds(10), 4), null);
            // The value stayed one encoded query value, and the request reached the configured server.
            assertThat(server.requests("/item")).singleElement()
                    .satisfies(request -> assertThat(request.rawQuery()).isEqualTo("q=x%2Fy%40evil.example%3Fz"));
        }
    }

    @Test void aFailureNamesTheCallAndSkipsWhatDependsOnIt() throws Exception {
        try (var server = new FakeWorkflowServer(); var http = client()) {
            server.respond("/token", 500, "{}");
            server.respond("/list", 200, "{}");
            var draft = WorkflowFixtures.singleWith(List.of(
                    call("token", server.url("/token").toString(), new Variable("token", "/token", true)),
                    call("list", server.url("/list") + "?t={token}")));
            assertThatThrownBy(() -> new WorkflowCalls(http).run(draft.calls(), WorkflowPlan.of(draft), Map.of(),
                    new WorkflowCalls.Run(Duration.ofSeconds(10), 4), null))
                    .isInstanceOfSatisfying(WorkflowException.class, e -> assertThat(e.call()).isEqualTo("token"))
                    .hasMessage("Call token: server returned HTTP 500");
            assertThat(server.count("/list")).isZero();
        }
    }

    @Test void aFailureEndsTheRunWithoutWaitingForASlowIndependentCall() throws Exception {
        var release = new CountDownLatch(1);
        try (var server = new FakeWorkflowServer(); var http = client()) {
            server.respond("/broken", 404, "{}");
            server.route("/slow", exchange -> {
                try { release.await(); } catch (InterruptedException _) { Thread.currentThread().interrupt(); }
                reply(exchange, "{}");
            });
            var draft = WorkflowFixtures.singleWith(List.of(
                    call("slow", server.url("/slow").toString()), call("broken", server.url("/broken").toString())));
            long started = System.nanoTime();
            assertThatThrownBy(() -> new WorkflowCalls(http).run(draft.calls(), WorkflowPlan.of(draft), Map.of(),
                    new WorkflowCalls.Run(Duration.ofSeconds(10), 4), null))
                    .hasMessage("Call broken: server returned HTTP 404");
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
        } finally { release.countDown(); }
    }

    @Test void aRunNeverHasMoreFetchesAtOnceThanItsShare() throws Exception {
        var active = new AtomicInteger();
        var most = new AtomicInteger();
        try (var server = new FakeWorkflowServer(); var http = client()) {
            for (String path : List.of("/a", "/b", "/c")) {
                server.route(path, exchange -> {
                    most.accumulateAndGet(active.incrementAndGet(), Math::max);
                    try { Thread.sleep(150); } catch (InterruptedException _) { Thread.currentThread().interrupt(); }
                    active.decrementAndGet();
                    reply(exchange, "{}");
                });
            }
            var draft = WorkflowFixtures.singleWith(List.of(call("a", server.url("/a").toString()),
                    call("b", server.url("/b").toString()), call("c", server.url("/c").toString())));
            new WorkflowCalls(http).run(draft.calls(), WorkflowPlan.of(draft), Map.of(),
                    new WorkflowCalls.Run(Duration.ofSeconds(10), 1), null);
            assertThat(most.get()).isEqualTo(1);
        }
    }

    @Test void theRunDeadlineEndsARunThatTakesTooLong() throws Exception {
        var release = new CountDownLatch(1);
        try (var server = new FakeWorkflowServer(); var http = client()) {
            server.route("/slow", exchange -> {
                try { release.await(); } catch (InterruptedException _) { Thread.currentThread().interrupt(); }
                reply(exchange, "{}");
            });
            var draft = WorkflowFixtures.singleWith(List.of(call("slow", server.url("/slow").toString())));
            long started = System.nanoTime();
            assertThatThrownBy(() -> new WorkflowCalls(http).run(draft.calls(), WorkflowPlan.of(draft), Map.of(),
                    new WorkflowCalls.Run(Duration.ofMillis(300), 4), null))
                    .isInstanceOf(WorkflowException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
        } finally { release.countDown(); }
    }

    @Test void aPerEntryFailureNamesTheEntry() throws Exception {
        try (var server = new FakeWorkflowServer(); var http = client()) {
            server.respond("/images/news", 404, "{}");
            var chain = WorkflowFixtures.chain(server.url("/"));
            var plan = WorkflowPlan.of(chain);
            var known = Map.of("id", new WorkflowJson.Value("news", false), "token", new WorkflowJson.Value("t", true));
            assertThatThrownBy(() -> new WorkflowCalls(http).run(plan.refreshCalls(CallScope.ENTRY), plan, known,
                    new WorkflowCalls.Run(Duration.ofSeconds(5), 3), "News"))
                    .hasMessage("Call images · entry \"News\": server returned HTTP 404");
        }
    }
}
```

`FakeWorkflowServer.route` and `requests` are package-private, so this test must stay in the same package.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.sources.workflows.WorkflowCallsTest'`
Expected: compilation fails (`WorkflowCalls` does not exist).

- [ ] **Step 3: Implement**

```java
package dev.andre.homecontrol.sources.workflows;

import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static dev.andre.homecontrol.sources.workflows.WorkflowDraft.Call;
import static dev.andre.homecontrol.sources.workflows.WorkflowException.Stage;

/** Runs calls as soon as the calls they depend on are done, within one run's deadline and share of fetches. */
final class WorkflowCalls {
    private static final Executor VIRTUAL = task -> Thread.ofVirtual().name("workflow-call").start(task);
    private final WorkflowHttpClient http;

    WorkflowCalls(WorkflowHttpClient http) {
        this.http = http;
    }

    /** One refresh, Play or Test: when it must end and how many of its fetches may run at once. */
    static final class Run {
        private final long deadline;
        private final Semaphore share;

        Run(Duration budget, int parallel) {
            deadline = System.nanoTime() + budget.toNanos();
            share = new Semaphore(parallel);
        }

        long deadline() { return deadline; }

        long remaining() { return Math.max(0, deadline - System.nanoTime()); }
    }

    /** The values the calls produced, and each call's parsed response. Neither leaves the run. */
    record Outcome(Map<String, WorkflowJson.Value> values, Map<String, JsonNode> responses) {
        @Override public String toString() { return "Outcome[redacted]"; }
    }

    Outcome run(List<Call> calls, WorkflowPlan plan, Map<String, WorkflowJson.Value> known, Run run, String entryTitle) {
        var values = new ConcurrentHashMap<>(known);
        var responses = new ConcurrentHashMap<String, JsonNode>();
        if (calls.isEmpty()) return new Outcome(Map.copyOf(values), Map.of());
        var stopped = new AtomicBoolean();
        var failed = new CompletableFuture<Void>();
        Map<String, CompletableFuture<Void>> started = new LinkedHashMap<>();
        Set<String> names = Set.copyOf(plan.variables());
        for (Call call : calls) {
            // Calls come in list order, so every dependency in this set has already been started.
            CompletableFuture<?>[] before = plan.dependencies(call.name()).stream()
                    .map(started::get).filter(Objects::nonNull).toArray(CompletableFuture[]::new);
            CompletableFuture<Void> future = CompletableFuture.allOf(before)
                    .thenRunAsync(() -> execute(call, names, values, responses, run, stopped, entryTitle), VIRTUAL);
            future.whenComplete((ignored, error) -> {
                if (error != null) {
                    stopped.set(true);
                    failed.completeExceptionally(error);
                }
            });
            started.put(call.name(), future);
        }
        var all = CompletableFuture.allOf(started.values().toArray(CompletableFuture[]::new));
        try {
            CompletableFuture.anyOf(all, failed).get(run.remaining(), TimeUnit.NANOSECONDS);
        } catch (ExecutionException e) {
            throw safe(e.getCause());
        } catch (TimeoutException _) {
            stopped.set(true);
            throw new WorkflowException(Stage.FETCH, "the workflow took too long; try again later");
        } catch (InterruptedException _) {
            stopped.set(true);
            Thread.currentThread().interrupt();
            throw new WorkflowException(Stage.FETCH, "request interrupted");
        }
        return new Outcome(Map.copyOf(values), Map.copyOf(responses));
    }

    private void execute(Call call, Set<String> names, Map<String, WorkflowJson.Value> values,
                         Map<String, JsonNode> responses, Run run, AtomicBoolean stopped, String entryTitle) {
        try {
            if (stopped.get()) throw new WorkflowException(Stage.FETCH, "not run because another call failed");
            acquire(run);
            try {
                if (stopped.get()) throw new WorkflowException(Stage.FETCH, "not run because another call failed");
                var request = WorkflowRunner.request(call, names, values);
                JsonNode response = WorkflowJson.parse(http.fetch(request, run.deadline()));
                values.putAll(WorkflowJson.values(call.variables(), response));
                responses.put(call.name(), response);
            } finally {
                run.share.release();
            }
        } catch (WorkflowException failure) {
            throw failure.inCall(call.name(), entryTitle);
        }
    }

    private static void acquire(Run run) {
        try {
            if (!run.share.tryAcquire(run.remaining(), TimeUnit.NANOSECONDS)) {
                throw new WorkflowException(Stage.FETCH, "busy; try again later");
            }
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            throw new WorkflowException(Stage.FETCH, "request interrupted");
        }
    }

    /** Only locally authored messages leave a run; anything else becomes a generic failure. */
    private static WorkflowException safe(Throwable error) {
        Throwable cause = error;
        while (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
        return cause instanceof WorkflowException known ? known : new WorkflowException(Stage.FETCH, "request failed");
    }
}
```

A call that has already started keeps its HTTP request running after the run ends. The fetch ends by its own
deadline and then releases its slot, so nothing waits beyond the request that started it.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.sources.workflows.WorkflowCallsTest'`
Expected: PASS. Run it three times (`--rerun-tasks`) to check that the timing tests are stable.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/andre/homecontrol/sources/workflows/WorkflowCalls.java \
        src/test/java/dev/andre/homecontrol/sources/workflows/WorkflowCallsTest.java
git commit -m "feat: run workflow calls in parallel or in order from the values they use"
```

---

### Task 6: WorkflowRunner — refresh and Play with calls

**Files:**
- Rewrite: `src/main/java/dev/andre/homecontrol/sources/workflows/WorkflowRunner.java`
- Modify: `WorkflowConfiguration.java` (runner bean gets `WorkflowProperties`)
- Test: `src/test/java/dev/andre/homecontrol/sources/workflows/WorkflowRunnerTest.java`, and any test that calls
  `new WorkflowRunner(http)` (`grep -rn 'new WorkflowRunner(' src/test src/e2e`)

**Interfaces:**
- Consumes: `WorkflowPlan`, `WorkflowCalls`, `WorkflowJson.entries/values/artwork`, `WorkflowTemplate`.
- Produces:
  - `new WorkflowRunner(WorkflowHttpClient, WorkflowProperties)`; `catalog(definition)` and
    `resolve(definition, entryKey)` keep their signatures
  - package-private `WorkflowCalls.Run refreshRun()` and `playRun()`
  - `Refresh refresh(WorkflowDefinition, Run)`, where `Refresh(List<CatalogEntry> entries, List<String> problems, boolean artworkOmitted)`
  - `PlayContext play(WorkflowDefinition, Run)`, where `PlayContext(WorkflowPlan plan, Map<String, Value> shared, List<WorkflowJson.Entry> entries)`
  - `Map<String, Value> entryValues(WorkflowDefinition, PlayContext, WorkflowJson.Entry, Run)`
  - `URI media(WorkflowDefinition, WorkflowPlan, Map<String, Value>)`
  - constants `ENTRY_LIMIT = 200` and `ENTRY_LIMIT_WITH_CALLS = 50`

- [ ] **Step 1: Write the failing tests**

Keep the existing tests in `WorkflowRunnerTest`, changed as follows:
- Every `new WorkflowRunner(client)` becomes `new WorkflowRunner(client, PROPERTIES)`, with
  `private static final WorkflowProperties PROPERTIES = new WorkflowProperties(true, true, Duration.ofSeconds(5), Duration.ofSeconds(10), 8, 2097152, 3);`.
- Mocked `client.fetch(any(WorkflowHttpClient.Request.class))` becomes
  `client.fetch(any(WorkflowHttpClient.Request.class), anyLong())`.
- `checkMedia(url)` becomes `checkMedia(eq(url), anyLong())`.
- The disappeared-entry test expects the message `"Choose entries: This item is no longer available; refresh the Dashboard"`.

Add, using a real client against `FakeWorkflowServer`:

```java
    /** Every host resolves to 127.0.0.1: the fake server is reachable and media.example passes the address check. */
    private static WorkflowHttpClient loopbackClient() {
        return new WorkflowHttpClient(PROPERTIES,
                new WorkflowUrlPolicy(true, host -> new InetAddress[]{InetAddress.ofLiteral("127.0.0.1")}));
    }

    private static WorkflowDefinition chain(FakeWorkflowServer server) {
        return new WorkflowDefinition(WorkflowDefinition.SCHEMA_VERSION, WorkflowIntegrationFixture.ID, 1,
                WorkflowFixtures.chain(server.url("/")));
    }

    private static void list(FakeWorkflowServer server, String token) {
        server.respond("/list", 200, "{\"token\":\"" + token + "\",\"items\":["
                + "{\"id\":\"news\",\"title\":\"News\"},{\"id\":\"music\",\"title\":\"Music\"}]}");
    }

    @Test void refreshRunsTheListOnceAndTheArtworkCallPerEntryButNeverThePlayCall() throws Exception {
        try (var server = new FakeWorkflowServer(); var http = loopbackClient()) {
            list(server, "t-1");
            server.respond("/images/news", 200, "{\"url\":\"https://images.example/news.png\"}");
            server.respond("/images/music", 200, "{\"url\":\"https://images.example/music.png\"}");
            var entries = new WorkflowRunner(http, PROPERTIES).catalog(chain(server));
            assertThat(entries).extracting(WorkflowRunner.CatalogEntry::title).containsExactly("News", "Music");
            assertThat(entries).extracting(e -> e.artwork().toString())
                    .containsExactly("https://images.example/news.png", "https://images.example/music.png");
            assertThat(server.count("/list")).isEqualTo(1);
            assertThat(server.count("/images/news")).isEqualTo(1);
            assertThat(server.count("/stream/news")).isZero();
        }
    }

    @Test void unsafeArtworkFromAnEntryCallFallsBackToThePlaceholder() throws Exception {
        try (var server = new FakeWorkflowServer(); var http = loopbackClient()) {
            list(server, "t-1");
            server.respond("/images/news", 200, "{\"url\":\"http://192.168.1.2/private.png\"}");
            server.respond("/images/music", 404, "{}");
            var refresh = new WorkflowRunner(http, PROPERTIES).refresh(chain(server),
                    new WorkflowCalls.Run(Duration.ofSeconds(10), 3));
            assertThat(refresh.entries()).extracting(WorkflowRunner.CatalogEntry::artwork).containsOnlyNulls();
            assertThat(refresh.artworkOmitted()).isTrue();
            assertThat(refresh.problems()).containsExactly("Call images · entry \"Music\": server returned HTTP 404");
        }
    }

    @Test void theEntryLimitDropsToFiftyWhenAPerEntryCallRunsAtRefresh() throws Exception {
        try (var server = new FakeWorkflowServer(); var http = loopbackClient()) {
            var items = new StringBuilder();
            for (int i = 0; i < 51; i++) items.append(i == 0 ? "" : ",").append("{\"id\":\"e").append(i).append("\",\"title\":\"E\"}");
            server.respond("/list", 200, "{\"token\":\"t\",\"items\":[" + items + "]}");
            assertThatThrownBy(() -> new WorkflowRunner(http, PROPERTIES).catalog(chain(server)))
                    .hasMessage("Choose entries: the list has 51 entries; the limit is 50");
            assertThat(server.count("/images/e1")).isZero();
        }
    }

    @Test void playRefetchesTheListFindsTheEntryAndRunsOnlyItsStreamCall() throws Exception {
        try (var server = new FakeWorkflowServer(); var http = loopbackClient()) {
            list(server, "t-1");
            server.respond("/images/news", 200, "{\"url\":\"https://images.example/news.png\"}");
            server.respond("/images/music", 200, "{\"url\":\"https://images.example/music.png\"}");
            var runner = new WorkflowRunner(http, PROPERTIES);
            var definition = chain(server);
            String key = runner.catalog(definition).getFirst().key();
            list(server, "t-2");
            server.respond("/stream/news", 200, "{\"path\":\"news-hd.m3u8\"}");
            var media = runner.resolve(definition, key);
            assertThat(media.url().toString()).isEqualTo("https://media.example/play/news-hd.m3u8?t=t-2");
            assertThat(media.title()).isEqualTo("News");
            assertThat(server.requests("/stream/news").getFirst().header("Authorization")).isEqualTo("Bearer t-2");
            assertThat(server.count("/stream/music")).isZero();
            assertThat(server.count("/images/news")).isEqualTo(1);
        }
    }

    @Test void migratedHeaderWithBracesIsSentUnchanged() throws Exception {
        try (var server = new FakeWorkflowServer(); var http = loopbackClient()) {
            server.respond("/feed", 200, "{\"id\":\"news\",\"token\":\"t\"}");
            String v1 = """
                    {"schemaVersion":1,"id":"w-0123456789ab","revision":1,"draft":{
                      "name":"News","enabled":true,"mode":"SINGLE","kind":"VIDEO",
                      "fetch":{"url":"%s","headers":[{"name":"X-Filter","value":"{\\"a\\":1}"}]},"listing":null,
                      "tile":{"title":"News","subtitle":null,"artwork":null},
                      "variables":[{"name":"A","scope":"ROOT","pointer":"/id","sensitive":false}],
                      "cast":{"template":"https://media.example/play?id={A}","mimeType":"video/mp4"}}}
                    """.formatted(server.url("/feed"));
            new WorkflowRunner(http, PROPERTIES).resolve(new WorkflowCodec().decode(v1), "single");
            assertThat(server.requests("/feed").getFirst().header("X-Filter")).isEqualTo("{\"a\":1}");
        }
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.sources.workflows.WorkflowRunnerTest'`
Expected: compilation fails (two-argument constructor, `refresh`, `Refresh` missing).

- [ ] **Step 3: Implement**

```java
package dev.andre.homecontrol.sources.workflows;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import static dev.andre.homecontrol.sources.workflows.WorkflowDraft.*;

/** Refreshes tiles and resolves a Play from a workflow's calls. Responses and secret values stay inside one run. */
public final class WorkflowRunner {
    static final int ENTRY_LIMIT = 200;
    static final int ENTRY_LIMIT_WITH_CALLS = 50;
    private static final int REFRESH_PARALLEL = 3;
    private static final int PLAY_PARALLEL = 4;
    private static final Executor VIRTUAL = task -> Thread.ofVirtual().name("workflow-entry").start(task);
    private final WorkflowHttpClient http;
    private final WorkflowCalls calls;
    private final WorkflowProperties properties;

    public WorkflowRunner(WorkflowHttpClient http, WorkflowProperties properties) {
        this.http = http;
        this.calls = new WorkflowCalls(http);
        this.properties = properties;
    }

    public record CatalogEntry(String key, String title, String subtitle, URI artwork) {}

    public record ResolvedMedia(URI url, String mimeType, String title) {
        @Override public String toString() { return "ResolvedMedia[redacted]"; }
    }

    /** A refresh's tiles, the per-entry failures that left tiles without extra fields, and whether artwork was unsafe. */
    record Refresh(List<CatalogEntry> entries, List<String> problems, boolean artworkOmitted) {}

    /** What a Play fetched before choosing an entry. */
    record PlayContext(WorkflowPlan plan, Map<String, WorkflowJson.Value> shared, List<WorkflowJson.Entry> entries) {
        @Override public String toString() { return "PlayContext[redacted]"; }
    }

    private record Tiled(CatalogEntry entry, String problem, boolean artworkOmitted) {}

    WorkflowCalls.Run refreshRun() { return new WorkflowCalls.Run(properties.refreshTimeout(), REFRESH_PARALLEL); }

    WorkflowCalls.Run playRun() { return new WorkflowCalls.Run(properties.playTimeout(), PLAY_PARALLEL); }

    public List<CatalogEntry> catalog(WorkflowDefinition definition) {
        return refresh(definition, refreshRun()).entries();
    }

    Refresh refresh(WorkflowDefinition definition, WorkflowCalls.Run run) {
        var draft = definition.draft();
        var plan = WorkflowPlan.of(draft);
        var listing = draft.listing();
        var shared = calls.run(plan.refreshCalls(CallScope.SHARED), plan, Map.of(), run, null);
        var entryCalls = plan.refreshCalls(CallScope.ENTRY);
        var entries = WorkflowJson.entries(listing, shared.responses().get(listing.call()), limit(plan));
        var entryValues = listing.variables().stream().filter(v -> plan.refreshEntryValues().contains(v.name())).toList();
        var tiles = entries.stream().map(entry -> CompletableFuture.supplyAsync(
                () -> tile(entry, plan, listing, entryValues, shared.values(), entryCalls, run), VIRTUAL)).toList();
        List<Tiled> done = tiles.stream().map(CompletableFuture::join).toList();
        return new Refresh(done.stream().map(Tiled::entry).toList(),
                done.stream().map(Tiled::problem).filter(Objects::nonNull).toList(),
                done.stream().anyMatch(Tiled::artworkOmitted));
    }

    private Tiled tile(WorkflowJson.Entry entry, WorkflowPlan plan, Listing listing, List<Variable> entryValues,
                       Map<String, WorkflowJson.Value> shared, List<Call> entryCalls, WorkflowCalls.Run run) {
        Map<String, WorkflowJson.Value> values = shared;
        String problem = null;
        try {
            var known = new HashMap<>(shared);
            known.putAll(WorkflowJson.values(entryValues, entry.node()));
            values = calls.run(entryCalls, plan, known, run, entry.title()).values();
        } catch (WorkflowException failure) {
            problem = failure.call() != null ? failure.getMessage() : "Entry \"" + entry.title() + "\": " + failure.detail();
        }
        String subtitle = subtitle(listing.subtitle(), entry.subtitle(), values);
        URI artwork = artwork(listing.artwork(), entry.artwork(), values);
        boolean omitted = artwork == null && hasArtworkText(listing.artwork(), entry, values);
        return new Tiled(new CatalogEntry(entry.key(), entry.title(), subtitle, artwork), problem, omitted);
    }

    private static String subtitle(Field field, String fromPointer, Map<String, WorkflowJson.Value> values) {
        if (field == null) return null;
        if (field.variable() == null) return fromPointer;
        WorkflowJson.Value value = values.get(field.variable());
        return value != null && value.text().length() <= 240 ? value.text() : null;
    }

    private static URI artwork(Field field, URI fromPointer, Map<String, WorkflowJson.Value> values) {
        if (field == null) return null;
        if (field.variable() == null) return fromPointer;
        WorkflowJson.Value value = values.get(field.variable());
        return value == null ? null : WorkflowJson.artwork(value.text());
    }

    /** True when an artwork address was there but was not a public HTTPS image address. */
    private static boolean hasArtworkText(Field field, WorkflowJson.Entry entry, Map<String, WorkflowJson.Value> values) {
        if (field == null) return false;
        if (field.variable() != null) return values.containsKey(field.variable());
        var node = entry.node() == null ? null : entry.node().at(field.pointer());
        return node != null && !node.isMissingNode() && !node.isNull();
    }

    public ResolvedMedia resolve(WorkflowDefinition definition, String entryKey) {
        var run = playRun();
        var context = play(definition, run);
        var entry = context.entries().stream().filter(e -> e.key().equals(entryKey)).findFirst()
                .orElseThrow(() -> new WorkflowException(WorkflowException.Stage.SELECT,
                        "This item is no longer available; refresh the Dashboard"));
        var values = entryValues(definition, context, entry, run);
        URI url = media(definition, context.plan(), values);
        http.checkMedia(url, run.deadline());
        return new ResolvedMedia(url, definition.draft().cast().mimeType(), entry.title());
    }

    PlayContext play(WorkflowDefinition definition, WorkflowCalls.Run run) {
        var draft = definition.draft();
        var plan = WorkflowPlan.of(draft);
        var shared = calls.run(plan.playCalls(CallScope.SHARED), plan, Map.of(), run, null);
        List<WorkflowJson.Entry> entries = draft.mode() == Mode.SINGLE ? List.of(single(draft.tile()))
                : WorkflowJson.entries(draft.listing(), shared.responses().get(draft.listing().call()), limit(plan));
        return new PlayContext(plan, shared.values(), entries);
    }

    Map<String, WorkflowJson.Value> entryValues(WorkflowDefinition definition, PlayContext context,
                                                WorkflowJson.Entry entry, WorkflowCalls.Run run) {
        var draft = definition.draft();
        var known = new HashMap<>(context.shared());
        String title = null;
        if (draft.mode() == Mode.GENERATED) {
            Set<String> needed = context.plan().playEntryValues();
            known.putAll(WorkflowJson.values(draft.listing().variables().stream()
                    .filter(v -> needed.contains(v.name())).toList(), entry.node()));
            title = entry.title();
        }
        return calls.run(context.plan().playCalls(CallScope.ENTRY), context.plan(), known, run, title).values();
    }

    URI media(WorkflowDefinition definition, WorkflowPlan plan, Map<String, WorkflowJson.Value> values) {
        return new WorkflowTemplate(definition.draft().cast().template(), Set.copyOf(plan.variables())).expand(values);
    }

    /** Play must see the same entries as the refresh that built the tiles, so both use this limit. */
    private static int limit(WorkflowPlan plan) {
        return plan.refreshCalls(CallScope.ENTRY).isEmpty() ? ENTRY_LIMIT : ENTRY_LIMIT_WITH_CALLS;
    }

    private static WorkflowJson.Entry single(Tile tile) {
        return new WorkflowJson.Entry("single", tile.title(), tile.subtitle(), WorkflowJson.artwork(tile.artwork()), null);
    }

    /** The GET a call makes, with the values known so far substituted into its URL and headers. */
    static WorkflowHttpClient.Request request(Call call, Set<String> names, Map<String, WorkflowJson.Value> values) {
        URI url = new WorkflowTemplate(call.url(), names, "call URL", WorkflowException.Stage.FETCH).expand(values);
        List<Header> headers = call.headers().stream()
                .map(header -> new Header(header.name(), new WorkflowHeaderTemplate(header.value()).expand(values)))
                .toList();
        return new WorkflowHttpClient.Request(url.toString(), headers);
    }
}
```

`WorkflowConfiguration`:

```java
    @Bean public WorkflowRunner workflowRunner(WorkflowHttpClient http, WorkflowProperties properties) {
        return new WorkflowRunner(http, properties);
    }
```

`WorkflowTestService` still uses its interim code from Task 3 until Task 7. Keep it compiling. It uses
`WorkflowRunner.ENTRY_LIMIT` and `WorkflowRunner.request`, which both still exist.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.sources.workflows.*' --tests 'dev.andre.homecontrol.web.*Workflow*'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/andre/homecontrol/sources/workflows/WorkflowRunner.java \
        src/main/java/dev/andre/homecontrol/sources/workflows/WorkflowConfiguration.java \
        src/test/java/dev/andre/homecontrol/sources/workflows/
git commit -m "feat: refresh tiles and resolve Play through a workflow's calls"
```

---

### Task 7: Test through the runner, with failures that name the call

**Files:**
- Rewrite: `src/main/java/dev/andre/homecontrol/sources/workflows/WorkflowTestService.java`
- Modify: `src/main/resources/templates/workflow-editor.html` (entries text only)
- Test: `src/test/java/dev/andre/homecontrol/sources/workflows/WorkflowTestServiceTest.java`

**Interfaces:**
- Consumes: `WorkflowRunner.refresh/refreshRun/play/playRun/entryValues/media`, `WorkflowPlan.variables/sensitive`.
- Produces: `new WorkflowTestService(WorkflowStore, LoginService, WorkflowRunner, WorkflowHttpClient)`.
  `Result(List<StageView> stages, int totalEntries, List<SampleView> samples, List<String> warnings)` is unchanged.
  The stage names are now `Refresh` (generated mode only), `Play`, or `Call <name>` for a failing call.

- [ ] **Step 1: Write the failing tests**

Rewrite `WorkflowTestServiceTest` against `FakeWorkflowServer` and a real client (as in `WorkflowRunnerTest`).
Keep its authentication and changed-revision tests, adapting their construction to
`new WorkflowTestService(store, login, runner, http)`. Then add:

```java
    @Test void generatedChainReportsRefreshPlayMaskedSamplesAndEntryProblems() throws Exception {
        try (var server = new FakeWorkflowServer(); var http = client()) {
            server.respond("/list", 200, "{\"token\":\"secret-token\",\"items\":[{\"id\":\"news\",\"title\":\"News\"},{\"id\":\"music\",\"title\":\"Music\"}]}");
            server.respond("/images/news", 200, "{\"url\":\"https://images.example/news.png\"}");
            server.respond("/images/music", 404, "{}");
            server.respond("/stream/news", 200, "{\"path\":\"secret-path-news\"}");
            server.respond("/stream/music", 200, "{\"path\":\"secret-path-music\"}");
            var saved = save(WorkflowFixtures.chain(server.url("/")));
            var result = service(http).test(saved.id(), saved.revision(), authenticatedRequest());
            assertThat(result.stages()).extracting(WorkflowTestService.StageView::name).containsExactly("Refresh", "Play");
            assertThat(result.stages()).allMatch(WorkflowTestService.StageView::success);
            assertThat(result.totalEntries()).isEqualTo(2);
            assertThat(result.samples()).extracting(WorkflowTestService.SampleView::title).containsExactly("News", "Music");
            assertThat(result.samples().getFirst().variables()).contains("token = •••", "path = •••", "id = news");
            assertThat(result.warnings()).contains("Call images · entry \"Music\": server returned HTTP 404");
            assertThat(result.toString()).doesNotContain("secret-token", "secret-path");
        }
    }

    @Test void aFailingSharedCallIsNamedAndLeavesNoSamples() throws Exception {
        try (var server = new FakeWorkflowServer(); var http = client()) {
            server.respond("/list", 500, "{}");
            var saved = save(WorkflowFixtures.chain(server.url("/")));
            var result = service(http).test(saved.id(), saved.revision(), authenticatedRequest());
            assertThat(result.stages()).last().satisfies(stage -> {
                assertThat(stage.name()).isEqualTo("Call list");
                assertThat(stage.success()).isFalse();
                assertThat(stage.message()).isEqualTo("Call list: server returned HTTP 500");
            });
            assertThat(result.samples()).isEmpty();
        }
    }
```

`save`, `service`, `client` and `authenticatedRequest` are small helpers in the test, built on the store/login mocks
the existing test already uses. `client()` resolves every host to 127.0.0.1, so `media.example` passes the address
check.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.sources.workflows.WorkflowTestServiceTest'`
Expected: FAIL (the four-argument constructor does not exist; the stages are the old names).

- [ ] **Step 3: Implement**

```java
@Service
@ConditionalOnModule(Module.WORKFLOWS)
public final class WorkflowTestService {
    public record StageView(String name, boolean success, String message) {}
    public record SampleView(String title, String subtitle, List<String> variables, String maskedUrl) {
        public SampleView { variables = List.copyOf(variables); }
    }
    public record Result(List<StageView> stages, int totalEntries, List<SampleView> samples, List<String> warnings) {
        public Result { stages = List.copyOf(stages); samples = List.copyOf(samples); warnings = List.copyOf(warnings); }
    }
    private static final String RECEIVER = "Your receiver must reach the media address directly. Custom media-download headers are not supported.";
    private static final String ARTWORK = "Some artwork was omitted because it is not a public HTTPS image address without credentials.";
    private final WorkflowStore store;
    private final LoginService login;
    private final WorkflowRunner runner;
    private final WorkflowHttpClient http;

    public WorkflowTestService(WorkflowStore store, LoginService login, WorkflowRunner runner, WorkflowHttpClient http) {
        this.store = store; this.login = login; this.runner = runner; this.http = http;
    }

    public Result test(String id, long revision, HttpServletRequest request) {
        authenticate(request);
        WorkflowDefinition saved = current(id, revision);
        var draft = saved.draft();
        List<StageView> stages = new ArrayList<>();
        List<SampleView> samples = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        int total = 0;
        String active = "Refresh";
        try {
            if (draft.mode() == WorkflowDraft.Mode.GENERATED) {
                var refresh = runner.refresh(saved, runner.refreshRun());
                total = refresh.entries().size();
                stages.add(new StageView("Refresh", true, total + " entries"));
                warnings.addAll(refresh.problems());
                if (refresh.artworkOmitted()) warnings.add(ARTWORK);
            }
            active = "Play";
            var run = runner.playRun();
            var context = runner.play(saved, run);
            if (draft.mode() == WorkflowDraft.Mode.SINGLE) total = 1;
            for (var entry : context.entries().stream().limit(5).toList()) {
                var values = runner.entryValues(saved, context, entry, run);
                var url = runner.media(saved, context.plan(), values);
                http.checkMedia(url, run.deadline());
                var template = new WorkflowTemplate(draft.cast().template(), java.util.Set.copyOf(context.plan().variables()));
                samples.add(new SampleView(entry.title(), entry.subtitle(), masked(context.plan(), values), template.preview(values)));
            }
            stages.add(new StageView("Play", true, "Complete"));
            warnings.add(RECEIVER);
        } catch (RuntimeException failure) {
            // WorkflowException's contract allows only locally authored safe context.
            // Never expose messages or causes from parser, network or other exceptions.
            boolean safe = failure instanceof WorkflowException;
            String name = safe && ((WorkflowException) failure).call() != null ? "Call " + ((WorkflowException) failure).call() : active;
            String message = safe ? failure.getMessage() : "Could not complete this step. Check the saved settings and response format.";
            stages.add(new StageView(name, false, message));
            samples.clear();
        }
        authenticate(request);
        current(id, revision); // An explicit Test may run while disabled, but never return an obsolete revision.
        return new Result(stages, total, samples, warnings);
    }

    private static List<String> masked(WorkflowPlan plan, java.util.Map<String, WorkflowJson.Value> values) {
        return plan.variables().stream().filter(values::containsKey)
                .map(name -> name + " = " + (plan.sensitive(name) ? "•••" : values.get(name).text())).toList();
    }

    // authenticate(...) and current(...) and changed() stay as they are; remove ok(...) and label(...).
}
```

Use a pattern-matching `instanceof` (`failure instanceof WorkflowException known && known.call() != null`) instead
of the casts if Sonar flags them.

In `workflow-editor.html`, the sample heading line becomes:
`<strong th:text="${testResult.totalEntries()}">0</strong> entries found. Showing up to five samples.` (unchanged).
Only the "Save your changes first" hint stays.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.sources.workflows.*'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/andre/homecontrol/sources/workflows/WorkflowTestService.java \
        src/main/resources/templates/workflow-editor.html \
        src/test/java/dev/andre/homecontrol/sources/workflows/WorkflowTestServiceTest.java
git commit -m "feat: test a workflow through its calls and name the call that failed"
```

---

### Task 8: Form and controller for several calls

**Files:**
- Rewrite: `src/main/java/dev/andre/homecontrol/sources/workflows/WorkflowForm.java`
- Modify: `src/main/java/dev/andre/homecontrol/sources/workflows/WorkflowSetupController.java`
- Test: `WorkflowSetupControllerTest.java`, `WorkflowSetupControllerErrorsTest.java`

**Interfaces:**
- Consumes: v2 records, `WorkflowPlan.of(...).phase(name).label()`, `WorkflowValidator.validate`,
  `WorkflowException.detail()`.
- Produces: form fields (and so request parameter names) used by Task 9's template:
  - `name`, `enabled`, `mode`, `kind`, `title`, `subtitle`, `artwork`
  - `calls[i].name`, `calls[i].scope`, `calls[i].savedName`, `calls[i].urlMode`, `calls[i].url`, `calls[i].headersMode`
  - `calls[i].headers[j].name|value`, `calls[i].variables[j].name|pointer|sensitive`
  - `entryCall`, `arrayPointer`, `idPointer`, `titlePointer`
  - `subtitleFrom` (`NONE|POINTER|VARIABLE`), `subtitlePointer`, `subtitleVariable`
  - `artworkFrom`, `artworkPointer`, `artworkVariable`
  - `entryVariables[j].name|pointer|sensitive`
  - `templateMode`, `template`, `mimeType`, `expectedRevision`, `loginPassword`, `loginPasswordConfirmation`

  Element ids are `workflow-` + the name with `[` → `-` and `].` → `-` (e.g. `workflow-calls-0-headers-1-name`).
  `WorkflowForm.blank()` gives a new workflow its first call, named `main`.

- [ ] **Step 1: Write the failing tests**

Update the existing controller tests' parameter names by these rules:

| Old parameter | New parameter |
|---|---|
| `url`, `urlMode`, `headersMode` | `calls[0].url`, `calls[0].urlMode`, `calls[0].headersMode` |
| `headers[j].name/value` | `calls[0].headers[j].name/value` |
| `variables[j].*` with scope `ROOT` | `calls[0].variables[j].name/pointer/sensitive` (drop `scope`) |
| `variables[j].*` with scope `ENTRY` | `entryVariables[k].name/pointer/sensitive` (drop `scope`) |
| `includeSubtitlePointer=true` | `subtitleFrom=POINTER` |
| `includeArtworkPointer=true` | `artworkFrom=POINTER` |
| (new, every post) | `calls[0].name=main`, `calls[0].scope=SHARED`, and for generated drafts `entryCall=main` |
| (new, edits) | `calls[0].savedName=main` |

Error-link expectations move with the fields (`#workflow-variables-1-name` → `#workflow-calls-0-variables-1-name`).
The binding-limit tests (`variables[32]`, `headers[16]`, `variables[01]`, …) move to the new families with the new
limits (`calls[8].name`, `calls[0].headers[16].name`, `calls[0].variables[64].name`, `calls[0].variables[01].name`).

Add:

```java
    @Test void savesTwoCallsWhereTheSecondUsesTheFirstsToken() throws Exception {
        authenticated();
        mvc.perform(post("/setup/workflows").with(csrfIfUsed())
                        .param("name", "Chain").param("mode", "SINGLE").param("kind", "VIDEO").param("title", "Chain")
                        .param("calls[0].name", "token").param("calls[0].scope", "SHARED").param("calls[0].urlMode", "REPLACE")
                        .param("calls[0].url", "https://api.example/token").param("calls[0].headersMode", "REPLACE")
                        .param("calls[0].variables[0].name", "token").param("calls[0].variables[0].pointer", "/token")
                        .param("calls[0].variables[0].sensitive", "true")
                        .param("calls[1].name", "stream").param("calls[1].scope", "SHARED").param("calls[1].urlMode", "REPLACE")
                        .param("calls[1].url", "https://api.example/stream").param("calls[1].headersMode", "REPLACE")
                        .param("calls[1].headers[0].name", "Authorization").param("calls[1].headers[0].value", "Bearer {token}")
                        .param("calls[1].variables[0].name", "path").param("calls[1].variables[0].pointer", "/path")
                        .param("templateMode", "REPLACE").param("template", "https://media.example/{path}")
                        .param("mimeType", "video/mp4").param("expectedRevision", "0"))
                .andExpect(status().is3xxRedirection());
        var draft = captureCreatedDraft();
        assertThat(draft.calls()).extracting(Call::name).containsExactly("token", "stream");
        assertThat(draft.calls().get(1).headers().getFirst().value()).isEqualTo("Bearer {token}");
    }

    @Test void keepFindsTheSavedCallAfterARename() throws Exception {
        var saved = savedDefinition(WorkflowFixtures.single(URI.create("https://api.example/saved-secret")));
        var form = formOf(saved);                            // the saved workflow as the editor posts it
        form.set("calls[0].name", "renamed");                // .param would add a second value, which is refused
        form.set("calls[0].savedName", "main");
        form.set("calls[0].urlMode", "KEEP");
        form.set("calls[0].headersMode", "KEEP");
        mvc.perform(post("/setup/workflows/" + saved.id()).with(csrfIfUsed()).params(form))
                .andExpect(status().is3xxRedirection());
        var call = captureUpdatedDraft().calls().getFirst();
        assertThat(call.name()).isEqualTo("renamed");
        assertThat(call.url()).isEqualTo("https://api.example/saved-secret");
        assertThat(call.headers()).isEqualTo(saved.draft().calls().getFirst().headers());
    }

    @Test void aCallUsingAValueFromFurtherDownIsMarkedOnThatCall() throws Exception {
        // As savesTwoCalls…, but call 0 uses {path} from call 1 in its URL.
        var result = mvc.perform(post("/setup/workflows").with(csrfIfUsed()).params(twoCallsWhereTheFirstUsesTheSecond()))
                .andReturn();
        String page = result.getResponse().getContentAsString();
        assertThat(page).contains("href=\"#workflow-calls-0-name\"")
                .contains("This call uses a value no call above it provides.");
    }

    @Test void nestedRowsOfACallThatWasNotSubmittedAreRejected() throws Exception {
        var result = mvc.perform(post("/setup/workflows").with(csrfIfUsed()).params(validSingleCall())
                        .param("calls[3].headers[0].name", "X-Orphan"))
                .andReturn();
        assertThat(result.getResponse().getContentAsString()).contains("Some submitted fields are invalid");
    }

    @Test void theEditorShowsEachCallsPhaseAndNoSavedAddresses() throws Exception {
        var saved = savedDefinition(WorkflowFixtures.chain(URI.create("https://api.example/saved-secret")));
        String page = mvc.perform(get("/setup/workflows/" + saved.id())).andReturn().getResponse().getContentAsString();
        assertThat(page).contains("Runs at refresh and Play", "Runs at refresh", "Runs at Play")
                .doesNotContain("saved-secret", "Bearer {token}");
    }
```

`captureCreatedDraft`, `captureUpdatedDraft`, `savedDefinition`, `formOf`, `validSingleCall` and
`twoCallsWhereTheFirstUsesTheSecond` are helpers written in the test class, next to the helpers it already has
(the existing tests capture drafts with an `ArgumentCaptor` on the store mock). `formOf(saved)` returns a
`MultiValueMap<String, String>` (a `LinkedMultiValueMap`) of the fields `WorkflowForm.from(saved)` would post. Change
single values with `set`, never with a further `.param`.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.sources.workflows.WorkflowSetupController*'`
Expected: FAIL (the parameters are not bound; the fields do not exist).

- [ ] **Step 3: Implement**

`WorkflowForm.java`:

```java
package dev.andre.homecontrol.sources.workflows;

import dev.andre.homecontrol.core.playback.ContentKind;

import java.util.ArrayList;
import java.util.List;

import static dev.andre.homecontrol.sources.workflows.WorkflowDraft.*;

/** MVC-only draft. Stored credentials never populate this object. */
public final class WorkflowForm {
    public enum Replacement { KEEP, REPLACE }
    public enum DisplaySource { NONE, POINTER, VARIABLE }

    public String name = "";
    public boolean enabled = true;
    public Mode mode = Mode.SINGLE;
    public ContentKind kind = ContentKind.VIDEO;
    public String title = "";
    public String subtitle = "";
    public String artwork = "";
    public List<CallRow> calls = new ArrayList<>();
    public String entryCall = "";
    public String arrayPointer = "";
    public String idPointer = "";
    public String titlePointer = "";
    public DisplaySource subtitleFrom = DisplaySource.NONE;
    public String subtitlePointer = "";
    public String subtitleVariable = "";
    public DisplaySource artworkFrom = DisplaySource.NONE;
    public String artworkPointer = "";
    public String artworkVariable = "";
    public List<VariableRow> entryVariables = new ArrayList<>();
    public Replacement templateMode = Replacement.REPLACE;
    public String template = "";
    public String mimeType = "video/mp4";
    public long expectedRevision;
    public String loginPassword = "";
    public String loginPasswordConfirmation = "";

    public static final class CallRow {
        public String name = "";
        public CallScope scope = CallScope.SHARED;
        /** The name this call was saved under; it finds the saved URL and headers for Keep. Empty for a new call. */
        public String savedName = "";
        public Replacement urlMode = Replacement.REPLACE;
        public String url = "";
        public Replacement headersMode = Replacement.REPLACE;
        public List<HeaderRow> headers = new ArrayList<>();
        public List<VariableRow> variables = new ArrayList<>();
        /** Shown, never bound: when the saved call runs. */
        public String phase = "";
    }

    public static final class VariableRow {
        public String name = "";
        public String pointer = "";
        public boolean sensitive = true;
    }

    public static final class HeaderRow {
        public String name = "";
        public String value = "";
    }

    /** A new workflow's editor starts with one shared call. */
    public static WorkflowForm blank() {
        WorkflowForm form = new WorkflowForm();
        CallRow call = new CallRow();
        call.name = WorkflowMigration.MAIN;
        form.calls.add(call);
        form.entryCall = call.name;
        return form;
    }

    public static WorkflowForm from(WorkflowDefinition saved) {
        WorkflowForm form = new WorkflowForm();
        WorkflowDraft d = saved.draft();
        WorkflowPlan plan = WorkflowPlan.of(d);
        form.name = d.name(); form.enabled = d.enabled(); form.mode = d.mode(); form.kind = d.kind();
        form.expectedRevision = saved.revision(); form.mimeType = d.cast().mimeType();
        form.templateMode = Replacement.KEEP;
        for (Call call : d.calls()) {
            CallRow row = new CallRow();
            row.name = row.savedName = call.name();
            row.scope = call.scope();
            row.urlMode = row.headersMode = Replacement.KEEP;
            // Header replacement starts empty; Keep retains the saved list in service memory.
            row.variables = rows(call.variables());
            row.phase = plan.phase(call.name()).label();
            form.calls.add(row);
        }
        if (d.tile() != null) {
            form.title = d.tile().title(); form.subtitle = text(d.tile().subtitle()); form.artwork = text(d.tile().artwork());
        }
        if (d.listing() != null) {
            Listing l = d.listing();
            form.entryCall = l.call();
            form.arrayPointer = l.arrayPointer(); form.idPointer = l.idPointer(); form.titlePointer = l.titlePointer();
            form.subtitleFrom = source(l.subtitle());
            form.subtitlePointer = l.subtitle() == null ? "" : text(l.subtitle().pointer());
            form.subtitleVariable = l.subtitle() == null ? "" : text(l.subtitle().variable());
            form.artworkFrom = source(l.artwork());
            form.artworkPointer = l.artwork() == null ? "" : text(l.artwork().pointer());
            form.artworkVariable = l.artwork() == null ? "" : text(l.artwork().variable());
            form.entryVariables = rows(l.variables());
        } else if (!d.calls().isEmpty()) {
            form.entryCall = d.calls().getFirst().name();
        }
        return form;
    }

    public WorkflowDraft toDraft(WorkflowDefinition saved) {
        if (templateMode == null || (saved == null && templateMode == Replacement.KEEP)) throw keepWithoutSaved();
        List<Call> built = new ArrayList<>();
        for (CallRow row : calls) built.add(call(row, saved));
        String media = templateMode == Replacement.KEEP ? saved.draft().cast().template() : template;
        return new WorkflowDraft(name, enabled, mode, kind, built, listing(),
                mode == Mode.SINGLE ? new Tile(title, optional(subtitle), optional(artwork)) : null,
                new Cast(media, mimeType));
    }

    private static Call call(CallRow row, WorkflowDefinition saved) {
        if (row.urlMode == null || row.headersMode == null) throw keepWithoutSaved();
        boolean keeps = row.urlMode == Replacement.KEEP || row.headersMode == Replacement.KEEP;
        Call previous = keeps ? savedCall(saved, row.savedName) : null;
        String url = row.urlMode == Replacement.KEEP ? previous.url() : row.url;
        List<Header> headers = row.headersMode == Replacement.KEEP ? previous.headers()
                : row.headers.stream().map(h -> new Header(h.name, h.value)).toList();
        return new Call(row.name, row.scope, url, headers, variables(row.variables));
    }

    private static Call savedCall(WorkflowDefinition saved, String savedName) {
        if (saved == null || savedName == null || savedName.isEmpty()) throw keepWithoutSaved();
        return saved.draft().calls().stream().filter(call -> call.name().equals(savedName)).findFirst()
                .orElseThrow(WorkflowForm::keepWithoutSaved);
    }

    private static WorkflowException keepWithoutSaved() {
        return new WorkflowException(WorkflowException.Stage.WORKFLOW, "new workflows require replacement settings");
    }

    private Listing listing() {
        if (mode != Mode.GENERATED) return null;
        return new Listing(entryCall, arrayPointer, idPointer, titlePointer,
                field(subtitleFrom, subtitlePointer, subtitleVariable), field(artworkFrom, artworkPointer, artworkVariable),
                variables(entryVariables));
    }

    private static Field field(DisplaySource source, String pointer, String variable) {
        if (source == null || source == DisplaySource.NONE) return null;
        return source == DisplaySource.POINTER ? new Field(pointer, null) : new Field(null, variable);
    }

    private static DisplaySource source(Field field) {
        if (field == null) return DisplaySource.NONE;
        return field.pointer() != null ? DisplaySource.POINTER : DisplaySource.VARIABLE;
    }

    private static List<Variable> variables(List<VariableRow> rows) {
        return rows.stream().map(row -> new Variable(row.name, row.pointer, row.sensitive)).toList();
    }

    private static List<VariableRow> rows(List<Variable> variables) {
        List<VariableRow> rows = new ArrayList<>();
        for (Variable v : variables) {
            VariableRow row = new VariableRow();
            row.name = v.name(); row.pointer = v.pointer(); row.sensitive = v.sensitive();
            rows.add(row);
        }
        return rows;
    }

    public void clearSecrets() {
        template = loginPassword = loginPasswordConfirmation = "";
        for (CallRow call : calls) {
            call.url = "";
            call.headers.forEach(row -> row.value = "");
        }
    }

    private static String text(String value) { return value == null ? "" : value; }
    private static String optional(String value) { return value == null || value.isEmpty() ? null : value; }
}
```

`WorkflowSetupController.java` — binding and error targeting:

```java
    private static final Set<String> SCALARS = Set.of("name", ENABLED, "mode", "kind", "title", "subtitle", "artwork",
            "entryCall", "arrayPointer", "idPointer", "titlePointer", "subtitleFrom", "subtitlePointer",
            "subtitleVariable", "artworkFrom", "artworkPointer", "artworkVariable", "templateMode", "template",
            "mimeType", "expectedRevision", "loginPassword", "loginPasswordConfirmation");
    private static final Set<String> CHECKBOXES = Set.of(ENABLED);
    private static final String CALLS = "calls";
    private static final Pattern CALL = Pattern.compile("calls\\[([0-7])\\]\\.(name|scope|savedName|urlMode|url|headersMode)");
    private static final Pattern HEADER = Pattern.compile("(calls\\[[0-7]\\]\\.headers)\\[(0|[1-9]\\d?)\\]\\.(name|value)");
    private static final Pattern VARIABLE = Pattern.compile(
            "(calls\\[[0-7]\\]\\.variables|entryVariables)\\[(0|[1-9]\\d?)\\]\\.(name|pointer|sensitive)");
```

In `emptyForm`, the Keep defaults now apply to `templateMode` only. Each call row's modes arrive with its row.
`createEditor` uses `WorkflowForm.blank()` instead of `new WorkflowForm()`. `binder.setAutoGrowCollectionLimit(64)`.

```java
    private static BindingFields allowedFields(HttpServletRequest request) {
        Set<String> allowed = new HashSet<>(SCALARS);
        Map<String, Set<Integer>> rows = new HashMap<>();
        boolean invalid = false;
        for (var parameter : request.getParameterMap().entrySet()) {
            if (!allowParameter(parameter.getKey(), parameter.getValue(), allowed, rows)) invalid = true;
        }
        Set<Integer> calls = rows.getOrDefault(CALLS, Set.of());
        for (var family : rows.entrySet()) {
            String prefix = family.getKey() + "[";
            // A call's rows without the call itself, or rows with gaps, are not bound at all.
            boolean orphan = family.getKey().startsWith("calls[") && !calls.contains(family.getKey().charAt(6) - '0');
            if (orphan || !contiguous(family.getValue())) {
                allowed.removeIf(field -> field.startsWith(orphan ? family.getKey() : prefix));
                invalid = true;
            }
        }
        return new BindingFields(allowed, invalid);
    }

    private static boolean allowParameter(String raw, String[] values, Set<String> allowed, Map<String, Set<Integer>> rows) {
        String field = raw.startsWith("_") ? raw.substring(1) : raw;
        boolean marker = raw.startsWith("_");
        if (SCALARS.contains(field) && (!marker || CHECKBOXES.contains(field))) return singleValue(values);
        if (!singleValue(values)) return false;
        var call = CALL.matcher(field);
        if (call.matches() && !marker) {
            rows.computeIfAbsent(CALLS, k -> new HashSet<>()).add(Integer.parseInt(call.group(1)));
            allowed.add(field);
            return true;
        }
        var header = HEADER.matcher(field);
        if (header.matches() && !marker) return row(header.group(1), Integer.parseInt(header.group(2)), 16, field, allowed, rows);
        var variable = VARIABLE.matcher(field);
        if (variable.matches() && (!marker || variable.group(3).equals("sensitive"))) {
            return row(variable.group(1), Integer.parseInt(variable.group(2)), 64, field, allowed, rows);
        }
        return false;
    }

    private static boolean row(String family, int index, int limit, String field, Set<String> allowed,
                               Map<String, Set<Integer>> rows) {
        if (index >= limit) return false;
        rows.computeIfAbsent(family, k -> new HashSet<>()).add(index);
        allowed.add(field);
        return true;
    }
```

The existing `singleValue` check for markers (`_calls[0].variables[0].sensitive`) must still accept one value.
The code above checks `singleValue` before the patterns, so it does.

Error targeting. `validationField` reads `failure.detail()`. The message shown stays locally authored:

```java
    private static void rejectSaveFailure(WorkflowException failure, WorkflowForm form, BindingResult binding,
                                          HttpServletResponse response) {
        if ("Workflow changed; reopen this item".equals(failure.detail())) { /* unchanged 409 branch */ }
        String field = validationField(failure, form);
        String message = guidance(failure.detail());
        if (field == null) binding.reject("settings", message == null ? "Check the calls, media template, fields and headers." : message);
        else binding.rejectValue(field, "settings", message == null ? "Check this field's format and limits." : message);
    }

    /** Text written here, chosen by the kind of problem; the domain message itself is never shown. */
    private static String guidance(String detail) {
        if (detail.contains(" uses {") && detail.contains("further down")) return "This call uses a value no call above it provides.";
        if (detail.contains(" uses {")) return "This call uses a value that no call defines.";
        if (detail.contains("make it a per-entry call")) return "This call uses an entry value; set it to run once per entry.";
        if (detail.endsWith("nothing uses this call")) return "Nothing uses this call. Use one of its values or remove it.";
        if (detail.endsWith("is marked sensitive")) return "A tile cannot show a value marked sensitive.";
        if (detail.equals("the entry source must be a shared call")) return "Choose a call that runs once as the source of entries.";
        return null;
    }

    private static String validationField(WorkflowException failure, WorkflowForm form) {
        if (failure.stage() == WorkflowException.Stage.BUILD) return "templateMode";
        String detail = failure.detail();
        String field = callField(detail, form);
        if (field == null) field = variableField(detail, form);
        if (field == null) field = scalarField(detail);
        return field;
    }

    private static String callField(String detail, WorkflowForm form) {
        for (int i = 0; i < form.calls.size(); i++) {
            String name = form.calls.get(i).name;
            String prefix = "calls[" + i + "].";
            if (name == null || !name.matches("[a-z][a-z0-9_]{0,23}")) {
                if (detail.equals("invalid call name")) return prefix + "name";
                continue;
            }
            if (detail.equals("duplicate call name: " + name)) return prefix + "name";
            if (!detail.startsWith("call " + name + ": ")) continue;
            if (detail.contains("fetch URL")) return prefix + "urlMode";
            if (detail.contains("header")) return prefix + "headersMode";
            return prefix + "name";
        }
        return null;
    }

    private static String variableField(String detail, WorkflowForm form) {
        for (int i = 0; i < form.calls.size(); i++) {
            String found = variableRows(detail, form.calls.get(i).variables, "calls[" + i + "].variables[");
            if (found != null) return found;
        }
        return variableRows(detail, form.entryVariables, "entryVariables[");
    }

    private static String variableRows(String detail, List<WorkflowForm.VariableRow> rows, String prefix) {
        for (int i = 0; i < rows.size(); i++) {
            var row = rows.get(i);
            if (row.name == null || !row.name.matches("[A-Za-z]\\w{0,31}")) return prefix + i + "].name";
            if (detail.contains("mapping " + row.name + " pointer")) return prefix + i + "].pointer";
            if (detail.equals("duplicate mapping name: " + row.name)) return prefix + i + "].name";
        }
        return null;
    }

    private static String scalarField(String detail) {
        if (detail.contains("media type")) return "mimeType";
        if (detail.contains("artwork URL")) return "artwork";
        if (detail.equals("invalid name")) return "name";
        if (detail.contains("kind must")) return "kind";
        if (detail.contains("tile title")) return "title";
        if (detail.contains("tile subtitle")) return "subtitle";
        if (detail.contains("entry source")) return "entryCall";
        if (detail.contains("array pointer")) return "arrayPointer";
        if (detail.contains("entry ID pointer")) return "idPointer";
        if (detail.contains("entry title pointer")) return "titlePointer";
        if (detail.contains("entry subtitle pointer")) return "subtitlePointer";
        if (detail.contains("entry subtitle")) return "subtitleVariable";
        if (detail.contains("entry artwork pointer")) return "artworkPointer";
        if (detail.contains("entry artwork")) return "artworkVariable";
        return null;
    }

    private static boolean safeField(String field) {
        return SCALARS.contains(field) || CALL.matcher(field).matches() || HEADER.matcher(field).matches()
                || VARIABLE.matcher(field).matches();
    }
```

`fieldId` is unchanged (`"workflow-" + field.replace("[", "-").replace("].", "-")`). It yields
`workflow-calls-0-headers-1-name`.

A pointer-shaped display message (`invalid entry subtitle pointer`) must hit `subtitlePointer` before the broader
`entry subtitle` check, which is why the order above matters.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.sources.workflows.*'`
Expected: PASS. The editor template still has the v1 inputs, so `WorkflowSetupControllerTest` GET tests that look
for new ids fail until Task 9. Move those assertions (the `theEditorShowsEachCallsPhase…` test) into Task 9 if they
fail here, and commit only when the rest is green.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/andre/homecontrol/sources/workflows/WorkflowForm.java \
        src/main/java/dev/andre/homecontrol/sources/workflows/WorkflowSetupController.java \
        src/test/java/dev/andre/homecontrol/sources/workflows/WorkflowSetupController*.java
git commit -m "feat: bind several workflow calls with Keep and Replace per call"
```

---

### Task 9: Editor — call cards, entry source and display sources

**Files:**
- Modify: `src/main/resources/templates/workflow-editor.html`
- Rewrite: `src/main/resources/static/js/workflows.js`
- Modify: `src/main/resources/static/app.css` (card layout only)
- Test: `src/e2e/java/dev/andre/homecontrol/e2e/WorkflowE2eTest.java`, `WorkflowSetupControllerTest.java`

**Interfaces:**
- Consumes: the Task 8 form field names and ids.

- [ ] **Step 1: Write the failing browser tests**

In `WorkflowE2eTest`, replace the v1 helpers with call-card helpers:

```java
    private static Locator card(Page page, int index) {
        return page.locator("[data-rows='calls'] > [data-row='calls']").nth(index);
    }

    private static Locator value(Locator card, int index) {
        return card.locator("[data-row='variables']").nth(index);
    }

    private static Locator entryField(Page page, int index) {
        return page.locator("[data-rows='entryVariables'] > [data-row='entryVariables']").nth(index);
    }

    private static void addValue(Locator card, String name, String pointer, boolean sensitive) {
        card.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Add value")).click();
        Locator row = card.locator("[data-row='variables']").last();
        row.getByLabel("Variable name").fill(name);
        row.getByLabel("JSON Pointer").fill(pointer);
        assertThat(row.getByLabel("Sensitive value")).isChecked();
        if (!sensitive) row.getByLabel("Sensitive value").uncheck();
    }

    private static void addEntryField(Page page, String name, String pointer, boolean sensitive) {
        button(page, "Add entry field").click();
        Locator row = page.locator("[data-rows='entryVariables'] > [data-row='entryVariables']").last();
        row.getByLabel("Variable name").fill(name);
        row.getByLabel("JSON Pointer").fill(pointer);
        if (!sensitive) row.getByLabel("Sensitive value").uncheck();
    }
```

`fillNew` fills the first card: `card(page, 0).getByLabel("New source URL")`, the card's "Add header" button and
header row, and `addValue(card(page, 0), "C", "/auth/token", true)`. For generated mode it uses
`addEntryField(page, "A", "/id", false)` instead of an entry-scoped mapping. Otherwise it uses
`addValue(card(page, 0), "A", "/id", false)` before `C`. In the single-mode flow, the Test stage assertion
`containsText("Fetch JSON")` becomes `containsText("Play")`.

Replace `mappingScopesFollowModeIncludingNewRowsAndTransitions` with:

```java
    @BrowserTest
    void callScopeFollowsTileModeAndCardsReindexWhenMoved(String browser) {
        try (BrowserSession session = open(browser)) {
            Page page = session.page();
            page.navigate("/setup/workflows/new");
            assertThat(card(page, 0).getByLabel("Call name")).hasValue("main");
            assertThat(card(page, 0).locator("select[data-field='scope'] option")).hasCount(1);
            label(page, "Tile mode").selectOption("GENERATED");
            assertThat(card(page, 0).locator("select[data-field='scope'] option")).hasCount(2);
            button(page, "Add call").click();
            card(page, 1).getByLabel("Call name").fill("stream");
            card(page, 1).getByLabel("Runs").selectOption("ENTRY");
            assertThat(card(page, 1).getByLabel("New source URL")).hasAttribute("name", "calls[1].url");
            assertThat(card(page, 1).getByLabel("Source URL").locator("option[value='KEEP']")).isDisabled();
            card(page, 1).getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Move call up")).click();
            assertThat(card(page, 0).getByLabel("Call name")).hasValue("stream");
            assertThat(card(page, 0).getByLabel("New source URL")).hasAttribute("name", "calls[0].url");
            assertThat(card(page, 0).getByLabel("New source URL")).hasAttribute("id", "workflow-calls-0-url");
            assertThat(page.locator("#workflow-entryCall option")).hasText(new String[]{"stream", "main"});
            label(page, "Tile mode").selectOption("SINGLE");
            assertThat(card(page, 0).getByLabel("Runs")).hasValue("SHARED");
        }
    }
```

In `generatedEditReindexErrorLinksAndStablePlay`, the error link becomes `#workflow-calls-0-variables-0-name` (the
`C` value is the first value of card 0), and the reindex assertions use `value(card(page, 0), i)`. The name
attribute becomes `calls[0].variables[1].name` for the added row.

Add the chain test:

```java
    @BrowserTest
    void aListAndAPerEntryStreamCallCastTheChosenEntry(String browser) {
        try (FakeWorkflowServer upstream = upstream(); BrowserSession session = open(browser)) {
            upstream.respond("/list", 200, "{\"token\":\"" + TOKEN + "\",\"items\":[{\"id\":\"news\",\"title\":\"News\"},{\"id\":\"music\",\"title\":\"Music\"}]}");
            upstream.respond("/stream/news", 200, "{\"path\":\"news-" + MEDIA_SECRET + "\"}");
            Page page = session.page();
            page.navigate("/setup/workflows/new");
            label(page, "Workflow name").fill("Chain");
            label(page, "Tile mode").selectOption("GENERATED");
            card(page, 0).getByLabel("Call name").fill("list");
            card(page, 0).getByLabel("New source URL").fill(upstream.url("/list").toString());
            addValue(card(page, 0), "token", "/token", true);
            button(page, "Add call").click();
            card(page, 1).getByLabel("Call name").fill("stream");
            card(page, 1).getByLabel("Runs").selectOption("ENTRY");
            card(page, 1).getByLabel("New source URL").fill(upstream.url("/stream") + "/{id}?token={token}");
            addValue(card(page, 1), "path", "/path", true);
            label(page, "Entries come from").selectOption("list");
            label(page, "Entry array pointer").fill("/items");
            label(page, "Entry ID pointer").fill("/id");
            label(page, "Entry title pointer").fill("/title");
            addEntryField(page, "id", "/id", false);
            label(page, "New media URL template").fill(upstream.url("/media") + "/{path}?t={token}");
            label(page, "Home Control password (10–1024 characters)").fill(PASSWORD);
            label(page, "Confirm Home Control password").fill(PASSWORD);
            button(page, "Save workflow").click();
            assertThat(page).hasURL(Pattern.compile(".*/setup/workflows/w-[0-9a-f]{12}$"));
            assertThat(card(page, 0)).containsText("Runs at refresh and Play");
            assertThat(card(page, 1)).containsText("Runs at Play");
            assertPrivate(page);

            page.navigate("/?device=bedroom");
            Locator news = page.locator("button.tile[data-source='workflows']").filter(new Locator.FilterOptions().setHasText("News"));
            assertThat(news).isVisible();
            org.assertj.core.api.Assertions.assertThat(upstream.count("/stream/news")).isZero();
            news.click();
            page.locator("#sheet-play").click();
            await().untilAsserted(() -> org.assertj.core.api.Assertions.assertThat(fakeDevices.recorded("bedroom"))
                    .filteredOn(Action.CastLoad.class::isInstance).hasSize(1));
            org.assertj.core.api.Assertions.assertThat(((Action.CastLoad) fakeDevices.recorded("bedroom").getFirst()).load().toString())
                    .contains("/media/news-" + MEDIA_SECRET, "t=" + TOKEN);
            org.assertj.core.api.Assertions.assertThat(upstream.count("/stream/news")).isEqualTo(1);
            org.assertj.core.api.Assertions.assertThat(upstream.count("/stream/music")).isZero();
            assertPrivate(page);
        }
    }

    @BrowserTest
    void aMigratedVersionOneWorkflowOpensAndSavesUnchanged(String browser) {
        // Write a v1 definition straight into the secret store the way v1 did, open it, save, check it still plays.
    }
```

Write `aMigratedVersionOneWorkflowOpensAndSavesUnchanged` like this:
1. Log in (as `fillNew` does, by creating a throwaway workflow and removing it, or through `login` directly).
2. Store the v1 JSON from `WorkflowMigrationTest.GENERATED_V1` under `workflow.w-0123456789ab`, with its URL pointed
   at `upstream.url("/feed")`, through the autowired `SecretStore` or `LoginService.storeSecrets`, then call
   `workflows.reload()`.
3. Open `/setup/workflows/w-0123456789ab`, press **Save workflow**, and expect the redirect back to the same page with
   no `.error`.
4. Check that `workflows.find(...)` now encodes as `"schemaVersion":2`.

Add the assertions from `theEditorShowsEachCallsPhaseAndNoSavedAddresses` to `WorkflowSetupControllerTest` if Task 8
deferred them.

- [ ] **Step 2: Run the browser tests to verify they fail**

Run: `scripts/e2e.sh -Pe2eBrowsers=chromium --tests '*WorkflowE2eTest'`
Expected: FAIL (no call cards in the editor).

- [ ] **Step 3: Implement the template**

In `workflow-editor.html`, replace fieldsets 2 ("Fetch JSON") and 4 ("Map fields") with one **Calls** fieldset.
Change fieldset 3 ("Choose tiles") as shown. The section nav links become Details, Calls, Tiles, Media, Action,
Test. Renumber the legends.

```html
<fieldset id="workflow-calls">
<legend>2. Calls</legend>
<p class="hint">Each call fetches one JSON response with GET. A call can use values of the calls above it, such as <code>{token}</code>, in its URL and headers. Calls that do not depend on each other run at the same time. A call that runs once per entry is made for each tile.</p>
<p class="hint">In a header value, write <code>{{</code> and <code>}}</code> for a literal brace.</p>
<div data-rows="calls">
<article class="workflow-call" data-row="calls" th:each="call, c : ${workflowForm.calls}">
<div class="workflow-call-header">
<h3 th:text="|Call ${c.index + 1}|">Call 1</h3>
<p class="workflow-phase" th:text="${call.phase != '' ? call.phase : 'Save to see when this call runs'}">Runs at Play</p>
<button type="button" data-move-row="up" aria-label="Move call up">Up</button>
<button type="button" data-move-row="down" aria-label="Move call down">Down</button>
<button type="button" data-remove-row="calls" aria-label="Remove call">Remove</button>
</div>
<input type="hidden" data-field="savedName" th:id="|workflow-calls-${c.index}-savedName|" th:name="|calls[${c.index}].savedName|" th:value="${call.savedName}">
<div class="workflow-field">
<label th:for="|workflow-calls-${c.index}-name|">Call name</label>
<input data-field="name" data-call-name type="text" maxlength="24" autocomplete="off" th:id="|workflow-calls-${c.index}-name|" th:name="|calls[${c.index}].name|" th:value="${call.name}">
</div>
<div class="workflow-field">
<label th:for="|workflow-calls-${c.index}-scope|">Runs</label>
<select data-field="scope" th:id="|workflow-calls-${c.index}-scope|" th:name="|calls[${c.index}].scope|">
<option value="SHARED" th:selected="${call.scope != null and call.scope.name() == 'SHARED'}">Once</option>
<option value="ENTRY" th:if="${workflowForm.mode != null and workflowForm.mode.name() == 'GENERATED'}" th:selected="${call.scope != null and call.scope.name() == 'ENTRY'}">Once per entry</option>
</select>
</div>
<div class="workflow-field">
<label th:for="|workflow-calls-${c.index}-urlMode|">Source URL</label>
<select data-field="urlMode" th:id="|workflow-calls-${c.index}-urlMode|" th:name="|calls[${c.index}].urlMode|">
<option value="KEEP" th:selected="${call.urlMode != null and call.urlMode.name() == 'KEEP'}">Keep saved URL</option>
<option value="REPLACE" th:selected="${call.urlMode != null and call.urlMode.name() == 'REPLACE'}">Replace URL</option>
</select>
</div>
<div data-replacement="urlMode">
<div class="workflow-field">
<label th:for="|workflow-calls-${c.index}-url|">New source URL</label>
<input data-field="url" type="text" maxlength="8192" autocomplete="off" th:id="|workflow-calls-${c.index}-url|" th:name="|calls[${c.index}].url|" th:value="${call.url}">
</div>
</div>
<div class="workflow-field">
<label th:for="|workflow-calls-${c.index}-headersMode|">Request headers</label>
<select data-field="headersMode" th:id="|workflow-calls-${c.index}-headersMode|" th:name="|calls[${c.index}].headersMode|">
<option value="KEEP" th:selected="${call.headersMode != null and call.headersMode.name() == 'KEEP'}">Keep saved headers</option>
<option value="REPLACE" th:selected="${call.headersMode != null and call.headersMode.name() == 'REPLACE'}">Replace headers</option>
</select>
</div>
<div data-replacement="headersMode">
<p class="hint">Replacing with no rows clears all saved headers of this call.</p>
<div data-rows="headers">
<div class="workflow-row" data-row="headers" th:each="header, h : ${call.headers}">
<div class="workflow-field">
<label th:for="|workflow-calls-${c.index}-headers-${h.index}-name|">Header name</label>
<input data-field="name" type="text" autocomplete="off" maxlength="120" th:id="|workflow-calls-${c.index}-headers-${h.index}-name|" th:name="|calls[${c.index}].headers[${h.index}].name|" th:value="${header.name}">
</div>
<div class="workflow-field">
<label th:for="|workflow-calls-${c.index}-headers-${h.index}-value|">New header value</label>
<input data-field="value" type="password" autocomplete="off" maxlength="16384" th:id="|workflow-calls-${c.index}-headers-${h.index}-value|" th:name="|calls[${c.index}].headers[${h.index}].value|">
</div>
<button type="button" data-remove-row="headers" aria-label="Remove header">Remove</button>
</div>
</div>
<button type="button" data-add-row="headers">Add header</button>
</div>
<p class="hint">Values read from this call's response. New values start sensitive to mask previews.</p>
<div data-rows="variables">
<div class="workflow-row" data-row="variables" th:each="row, v : ${call.variables}">
<div class="workflow-field">
<label th:for="|workflow-calls-${c.index}-variables-${v.index}-name|">Variable name</label>
<input data-field="name" type="text" autocomplete="off" maxlength="32" th:id="|workflow-calls-${c.index}-variables-${v.index}-name|" th:name="|calls[${c.index}].variables[${v.index}].name|" th:value="${row.name}">
</div>
<div class="workflow-field">
<label th:for="|workflow-calls-${c.index}-variables-${v.index}-pointer|">JSON Pointer</label>
<input data-field="pointer" type="text" autocomplete="off" maxlength="512" th:id="|workflow-calls-${c.index}-variables-${v.index}-pointer|" th:name="|calls[${c.index}].variables[${v.index}].pointer|" th:value="${row.pointer}">
</div>
<div class="workflow-field">
<label th:for="|workflow-calls-${c.index}-variables-${v.index}-sensitive|">Sensitive value</label>
<input data-field="sensitive" type="checkbox" value="true" th:id="|workflow-calls-${c.index}-variables-${v.index}-sensitive|" th:name="|calls[${c.index}].variables[${v.index}].sensitive|" th:checked="${row.sensitive}">
<input type="hidden" data-marker="sensitive" value="on" th:name="|_calls[${c.index}].variables[${v.index}].sensitive|">
</div>
<button type="button" data-remove-row="variables" aria-label="Remove value">Remove</button>
</div>
</div>
<button type="button" data-add-row="variables">Add value</button>
</article>
</div>
<button type="button" data-add-row="calls">Add call</button>
</fieldset>
```

Templates for new rows go at the end of the form, one per family, with `data-field` attributes and no `name` or
`id` (JavaScript sets both on insertion):
- `#workflow-calls-template` holds the whole `<article data-row="calls">` above with empty values. It has a hidden
  `savedName` with an empty value, the phase text `Save to see when this call runs`, the `Once per entry` option
  present, and empty `data-rows="headers"` and `data-rows="variables"` containers each followed by its add button.
- `#workflow-headers-template` holds the header row.
- `#workflow-variables-template` holds the value row, with `Sensitive value` checked.
- `#workflow-entryVariables-template` holds the same value row with `data-row="entryVariables"` and the button label
  `Remove entry field`.

Fieldset 3 ("Choose tiles"), `data-mode="GENERATED"` part:

```html
<div data-mode="GENERATED">
<div class="workflow-field">
<label for="workflow-entryCall">Entries come from</label>
<select id="workflow-entryCall" name="entryCall">
<option th:each="call : ${workflowForm.calls}" th:value="${call.name}" th:text="${call.name}" th:selected="${call.name == workflowForm.entryCall}">main</option>
</select>
</div>
<!-- Entry array, ID and title pointer fields: unchanged from today. -->
<div class="workflow-field">
<label for="workflow-subtitleFrom">Subtitle</label>
<select id="workflow-subtitleFrom" name="subtitleFrom">
<option value="NONE" th:selected="${workflowForm.subtitleFrom.name() == 'NONE'}">No subtitle</option>
<option value="POINTER" th:selected="${workflowForm.subtitleFrom.name() == 'POINTER'}">A field of the entry</option>
<option value="VARIABLE" th:selected="${workflowForm.subtitleFrom.name() == 'VARIABLE'}">A value from a call</option>
</select>
</div>
<div data-choice="subtitleFrom:POINTER"><div class="workflow-field">
<label for="workflow-subtitlePointer">Entry subtitle pointer</label>
<input id="workflow-subtitlePointer" name="subtitlePointer" type="text" maxlength="512" autocomplete="off" th:value="${workflowForm.subtitlePointer}">
</div></div>
<div data-choice="subtitleFrom:VARIABLE"><div class="workflow-field">
<label for="workflow-subtitleVariable">Subtitle value name</label>
<input id="workflow-subtitleVariable" name="subtitleVariable" type="text" maxlength="32" autocomplete="off" th:value="${workflowForm.subtitleVariable}">
</div></div>
<!-- The same three blocks for artwork: artworkFrom ("Artwork"), artworkPointer ("Entry artwork pointer"),
     artworkVariable ("Artwork value name"). -->
<p class="hint">Entry fields are values read from each entry. Per-entry calls and the media address can use them.</p>
<div data-rows="entryVariables">
<!-- th:each over workflowForm.entryVariables: the value row markup with names entryVariables[i].name/pointer/sensitive,
     ids workflow-entryVariables-i-…, remove button "Remove entry field". -->
</div>
<button type="button" data-add-row="entryVariables">Add entry field</button>
<p class="hint">IDs must be unique strings or whole numbers. Up to 200 entries are supported, or 50 when a per-entry call runs at refresh. A tile's subtitle and artwork cannot use a value marked sensitive.</p>
</div>
```

The old checkboxes `includeSubtitlePointer`/`includeArtworkPointer` and their `data-optional` sections are removed.

`app.css`: add card styling next to the existing `.workflow-row` rules, using existing tokens:

```css
.workflow-call { border: 1px solid var(--line); border-radius: var(--radius); padding: 1rem; margin-block: 1rem; }
.workflow-call-header { display: flex; flex-wrap: wrap; align-items: center; gap: .5rem; }
.workflow-call-header h3 { margin: 0; flex: 1 1 auto; }
.workflow-phase { margin: 0; flex-basis: 100%; color: var(--muted); }
```

Before using them, check that `--line`, `--radius` and `--muted` exist (`grep -n -- '--line\|--radius\|--muted' src/main/resources/static/app.css`).
Otherwise, use the tokens that `.workflow-row` uses.

- [ ] **Step 4: Implement the script**

`static/js/workflows.js`:

```js
const editor = document.querySelector('.workflow-editor');
if (editor) {
    const form = editor.querySelector('#workflow-form');
    const limits = { calls: 8, headers: 16, variables: 64, entryVariables: 64 };

    const rowsOf = container => Array.from(container.children).filter(child => child.matches('[data-row]'));

    /** "calls[1].headers[0]": the rows that contain this element, outermost first. */
    function pathOf(element) {
        const parts = [];
        for (let row = element.closest('[data-row]'); row; row = row.parentElement.closest('[data-row]')) {
            parts.unshift(`${row.dataset.row}[${rowsOf(row.parentElement).indexOf(row)}]`);
        }
        return parts.join('.');
    }

    const idOf = name => `workflow-${name.replaceAll('[', '-').replaceAll('].', '-')}`;

    function reindex() {
        const targets = new Map();
        editor.querySelectorAll('[data-row] [data-field]').forEach(input => {
            const previous = input.id;
            const name = `${pathOf(input)}.${input.dataset.field}`;
            input.name = name;
            input.id = idOf(name);
            input.closest('[data-row]').querySelectorAll('label').forEach(label => {
                if (previous && label.htmlFor === previous) label.htmlFor = input.id;
                if (!previous && !label.htmlFor && label.parentElement === input.parentElement) label.htmlFor = input.id;
            });
            // A row added just now owns no existing error links.
            if (previous && !input.closest('[data-new-row]')) targets.set(`#${previous}`, `#${input.id}`);
        });
        editor.querySelectorAll('[data-row] [data-marker]').forEach(input => {
            input.name = `_${pathOf(input)}.${input.dataset.marker}`;
        });
        editor.querySelectorAll('.error a[href^="#workflow-"]').forEach(link => {
            const target = targets.get(link.getAttribute('href'));
            if (target) link.setAttribute('href', target);
        });
        editor.querySelectorAll('[data-new-row]').forEach(row => delete row.dataset.newRow);
        editor.querySelectorAll('[data-add-row]').forEach(button => {
            button.disabled = rowsOf(button.previousElementSibling).length >= limits[button.dataset.addRow];
        });
        rowsOf(editor.querySelector('[data-rows="calls"]')).forEach((card, index) => {
            card.querySelector('h3').textContent = `Call ${index + 1}`;
        });
        callNames();
    }

    function callNames() {
        const select = form.elements.entryCall;
        if (!select) return;
        const names = Array.from(editor.querySelectorAll('[data-rows="calls"] [data-call-name]'), input => input.value.trim())
            .filter(Boolean);
        const chosen = select.value;
        select.replaceChildren(...names.map(name => new Option(name, name)));
        select.value = names.includes(chosen) ? chosen : (names[0] ?? '');
    }

    /** A call that was never saved has no saved URL or headers to keep. */
    function lockNewCalls() {
        editor.querySelectorAll('[data-row="calls"]').forEach(card => {
            if (card.querySelector('[data-field="savedName"]').value) return;
            ['urlMode', 'headersMode'].forEach(field => {
                const select = card.querySelector(`[data-field="${field}"]`);
                select.querySelector('option[value="KEEP"]').disabled = true;
                select.value = 'REPLACE';
            });
        });
        if (form.elements.expectedRevision.value === '0') {
            const keep = form.elements.templateMode.querySelector('option[value="KEEP"]');
            keep.disabled = true;
            form.elements.templateMode.value = 'REPLACE';
        }
    }

    function visibility() {
        const mode = form.elements.mode.value;
        editor.querySelectorAll('select[data-field="scope"]').forEach(select => {
            const entry = select.querySelector('option[value="ENTRY"]');
            if (mode !== 'GENERATED') {
                select.value = 'SHARED';
                entry?.remove();
            } else if (!entry) {
                select.add(new Option('Once per entry', 'ENTRY'));
            }
        });
        editor.querySelectorAll('[data-mode]').forEach(section => { section.hidden = section.dataset.mode !== mode; });
        editor.querySelectorAll('[data-replacement]').forEach(section => {
            const card = section.closest('[data-row]');
            const control = card ? card.querySelector(`[data-field="${section.dataset.replacement}"]`)
                : form.elements[section.dataset.replacement];
            section.hidden = control.value === 'KEEP';
        });
        editor.querySelectorAll('[data-choice]').forEach(section => {
            const [name, value] = section.dataset.choice.split(':');
            section.hidden = form.elements[name].value !== value;
        });
    }

    const firstInput = row => row.querySelector('input:not([type="hidden"])');

    editor.addEventListener('click', event => {
        const add = event.target.closest('[data-add-row]');
        const remove = event.target.closest('[data-remove-row]');
        const move = event.target.closest('[data-move-row]');
        if (add) {
            const family = add.dataset.addRow;
            const rows = add.previousElementSibling;
            if (rowsOf(rows).length >= limits[family]) return;
            const fragment = editor.querySelector(`#workflow-${family}-template`).content.cloneNode(true);
            const row = fragment.querySelector('[data-row]');
            row.dataset.newRow = 'true';
            rows.append(fragment);
            reindex();
            lockNewCalls();
            visibility();
            firstInput(row).focus();
        } else if (remove) {
            const row = remove.closest(`[data-row="${remove.dataset.removeRow}"]`);
            const container = row.parentElement;
            const next = row.nextElementSibling || row.previousElementSibling;
            row.querySelectorAll('[data-field]').forEach(input => {
                editor.querySelectorAll(`a[href="#${input.id}"]`).forEach(link => link.setAttribute('href', '#workflow-form'));
            });
            row.remove();
            reindex();
            visibility();
            (next ? firstInput(next) : container.nextElementSibling).focus();
        } else if (move) {
            const card = move.closest('[data-row="calls"]');
            const up = move.dataset.moveRow === 'up';
            const sibling = up ? card.previousElementSibling : card.nextElementSibling;
            if (!sibling) return;
            if (up) sibling.before(card); else sibling.after(card);
            reindex();
            move.focus();
        }
    });
    form.addEventListener('change', visibility);
    form.addEventListener('input', event => {
        if (event.target.matches('[data-call-name]')) callNames();
    });
    reindex();
    lockNewCalls();
    visibility();
}
```

The label wiring for inserted rows relies on each `<label>` sitting in the same `.workflow-field` as its input, as the
row markup above has it.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.sources.workflows.*' --tests 'dev.andre.homecontrol.web.*'`
Expected: PASS.

Run: `scripts/e2e.sh -Pe2eBrowsers=chromium --tests '*WorkflowE2eTest'`
Expected: PASS. Then run all three browsers: `scripts/e2e.sh --tests '*WorkflowE2eTest'`.

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/templates/workflow-editor.html src/main/resources/static/js/workflows.js \
        src/main/resources/static/app.css src/e2e/java/dev/andre/homecontrol/e2e/WorkflowE2eTest.java \
        src/test/java/dev/andre/homecontrol/sources/workflows/WorkflowSetupControllerTest.java
git commit -m "feat: edit workflow calls as cards that can be added, removed and reordered"
```

If `themes/cyberpunk.css` restyles `.workflow-row` (`grep -n workflow src/main/resources/static/themes/cyberpunk.css`),
add the matching `.workflow-call` rule there too and stage it.

---

### Task 10: Decision record, user guide, full build

**Files:**
- Create: `docs/adr/0003-workflow-chains.md`
- Modify: `docs/adr/README.md`, `docs/user/sources.md`, `docs/user/configuration.md`

- [ ] **Step 1: Write the ADR**

`docs/adr/0003-workflow-chains.md`:

```markdown
# ADR: Workflow chains — schema version 2, and response values in the editor

**Date:** 2026-09-29
**Status:** Accepted
**Context:** Workflow chains, build step 1.
**Spec:** `docs/superpowers/specs/2026-09-29-workflow-chains-design.md`.

## Decision

- A workflow definition is **schema version 2**: an ordered list of up to eight GET calls, each shared or per entry,
  with an entry source naming one shared call. Version 1 definitions are read and converted in memory, their one
  fetch becoming the shared call `main`, and written as version 2 on the next Save. Version 2 gains optional fields
  (date values, filters) in later steps without a new version.
- A call may use only values of calls above it. When a call runs (refresh, Play, both) is derived from what uses its
  values, not stored.
- In a header value, `{name}` is a placeholder and `{{`/`}}` are literal braces. Migration doubles the braces of
  version 1 header values.
- The editor's Try (build step 3) shows a logged-in household member real response values, which relaxes the rule
  that raw responses never reach the browser. Test, the Dashboard, logs and errors keep masking.

## Consequences

- Rolling back to a release before version 2 cannot read workflows saved since; they show as unreadable in Setup
  until removed or the newer release is restored.
- A variable's value can fill a call URL's path and query values but never its host, so no response can redirect a
  credential to another server.
```

Add the row to `docs/adr/README.md`:

```markdown
| [0003](0003-workflow-chains.md) | Workflow chains: schema version 2, and response values in the editor | Accepted | 2026-09-29 |
```

- [ ] **Step 2: Update the user guide**

In `docs/user/sources.md`, rewrite the workflow section's editor walkthrough for calls:
- Calls replace "Fetch JSON". Each call has a name, **Runs** (Once / Once per entry), a URL, headers and values.
- Values of calls above can be used as `{name}` in a URL path or query value and in header values; `{{`/`}}` for
  literal braces.
- Each call's label says when it runs.
- Entries come from one call. Subtitle and artwork can come from the entry or from a value; a sensitive value
  cannot be shown on a tile.
- The limits: 8 calls, 64 values, 50 entries when a per-entry call runs at refresh.

Use the example from the spec (a list call, a per-entry artwork call, a per-entry stream call).

In `docs/user/configuration.md`, update the workflow settings table:
- `home-control.workflows.request-timeout`: default `10s`, the limit for one call;
- `home-control.workflows.max-concurrent-fetches`: default `8`;
- new `home-control.workflows.play-timeout`: default `20s`, the limit for one Play or the Play part of a Test;
- new `home-control.workflows.refresh-timeout`: default `60s`, the limit for one refresh of a workflow's tiles.

- [ ] **Step 3: Run the full build and the browser tests**

Run: `scripts/gradle.sh build`
Expected: BUILD SUCCESSFUL. This includes `ArchitectureTest`. If a frozen violation's text changed because of a
renamed class or method, replace its line in `src/test/archunit-store` by hand as `docs/dev/architecture.md`
describes; never refreeze.

Run: `scripts/e2e.sh --tests '*WorkflowE2eTest'`
Expected: PASS in Chromium, Firefox and WebKit.

- [ ] **Step 4: Commit**

```bash
git add docs/adr/0003-workflow-chains.md docs/adr/README.md docs/user/sources.md docs/user/configuration.md
git commit -m "docs: record workflow schema version 2 and explain calls in the user guide"
```
