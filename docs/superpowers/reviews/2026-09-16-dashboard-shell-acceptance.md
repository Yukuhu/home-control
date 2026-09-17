# Dashboard shell (sub-project D) — manual acceptance

Automated coverage: `RailCacheTest`, `RailControllerTest`, `ContentPlayPreviewTest`, `SourcesSetupControllerTest`,
`SearchServiceTest`, `IconControllerTest`, and the Playwright suite (`./gradlew e2eTest`: play sheet, device
switching, rail failure, login gating, touchpad) in Chromium and WebKit. Playwright WebKit is not iOS Safari,
so the items below need real phones, tablets and devices. Agents never mark these as passed.

| # | Check | Result |
|---|---|---|
| 1 | With Jellyfin connected, the dashboard shows Continue watching / Next up / Latest within seconds of opening, and opening it again is instant | Pending — requires real hardware |
| 2 | Stop the Jellyfin server: rails keep their items with "Couldn't refresh"; after a restart of the app with Jellyfin still down, each rail shows the compact error with Retry and no empty gap; start Jellyfin and press Retry | Pending — requires real hardware |
| 3 | Resume an episode on the TV, wait 5 minutes with the dashboard open: Continue watching updates without reloading | Pending — requires real hardware |
| 4 | Tap an item: the sheet names the route for the Shield (e.g. "Play in the open Jellyfin app (Android TV)"); switch to a Chromecast: the route changes to a Cast route; Play starts it on the chosen device | Pending — requires real hardware |
| 5 | Make a route fail (e.g. power off the Cast side): the toast names the failed route and the next one, and "Try …" plays through it | Pending — requires real hardware |
| 6 | Open a YouTube item on a device without the YouTube app: after about 5 s the "may not be installed" hint appears; with the app installed it does not | Pending — requires real hardware |
| 7 | Setup: hide a rail, move another to the top, set Jellyfin to refresh every 10 minutes; an open dashboard on a second phone reorders without reload; `/data/sources.json` holds a `preferences` object and survives a container restart | Pending — requires real hardware |
| 8 | Search "a" shows the hint, a title finds movies and episodes, typing fast sends few requests (browser devtools), tapping a result opens the sheet | Pending — requires real hardware |
| 9 | iPhone (Safari): Add to Home Screen, launch: standalone, icon correct, content clear of the notch and home indicator; login works in the standalone app | Pending — requires real hardware |
| 10 | Android (Chrome): install from Setup or the menu; the maskable icon is not clipped; behind an HTTPS reverse proxy, airplane mode shows the offline page and live updates resume after reconnecting | Pending — requires real hardware |
| 11 | iPad landscape: touchpad on the left, essential buttons on the right; phone portrait: touchpad mode; tap, swipes of increasing length (1–4 steps) and hold behave as documented on the Shield | Pending — requires real hardware |
| 12 | Software keyboard on iPhone and Android does not cover the search box or the setup inputs in an unusable way | Pending — requires real hardware |

## Findings

- D6 review: `static/js/touchpad.js` could leave a `START_LONG` open forever if the tab was
  backgrounded or the screen locked mid-hold (no `pointerup` is ever delivered in that case).
  Fixed by cancelling the gesture (sending `END_LONG` when a hold is active) on both
  `visibilitychange` (when hidden) and `pagehide`; `TouchpadE2eTest.backgroundingTheTabDuringAHoldEndsIt`
  covers it end to end in both browsers.
- `9a44595`: the play sheet's own modal `<dialog>` made its failure toast's "Try the next route"
  button structurally unclickable in every browser (everything outside an open `showModal()`
  dialog is inert). Fixed by closing the sheet before reporting any play-attempt outcome, not
  just on success. Found by `PlaySheetE2eTest.aFailureNamesTheFailedRouteAndOffersTheNextOne`.
- `d3ae3e0`: a rail's retry button could overwrite correct, already-`READY` content with a stale
  `FAILED` snapshot, because its own POST response (taken before the refresh it starts even
  finishes) raced the version-guarded SSE `rail` watcher and could arrive after it. Fixed with
  `hx-swap="none"`, leaving rendering entirely to the watcher. Found by
  `RailFailureE2eTest.retryRecoversTheRail`, reproducing deterministically against the
  (near-instantaneous) fake content source.
- `ef7eb57`: clearing the search box left the rails hidden forever, because the empty-query
  search fragment rendered as whitespace rather than truly empty, which CSS's `:empty` guard for
  re-showing `#rails` does not match. Fixed by moving the fragment's `th:if` onto its own root so
  the whole (whitespace included) subtree is omitted. Found by
  `RailFailureE2eTest.searchResultsOpenThePlaySheet`.
