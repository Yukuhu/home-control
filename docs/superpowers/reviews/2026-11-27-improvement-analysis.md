# Improvement analysis (2026-11-27)

Base: `main` at 3932f9b (2026-11-26). The analysis ran on f9f9de9; every proposal was then re-checked against 3932f9b,
which added 41 commits, among them 26 fixes on 2026-11-26.

Outcome: **161 proposals** survived verification, 126 of them distinct once duplicates across areas are merged. At
3932f9b, 151 are still open, 8 are partly fixed and 2 are fixed. Seven more were rejected by the verifiers. The full
list, with problem, proposal, trade-offs and evidence for each, is in [the
catalogue](2026-11-27-improvement-catalogue.md).

The 2026-10-03 review looked for defects. This one asks what to improve next. Defects count, but so do design, missing
capabilities, operations, the release policy and developer experience.

## How this was done

- Eleven read-only analyses ran in parallel, one per area. Each one measured its area against the product and
  architecture rules in `AGENTS.md` and the ADRs. Each was told not to report findings of the 2026-10-03 review again
  unless the current code still has them, and to check that review's open questions that fall in its area.
- For every area, a second agent re-read the cited code and tried to refute each proposal. It confirmed, revised or
  rejected each one, and could add up to three issues the first analysis missed (ids `<area>-V<n>`).
- A synthesis pass merged duplicates across areas, graded each area, ranked priorities and built the roadmap. It
  spot-checked the five highest priorities against the code, and all five held.
- The checkout turned out to be 41 commits behind `origin/main`. Every proposal was re-checked against 3932f9b, and its
  evidence was re-pointed to the line numbers there. Three of the top priorities below were independently checked again
  at 3932f9b: `MdnsBrowser.bindAddress`, the bare `ENTRYPOINT` and the release step's `skip-on-empty`.
- Nothing was built or run, and no device was touched. Release dates, Dependabot PR counts, CI job timings and the Maven
  Central 429 failures (BUILD-1, BUILD-3, BUILD-6, BUILD-V1) were read from the GitHub API by the analysts and were not
  checked again.
- Ids are `<area>-<n>`:

  | Prefix | Area |
  | --- | --- |
  | ARCH | Architecture, modularity and code health |
  | SEC | Security |
  | DEV | Device adapters, discovery and device management |
  | SRC | Content sources, rails, search and playback planning |
  | DATA | Storage, configuration, upgrades and data lifecycle |
  | WEB | Web layer, frontend UX, accessibility and PWA |
  | OPS | Performance, resource use and operability |
  | TEST | Testing strategy and test quality |
  | BUILD | Build, CI/CD, release, supply chain and packaging |
  | DOCS | Documentation and developer experience |
  | PROD | Product capabilities and feature opportunities |

- Effort: S < 2 h, M < 1 day, L a few days, XL more than a week.

## Scorecard

| Area | Grade | In one line |
| --- | --- | --- |
| ARCH | A | Compiler- and ArchUnit-enforced modules, sealed exhaustive models and a composed adapter toolkit. Held back by the unmerged R6 registry fixes and a few seams that match on strings. |
| TEST | A | 4,366 JVM and 119 browser tests, fakes that speak the real protocols, shared contexts enforced by rule and 97% line coverage. Missing: whole-install upgrade tests and generated or hostile inputs. |
| SEC | B | A strong web edge (host allowlist, Fetch-Metadata guard, exact-URI gate, hashed remembered logins, AES-GCM). Credentials still leave through device fault text and stream URLs, and the packaging runs as root with default capabilities. |
| DEV | B | An excellent session toolkit and bounded I/O. But mDNS binds to loopback on the main target hosts, device identity rests on IP addresses, Cast follows any announcement, and the Android TV UNPAIRED latch never heals. |
| SRC | B | A sound guarded client and a pure planner. The gaps are policy and coverage: pinned stream links lose their stream, series cannot be found by search, six of nine streaming services have no route, and search waits for its slowest source. |
| DATA | B | `AtomicFiles` and `VersionedJsonFile` are exemplary. What surrounds them is thin: no snapshot before one-way upgrades, no backup guide, and stored preferences that fail on read and lock out the dashboard. |
| WEB | B | Strong accessibility basics, a rebuilt per-tab SSE stream and progressive enhancement. The daily details fall short: rails jump on every refresh, there is no hold-to-repeat, toasts may go unannounced, and setup gives little feedback. |
| BUILD | B | Outstanding supply-chain mechanics. But the release policy never ships dependency or base-image fixes on their own, Gradle runs with a `contents: write` token, and Dependabot churn is high. |
| DOCS | B | Accurate, well-linked guides and ADRs. The configuration reference is incomplete and unchecked, three setup labels are stale, there is no troubleshooting or update page, and `docs/superpowers` swamps searches. |
| PROD | B | Covers what the concept promised. Missing are the everyday features that decide whether a household keeps using it: text input, an app launcher, idempotent power, scenes, an automation API and readable names. |
| OPS | C | An economical runtime. But the container defaults (no OOM policy, no `TZ`, no log rotation) and the lack of failure logging or diagnostics make problems in the field hard to see and hard to recover from. |

