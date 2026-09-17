# Jellyfin Source and Playback — Manual Acceptance (sub-project C, release 0.8)

Automated coverage: `JellyfinEndToEndTest`, `LoginGatingTest`, `JellyfinFixtureContractTest` and the unit
tests of Tasks 1–8, all against in-process fakes. Nothing below has been run on real hardware by an agent.

Setup assumed: Jellyfin 10.9+ on the LAN; an NVIDIA Shield paired over Android TV (with its Cast side
merged per sub-project B) running the official Jellyfin Android TV app; one Chromecast or Cast TV.

## Secrets and login

| # | Check | Result |
|---|---|---|
| 1 | Fresh install: `/`, `/setup` and the remote work without a login; `/data` has no `secrets.json` or `secret.key` | Pending — requires real hardware |
| 2 | Connect Jellyfin with user + password and a new login password; the browser stays logged in; `/data/secrets.json` and `/data/secret.key` exist with mode 0600 and contain no readable token | Pending — requires real hardware |
| 3 | A second phone is sent to `/login`; the wrong password is refused; after five wrong attempts it must wait; the right password opens the dashboard | Pending — requires real hardware |
| 4 | Change the password on the setup page: the other phone is logged out | Pending — requires real hardware |
| 5 | Restart the container: login still required, Jellyfin still connected | Pending — requires real hardware |
| 6 | Set `HOME_CONTROL_SECRET` and restart: `secrets.json` now names `HOME_CONTROL_SECRET`; starting with a different value fails with a named error | Pending — requires real hardware |
| 7 | Disconnect Jellyfin: the login requirement disappears | Pending — requires real hardware |

## Jellyfin content

| # | Check | Result |
|---|---|---|
| 8 | `GET /sources/jellyfin/rails/resume`, `next-up`, `latest` list the same items as the Jellyfin web client's home rows | Pending — requires real hardware |
| 9 | Artwork loads through `/sources/jellyfin/images/…` from a phone that cannot reach the Jellyfin server directly | Pending — requires real hardware |
| 10 | `GET /search?q=<title>` finds movies, episodes and songs | Pending — requires real hardware |
| 11 | "Test connection" reports server name, version and user; a wrong URL gives "Could not reach Jellyfin at …" | Pending — requires real hardware |

## Play routes

| # | Check | Result |
|---|---|---|
| 12 | Jellyfin Android TV app open in the foreground on the Shield: `POST /devices/<shield>/play` with a Continue-watching item answers "Play in the open Jellyfin app (Android TV)" and resumes at the saved position with the user's subtitle choice | Pending — requires real hardware |
| 13 | Same with the Jellyfin app in the background (Home pressed): record whether the app comes to the front and plays | Pending — requires real hardware |
| 14 | Jellyfin behind Docker bridge networking (sessions show a gateway address): the Shield is not matched until linked on the setup page, then rung 12 works | Pending — requires real hardware |
| 15 | Jellyfin app closed, Shield Cast side merged: the route becomes "Cast with the Jellyfin receiver" and playback resumes on the Shield | Pending — requires real hardware |
| 16 | Chromecast: "Cast with the Jellyfin receiver" starts playback; the device strip shows the title from Cast media status; Jellyfin's dashboard shows the Chromecast session progressing | Pending — requires real hardware |
| 17 | Jellyfin reached by the container as `http://jellyfin:8096`: the setup page warns; with the TV address set, rung 15/16 works | Pending — requires real hardware |
| 18 | Jellyfin 12 with legacy authorization disabled: rails, session play and the Jellyfin receiver still work (the receiver builds its own stream URLs — note any failure) | Pending — requires real hardware |
| 19 | `GET /devices/<id>/route?source=jellyfin&item=<id>` names the same route that play then uses, for the Shield and the Chromecast | Pending — requires real hardware |

## Findings

- During Task 9, the automated end-to-end test (`JellyfinEndToEndTest`) surfaced a real integration
  defect in the setup page: `fragments/jellyfin-setup.html` used `session` as the `th:each` iteration
  variable name for the Jellyfin-app session list, which Thymeleaf's web context rejects at render time
  (`session` is a reserved word in the web variables map — `IllegalArgumentException`, 500 on `/setup`
  once a source is connected). This was invisible to fragment-level MockMvc tests but broke the real
  request pipeline. Fixed by renaming the loop variable to `jellyfinSession` (Task 9 commit); no other
  behavior changed.
- Final review: the direct stream route (`PlayableRef.StreamUrl`, `JellyfinStreams`) is groundwork for
  media renderers (sub-project I), not a route any device in this release uses — `JellyfinPlayableResolver`
  now only asks Jellyfin for one when the device is not a Cast receiver and does advertise
  `Capability.MEDIA_RENDERER`, since the Jellyfin receiver message always wins over it for a Cast device
  anyway. Rung 16/18 above ("Cast with the Jellyfin receiver") remain the only Cast-side checks.
