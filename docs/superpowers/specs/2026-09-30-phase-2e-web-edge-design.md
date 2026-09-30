# Phase 2E: The Web Edge

**Status:** approved in conversation on 2026-09-30, section by section. The user waived the written spec and plan
reviews.

**Roadmap:** `docs/superpowers/specs/2026-09-27-architecture-roadmap-design.md`, workstream "2E: The web edge". The
removal of the unused `ContentController` JSON API is a visible change the roadmap already approved.

## Purpose

The web edge grew one controller at a time:
- There is no global error handling. Three controllers repeat one exception mapping; other errors reach Spring's
  default `/error`. An unknown device id gives 404 on most endpoints, but merge and split answer 200 with the setup page,
  the deep-link test can answer 200 "failed", and the MAC endpoint can answer 500.
- Eight classes outside the web edge take `HttpServletRequest` (24 frozen ArchUnit violations), mostly to check the
  login or start a session. The login check is repeated in about eight places.
- `setup.html` lists its sections by hand, and seven module advices import `web.SetupController`. Two of those imports
  are frozen violations: Bluetooth → `web`, and the security ↔ web cycle through `LoginModelAdvice`.
- Four templates repeat the page head and the app header; five setup forms repeat the first-password fields. One inline
  module script, one `onsubmit` and two `hx-on` attributes stand in the way of a strict Content-Security-Policy, and the
  only CSP sent is `frame-ancestors 'none'`.
- `ContentController`'s JSON API (`/sources`, `/sources/{s}/rails/{r}`, JSON `/search`) has no caller outside tests.
- The event stream has no heartbeat, so an idle reverse proxy closes it, and its classes are named for device state
  though they carry rail updates too.

After this workstream: one exception mapping; an unknown device is 404 wherever it is named; the login lives in one
place behind `LoginContext`; setup sections are beans; pages share their layout fragments and run under a full CSP with
`script-src 'self'`; the unused JSON API is gone; the event stream sends a heartbeat.

## Decisions (the user's, 2026-09-30)

1. **Two PRs.** PR 1 is the server edge (errors, `LoginContext`, the JSON API, the event stream). PR 2 is the pages
   (setup sections, layout fragments, CSP), cut from main after PR 1 merges.
2. **Forget stays idempotent.** Forgetting a device that is already gone redirects to `/setup`, as today. It is the one
   deliberate exception to "an unknown device id is 404".
3. **`LoginContext` is an argument.** Controllers get it from an argument resolver and pass it to services, stores and
   `LoginService`. Rejected: services taking plain values while controllers start sessions (the session logic would
   spread over seven controllers), and a request-scoped bean (a hidden dependency that fails outside a request).
4. The five design sections below were approved as presented.

## Constraints

- No `/data` format changes. The session cookie (`HOME_CONTROL_SESSION`) is unchanged, so existing logins survive.
- Paths, the `/events` event names (`state`, `rail`, `rails`) and every page stay, apart from the deleted JSON API and
  the visible changes this spec names.
- The names that never change (`shield.*`, `SHIELD_KEYSTORE_PASSWORD`, the CasaOS app id, the client package) are not
  touched.
- `scripts/gradle.sh build` is green after every commit. The test count only rises, except for `ContentControllerTest`,
  deleted with its class. Frozen ArchUnit violations only fall.

## Design

### 1. Errors (PR 1)

- **`web.ErrorAdvice`**, a `@RestControllerAdvice`, answers with a status and a plain-text body carrying the
  exception's message:

  | Exception | Status |
  | --- | --- |
  | `DeviceNotFoundException` | 404 |
  | `DeviceOfflineException` | 409 |
  | `UnsupportedActionException`, `UnroutableException` | 422 |
  | `ActionFailedException`, `ContentSourceException` | 502 |
  | `LoginRequiredException` | 401, "Log in first", as `LoginGateFilter` answers |

  The local copies in `DeviceController`, `ContentPlayController` and `BluetoothSetupController` go. A controller whose
  `IllegalArgumentException` means "bad input" keeps its local 400; a global 400 would hide bugs.
