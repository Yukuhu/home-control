# Phase 2E, PR 2: The Pages — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The setup page is assembled from `SetupSection` beans, every page shares its layout fragments, no page
carries inline script, and a full Content-Security-Policy with `script-src 'self'` is sent.

**Architecture:**
- `config.SetupSection` is what a module contributes to the setup page. The seven `*SetupAdvice` classes become
  `*SetupSection` components, and `SetupController` renders them by group and order.
- `fragments/layout.html` holds the page head, the app header, the brand link and the first-password fields.
- `js/setup.js` and `js/app.js` take over the inline script and the handler attributes.
- The security-headers filter sends the full policy, and the browser tests fail on any CSP violation.

**Tech Stack:** Java 25, Spring Boot 4.1.1, Thymeleaf 3 (fragment expressions with preprocessing), htmx 2, plain ES
modules, Playwright (Java) for the browser tests.

**Spec:** `docs/superpowers/specs/2026-09-30-phase-2e-web-edge-design.md`, section 4 and the PR 2 delivery list.

## Global Constraints

- No `/data` format changes; paths, the setup page's section ids and anchors (`#jellyfin`, `#youtube`, `#tmdb`,
  `#pinned`, `#workflows`, `#sports`, `#bluetooth`, `#sources`, `#account`, `#install`) stay.
- Visible changes are only these:
  - Workflows joins the "Content sources" link list; the "↳ Workflows" shortcut in the side navigation stays.
  - The workflow editor's password fields gain the shared `minlength` of 10.
- The CSP is exactly:
  `default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: https:; connect-src 'self'; manifest-src 'self'; worker-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'`.
  There is no `form-action`.
- A switched-off module contributes no section, as today.
- `scripts/gradle.sh build` is green after every commit. `scripts/gradle.sh compileE2eJava` is green after every
  commit that touches pages or `src/e2e`. The test count only rises (3,083 at `031adcf`). Frozen ArchUnit violations
  only fall.
- Commits follow Conventional Commits; stage only the files you changed.

## Review Focus

1. **The deep-link test's failures on the setup page.** A refusal (404, 422) or an unreachable server must still show
   its sentence under the button once `hx-on` is gone. Task 3 adds a browser test: forget the device after the page
   renders, click, and read "No device with id …".
2. **A page that still needs inline script or eval under the new policy.** The browser tests visit the dashboard,
   setup, login, the workflow editor and the offline page; Task 4 makes any `securitypolicyviolation` fail them.
3. **YouTube's sign-in hop.** A form post that redirects to Google must not be blocked; Task 4's header test pins that
   the policy has no `form-action`.
4. **A switched-off module.** It must leave no section and no link in "Content sources"; Task 1 extends the
   module-switch tests to the section beans.
5. **The first-password fields.** They must still appear only while no password is set, in all six places, with the
   same field names the controllers bind; Task 2's tests read them through the forms.

---

### Task 1: Setup sections

**Files:**
- Create: `src/main/java/dev/andre/homecontrol/config/SetupSection.java`
- Rename with `git mv` and convert:
  - `adapters/bluetooth/BluetoothSetupAdvice.java` → `BluetoothSetupSection.java`;
  - `sources/jellyfin/JellyfinSetupAdvice.java` → `JellyfinSetupSection.java`;
  - `sources/pinned/PinnedSetupAdvice.java` → `PinnedSetupSection.java`;
  - `sources/sports/SportsSetupAdvice.java` → `SportsSetupSection.java`;
  - `sources/tmdb/TmdbSetupAdvice.java` → `TmdbSetupSection.java`;
  - `sources/workflows/WorkflowSetupAdvice.java` → `WorkflowSetupSection.java`;
  - `sources/youtube/YouTubeSetupAdvice.java` → `YouTubeSetupSection.java`.
