# Firefox browser tests — 2026-09-27

Branch: `ci/firefox-browser-tests`.

The browser tests ran in Chromium and WebKit. Firefox's Gecko is the one other engine with real users: every
other browser is Blink or WebKit underneath, including every browser on an iPhone. The bug template's example
reporter runs Firefox on Android. The harness already accepted `firefox`, so this work ran the suite in it, fixed
what that exposed, and gave Firefox a CI job.

## What the first run showed

Playwright 1.63 pins Firefox 155. Run locally, the suite:

- hung for good in `RemoteLayoutE2eTest`, inside `page.setViewportSize` in
  `remoteHeaderAndCloseStayVisibleWhenItsControlsScroll`;
- otherwise passed, 53 of 53, but eight times closing a test class's application took 30 s, logging "Graceful
  shutdown aborted with one or more requests still active". Those waits were four of the run's five minutes.
  Chromium and WebKit never waited, in CI or locally.

## The hang: `isMobile` in Firefox

- Playwright documents `isMobile` as unsupported in Firefox, yet its Firefox backend passes it on to Juggler, the
  Firefox end of its protocol, in `Browser.setDefaultViewport` and `Page.setViewportSize`.
- Juggler turns `isMobile` into Firefox's responsive design mode (`browsingContext.inRDMPane`). `Page.setViewportSize`
  resizes the window and then waits, with no timeout, until `innerWidth` and `innerHeight` equal the requested size
  (`TargetRegistry.js` and `content/main.js` in Firefox 155's `omni.ja`).
- In that mode Firefox lays out a page without a viewport meta tag 980 px wide. On `about:blank`, every width under
  980 px tried (390, 320, 667) never matched and never replied; 1080 and 1440 did. On a page with
  `width=device-width`, as every page of the app has, every size resized normally. The tests that hung resize before
  their first navigation, on `about:blank`.
- Touch emulation alone already gives Firefox `(pointer: coarse)` and `(hover: none)`, which is what the phone layout
  keys on. None of Playwright's 200 mobile device descriptors pairs `isMobile` with Firefox.

Fix: `Browsers` opens Firefox contexts without `isMobile`.

## A race the hang had hidden

With the hang gone, `phoneRemoteOpensAboveThePageAndKeepsFocusAndScrollingInside` failed in Firefox: a rail's
`scrollLeft`, just set to its `scrollWidth`, read back 0.

- Rails scroll smoothly (`scroll-behavior: smooth` under `prefers-reduced-motion: no-preference`), so the assignment
  only starts an animation. When the next line read the position, Firefox had not moved yet and Chromium had moved 4
  of the 9 px the rail can scroll: the assertion held in Chromium and WebKit by timing.
- The rail does scroll in Firefox: `scrollLeftMax` is 9, and an instant `scrollBy` reaches it.

Fix: the test scrolls with `behavior: 'instant'`.

## The stalls: event streams held graceful shutdown

- A Tomcat access log showed the request still active: the last page's `GET /events`, the server-sent event stream,
  in flight until the 30 s ran out.
- Sampling the sockets showed Firefox had closed that connection within half a second of the page closing (server
  side `CLOSE-WAIT`, client `FIN-WAIT-2`), and the server did not notice until the timeout closed it. Against a bare
  event-stream server, Firefox and Chromium both closed the stream when their context closed.
- A servlet container learns that a client left only when a write fails, and the first write after the client closed
  still succeeds. Chromium closes the socket before the test's clean-up publishes device events, so a later write
  fails and the stream ends, 120 ms after it began in the access log. Firefox closes it a moment later, after those
  events, and nothing writes to it again.
- The cause is on the server: `/events` is an `SseEmitter` without a timeout, which never ends by itself, and nothing
  ended it at shutdown, so graceful shutdown waited its whole 30 s phase timeout. That happens with any browser
  whenever a dashboard is open while the application stops, and it is longer than Docker's default 10 s stop grace
  period, so such a container was killed rather than stopped.

Fix: `DeviceStateBroadcaster` ends every stream on `ContextClosedEvent`, which is published before the web server's
graceful shutdown begins; browsers reconnect by themselves. `EventStreamShutdownEndToEndTest` holds a stream open and
closes the application: 32 s before the fix, 2 s after it, with graceful shutdown done in about 50 ms.

## Results

On the branch, rebased onto `main` at `5988fdb`:

- `./gradlew build` is green: 2,699 unit, integration and architecture tests.
- Browser tests: Chromium 60 of 60, Firefox 58 of 58 and WebKit 58 of 58; the two pinch-zoom tests run in Chromium
  only. No test needed a retry, and no application waited on shutdown. Chromium and Firefox ran on a Fedora host;
  WebKit ran in the `scripts/e2e.Dockerfile` image, since Playwright's WebKit does not start there.
- The Firefox run takes 44 s. Before, it never finished, and the part that could run took five minutes.
- The pull request summary script's tests pass, 58 of 58.

## CI

- `Browser tests (Firefox)` runs beside Chromium and WebKit. `CI passed` and `Release` need it, and the pull request
  summary reports it.
- `./gradlew e2eTest`, `installPlaywrightBrowsers` and the `scripts/e2e.sh` image include Firefox by default.
