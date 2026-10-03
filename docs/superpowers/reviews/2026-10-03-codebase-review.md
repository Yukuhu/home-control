# Whole-codebase review (2026-10-03)

Base: `main` at 593dc0ce (after #181, which closed the deferred-minors sweep). CI on main is green (run 37101590531).

Outcome: **1 Critical, 12 Important, 47 Minor.** The architecture and the parts that previous phases hardened hold
up well. The Critical finding is a line of text on the login page. Most Important findings sit where the app meets its
deployment: restarts, reverse proxies, damaged files, hostile LAN datagrams. Two are adapter races.

## How this was done

- Ten read-only reviews ran in parallel, one per area, each covering about 4–6k lines of main code. Each measured the
  code against the product rules in `AGENTS.md` and read tests only to see whether a defect is pinned, and whether the
  area's fakes speak the real protocol.
- Every finding needed a concrete failure scenario traced through the code. Items from `.superpowers/deferred-triage.md`
  and the 2026-10-02 SonarCloud triage were checked, not rediscovered.
- Every Critical and Important finding was then re-read against the cited lines on 593dc0ce before it went in here.
  Two were downgraded (SP1, HJ3); two pairs of duplicates were merged (DC4 = ST5, ST6 = FB11).
- Nothing was built or run. One reviewer made two read-only GETs to TheSportsDB's public API to check the fake.
- Ids are `<area><n>`:

  | Prefix | Area |
  | --- | --- |
  | WS | security and web |
  | ST | storage and themes |
  | DC | device, core, content, playback |
  | AC | Android TV, Cast, adapter support |
  | BU | Bluetooth, Sonos, UPnP |
  | TV | webOS, Tizen, discovery |
  | HJ | outbound HTTP, Jellyfin, TMDB, pinned |
  | YW | YouTube, workflows |
  | SP | sports |
  | FB | frontend, build, CI, docs |

- Effort: S < 2 h, M < 1 day.

| Area | Critical | Important | Minor | Weakest point |
| --- | --- | --- | --- | --- |
| WS security, web | – | 1 | 3 | Rate-limit keying behind a proxy; event-stream ordering and isolation |
| ST storage, themes | – | 1 | 4 | `ThemeCatalog`'s resource model (heap, one lock, no cleanup) |
| DC device, core, content | – | 1 | 4 | Multi-write operations and an IPC call under the registry lock |
| AC Android TV, Cast | – | 1 | 7 | Cast counts itself connected early; no device is followed when it moves or returns |
| BU Bluetooth, Sonos, UPnP | – | 2 | 2 | `close()` vs. a play in flight; Sonos routing by a stale topology |
| TV webOS, Tizen, discovery | – | 2 | 5 | The SSDP receive path trusts parsed values |
| HJ HTTP, Jellyfin, TMDB, pinned | – | 1 | 4 | Where sources meet the rest of the app (`sources.http` itself is clean) |
| YW YouTube, workflows | – | 1 | 2 | Subscriptions feed failure handling |
| SP sports | – | – | 8 | RFC 5545 fidelity at the edges; id and label stability |
| FB frontend, build, docs | 1 | 1 | 8 | Recovery after server restarts; recovery text contradicting the guides |

Verified sound, with no finding: the login gate (exact raw-URI match), Host allowlist and origin guard, session fixation
handling, and safe `next`. The `shield.*` legacy mapping is complete. Secrets are AES-256-GCM with random nonces and never
appear in messages or logs. Atomic writes are fsynced and refuse newer versions. The theme ZIP import is free of zip
slip, symlinks and zip bombs, and its CSS is allow-listed with `url()` limited to the package. `sources.http` pins DNS,
uses one deadline, caps bodies, strips credentials across origins and redacts failures. Device XML parsing has no
DTDs or external entities. Android TV pairing digest and fingerprint pinning, Cast framing limits and request
correlation hold. No XSS sink exists (no `th:utext`, no `innerHTML`). CI actions are pinned by SHA, and the image runs
as non-root. The CodeQL `java/ssrf` dismissal on `TizenRest` is justified: hosts come only from the setup form and are
re-validated by `DeviceUris`.

## Critical

### FB1 — The login page tells a locked-out user to delete `secrets.json`, which stops the app from starting and loses every TV pairing — S

- **Where:** `src/main/resources/templates/login.html:21-23`.
- **Defect:** this is the only recovery advice a locked-out user sees. It says "Stop Home Control and delete
  `secrets.json` … to reset access". `docs/user/security.md:34-39` says the opposite ("Do not delete `secrets.json` for
  this: it also holds the TV pairings") and documents the safe route, `HOME_CONTROL_RESET_LOGIN=true`.
- **Scenario:** a default install (no `SHIELD_KEYSTORE_PASSWORD`) with an Android TV and an LG TV paired, and a
  forgotten password. The user deletes `secrets.json`.
  - On the next start, `KeystorePassword.resolve` (`KeystorePassword.java:36-49`) finds no stored password and
    generates a new one.
  - `keystore.p12` opens neither with that password nor with `shield` or `change-me`, so `StorageException` is thrown.
  - `AndroidTvAdapter`'s `@PostConstruct` → `CertificateStore.verifyReadable` fails the context, and the container
    restart-loops.
  - The way out is to delete `keystore.p12` too. The webOS/Tizen keys were device secrets in the deleted file. Every TV
    must be paired again.
- **Fix:** replace the hint with the `HOME_CONTROL_RESET_LOGIN=true` procedure and a link to the security guide.
- **Pinned by a test:** no.

## Important

### WS1 — Behind the recommended HTTPS proxy, five wrong guesses lock every browser out for 15 minutes — S

- **Where:**
  - `web/LoginController.java:57-65` (and `:129-133`) keys `LoginRateLimiter.reserve` on `request.getRemoteAddr()`,
    and refuses before the password is checked.
  - `application.yaml` sets no `server.forward-headers-strategy`.
  - `docs/user/security.md:41-44` recommends a reverse proxy.
- **Scenario:** Home Control runs behind Caddy or nginx. A guest mistypes the password five times. Every browser now
  shares the proxy's address, so for 15 minutes every login, including the right password, gets `429 Too many
  attempts`.
  - The `framework` strategy that `docs/user/sources.md:341-345` suggests for YouTube flips it the other way:
    `X-Forwarded-For` becomes the key, chosen by any client that can reach the backend port.
- **Fix:**
  - Trust a forwarded address only from a configured proxy (`native` strategy with
    `server.tomcat.remoteip.internal-proxies`), and document it in `security.md`.
  - Consider slowing attempts past the limit instead of refusing them.
- **Pinned by a test:** `LoginGatingTest.repeatedFailuresAreRateLimitedEvenForTheRightPassword` pins the refusal for a
  single address. No test covers a proxy.

### FB2 — A live-update stream that gets an HTTP error is never reopened: "Reconnecting…" forever, touchpad disabled — S

- **Where:**
  - `static/js/events.js:5-13` creates one `EventSource` and relies on its own reconnect.
  - `security/LoginGateFilter.java:67-68` answers plain `401` for a non-htmx, non-HTML request.
  - `static/js/touchpad.js:128-130`.
- **Defect:** `EventSource` reconnects only after a network error. A non-200 status, or a non-event-stream content
  type, closes it for good (`readyState` CLOSED). Nothing handles that.
- **Scenario:**
  - (a) A login is set, and the container restarts (sessions are in memory, see WS2). The reconnect to `/events` gets
    401, so the stream is dead. The status stays "Reconnecting…", the touchpad stays disabled with "The device is not
    connected", and no state or rail update arrives until a manual reload. The same happens after a logout or password
    change in another browser.
  - (b) No login, behind a proxy. During the restart the proxy answers 502/503, and the stream is closed forever.
- **Fix:**
  - On `error` with `readyState === CLOSED`, re-create the `EventSource` with backoff and re-attach the registered
    handlers.
  - On 401, go to `/login?next=<page>`.
- **Pinned by a test:** no.

### BU1 — A Bluetooth play racing the session's close starts an mpv nobody tracks; Stop cannot stop it — S

- **Where:**
  - `adapters/bluetooth/BluetoothSpeakerSession.java:97-101`, `:128-133` and `:142-153`.
  - `adapters/bluetooth/player/MpvPlayer.java:100-106` and `:223-235`.
  - `BluetoothSpeakerAdapter.java:56` (a new `MpvPlayer` per session).
- **Defect:** `close()` is not synchronized with `commands`. It sets `closed` and calls `player.stop()`, which stops
  only an mpv that is already running. A play past its `closed` check, still in `ensureConnected()` (BlueZ connect, up
  to seconds) or `resolve()`, then enters `player.play()` and spawns mpv.
- **Scenario:** while a play connects the speaker, the user changes the audio output or re-pairs (both reconnect the
  device), or forgets, merges or splits it.
  - The orphan mpv keeps playing, and a radio stream never ends.
  - The replacement session's player does not know it, so Stop does nothing and the next Play starts a second stream.
  - After a forget, which unpairs first, the audio moves to the host's own output: the case the code and the guide say
    cannot happen.
- **Fix:** give `MpvPlayer` a `closed` flag under its monitor. `play()` refuses once closed, and the session's
  `close()` calls `player.close()`.
- **Pinned by a test:** no.

### BU2 — A Sonos room ungrouped in the Sonos app sends Play/Pause/Stop to its old coordinator for up to 30 s — S

- **Where:**
  - `adapters/sonos/SonosSession.java:226-237` routes by the cached topology.
  - `:213-223` re-reads only on fault 800 (not coordinator).
  - `:300-306` refreshes the topology every `topologyInterval` (30 s).
- **Scenario:** Kitchen is ungrouped from Living Room in the Sonos app. Within 30 s, Pause on Kitchen goes to Living
  Room, which is still a coordinator, so it accepts. The wrong room pauses, and Kitchen's drawer shows Living Room's
  track. The 800 retry covers only the opposite direction.
- **Fix:** before a transport command, re-read the topology (one `GetZoneGroupState`) whenever the cache names another
  coordinator.
- **Pinned by a test:** no.

### HJ1 — The VLC route authenticates with `api_key`, the legacy form newer Jellyfin disables — S

- **Where:**
  - `sources/jellyfin/JellyfinVlcExecutor.java:127-128` uses `&api_key=`.
  - `JellyfinStreams.java:82-83` uses `&ApiKey=`.
  - The PlaybackInfo body is also built twice (`JellyfinVlcExecutor.java:113-118` vs. `JellyfinStreams.java:44-72`).
- **Scenario:** a Jellyfin server with legacy authorization switched off (an option in 10.11, slated as the default for
  12). Play via VLC: the route is optimistic and reports success, then VLC's GET gets 401 and fails on the TV. The Cast,
  renderer and app routes keep working.
- **Fix:** use `ApiKey=` (supported since 10.9, the minimum `publicInfo` enforces). Better, give `JellyfinStreams` one
  static-stream-URL builder that both routes use.
- **Pinned by a test:** `JellyfinVlcExecutorTest:53` pins the legacy form, and the fake has no stream endpoint.
- The version facts come from the reviewer's reading of Jellyfin's authorization docs and PRs (#15559, #13306). They
  were not re-checked here; the drift between the two builders is certain either way.

### HJ2 — A damaged `pinned.json` returns 500 for the dashboard (the remote), the setup page, search and the TMDB rail — S

- **Where:**
  - `sources/pinned/PinnedContentSource.java:38-45`: `available()` → `pins.all()` → `PinnedShortcuts.ensureLoaded`
    (`:291-296`) → `VersionedJsonFile.load` throws `StorageException`.
  - `StoredRailPreferences.java:42` (`filter(ContentSource::available)`), `ContentSources.searchable()` and
    `TmdbContentSource.playablesFor` → `PinnedShortcuts.linkFor` let it through.
  - `ErrorAdvice` has no `StorageException` handler.
- **Scenario:** `pinned.json` is hand-edited badly, or was written by a newer version before a rollback (refusing it is
  correct). After that:
  - `GET /` → `RailCache.snapshots()` → 500.
  - `/setup` → `PinnedSetupSection.view` → 500, so the page where pins are removed cannot open.
  - Search and the TMDB trending rail fail too.
  - The way out is to delete the file or set `home-control.pinned.enabled=false`. The store's "fix or delete it"
    message never reaches the user.
- **Fix:** contain the failure inside the module:
  - `available()` is false, logged once at WARN;
  - `rail()` throws a `ContentSourceException` with the store's message;
  - `linkFor` returns empty;
  - the setup section renders the error.
- **Pinned by a test:** store level only (`JsonFilePinStoreTest:100-121`).
- The same audit is worth doing for the other `VersionedJsonFile` stores reached from `available()` or page rendering.

### DC1 — An invalid `HOME_CONTROL_LOCALE` or `HOME_CONTROL_REGION` returns 500 for the dashboard and setup instead of failing startup — S

- **Where:**
  - `content/ContentProperties.java:10-11` does not validate `locale` and `region`.
  - `SourcePreferencesService.current()` (`:27-37`) builds `SourcePreferences.defaults(...)` on every call while
    nothing is stored.
  - `core/.../SourcePreferences.java:54-59,134-141` throws `IllegalArgumentException` unless the value is a canonical
    tag and a `[A-Z]{2}` region.
- **Scenario:** a fresh install with `HOME_CONTROL_LOCALE=en-us` (not canonical), `en_US` or `HOME_CONTROL_REGION=us`.
  - The app starts.
  - `GET /` (via `StoredRailPreferences.rails`) and the setup page (`SourcesSetupAdvice`) throw "Use a language tag
    such as de-DE", which names no setting.
  - The UI cannot store a fix, because `update()` calls `current()` first.
  - The rail ticker logs a failure every 15 s.
- **Fix:** validate both in `ContentProperties`' compact constructor with `SourcePreferences`' rules, so startup fails
  and names the property.
- **Pinned by a test:** no.

### TV1 — One SSDP datagram with a huge `max-age` ends the SSDP receive thread until restart — S

- **Where:**
  - `protocols/.../discovery/ssdp/protocol/SsdpMessage.java:18,65-68`: `Long.parseLong` on an unbounded `\d+`.
  - `discovery/ssdp/SsdpDiscovery.java:228` (`clock.instant().plus(maxAge)`) and `:185-201`: `receive()` has no catch
    around `handle()`, and the threads are started bare.
- **Scenario:** any LAN host multicasts a NOTIFY with a watched `NT`, any `USN` and `CACHE-CONTROL:
  max-age=99999999999999999999`.
  - The result is a `NumberFormatException` for 20 digits, or an `Instant.plus` overflow for 17–19.
  - The `ssdp-notify` thread dies. The same datagram sent unicast to the search socket kills `ssdp-responses`.
  - Until a restart: discovered TVs and speakers expire from Setup, Sonos and UPnP discovery stop, and webOS/Tizen lose
    their reconnect-on-announce.
  - A `RuntimeException` from any listener ends the loop the same way.
- **Fix:**
  - Clamp `max-age` (for example to one day) and fall back to the default on a parse failure.
  - Wrap each iteration of `receive()` in `try/catch (RuntimeException)` with a WARN.
- **Pinned by a test:** no.

### TV2 — A malformed `DLNADeviceName.lge.com` header returns 500 for the setup page and fails every webOS/Tizen pairing — S

- **Where:**
  - `adapters/webos/WebOsAdapter.java:119-124` calls `URLDecoder.decode` on the raw header when no description was
    fetched, and throws `IllegalArgumentException` on `%zz`.
  - `device/Enrollment.java:212-214`: `discovered()` gathers every adapter's list, and it is used by `pairable()`,
    `addable()` and `attach()`.
- **Scenario:** a LAN host multicasts a webOS NOTIFY without `LOCATION`, with `DLNADeviceName.lge.com: %zz` and a long
  `max-age`. Then:
  - `/setup` → `enrollment.pairable()` → 500;
  - every webOS or Tizen prompt pairing fails in `attach()` after the new key was already stored.
  - Real LG sets encode the name correctly, so the trigger is a hostile or buggy host. It lasts for the sender-chosen
    `max-age`.
- **Fix:**
  - Fall back to the raw value or "LG webOS TV" on a decode failure.
  - In `Enrollment.discovered()`, skip and log an adapter whose `discovered()` throws.
- **Pinned by a test:** no.

### ST1 — Imported themes are held in heap about twice, for current and retained revisions; on a small host they can exhaust the default heap and crash-loop startup — M

- **Where:**
  - `themes/ThemePackage.java:24-28` keeps the source files plus every asset again.
  - `ThemeAsset.java:7` clones the asset bytes.
  - `ThemeCatalog.java:37-39,170-183` loads every current and previous revision at startup.
  - `MAX_STORAGE` = 256 MiB counts disk bytes once (`:28,89`).
  - The `load` catch is `RuntimeException | IOException` only.
  - `Dockerfile:44` sets no heap option (JVM default: 25 % of RAM). Compose uses `restart: unless-stopped`.
- **Scenario:** a 1–2 GB host (heap 256–512 MB) with several large imported themes, updated a few times.
  - The resident set nears 2× the storage.
  - The next install, or a concurrent large request, hits `OutOfMemoryError`.
  - If the retained set no longer fits at startup, the `Error` escapes `load`, the app fails to start, and the restart
    policy loops until `/data/themes` is deleted by hand.
  - The trigger is narrow (it needs a lot of deliberately imported theme data), but the failure is severe.
- **Fix:**
  - Keep only the presentation assets in memory, and read the source files from disk for export.
  - Or derive the storage budget from the heap.
- **Pinned by a test:** no.

### YW1 — One failed uploads lookup leaves "New from your subscriptions" empty, without an error, for up to 24 h — S

- **Where:** `sources/youtube/SubscriptionsFeed.java`:
  - `:92-94` commits `subscriptions` and `subscriptionsFetchedAt` before the `channels.list` batches (`:98-114`);
  - `:62` skips `loadSubscriptions` while the list is "fresh" (24 h);
  - `:145` drops channels without an uploads entry;
  - `:73-77` treats an empty merge as success.
- **Scenario:** right after connecting, `subscriptions.list` succeeds, then `channels.list` fails once (a 5xx or a
  timeout).
  - The next refresh skips the lookup and finds no candidates.
  - It stores READY with no items, and repeats that until the daily subscription refresh.
  - With more than 50 subscriptions, a failing batch k silently drops batches k and later.
  - The workaround is disconnect/reconnect or a restart.
- **Fix:**
  - Commit the list only after every batch succeeded, or re-look-up subscribed channels without an uploads entry.
  - Treat "nothing could be polled" as a failure.
- **Pinned by a test:** no.

### AC2 — An Android TV that moves to a new address is never followed, and pairing it again creates a second device — M

This is a known design limitation, not an oversight: `docs/superpowers/specs/2026-08-30-shield-remote-vnext-design.md:465`
lists "device-identity improvements that survive DHCP address changes" as future work.

- **Where:**
  - `adapters/androidtv/PairingService.java:62,141-143`: the device id and keystore alias come from the host.
  - `MdnsDiscovery.java:45-59` publishes no discovery event.
  - `AndroidTvAdapter` has no `carries`/`settingsFor`.
  - Cast, by contrast, is re-pointed by its mDNS id (`devices.md:86-88` promises it).
- **Scenario:** a Shield at 192.168.1.20 gets .30 from DHCP.
  - Its session retries .20 forever.
  - Re-pairing creates `192-168-1-30` as a second "SHIELD". The old one, holding any merged Cast entry, stays
    DISCONNECTED, and the two cannot be merged (both carry `androidtv`).
  - The workaround is to forget the old entry, or set a DHCP reservation.
- **Fix:** on re-pair, find a registered Android TV whose stored `certificateFingerprint` matches the newly paired
  server certificate, and re-pair into it (keep its id and alias, update its host). Optionally re-point by mDNS name
  after a pinned handshake.
- **Pinned by a test:** no.

## Minor

### Security and web (WS)

- **WS2 — Every restart logs every browser out, and a plain form POST then gets a bare `401 "Log in first"` page.**
  - Sessions are in-memory (no `server.servlet.session.persistent`), against a 30-day cookie. The
    `RequestLoginContext` Javadoc claims logins survive upgrades.
  - Typed form input is lost.
  - Fix: for non-htmx `text/html` requests of any method, answer 303 to `/login?next=`. Correct the Javadoc. Consider
    persistent sessions under `/data`.
  - `LoginGateFilter.java:61-69`, `RequestLoginContext.java:9-10`. S.
- **WS3 — A new tab's device snapshot can overwrite a newer live event.**
  - `EventStreamController.java:42-52` registers the emitter, then sends `states()` on the request thread. A
    concurrent fan-out send lands first, and the older snapshot follows.
  - A push-only adapter (Android TV) can show a stale app for hours.
  - Fix: send the snapshot from the fan-out executor. S.
- **WS4 — One stalled event-stream client delays every tab.**
  - `EventStream.java:51,171-196` uses one fan-out thread with blocking sends. A half-open phone connection blocks
    every other tab and the heartbeat until the write times out.
  - Fix: a bounded queue and virtual thread per subscriber. M.

### Storage and themes (ST)

- **ST2 — A theme install holds the catalog lock through validation and fsyncs, and every request waits on it.**
  - `ThemeViewAdvice` (unrestricted `@ControllerAdvice`, incl. the remote key endpoints) and `LoginGateFilter`
    (`publicAssets.test`) call synchronized `ThemeCatalog` methods.
  - Fix: stage outside the lock, publish an immutable snapshot through a `volatile` field, and restrict the advice to
    page controllers. S.
- **ST3 — Orphaned theme storage is never swept but counts against the 256 MiB quota.**
  - Crashed staging and failed reclaims add up. An update near the quota counts the revision it would free. Damaged
    entries have no Remove button but take a slot. `problems` only grows.
  - `ThemeCatalog.java:85-108,161-185,218-264`. S.
- **ST4 — Theme failures discard their cause, and nothing in `themes/` logs.**
  - The "damaged or incompatible" and 507 messages carry no reason.
  - `ThemeCatalog.java:104-106,165,175,182,263`. S.
- **ST6 (= FB11) — The unusable-data-directory remedy always says `chown -R 1000:1000`.**
  - That is wrong for the `user: "1001:1001"` setup `compose.yaml` documents, and an unnamed uid shows as "running as
    ?".
  - Fix: print the process's numeric uid:gid.
  - `UnusableDataDirectoryException.java:12,27-32`, `DataDirectory.java:60-65`. S.

### Device, core, content (DC)

- **DC2 — Merge and split write the registry twice.**
  - A failure between `delete(source)` and `save(merged)` (`Enrollment.java:353-358`; split `:396-399`) loses the
    source's adapter entry and webOS/Tizen `keyRef`. It also orphans a live session that `forget` can no longer reach.
  - Fix: one registry operation that replaces and removes in one `file.update`, or at least save before delete. S.
- **DC3 — Forgetting a Bluetooth speaker makes a D-Bus round trip (up to the 45 s BlueZ timeout) while holding the
  registry lock.**
  - `Enrollment.java:170-185`, `BluetoothSpeakerAdapter.java:67-74`. This breaks `RegistryLock`'s "local work only"
    contract and stalls TV sessions, discovery and pairings.
  - Fix: run the removal after the lock. S.
- **DC4 (= ST5) — With the webOS or Tizen module off, the devices.json v2→v3 migration leaves that TV's pairing key in
  plain text.**
  - Only the `.v2` backup is redacted (`JsonFileDeviceRegistry.java:34-56`). The key move runs only through the
    adapter's `migrate` (`Enrollment.java:127-139`). This goes against "secrets encrypted at rest".
  - Fix: run the `PairingKeys` migrations from an always-on bean. M.
- **DC5 — A refresh-interval change made while a rail loads is overwritten by the load's stale interval.**
  - `RailCache.java:280,313,327` vs. `reschedule()` `:210-222`. Example: a 60→15 min change mid-load gives the next
    refresh 45 min late. It is the same class as the fixed H1.
  - Fix: read the interval in `succeed`/`fail`. S.

### Android TV, Cast, adapter support (AC)

- **AC1 — Cast reports CONNECTED (and resets the backoff) after writing GET_STATUS, before the receiver answers.**
  - A receiver that closes right after CONNECT is retried every 1 s forever. A Stop sent before the first
    RECEIVER_STATUS "succeeds" without sending anything.
  - `CastSession.java:129-137,188-203`.
  - Fix: return PENDING until the first RECEIVER_STATUS (Android TV's pattern), and fail Stop while `receiver` is null.
    S.
- **AC3 — Cast and Android TV do not reconnect when the device announces itself again.**
  - webOS, UPnP and Sonos call `reconnectNow()`. After an outage longer than a minute, reconnecting can wait out the
    60 s backoff.
  - `CastAdapter`, `MdnsDiscovery.java:45-59`. S.
- **AC4 — A Cast contentId with a malformed `%` escape breaks now-playing for the whole cast.**
  - `MediaStatus.java:57-70` (`URLDecoder.decode`) throws on every status, logged as a WARN stack trace.
  - Fix: fall back to the raw file name. S.
- **AC5 — The UNPAIRED latch fires about 15 s after the first ambiguous verdict, not "about half a minute".**
  - Four delays elapse (1+2+4+8 s). The latch is permanent until a re-pair or restart, so a slow Shield boot after a
    power cut can latch a valid pairing.
  - `AndroidTvSession.java:45-57`. This was already recorded as shipping unfixed in the 2026-08-29 review §1.
  - Fix: gate the latch on elapsed time as well, or correct the comment. S.
- **AC6 — A latched UNPAIRED Android TV answers commands with "is not connected" instead of "must be paired again".**
  - `AndroidTvSession.java:157-163` → `DeviceCalls.java:49-51`. S.
- **AC7 — A double-submitted pairing code runs two `submit()` calls on one pairing socket.**
  - A successful pairing can be reported as failed.
  - `PairingService.java:89-123`, `setup.html:58-64` (no double-submit guard).
  - Fix: serialize per attempt, and disable the button. S.
- **AC8 — known (B3), with a new part: pairings leave private keys in `keystore.p12`.**
  - New: `begin` saves a key entry before the device is reached (`PairingService.java:63` →
    `CertificateStore.java:114-120`), so a mistyped address leaves a key for good.
  - Fix: keep the credential in memory until `Paired`, and add the B3 sweep. S.

### Bluetooth, Sonos, UPnP (BU)

- **BU3 — `MpvPlayer` treats every `end-file` before `file-loaded` as a failure, including mpv's `redirect` (M3U/PLS
  expansion).**
  - `MpvPlayer.java:113-116`. `FakeMpv` never emits `redirect`. S.
- **BU4 — The setup page's Disconnect does not stop the player first.**
  - Audio can move to the host output for up to one poll. `pollNow`'s Javadoc names setup actions that never call it.
  - `BluetoothSpeakerSession.java:135-140`, `BluetoothSetupController.java:293-302`. S.

### webOS, Tizen, discovery (TV)

- **TV3 — Tizen REST and DIAL calls have no deadline on the response body.**
  - `HttpRequest.timeout` stops at the headers. A stalled body freezes the Tizen session loop, or the request thread,
    for good.
  - `TizenRest.java:49-71`, `DialClient.java:21-41`.
  - Fix: use `DeviceFetch.send`, which exists for this. S.
- **TV4 — The webOS pointer socket opens a device-supplied `socketPath` unchecked (a device-steered request).**
  - A malformed path throws `IllegalArgumentException` outside the `DeviceCalls` mapping, so d-pad keys get a 500.
  - `SsapConnection.java:153-172`.
  - Fix: accept only `ws`/`wss` to the TV's own host. S.
- **TV5 — The webOS client key crosses the LAN in clear on every reconnect when the TV also offers TLS.**
  - The plain `ws://:3000` is tried first (`SsapConnection.java:61-90`).
  - Trying `wss://:3001` first is a design call for the owner. S.
- **TV6 — SSDP keeps an unbounded number of services, for as long as the sender asks, and starts one description
  fetch per new USN.**
  - A flood of distinct USNs grows the heap and opens many connections.
  - `SsdpDiscovery.java:131-138,224-232`.
  - Fix: cap the map, clamp `max-age` (TV1), and fetch through one bounded executor. S.
- **TV7 — The `WebOsTimings` Javadoc names `WebOsProperties#requestTimeoutSeconds()`, which no longer exists.** S.

### Outbound HTTP, Jellyfin, TMDB, pinned (HJ)

- **HJ3 (downgraded from Important) — Jellyfin setup reports a redirecting server as "not a Jellyfin server".**
  - `JellyfinClient.publicInfo` (`:107-116`) maps every `BAD_RESPONSE`, including the 3xx "redirected elsewhere; enter
    the final server address" from `requireSuccess` (`:238-242`), to `notJellyfin`.
  - A server with a Base URL, or an http→https proxy, gets the wrong advice. The connect fails either way; only the
    message misleads.
  - Fix: let the redirect failure through. S.
- **HJ4 — TMDB setup calls TMDB before it checks the first-password rules.**
  - `TmdbSetupService.java:56-68` has no `login.permitSecrets` before `validate`, unlike Jellyfin and sports. S.
- **HJ5 — Every `/setup` render waits for a live Jellyfin `/Sessions` call (up to 15 s when the NAS is asleep).**
  - Every setup form redirects to `/setup`, so every click pays it.
  - `JellyfinSetupSection.java:76-86`.
  - Fix: load the list lazily with htmx, or use a 2 s deadline. S.
- **HJ6 — The TMDB trending loop keeps calling after a timeout or a 429.**
  - With 40 candidates and a 10 s timeout, a refresh can run for about 400 s.
  - `TmdbContentSource.java:111-127`.
  - Fix: stop on `UNREACHABLE`, `RATE_LIMITED` or `UNAUTHORIZED`. S.
- **G9 (known):** only a 200's body is read. It has no effect in this area today.

### YouTube, workflows (YW)

- **YW2 — Setup can hang for minutes behind YouTube's authorization monitor.**
  - `status()` (every setup render) shares the monitor with `revoke`, `exchangeCode`, `requestDeviceCode` and the
    connect hook, which waits for a running subscriptions refresh (`YouTubeAuthorizationService.java:79-146,208-235`,
    `YouTubeSetupService.java:226-248`).
  - Fix: keep the monitor to in-memory state, and give `clear()` an epoch. S.
- **YW3 — Saving, toggling or removing any workflow throws away every other workflow's last good tiles and Play
  targets.**
  - Invalidation is per source (`WorkflowStore.java:277-279` → `WorkflowConfiguration.java:80-87`). This breaks
    `sources.md:91`'s promise.
  - Fix: invalidate per workflow. S.
- **C5b (known, harmless):** the dead clause at `YouTubeSetupService.java:143-144` is still there.

### Sports (SP)

- **SP1 (downgraded from Important) — A calendar whose label falls back to a host longer than 80 characters is added,
  then silently dropped on the next start.**
  - The secret is orphaned and the next save erases the entry.
  - `SportsCalendars.java:121-130` vs. `JsonFileSportsStore.java:127-131`.
  - Fix: `cut()` the host label, and truncate instead of dropping when reading. S.
- **SP2 — A line folded inside a multi-byte UTF-8 character is garbled ("M��nchen").**
  - The body is decoded before `IcsParser.unfold` (`CalendarFetcher.java:56`). RFC 5545 §3.1 calls this out.
  - Fix: unfold the bytes before decoding. S.
- **SP3 — The `.ics` fixtures are cleaner than real feeds (LF only, never folded at a byte boundary).** This hides SP2.
  S.
- **SP4 — A moved kick-off loses its pinned link.**
  - The item id is UID + start time (`CalendarSchedule.java:267-270`), against the design note's "stable across
    refreshes".
  - It needs a pin migration or a fallback lookup. M.
- **SP5 — `WKST` is parsed and ignored.**
  - Weeks always start on Monday, so `FREQ=WEEKLY;INTERVAL=2;BYDAY=SU,MO;WKST=SU` misplaces the Mondays, and the
    status line does not mention it.
  - `IcsRecurrence.java:61`, `IcsOccurrences.java:191`. S.
- **SP6 — The expansion work is capped per event, not per calendar, and runs under the schedule lock.**
  - A 0.5 MB hostile feed costs about 250 M steps per pass, and the expansion runs again on every setup view.
  - `IcsParser.java:31`, `IcsOccurrences.java:24`, `CalendarSchedule.java:150-177,233`. S.
- **SP7 — All-day events are placed in the calendar's `X-WR-TIMEZONE` but "today" is judged in the household zone.**
  - Separately, TheSportsDB's 24 h cache keeps the old zone after a time-zone change.
  - `IcsOccurrences.java:89`, `EventPhase.java:13-18`, `TheSportsDbEventMapper.java:100-101`. S.
- **SP8 — Sports durations are not validated.**
  - A bare `90` means 90 ms. A negative value makes every sports rail load fail, because `fetchIfStillDue` catches only
    `TheSportsDbException`.
  - `SportsProperties.java:25,30,44,49`. S.

### Frontend, build, CI, docs (FB)

- **FB3 — After a server restart, an open dashboard ignores rail updates until the new version counter passes the old
  one.**
  - Rail versions restart at 1 (`RailCache.java:46`), and `rails.js:14-22,42` only moves forward. The reconnect
    snapshot sends no `rails` list either.
  - Fix: seed the counter from the clock, or reset `highestSeen` on `open`. S.
- **FB4 — Behind the HTTPS proxy (the only setup with a service worker), a stopped server shows the proxy's 502 page,
  not the offline page.**
  - `sw.js:287-291` falls back only when `fetch` rejects.
  - Fix: also serve the offline page for 502/503/504. S.
- **FB5 — `configuration.md:11` gives the keystore password default as `shield`.** It is empty, meaning generated.
  Setting it to `shield` "to keep the default" stops startup. S.
- **FB6 — README CasaOS "important files" (`:81-85`) leave out `secrets.json` and `secret.key`.**
  - Without them the keystore is useless.
  - `:129-130` "only devices has no login" is no longer true. S.
- **FB7 — `sources.md` has two stale statements.**
  - `:223-226` says no device uses the Jellyfin direct-stream route; renderers and Bluetooth do.
  - `:431-434` calls DNS rebinding an accepted residual risk; `GuardedHttpClient` pins DNS, as `security.md:63-65`
    says. S.
- **FB8 — `devices.md:40-41,96-97,149-150` names setup sections that do not exist.**
  - The page's sections are "Find a device", "Ready to add (no pairing needed)", "Add another smart TV by address" and
    "Install app". S.
- **FB9 — `SONAR_TOKEN` reaches unreviewed npm dependency code on Dependabot PRs.**
  - `ci.yml:707-733` runs `npm run summary` with the token. Dependabot bumps `fast-xml-parser`, and npm has no checksum
    gate.
  - Fix: query SonarCloud in a separate step without npm, or skip it for `dependabot[bot]`. S.
- **FB10 — A docs-only PR skips the tests that read the docs.**
  - `BluetoothDeploymentTest` reads `docs/user/bluetooth-speakers.md` and `docs/bluetooth-speakers.md`, but
    `scripts/code-changed.sh:11` counts them as documentation. Main then fails after merge.
  - Fix: exclude those files from the docs pattern. S.

## Cross-cutting themes

1. **Restarts and proxies.** WS1, WS2, FB2, FB3, FB4: the server side handles both well, the browser side does not
   recover. Restarts are routine for an appliance (upgrades, Watchtower, power cuts), so this group is worth doing as
   one piece.
2. **One bad input takes down unrelated pages.** HJ2, DC1, TV2: aggregators (`StoredRailPreferences` filtering by
   `available()`, `Enrollment.discovered()`, `ContentSources.searchable()`) let one module's exception escape into the
   dashboard and setup page. A containment rule at those seams would cover future modules as well.
3. **Unauthenticated SSDP input.** TV1, TV2, TV6: the code already refuses forged `LOCATION`s, but it trusts parsed
   values and the loop has no guard.
4. **`close()` vs. work in flight, and operations that are not atomic.** BU1, DC2, AC1.
5. **Locks held across I/O.** DC3, ST2, YW2, HJ5, SP6.
6. **Recovery text that contradicts the guides.** FB1, FB5, FB6, ST6: the texts users read when something has
   already gone wrong deserve a check against the guides.

## Suggested pull requests

| PR | Contents | Effort |
| --- | --- | --- |
| R1 recovery text and docs | FB1 first, then FB5, FB6, FB7, FB8, ST6, TV7, FB10 | S–M |
| R2 browser survives restarts | FB2, FB3, FB4, WS2 (persistent sessions), WS3, WS4 (per-subscriber queues); WS1 with its doc change | M–L |
| R3 fault isolation and SSDP hardening | HJ2, DC1, TV1, TV2, TV6, AC4 | M |
| R4 adapter races and routing | BU1, BU2, AC1, AC6, AC7, TV3, TV4, TV5 (TLS first), BU3, BU4, AC3 | M |
| R5 sources | HJ1, YW1, HJ3–HJ6, YW2, YW3, SP1, SP2 (+ SP3), SP4 (UID ids), SP5–SP8 | M–L |
| R6 storage, themes and the registry | ST1–ST4, DC2, DC3, DC4, DC5, AC8 | M |
| R7 device identity | AC2 | M |

### Owner's rulings (2026-10-03)

- **AC2 — yes.** An Android TV keeps its identity when it moves. A re-pair whose server certificate matches a
  registered device's `certificateFingerprint` lands on that entry (same id and keystore alias, new host). The device
  is also re-pointed when mDNS announces it elsewhere, after a pinned handshake at the new address. This becomes its
  own PR (R7).
- **TV5 — TLS first, with fallback.** webOS connects to `wss://host:3001` first and falls back to `ws://host:3000` only
  when the TLS port refuses. The fallback stays for old firmware. This goes in R4.
- **WS2 — yes, with an own store.** Logins survive restarts. The browser gets a random login token in a cookie of its
  own, and `/data/logins.json` keeps only the token's hash with the password version and an expiry, so nothing on
  disk logs anyone in and a crash loses nothing. (Hashing the session id instead, as first proposed, broke the
  parallel requests of a page loaded right after a restart: the first one would use up the entry.) This and the
  303-to-login fix for plain form posts go in R2.
- **SP4 — yes.** A sports item id is derived from the UID (plus `RECURRENCE-ID` for overrides), not the start time.
  Pins made under the old ids keep working (migration or fallback lookup). This goes in R5.
- **WS4 — yes.** Each event-stream subscriber gets its own bounded queue and sender. A subscriber whose queue
  overflows is dropped, so one stalled client delays nobody else. This goes in R2.

With the rulings, R2 holds WS1–WS4 and FB2–FB4, and AC2 moves out of R4 into R7.

## Open questions (unverified, worth a check)

- **Device fault text echoing a stream URL.** UPnP/Sonos SOAP fault text (`RendererCommands.run`) and custom Cast
  receiver error messages (`CastApps.reason`) go to the browser verbatim. A device that echoes the stream URL would leak
  a Jellyfin `ApiKey` into a toast. Redacting query strings in device fault text would close this whatever the device
  does.
- **Google `error_description` in the device flow.** `GoogleOAuthClient.exchangeCode` says never to echo Google error
  descriptions, but the device-flow `poll` shows `error_description` on Setup. The two paths should agree.
- **Tizen remote channel and older sets.** It always uses `wss` (`TizenMessages.java:38`); pre-2018 sets that offer
  only `ws://:8001` may not connect.
- **mDNS bind address.** `MdnsBrowser` may bind 127.0.1.1 on Debian hosts with host networking
  (`MdnsBrowser.java:111-118`).
- **Event-stream registration.** A `RuntimeException` from `devices.states()` or `rails.peek()` in
  `EventStreamController` would leave the emitter registered, with a growing early-send buffer.
- **TMDB provider-id environment variables.** `HOME_CONTROL_TMDB_PROVIDER_IDS_*` binding to a dashed map key is
  untested.
- **The `dependencies` CI job.** It has `contents: write`, runs Gradle on Dependabot PRs and does not wait for
  `checksums`. Does `dependency-submission` keep strict verification?
- **Workflow rail order.** `SourcesSetupController.moveRail` computes the order outside `prefs.update`, so two
  near-simultaneous moves can lose one.
- **YouTube searches.** A search is charged before the request, but `SearchService` cancels at 8 s while YouTube's
  request timeout is 15 s, so a slow search burns quota and is never cached.