- Delete: `security/LoginModelAdvice.java`
- Modify:
  - `web/SetupController.java` (sections into the model, plus `loginRequired` and `connectedAccounts`);
  - `templates/setup.html` (iterate the groups);
  - `templates/fragments/youtube-setup.html` (callback URL and flag from the view);
  - the frozen stores, which shrink by themselves (commit the smaller store).
- Test:
  - create `web/SetupSectionsTest.java`;
  - modify `testsupport/WebSliceTest.java` (`@Import` the seven sections), `testsupport/SharedWebSliceTest.java`;
  - modify the module-switch tests that name an advice (Jellyfin, YouTube, TMDB switch and enabled, workflows),
    `sources/sports/SportsStatusTextTest.java` and `web/BluetoothSetupOffTest.java`.

**Interfaces:**
- Produces:

```java
package dev.andre.homecontrol.config;

import java.net.URI;

/**
 * What one module shows on the setup page. A module that is switched off has no bean, so no section. The page puts
 * {@link #view} in its model under {@link #id} and includes {@link #fragment}'s {@code section} fragment, which reads it.
 */
public interface SetupSection {

    /** Where a section goes on the page: with the devices, or with the content sources. */
    enum Group { DEVICES, CONTENT_SOURCES }

    /** The section's anchor and the model name its fragment reads, e.g. {@code jellyfin}. */
    String id();

    /** The link text in the page's navigation, e.g. {@code Movies & series}. */
    String title();

    /** The template that holds its {@code th:fragment="section"}, e.g. {@code fragments/jellyfin-setup}. */
    String fragment();

    Group group();

    /** Its place within its group, lowest first. */
    int order();

    /**
     * The model its fragment renders, or null when the section has nothing to show (its module's services are
     * missing). {@code baseUrl} is this server's root as the browser reaches it.
     */
    Object view(URI baseUrl);
}
```

  | Section | `id` | `title` | `fragment` | `group` | `order` |
  | --- | --- | --- | --- | --- | --- |
  | `BluetoothSetupSection` | `bluetooth` | `Bluetooth speakers` | `fragments/bluetooth-setup` | `DEVICES` | 10 |
  | `JellyfinSetupSection` | `jellyfin` | `Jellyfin` | `fragments/jellyfin-setup` | `CONTENT_SOURCES` | 10 |
  | `YouTubeSetupSection` | `youtube` | `YouTube` | `fragments/youtube-setup` | `CONTENT_SOURCES` | 20 |
  | `TmdbSetupSection` | `tmdb` | `Movies & series` | `fragments/tmdb-setup` | `CONTENT_SOURCES` | 30 |
  | `PinnedSetupSection` | `pinned` | `Pinned links` | `fragments/pinned-setup` | `CONTENT_SOURCES` | 40 |
  | `WorkflowSetupSection` | `workflows` | `Workflows` | `fragments/workflows-setup` | `CONTENT_SOURCES` | 50 |
  | `SportsSetupSection` | `sports` | `Sports` | `fragments/sports-setup` | `CONTENT_SOURCES` | 60 |

  - Each section stays a scanned class, as the advice was, rather than a `@Bean` in its module configuration, so the
    web slice can `@Import` it without the module's other beans; its `@ConditionalOnModule` keeps the switch.
  - Each section class keeps its `@ConditionalOnModule`, becomes a `@Component` instead of a
    `@ControllerAdvice(assignableTypes = SetupController.class)`, and turns its `@ModelAttribute` method into
    `view(URI baseUrl)`. Its nested records and static helpers (for example `SportsSetupSection.statusText`) stay.
  - `YouTubeSetupSection.View` gains `String callbackUrl` and `boolean browserSupported`, built from
    `YouTubeOAuthCallback.uri(baseUrl)`. `youtube-setup.html` reads `${youtube.callbackUrl()}` and
    `${youtube.browserSupported()}` instead of `${youtubeCallbackUrl}` and `${youtubeBrowserSupported}`.
  - `SetupController` takes `ObjectProvider<SetupSection> sections` and `ObjectProvider<LoginService> login`.
    `populateSetupModel` computes `ServletUriComponentsBuilder.fromCurrentContextPath().build().toUri()` and puts:
    - each non-null view under its section's id;
    - `deviceSections` and `sourceSections`: the sections with a view, by group, sorted by `order`;
    - `loginRequired` and `connectedAccounts`, as `LoginModelAdvice` did.
  - `setup.html`:

```html
<a href="#connections" th:unless="${sourceSections.isEmpty()}"><span aria-hidden="true">02</span> Content sources</a>
...
<th:block th:each="section : ${deviceSections}">
    <section th:replace="~{__${section.fragment()}__ :: section}"></section>
</th:block>

<div id="connections" class="setup-category" th:unless="${sourceSections.isEmpty()}">
    ...
    <nav class="source-links" aria-label="Content source settings">
        <a th:each="section : ${sourceSections}" th:href="|#${section.id()}|" th:text="${section.title()}">Jellyfin</a>
    </nav>
</div>

<th:block th:each="section : ${sourceSections}">
    <section th:replace="~{__${section.fragment()}__ :: section}"></section>
</th:block>
```

- [ ] **Step 1: Write the failing test** `web/SetupSectionsTest` (a `WebSliceTest`):
  - `theContentSourcesAreListedInOrderWithWorkflows`: `/setup`'s "Content source settings" navigation holds, in this
    order, `#jellyfin`, `#youtube`, `#tmdb`, `#pinned`, `#workflows` and `#sports`;
  - `eachSectionIsRenderedUnderItsAnchorInOrder`: the section ids appear in the page in the same order, after
    `id="bluetooth"`;
  - `theAccountSectionStillKnowsWhetherALoginIsRequired`: with `login.loginRequired()` stubbed true, the Account
    section offers "Change password".

  Also extend the Jellyfin, YouTube, TMDB and workflow module-switch tests to assert that the section bean is absent.
- [ ] **Step 2:** run `web.SetupSectionsTest`. Expected: FAIL, since `#workflows` is not in the link list.
- [ ] **Step 3: Implement** the interface, the seven conversions, `SetupController`, `setup.html` and the YouTube
  fragment. Delete `LoginModelAdvice`. Update the tests that named an advice: they use the section class. Add the
  seven sections to `WebSliceTest`'s `@Import`, and swap them into `SharedWebSliceTest`'s list in place of the advices
  and `LoginModelAdvice`.
- [ ] **Step 4:** run `scripts/gradle.sh build`. Expected: green. `ArchitectureTest` drops the frozen Bluetooth →
  `web` import and the security ↔ web cycle from the store. Commit the smaller store; check that `git diff` of
  `src/test/archunit-store` only removes lines.
- [ ] **Step 5: Commit** `refactor: build the setup page from the modules' setup sections`.

---

### Task 2: Layout fragments

**Files:**
- Create: `src/main/resources/templates/fragments/layout.html`
- Modify:
  - `templates/dashboard.html`, `setup.html`, `login.html`, `workflow-editor.html`;
  - `templates/fragments/jellyfin-setup.html`, `tmdb-setup.html`, `youtube-setup.html`, `sports-setup.html` (twice).
- Test: create `web/LayoutFragmentsTest.java` (reads the template files, no Spring). Modify tests that pin the
  editor's password markup, if any.

**Interfaces:**
- Produces, in `fragments/layout.html`:
  - `head(title, resizesContent)`: a `th:block` holding charset; a viewport that adds
    `interactive-widget=resizes-content` when `resizesContent` is true; the theme-colour and app-install metas; the
    manifest and icon links; the title; `app.css`; `themes/cyberpunk.css`; and `js/theme.js`. Each page includes it
    first in its own `<head>`, then adds its own scripts.
  - `brand`: the "Home Control" link with its SVG mark, used by `appHeader` and `login.html`.
  - `appHeader(current)`: the app header with `brand`, the Home and Setup links (`aria-current="page"` on `current`,
    `home` or `setup`), the dashboard's `#connection-status` output when `current == 'home'`, and the theme toggle.
  - `firstPassword`: the intro-free pair of labelled inputs `loginPassword` and `loginPasswordConfirmation`, both
    `type="password" minlength="10" maxlength="1024" autocomplete="new-password"`, with the setup fragments' labels
    and placeholders. Each place keeps its own intro sentence and its own `th:if`.

