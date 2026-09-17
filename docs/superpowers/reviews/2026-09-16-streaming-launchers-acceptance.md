# Streaming launchers — acceptance record (2026-09-16)

Plan G "Streaming launchers", Task 5. No agent running this plan has access to a real NVIDIA Shield, LG or
Samsung TV, a TMDB account or streaming subscriptions, so every manual check below is recorded as pending for
a human with hardware. None is claimed as passed. Automated coverage: `StreamingLaunchersEndToEndTest`
(fake TMDB, fake Shield, exact app-link strings), `PinUpgradeE2eTest` (play sheet), unit and fixture tests.

## TMDB

1. Connect a real TMDB API Read Access Token: setup says "Connected (read access token)"; login is now required.
   **Pending — requires real hardware.**
2. Disconnect and connect a v3 API key instead: "Test connection" succeeds.
   **Pending — requires real hardware.**
3. With locale de-DE, region DE and providers Netflix + Prime Video, "Trending on your services" lists only
   titles TMDB shows as streaming on those services in Germany; posters load on a phone over the plain-HTTP
   dashboard.
   **Pending — requires real hardware.**
4. Provider ids: in the real `/watch/providers` data for DE, Netflix with Ads and Prime Video with Ads titles are
   matched (ids 1796 / 2100 or by name); no rent/buy-only title appears.
   **Pending — requires real hardware.**
5. Search "matrix" shows TMDB results next to other sources.
   **Pending — requires real hardware.**

## Shield (Android TV app links)

6. Netflix title link `https://www.netflix.com/title/<id>` (pinned from a real Netflix URL) opens that title's page
   in the Netflix app.
   **Pending — requires real hardware.**
7. "Open Netflix" (`https://www.netflix.com/browse`) from a trending item opens the Netflix app home.
   **Pending — requires real hardware.**
8. Prime Video `https://app.primevideo.com/detail?gti=…` (pinned from a primevideo.com share link) opens the title
   in the Prime Video app.
   **Pending — requires real hardware.**
9. Prime Video `https://www.amazon.de/gp/video/detail/<ASIN>` opens the title in the Prime Video app.
   **Pending — requires real hardware.**
10. Prime Video `https://www.primevideo.com/detail/<ID>` (no GTI) — record whether the app opens the title, the
    app home, or nothing.
    **Pending — requires real hardware.**
11. "Open Prime Video" (`https://app.primevideo.com/`) opens the Prime Video app.
    **Pending — requires real hardware.**
12. A pinned DAZN link opens the DAZN app.
    **Pending — requires real hardware.**
13. After a successful Netflix launch the "the app may not be installed" hint does not appear; after pinning and
    launching a link for an app that is not installed (e.g. a Disney+ web link) the hint appears.
    **Pending — requires real hardware.**

## LG webOS and Samsung Tizen (F adapters)

14. webOS: a pinned Netflix title opens the title (ConnectSDK `contentId`); record the webOS version.
    **Pending — requires real hardware.**
15. webOS: a Prime Video link opens the Prime Video app (no title — documented).
    **Pending — requires real hardware.**
16. Tizen: a Netflix title link opens the Netflix app without the title; the play sheet's wording is acceptable.
    **Pending — requires real hardware.**
17. Tizen: a DAZN link is refused with "Samsung TVs cannot open web links …".
    **Pending — requires real hardware.**

## Honesty and security

18. No screen suggests a personalised Netflix, Prime Video or DAZN feed; the trending rail is labelled TMDB.
    **Pending — requires real hardware.**
19. View the page source and network tab of the dashboard, setup page and play sheet: the TMDB credential never
    appears.
    **Pending — requires real hardware.**