## Summary

Home Control is in good health. Its engineering is better than most appliances of its size:
- `core` and `protocols` are Gradle modules the compiler enforces, and 20 strict ArchUnit rules have no frozen
  violations.
- The playback planner is pure and built on sealed types.
- One guarded outbound client pins DNS and keeps one deadline per request.
- Every `/data` file is written atomically, through versioned migrations.
- The fakes speak the real protocols, and coverage is 97% leaving out generated code.
- CI pins every action by SHA, verifies dependency checksums strictly and publishes only image digests that passed the
  smoke tests.

Most of what remains is not structural. It falls into three groups: defects at the edges, items from the 2026-10-03
review that were accepted but never landed, and missing everyday capabilities.

The biggest risks, in order:

1. **Discovery on the main target hosts.** On Debian, Ubuntu and Raspberry Pi OS with host networking, the host name
   resolves to 127.0.1.1. `MdnsBrowser.bindAddress` passes that loopback address straight to jmDNS, so Shield and Cast
   discovery fails, or works only in part, and nothing in the log says why (DEV-1).
2. **Fixes in dependencies do not ship.** Dependabot commits as `build(deps)`, which the release step does not count as
   releasable. A security fix in a library or in the JRE base image waits for the next unrelated `fix:` before users on
   `latest` or CasaOS get it (BUILD-1).
3. **Secrets can reach the browser in device text.** UPnP `errorDescription`, Cast receiver messages and SSAP
   `errorText` go to the toast verbatim. Jellyfin stream URLs carry `ApiKey=`, so a renderer that echoes the URL in its
   fault shows the token on screen. That breaks the rule that secrets never reach the browser (SEC-1).
4. **Registry changes are not atomic (R6, never merged).** Merge and split still write twice, so a failure between the
   writes loses a pairing. Forget holds the registry lock across a BlueZ call (timeout 45 s), and TV keys stay in plain
   text in `devices.json` while their module is off (ARCH-1).
5. **One stale preference locks out the dashboard.** Stored preferences are validated strictly on every read. A renamed
   streaming-provider key, a downgrade or a hand edit makes `/` and `/setup` fail. Since 523459b they answer 503 with
   the store's message instead of 500, but Setup, the page that could fix it, still cannot open (DATA-V1, DATA-3).
6. **An `OutOfMemoryError` leaves a zombie.** The JVM has no OOM policy. Session loops or the rail ticker die while
   `/health` keeps answering 200, so `restart: unless-stopped` never acts (OPS-2).

Daily use has two clear pain points:
- Every background rail refresh re-renders each rail twice and resets its scroll position and focus (WEB-1).
- The remote has no hold-to-repeat, and keyboard auto-repeat floods the device (WEB-2). The dropped taps reported by
  WEB-V1 were fixed by 89e8d65 on 2026-11-26.

Several high-value fixes are small: the mDNS bind address, the Dependabot commit prefix, the redaction helper, the
Android TV UNPAIRED latch, the OOM flag, lenient preference reads, the missing LG volume slider and playable pinned
stream links.