- **An unknown device is 404 wherever it is named:**
  - `Enrollment.merge` and `split` throw `DeviceNotFoundException` instead of
    `IllegalArgumentException("No device with id …")`.
  - The deep-link test drops its pre-check. `DeepLinkTestService` throws `DeviceNotFoundException` instead of
    `DeviceOfflineException("No device with id …")`, which closes the race that answered 200 "failed". An offline
    device still gets its 200 "failed" result fragment: that is a test result, not an error.
  - `/setup/devices/{id}/mac` throws `DeviceNotFoundException` instead of a `ResponseStatusException`, which also ends
    the 500 when the device vanishes mid-request.
  - Forget stays idempotent (decision 2).
- **Unchanged:** the `ResponseStatusException`s for "no such rail" and "Android TV is switched off"; the JSON
  `/play-attempt` result and its status; the source setup controllers' flash-and-redirect; the workflow editor's
  re-rendered page.

### 2. The login context (PR 1)

- **`security.LoginContext`**, an interface for one browser session's login:
  - `loggedIn()`: no password is set, or this session logged in with the current one;
  - `requireLogin()`: throws `LoginRequiredException` unless `loggedIn()`;
  - `sessionKey()`: a key that stays the same for this browser session, starting one if needed; YouTube binds its
    OAuth state to it;
  - `whileLoggedIn()`: the same check bound to the session instead of the request, for work that outlives the request
    (the event stream);
  - `startSession()` and `endSession()`: for `LoginService` alone.
- **One argument resolver** in `security` builds a request-backed `RequestLoginContext` for any controller parameter of
  that type. It is the only code that touches the HTTP session for the login.
- **`LoginService`** takes a `LoginContext` in `authenticate`, `logout`, `storeSecrets`, `setPassword` and
  `changePassword`. It gains `permitSecrets(context, newPassword, confirmation)`, "logged in, or a valid first
  password", which replaces Jellyfin's `passLoginGate` and the Sports calendar and competition pre-checks.
- **Services and stores** take `LoginContext` instead of the request: `JellyfinSetupService`, `SportsCalendars`,
  `SportsCompetitions`, `TmdbSetupService`, `WorkflowStore`, `WorkflowTestService` and `YouTubeSetupService`. Their
  private checks (`requireLogin`, `authenticate`, `passLoginGate`) and the workflow editor controller's `authenticate`
  become `context.requireLogin()`.
- **YouTube's callback URL** is built by its setup controller and advice, which may read the request, and handed to the
  service as a `URI`. `YouTubeOAuthCallback` no longer touches servlet types.
- **Unchanged:** `LoginController` reads the client address for rate limiting, and the filters work on requests; both
  are at the web edge.
- **The rule:** the frozen `jakarta.servlet` rule falls from 24 violations to 0 and becomes strict.

### 3. The JSON API and the event stream (PR 1)

- **`ContentController` is deleted** with `GET /sources`, `GET /sources/{s}/rails/{r}`,
  `POST /sources/{s}/rails/{r}/refresh`, JSON `GET /search` and `POST /search?source=`. The dashboard's HTML endpoints
  (`/rails/…`, `/search/results`) and the documented `GET /devices/<id>/route` (in `ContentPlayController`) stay.
  End-to-end tests that used the JSON API switch to the HTML endpoints or the services and keep what they prove; those
  that only prove the login gate or the cross-origin filter move to a path that exists. The commit is `refactor:`
  without `!`: the API was never documented.
- **Heartbeat:** a comment line (`: keep-alive`) goes to every open stream every 25 seconds, on the stream's fan-out
  thread. It keeps reverse proxies from closing an idle stream and drops dead clients sooner. The interval is a
  `Duration` property, so tests can shorten it. Browsers ignore comment lines; `events.js` is unchanged.
- **Shutdown:** emitters are completed on shutdown, as today, and the heartbeat stops with them.
- **Renames:** `DeviceStateBroadcaster` → `EventStream`, `StateController` → `EventStreamController`.

### 4. Setup sections, layout and CSP (PR 2)

- **`config.SetupSection`**, an interface each module implements: `id()` (anchor and model name), `title()`,
  `fragment()`, `group()` (`DEVICES` or `CONTENT_SOURCES`), `order()` and `view(URI baseUrl)`. It lives in `config`,
  next to `Module`, because adapters, sources and web may all depend on `config`.