- [ ] **Step 1: Write the failing test** `LayoutFragmentsTest`:
  - only `fragments/layout.html` among the templates contains `<meta name="viewport"`, `class="app-header"` or
    `name="loginPasswordConfirmation"`;
  - the dashboard, setup, login and editor templates each include `fragments/layout :: head(`.
- [ ] **Step 2:** run it. Expected: FAIL; four templates carry their own head.
- [ ] **Step 3: Implement** the fragments and switch the four pages and six password blocks to them. The editor's
  password fieldset keeps its legend and intro, and uses `firstPassword`.
- [ ] **Step 4:** run `scripts/gradle.sh build` and `scripts/gradle.sh compileE2eJava`. Expected: green. The existing
  page tests (`DashboardPageTest`, `SetupControllerTest`, `LoginControllerTest` pages, `WorkflowSetupControllerTest`,
  the TMDB, YouTube, Jellyfin and Sports section tests) keep passing.
- [ ] **Step 5: Commit** `refactor: share the page head, header and first-password fields as layout fragments`.

---

### Task 3: No inline script

**Files:**
- Create: `src/main/resources/static/js/setup.js`
- Modify:
  - `templates/setup.html` (the inline module goes; the page loads `js/setup.js`; the deep-link button loses `hx-on`
    and gains `class="deep-link-test"`);
  - `templates/dashboard.html` (`onsubmit` goes);
  - `static/js/app.js` (the search form's submit is prevented);
  - `templates/fragments/layout.html` (`<meta name="htmx-config" content='{"allowEval":false}'>` in `head`);
  - nothing in `sw.js`: its offline assets (`app.css`, the theme, `js/theme.js`, the icon) do not include page scripts.
- Test:
  - create `web/InlineCodeTest.java` (reads the template and static HTML files);
  - modify `web/SetupControllerTest.java:205`, which asserts `hx-on::response-error`, and `web/DashboardPageTest.java:410`,
    which asserts `type="module"`, to the new markup;
  - create `src/e2e/java/dev/andre/homecontrol/e2e/SetupDeepLinkE2eTest.java`.

**Interfaces:**
- `js/setup.js`:

```js
// The setup page's own script: the app install card, and the deep-link test's failures under its button.
import { initPwa } from "/js/pwa.js";

initPwa();

function showUnderTheButton(event, text) {
    const button = event.detail.elt;
    if (button?.classList.contains("deep-link-test")) {
        button.nextElementSibling.textContent = text;
    }
}

document.body.addEventListener("htmx:responseError", (event) =>
    showUnderTheButton(event, event.detail.xhr.responseText || "The test could not run"));
document.body.addEventListener("htmx:sendError", (event) => showUnderTheButton(event, "Cannot reach the server"));
```

- `app.js`: `document.querySelector("form.search")?.addEventListener("submit", (event) => event.preventDefault());`

- [ ] **Step 1: Write the failing tests.**
  - `InlineCodeTest`: across `src/main/resources/templates/**/*.html` and `src/main/resources/static/*.html`, fail on
    a `<script` tag without `src`, an attribute matching `\son[a-z]+=`, or `hx-on`, naming file and line.
  - `SetupDeepLinkE2eTest`:
    - pair a fake device with `APP_LINK`, as the other browser tests set up fake devices;
    - open `/setup` and forget the device through `DeviceEnrollment`;
    - click "Test deep link" and expect `.deep-link-output` to read "No device with id <id>".
- [ ] **Step 2:** run `web.InlineCodeTest`. Expected: FAIL on `setup.html` (inline module, `hx-on`) and
  `dashboard.html` (`onsubmit`).
- [ ] **Step 3: Implement.**
- [ ] **Step 4:** run `scripts/gradle.sh build`, `scripts/gradle.sh compileE2eJava`, and
  `scripts/e2e.sh -Pe2eBrowsers=chromium`. Expected: green, `SetupDeepLinkE2eTest` included.
- [ ] **Step 5: Commit** `refactor: move the pages' inline script and handlers into their script files`.

---

### Task 4: The Content-Security-Policy

**Files:**
- Modify: `security/CrossOriginFilter.java` (`secure(...)` sends the full policy)
- Modify: `src/e2e/java/dev/andre/homecontrol/e2e/Browsers.java`, `BrowserSession.java` (record every
  `securitypolicyviolation` and fail the session on close)
- Test: `security/CrossOriginFilterTest.java` (`:125-128` pin today's `frame-ancestors 'none'`), `web/LoginGatingTest`
  where it pins the header

**Interfaces:**
- `CrossOriginFilter`:

```java
    /** Scripts only from this server; see the design's section 4 for each directive and why there is no form-action. */
    static final String CONTENT_SECURITY_POLICY = "default-src 'self'; script-src 'self'; "
            + "style-src 'self' 'unsafe-inline'; img-src 'self' data: https:; connect-src 'self'; manifest-src 'self'; "
            + "worker-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'";
```

- Browser tests:
  - `Browsers.openWithPointer` calls `context.exposeBinding("__cspViolation", …)`, which adds each report to a
    `List<String>`;
  - it also calls `context.addInitScript(...)` with a `securitypolicyviolation` listener that reports
    `e.violatedDirective + " " + (e.blockedURI || "inline") + " at " + e.sourceFile + ":" + e.lineNumber`;
  - `BrowserSession` gains `List<String> cspViolations`, and `close()` throws `AssertionError` listing them when any.

- [ ] **Step 1: Write the failing test.** `CrossOriginFilterTest.everyResponseCarriesTheContentSecurityPolicy` asserts
  the exact header on an allowed, a refused and a login-gated response. It also asserts `doesNotContain("form-action")`
  (review focus 3). Update the existing `frame-ancestors` assertions to the full policy.
- [ ] **Step 2:** run `security.CrossOriginFilterTest`. Expected: FAIL; today's header is `frame-ancestors 'none'`.
- [ ] **Step 3: Implement** the header and the browser-test guard.
- [ ] **Step 4:** run `scripts/gradle.sh build`, `scripts/gradle.sh compileE2eJava`, and
  `scripts/e2e.sh -Pe2eBrowsers=chromium`. Expected: green, with no CSP violation on any page the browser tests visit.
- [ ] **Step 5: Commit** `feat: send a Content-Security-Policy that allows scripts only from this server`.

---

### Task 5: The guides

**Files:**
- Modify: `docs/dev/architecture.md`:
  - the `config` row: `SetupSection`;
  - the `web` row: the setup page built from sections, and the layout fragments;
  - the `security` row: the Content-Security-Policy;
  - the rules table: the frozen counts for `adapters` → `web` and cycles;
  - the measures table: frozen violations.
- Modify: `docs/dev/testing.md` (the browser tests fail on a CSP violation; a new setup section goes into
  `WebSliceTest`'s `@Import`)
- Modify: `docs/user/security.md`, section "Allowed hosts and origins": the pages send a Content-Security-Policy that
  allows scripts only from Home Control itself; a reverse proxy should pass it through, not replace it.

- [ ] **Step 1:** write the edits. Take the frozen counts from `src/test/archunit-store` as it is after Task 1.
- [ ] **Step 2:** run `scripts/gradle.sh build`, `scripts/gradle.sh compileE2eJava` and
  `scripts/e2e.sh -Pe2eBrowsers=chromium`. Expected: green.
- [ ] **Step 3: Commit** `docs: describe setup sections, the layout fragments and the Content-Security-Policy`.
