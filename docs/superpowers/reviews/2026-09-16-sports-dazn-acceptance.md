# Sports and DAZN — acceptance record (2026-09-16)

Plan H "Sports and DAZN", Task 5. No agent running this plan has access to a real NVIDIA Shield, LG or Samsung
TV, a DAZN subscription, real calendar feeds or a TheSportsDB personal key, so every manual check below is
recorded as pending for a human with hardware. None is claimed as passed. Automated coverage:
`SportsEndToEndTest` (fake calendar server, fake TheSportsDB, fake Shield, exact app-link strings, secrets),
fixture contract tests, `LiveEventRoutingTest`, unit tests for the ICS subset, fetch policy, cache and rail order.

## Calendars

1. Add a public sports calendar by its `webcal://` link (e.g. a team or league feed from a fixtures site): it is
   fetched over https, named from the calendar, and its matches appear in "Live now / Today" at the right local
   kick-off times.
   **Pending — requires real hardware.**
2. Add a Google Calendar "secret address in iCal format": setup asks for a login password first; afterwards the
   setup page, page source and network tab never show the secret part of the link.
   **Pending — requires real hardware.**
3. Add a calendar served by a Nextcloud or Radicale server on the LAN (private IP): it loads.
   **Pending — requires real hardware.**
4. Try `http://localhost:8080/…` and `http://169.254.169.254/`: both are refused with the "belongs to this machine
   or its network link" message.
   **Pending — requires real hardware.**
5. A calendar exported from Outlook (Windows time zone names) shows correct times; setup reports no unknown
   time zones.
   **Pending — requires real hardware.**
6. A weekly recurring event (e.g. a league night) appears every week; a monthly one is reported in setup as shown
   only once.
   **Pending — requires real hardware.**

## TheSportsDB

7. Find "Germany" / "Soccer" in setup and add the Bundesliga: today's matches appear; setup states the free key's
   limit of 3 matches per day.
   **Pending — requires real hardware.**
8. Enter a personal TheSportsDB key: it is accepted, all of today's matches appear, and switching back to the
   free key removes the secret.
   **Pending — requires real hardware.**
9. Add 10 competitions and reload the dashboard repeatedly: no "limiting requests" error within a day (daily
   cache).
   **Pending — requires real hardware.**
10. Compare kick-off times with the league's official schedule on a match day around a DST change (last Sunday of
    October): times match in the configured time zone.
    **Pending — requires real hardware.**

## Where you watch it (honesty)

11. The setup page labels the provider column "Where you watch it (your setting)" and says Home Control does not
    know broadcast rights; no competition is preselected as DAZN.
    **Pending — requires real hardware.**
12. On the dashboard every tile that names a service says "(your setting)"; no screen suggests a personalised DAZN
    feed or official broadcast data.
    **Pending — requires real hardware.**

## Shield (Android TV app links)

13. A live event of a competition set to DAZN: the play sheet says "Open the DAZN app (not this title)"; playing
    opens the DAZN app (`https://www.dazn.com/`).
    **Pending — requires real hardware.**
14. Paste a DAZN event link (from dazn.com in a browser) in the play sheet: the sheet then says "Open in the DAZN
    app"; record whether the DAZN app opens that event, the app home, or nothing.
    **Pending — requires real hardware.**
15. With the DAZN app not installed, playing a DAZN event shows the "the app may not be installed" hint.
    **Pending — requires real hardware.**
16. A competition set to Prime Video (e.g. a Champions League Tuesday match) opens the Prime Video app.
    **Pending — requires real hardware.**

## LG webOS and Samsung Tizen (F adapters)

17. webOS: a DAZN event opens `https://www.dazn.com/` in the TV browser (F's default for web links); record whether
    the DAZN webOS app claims it instead.
    **Pending — requires real hardware.**
18. Tizen: a DAZN event is refused with "Samsung TVs cannot open web links …".
    **Pending — requires real hardware.**

## Live state

19. Leave the dashboard open across a kick-off: within 5 minutes the match moves from its time to "Live"; after the
    match (duration elapsed) it disappears from the rail.
    **Pending — requires real hardware.**
20. After local midnight the rail shows the new day's matches without a restart.
    **Pending — requires real hardware.**