- **The seven `*SetupAdvice` classes become `*SetupSection` beans** in their module configurations. `SetupController`
  puts each section's view under its id, and `setup.html` iterates over the groups instead of hand-written lists and
  `th:if` chains. YouTube's callback URL and "browser supported" flag move into its view, built from the base URL
  `SetupController` passes. `LoginModelAdvice` goes; `SetupController` adds `loginRequired` and `connectedAccounts`.
  Visible: Workflows joins the "Content sources" link list; the "↳ Workflows" shortcut in the side navigation stays.
- **Layout fragments** in `fragments/layout.html`: `head(title)`, `appHeader` and `firstPassword`, used by the
  dashboard, setup, login and the workflow editor. `offline.html` is static and stays. The editor's password fields
  gain the shared `minlength` of 10.
- **No inline code:** the setup page's inline module script and its two `hx-on` handlers move to `js/setup.js`; the
  dashboard search form's `onsubmit` moves to `app.js`; `<meta name="htmx-config">` sets `allowEval: false`, and no
  htmx attribute may need eval (trigger filters, `js:` values).
- **The CSP**, sent by the security-headers filter on every response:

  ```
  default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: https:;
  connect-src 'self'; manifest-src 'self'; worker-src 'self'; object-src 'none'; base-uri 'none';
  frame-ancestors 'none'
  ```

  Inline styles stay allowed (htmx's indicator style, rail card widths); the roadmap asks for strict scripts.
  `img-src https:` because workflow artwork may come from any public HTTPS host. No `form-action`: browsers apply it to
  the redirect after a form post, which would block YouTube's sign-in hop to Google.

### 5. Delivery

**PR 1, `refactor/web-edge`, from main `6fbbe9b`.** One commit per step:
1. `ErrorAdvice`; the local handler copies go.
2. An unknown device is 404 in merge, split, the deep-link test and the MAC endpoint.
3. `LoginContext`, its resolver and `RequestLoginContext`; `LoginService` takes the context and gains `permitSecrets`.
4. Services and stores take `LoginContext`; YouTube's callback is a `URI`; the `jakarta.servlet` rule is strict.
5. `ContentController` deleted; its end-to-end tests move.
6. The event-stream heartbeat and the renames.
7. The architecture guide: the `web` and `security` rows and the rules table.

**PR 2, `refactor/web-pages`, from main after PR 1.** One commit per step:
1. `SetupSection`; the advices and `LoginModelAdvice` go; the frozen stores shrink.
2. The layout fragments.
3. Inline code out: `setup.js`, `app.js`, the htmx config, and a template scan test.
4. The CSP and the browser tests' CSP-error guard.
5. The architecture and testing guides.

## Testing

- **Errors:** one test per mapping in `ErrorAdvice`; 404 for an unknown id in merge, split, the deep-link test and the
  MAC endpoint; the existing tests that pin 404 bodies ("No device with id ghost") keep passing.
- **Login:** `RequestLoginContext` against a mock request (session start, session-id change, a later password change,
  logout); `permitSecrets`; an in-memory `testsupport.FakeLoginContext` replaces the mocked servlet requests in the
  service tests.
- **Event stream:** a heartbeat test with a short interval, reading the comment through `EventStreamReader`; the
  shutdown test keeps pinning completion.
- **Pages (PR 2):** a header test for the CSP; a template scan that fails on an inline `<script>` without `src`, an
  `on…=` attribute or `hx-on`; browser tests that fail on any "Refused to …" CSP console error; module-switch tests that
  check no section is left behind.
- Each PR runs `scripts/gradle.sh build`, `scripts/e2e.sh -Pe2eBrowsers=chromium`, a final whole-branch review, and CI
  in three browsers.

## Measures

| | Before | After |
| --- | ---: | ---: |
| Frozen `jakarta.servlet` violations | 24 | 0, strict |
| Frozen `adapters` → `web` | 1 | 0 |
| Frozen package cycles | 1 | 0 if `LoginModelAdvice` is the cycle's only edge |
| Places that check the login | about 8 | 1 (`LoginService` through `LoginContext`) |
| Copies of the core exception mapping | 3 | 1 |
| Content-Security-Policy | `frame-ancestors` only | full, `script-src 'self'` |

## Out of scope

- The theme architecture (2F), which builds on PR 2's layout fragments.
- Moving source setup controllers' flash-and-redirect error handling or the workflow editor's re-rendering onto the
  advice.
- A `form-action` directive and a strict `style-src`.