On the product side, the highest-leverage additions all fit the product rules, as long as commands stay ephemeral
(nothing scheduled, nothing retried after the response):
- a token-authenticated local API, so Home Assistant and Shortcuts can keep working once a login exists;
- idempotent power on and power off (today Power toggles on LG and Samsung);
- typing text and launching apps on TVs, which the protocol code already supports;
- stable device identity across DHCP changes, which the owner ruled on in October (AC2) and nobody built.

## What changed on main while this ran

The fixes of 2026-11-26 overlap the analysis in ten places:

| Proposal | Status at 3932f9b | What is left |
| --- | --- | --- |
| [WEB-V1](2026-11-27-improvement-catalogue.md#web-v1) remote taps dropped by htmx's queue | fixed by 89e8d65 | — |
| [WEB-15](2026-11-27-improvement-catalogue.md#web-15) every request takes the theme catalog's lock | fixed by 8e64157 | — |
| [WEB-2](2026-11-27-improvement-catalogue.md#web-2) remote keys | partly fixed | Hold-to-repeat, keyboard auto-repeat (no `event.repeat` check), ordering of concurrent presses, haptics. |
| [DATA-3](2026-11-27-improvement-catalogue.md#data-3) damaged `sources.json` | partly fixed by 523459b | 503 with a reason now; no startup preflight, Setup still cannot open, the ticker logs every tick. |
| [DATA-7](2026-11-27-improvement-catalogue.md#data-7) / [OPS-6](2026-11-27-improvement-catalogue.md#ops-6) theme catalog | partly fixed by 8e64157 | ST2 (the lock) is fixed. ST1 (asset bytes held in heap and cloned per response) and the staging clean-up remain. |
| [DEV-8](2026-11-27-improvement-catalogue.md#dev-8) SSAP monitor across the pointer socket | partly fixed by aab4fe8 | `close()` no longer waits; `button()` is still synchronized with no single-flight pointer open. |
| [TEST-6](2026-11-27-improvement-catalogue.md#test-6) wire formats pinned through shared codecs | partly fixed by 04928a4 | The BlueZ client contract and fixture provenance notes. |
| [ARCH-14](2026-11-27-improvement-catalogue.md#arch-14) dependency overrides | partly fixed by 432fbd2 | No check fails when an override falls below the managed version. |
| [DOCS-11](2026-11-27-improvement-catalogue.md#docs-11) small contradictions | partly fixed by 432fbd2 | `perf:` is documented but refused, the PR template says `./gradlew`, stale measures. |

## Top priorities

In order. The ranking weighs daily use, the risk of data loss, lock-out or leaked secrets, and maintainability gained
per hour of work.

| # | Proposal | Impact | Effort | Why now |
| --- | --- | --- | --- | --- |
| 1 | [DEV-1](2026-11-27-improvement-catalogue.md#dev-1) Choose the mDNS interface by routing, not `getLocalHost`, and make it configurable | high | S | Discovery quietly fails on the main target hosts. `MdnsBrowserTest` pins the bypass. Fold in OPS-1's log line and TEST-1's loopback tests. |
| 2 | [BUILD-1](2026-11-27-improvement-catalogue.md#build-1) Release dependency and base-image updates | high | S | Configuration only: commit Gradle and Docker bumps as `fix(deps)`, keep Actions bumps as `build`, and allow a manual patch release. |
| 3 | [SEC-1](2026-11-27-improvement-catalogue.md#sec-1) Redact credentials from device failure text | medium | S | Enforces a product rule at one choke point (`DeviceCalls`, `RendererCommands`). Pin it app-wide with TEST-V1's canary secrets. |
| 4 | [ARCH-1](2026-11-27-improvement-catalogue.md#arch-1) Atomic registry changes, no I/O under the lock, keys encrypted with modules off | medium | M | The one path that loses data or keeps a plaintext secret. Closes DC2, DC3, DC4 and ARCH-V1 in one PR. |
| 5 | [WEB-1](2026-11-27-improvement-catalogue.md#web-1) Rails keep their scroll position and focus across refreshes | high | M | Rails are the main browsing surface, and a 5-minute Jellyfin interval resets them all the time. Fix DC5 (ARCH-2) in the same pass. |
| 6 | [WEB-2](2026-11-27-improvement-catalogue.md#web-2) Hold-to-repeat and ordered keys on the remote | high | M | The remote is the core interaction. Collapse presses into `repeat=n` with at most one request in flight. That is a per-page buffer, not a queue. |
| 7 | [DATA-V1](2026-11-27-improvement-catalogue.md#data-v1) Read stored preferences leniently, write them strictly | medium | S | A provider rename in a release locks out every affected install. Pair it with what remains of DATA-3. |
| 8 | [OPS-2](2026-11-27-improvement-catalogue.md#ops-2) `-XX:+ExitOnOutOfMemoryError` in the `ENTRYPOINT` | medium | S | One line. Ship it with or after DATA-7's heap fix, so a large theme import cannot turn into a restart loop. |
| 9 | [DEV-7](2026-11-27-improvement-catalogue.md#dev-7) A time-based, self-healing Android TV UNPAIRED latch | medium | S | After a power cut a booting Shield is latched UNPAIRED until Home Control restarts, and the user is told to pair again for nothing (AC5). |
| 10 | [OPS-3](2026-11-27-improvement-catalogue.md#ops-3) Failed plays and commands leave a redacted line in the log | high | M | Every bug report starts with logs that say nothing. Build it on SEC-1's redactor. |
| 11 | [BUILD-4](2026-11-27-improvement-catalogue.md#build-4) Keep Gradle off the `contents: write` token | medium | S | The dependency-graph job runs Gradle with verification off under a write token. Split it into a read-only generate job and a write-only submit job. |
| 12 | [DATA-1](2026-11-27-improvement-catalogue.md#data-1) Snapshot `/data` before a new version's first start | medium | L | CasaOS and Watchtower users update unattended, and the one-way migrations leave no way back. Ship the backup guide (DATA-2) and the reset warning (DATA-9) first, both S. |
| 13 | [DATA-7](2026-11-27-improvement-catalogue.md#data-7) Serve theme assets from disk | medium | M | What is left of ST1: on a 1 GB host, themes are held twice in heap and every asset response clones its bytes. |
| 14 | [DEV-3](2026-11-27-improvement-catalogue.md#dev-3) Stable device identity across address changes | high | L | A new DHCP lease creates a second device. Start with the AC2 ruling (re-pair by certificate fingerprint, M), then Rename and Change address on Setup. |
| 15 | [PROD-1](2026-11-27-improvement-catalogue.md#prod-1) A documented local API with per-integration tokens | high | L | Once a login exists nothing outside the browser can drive the app. Land it together with idempotent power (PROD-2's first step). Needs its own threat review and ADR. |

### Quick wins

High value at effort S, in addition to those above:
- [PROD-V1](2026-11-27-improvement-catalogue.md#prod-v1): show the volume slider for an LG TV on its own.
- [SRC-3](2026-11-27-improvement-catalogue.md#src-3): pinned links to media files keep their stream.
- [DATA-9](2026-11-27-improvement-catalogue.md#data-9): say that the login reset also deletes every workflow.
- [OPS-5](2026-11-27-improvement-catalogue.md#ops-5): `X-Accel-Buffering: no` on the event stream.
- [DATA-2](2026-11-27-improvement-catalogue.md#data-2): a backup, restore and move guide.
- [BUILD-2](2026-11-27-improvement-catalogue.md#build-2) and [BUILD-3](2026-11-27-improvement-catalogue.md#build-3):
  the pre-1.0 breaking-change rule, and grouped Dependabot updates with a cooldown.
- [SEC-5](2026-11-27-improvement-catalogue.md#sec-5): drop capabilities and set `no-new-privileges` in the manifests.
- [WEB-7](2026-11-27-improvement-catalogue.md#web-7) and [WEB-9](2026-11-27-improvement-catalogue.md#web-9): toasts
  that are announced and can be dismissed, and a confirm step for destructive setup actions.
- [SEC-2](2026-11-27-improvement-catalogue.md#sec-2): stop echoing Google's `error_description`.
- [OPS-10](2026-11-27-improvement-catalogue.md#ops-10): a troubleshooting guide.
- [ARCH-2](2026-11-27-improvement-catalogue.md#arch-2), [ARCH-6](2026-11-27-improvement-catalogue.md#arch-6),
  [DATA-12](2026-11-27-improvement-catalogue.md#data-12), [SEC-12](2026-11-27-improvement-catalogue.md#sec-12),
  [DOCS-11](2026-11-27-improvement-catalogue.md#docs-11).

## Roadmap

Work packages sized as one PR or a few. "After" names a package that should land first.

| Package | Goal | Proposals | Effort | After |
| --- | --- | --- | --- | --- |
| Discovery on the real LAN interface | mDNS picks a routed, non-loopback interface, honours a documented `home-control.discovery.interface` and logs its choice. Android TV discovery uses `MdnsService.host()`. | DEV-1 | S | — |
| Release what ships | Gradle and Docker bumps release as `fix(deps)`. A `workflow_dispatch` input can force a patch release. `check-commits` accepts `perf`. A breaking change bumps the minor below 1.0. Dependabot groups updates and waits a cooldown. | BUILD-1, BUILD-2, BUILD-3, DOCS-11 | M | — |
| Secrets stay on the server | One text redactor in `core` applied in `DeviceCalls` and `RendererCommands`, with device text length-capped. The device flow stops echoing `error_description`. An `error.html` replaces Whitelabel pages. A canary-secrets test walks the routes and the SSE snapshot. | SEC-1, TEST-V1, SEC-2, WEB-5 | M | — |
| Registry writes atomic (R6) | `DeviceRegistry.replace` does merge and split in one write. Forget runs its I/O after the lock. An always-on helper migrates TV keys and removes orphaned secrets, named in ADR 0002. ArchUnit bans registry writes outside `device`. | ARCH-1, ARCH-6 | M | — |
| Rails keep their place | A refresh marker toggles a class instead of re-fetching the rail. Swaps restore `scrollLeft` and the focused tile. DC5 is fixed under the lock. A browser test covers it. | WEB-1, ARCH-2 | M | — |
| Remote keys that never drop or flood | Presses made while a request is in flight collapse into `repeat=n`. D-pad and volume repeat while held, and keyboard auto-repeat goes through the same path. LG gets its slider. Toasts sit in live regions and can be dismissed. | WEB-2, PROD-V1, WEB-7 | M | — |
| Stored preferences cannot lock out the UI | Lenient reads (unknown providers dropped) and strict writes. Stores read at startup. Rail moves computed inside `update()`. Entries skipped on read are moved aside, not deleted. | DATA-V1, DATA-3, DATA-6, DATA-13 | M | — |
| Survive memory pressure | `ExitOnOutOfMemoryError`, theme assets served from disk, staging directories cleaned at start, causes logged. | OPS-2, DATA-7 | M | — |
| Device sessions heal themselves | A time-based UNPAIRED latch with a slow probe. webOS waits for the Allow prompt the TV shows. The SSAP pointer socket opens single-flight. Bluetooth Stop pre-empts a play in flight. | DEV-7, DEV-V1, DEV-8, DEV-13 | M | — |
| Failures leave a trace | Exceptions keep their causes. One redacted INFO line per failed command or play. A security audit logger for sign-in and pairing. One startup line lists the modules that are on. Write failures say when `/data` is full. | OPS-3, SEC-8, DATA-12, DATA-8 | M | Secrets stay on the server |
| Deployment defaults and hardening | SSE anti-buffering headers. `TZ` and log rotation in the compose files. `cap_drop: ALL`, `no-new-privileges` and a read-only root filesystem on the non-Bluetooth manifests, with the smoke test using the same flags. Shorter in-memory sessions. | OPS-5, OPS-7, SEC-5, SEC-6 | S | Survive memory pressure |
| CI trust boundaries | Gradle never runs with a write token. Retry on Maven Central 429s. Tighter cache keys. Decide whether SonarCloud gates releases. A complete `.dockerignore`. | BUILD-4, BUILD-V1, BUILD-7, BUILD-6, BUILD-15 | M | — |
| Upgrade and rollback safety net | The reset warning, a backup, restore and move guide, hard-linked snapshots before each one-way migration (newest three kept), and boot tests on whole old `/data` directories. | DATA-9, DATA-2, DATA-1, TEST-2 | L | Stored preferences cannot lock out the UI |
| Docs a household can follow | A troubleshooting and network page. A configuration reference checked against the configuration metadata. Stale setup labels fixed and guarded. CasaOS metadata refreshed (the app id stays). ADRs for the October rulings. | OPS-10, DOCS-1, DOCS-3, DOCS-10, DOCS-7 | M | Discovery, Failures leave a trace |
| Device identity that survives DHCP | AC2's fingerprint re-pair, Rename and Change address on Setup, Cast re-pointed only after an address check or the user's confirmation. | DEV-3, DEV-4, PROD-5 | L | Registry writes atomic |
| Diagnostics on Setup | A login-gated System section: version, time zone, free space, modules, mDNS and SSDP sockets, services heard, each connection's state and last failure, a Reconnect button and a redacted "Copy support info". CI records startup time, RSS and image size. | OPS-4, OPS-11 | L | Failures leave a trace, Secrets stay on the server |
| Local automation API with safe power | Idempotent `PowerOn` and `PowerOff` in `core` for Android TV, webOS and Tizen. Then control-scoped bearer tokens stored as hashes, thin JSON reads, `docs/user/api.md`, and revocation on reset or password change. | PROD-2 (step 1), PROD-1 | L | — |
| Type and launch apps on the TV | Text input on webOS and Tizen first and Android TV last, never logged. App lists and `LaunchApp` from what the device reports, plus a curated Android TV catalogue. Channel, source and search keys. Cast pause and resume. | DEV-10, DEV-11, DEV-5 | L | — |
| Content that plays where it should | Pinned media links keep their `StreamUrl`. Jellyfin series are found by name and play the next-up episode. Start over. Search sections load per source. On-demand YouTube searches are not cancelled before their transport deadline. Workflow Play gets its own slots. | SRC-3, SRC-4, SRC-12, SRC-11, SRC-1, SRC-V1, ARCH-8 | L | — |
| Bluetooth and CasaOS without root | The Bluetooth image runs as uid 1000 with `group_add` and a documented D-Bus policy drop-in, validated on Raspberry Pi OS and Debian. CasaOS gets an opt-in privilege drop. | SEC-4, BUILD-10 | M | Deployment defaults and hardening |
| Guards that fail closed | ArchUnit rules for sockets, `URL.openStream` and DNS lookups, each exception named. Adapter lists discovered, not kept by hand. Curated Error Prone checks and `@GuardedBy`. Wall-clock assertions replaced. `FullAppReset` fingerprinted. | ARCH-7, ARCH-15, ARCH-13, TEST-10, TEST-12, TEST-13 | M | — |
| Household features after the API | Scenes run on request with one total deadline. A "Played from Home Control" history. Albums, playlists and series through device-native queues. Workflow tiles on speakers. Trust on first use for TV TLS. | PROD-2, PROD-12, PROD-6, PROD-8, SEC-3 | XL | Local automation API, Device identity |

## Cross-cutting themes

1. **Unfinished business from 2026-10-03.** The fix wave of 2026-10-03 landed most of R1 to R5. R6 (registry, rail
   cache, themes) and R7 (AC2) did not: DC2 to DC5, ST1, ST3, ST4, AC5, AC8 and AC2 are still in the code, together with
   the rail-order race from the open questions (ARCH-1, ARCH-2, DATA-6, DATA-7, DEV-3, DEV-7). ST2 was fixed on
   2026-11-26. These items are already specified and accepted, so they are the cheapest to finish.
2. **Secrets leave through text nobody wrote.** The web edge is strong, but text from devices and from Google reaches
   toasts and Setup verbatim, and Jellyfin's long-lived token travels inside stream URLs. One redactor at the choke
   points, plus a test with canary secrets across the whole app, turns the rule into a check (SEC-1, TEST-V1, SEC-2,
   WEB-5, SEC-11).
3. **Discovery and identity.** Discovery depends on `getLocalHost`. Identity is mostly the IP address, so a new lease
   means a second device. Cast follows any unauthenticated announcement, unlike the policy UPnP and Sonos enforce
   (DEV-1, DEV-3, DEV-4, DEV-V2).
4. **Upgrade, rollback and containment.** Upgrades in place work. Rollback relies on a manual copy that CasaOS and
   Watchtower users never take. Strict re-validation on read turns one stale key into a lock-out, and entries skipped on
   read are deleted for good by the next write (DATA-1, DATA-2, DATA-3, DATA-V1, DATA-13, TEST-2).
5. **Operability.** A failed play or command leaves nothing in the log, reach failures sit at DEBUG, an OOM leaves a
   healthy-looking zombie, SSE stalls behind nginx, and nothing in the app shows discovery or connection state (OPS-2,
   OPS-3, OPS-4, OPS-5, OPS-7, OPS-10).
6. **The release policy undoes good mechanics.** The supply-chain machinery is excellent, but dependency fixes never
   release on their own, a breaking-change marker fails CI, and Gradle runs unverified with a write token in one job
   (BUILD-1, BUILD-2, BUILD-4, BUILD-V1).
7. **Guards that fail open.** Some rules are checked by hand-kept lists or file by file: registry writes, low-level
   `java.net` use, adapter lists in tests, the documented configuration, `FullAppReset`'s completeness. Each is safer as
   a structural check (ARCH-6, ARCH-7, ARCH-15, DOCS-1, TEST-12).
8. **Everyday capabilities.** Text input, an app launcher, idempotent power, readable names and an automation API cost
   little given the existing extension points, and decide whether a household keeps using the app (DEV-10, DEV-11,
   PROD-1, PROD-2, PROD-5).

## Strengths to keep

- Module boundaries the compiler enforces, plus ArchUnit rules that are all strict and each carry a reason. Module
  independence is checked on the live bean graph (`noSwitchableModuleNeedsAnotherModulesBean`, `ModulesOffTest`).
- Sealed `Action`, `Route` and `PlayableRef` with exhaustive switches, and `SourceRef`/`DelegatedRoute` as deliberate
  extension points. The planner is pure.
- The adapter toolkit (`SessionLoop`, `Reconnector`, `ConnectionSlot`, `StatePublisher`), `DeviceConnections`'
  generations and turns, and `Enrollment`'s rule that no I/O runs under the registry lock. An analyst proposed one
  `PushSession` abstraction over the four push sessions. The verifier rejected it: their differences are deliberate
  protocol fixes.
- `GuardedHttpClient` (DNS pinning, one deadline, body cap, credentials stripped on a redirect to another origin) stays
  as it is. Reusing pooled connections in it was rejected (OPS-12).
- `AtomicFiles` and `VersionedJsonFile`: owner-only temp files, fsync, atomic rename, refused newer versions, redacted
  migration backups.
- The web edge: a host allowlist, the Fetch-Metadata cross-origin guard, an exact-URI login gate, remembered logins
  stored as hashes and bound to the password version, Argon2id on bounded platform threads, and trusted proxies by
  address only.
- Fakes that speak the real protocols, a shared-context rule enforced by a test, very few sleeps, and Playwright on
  three engines with CSP violations failing the test.
- Every action pinned by SHA, strict dependency verification with the automatic checksum flow (ADR 0008), native
  multi-arch builds, provenance attestations, and releases that re-tag the smoke-tested digests.
- Docs read by tests are treated as code, and every relative link and anchor in the guides resolves.

## What not to do

- **Time-triggered scenes, sleep timers, "at kick-off" automations, or retrying a scene after the response.** These are
  deferred commands, which "commands are ephemeral" rules out. Leave scheduling to Home Assistant or cron calling the
  PROD-1 API.
- **Web Push or any cloud relay for notifications** (PROD-14, rejected). This breaks the LAN-appliance rule.
- **A server-driven "next track" loop for plain UPnP renderers** (part of PROD-6). The server would issue later commands
  on its own. Use device-native queues only.
- **`hx-sync="queue all"` for remote buttons.** It builds an unbounded client-side queue of stale presses. Collapse
  presses into `repeat=n` with at most one request pending.
- **An unencrypted `/data` export from the browser.** It sends every household credential to the browser. Any export
  needs a passphrase, the login, the password entered again, and an ADR.
- **A one-time claim code for the first password** (SEC-7, rejected). A large onboarding cost against a prank-level
  threat that `HOME_CONTROL_RESET_LOGIN` already recovers from.
- **Proxying Jellyfin media through the appliance to hide the token** (SEC-11's option b). L to XL, an amendment to
  ADR 0005, and media bytes through a Raspberry Pi. Document the API-key risk instead.
- **Catching `OutOfMemoryError` while loading themes.** It is unreliable and contradicts OPS-2. Stop holding theme bytes
  in the heap instead.
- **`LOCAL_AUDIO_SINK` routes for media URLs chosen by a workflow or the API.** mpv fetches outside the guarded client,
  so a feed could point the server at internal addresses.
- **Pruning the rail-order keys of switched-off modules.** Modules can be switched back on.
- **Changing any name that never changes** while refreshing metadata: the CasaOS id `dev.andre.shield-remote`, the
  client package name `dev.andre.shield`, the `shield.*` keys.
- **Full UI localisation, Roku or Kodi adapters, or per-person profiles before there is demand** (WEB-12, PROD-15,
  PROD-11, PROD-13). Each carries ongoing upkeep, and an adapter nobody can test on hardware cannot be kept honest.
- **Removing the checksum gate from Gradle jobs to save about 50 s** (part of BUILD-6). ADR 0008 requires it on purpose.

## Open questions and limits

- **Not reproduced on hardware.** These outcomes were inferred from the code and the protocols:
  - mDNS with a 127.0.1.1 bind on a real Pi (DEV-1);
  - whether a real renderer or Cast receiver echoes the stream URL in a fault (SEC-1);
  - pre-2018 Samsung sets that offer only `ws:8001` (DEV-2, PROD-10);
  - whether LG's Allow prompt stays up while the socket is open (DEV-V1);
  - the Shield's boot window (DEV-7);
  - nginx buffering SSE (OPS-5);
  - half-open SSE after a phone sleeps (WEB-4);
  - Bluetooth with `group_add` and a D-Bus policy (SEC-4).
- **Runtime footprint was not measured.** Heap use, startup time on a 1 GB arm64 host, and what an AOT cache or a
  layered image would gain (OPS-9, BUILD-8). Take OPS-11's measurement before tuning `MaxRAMPercentage` or the GC.
- **CasaOS assumptions.** That installs are keyed on the app id and not the title, and that `$TZ` is provided. Check
  both on a CasaOS host before DOCS-10 and OPS-7.
- **Security boundaries.** PROD-1 (tokens, their interplay with the cross-origin guard, scopes) and PROD-7 (an encrypted
  backup download) need a threat review and an ADR of their own. Their effort assumes that review finds nothing
  structural.
- **Screen readers.** The toast live region (WEB-7) and the rail re-renders were reasoned from the markup, not tried
  with VoiceOver or NVDA.
- **Jellyfin search semantics** behind SRC-4 rest on Jellyfin's documented behaviour. Record a fixture from a real 10.9+
  server first.
- **Ordering risk.** OPS-2's `ExitOnOutOfMemoryError` turns a large theme import into a restart until DATA-7 lands. Ship
  them together, or DATA-7 first.
- **Thin coverage.** The theme package beyond ST1 to ST4 and ARCH-11, the sports ICS engine beyond TEST-4, and the
  YouTube Lounge (Cast) path.
