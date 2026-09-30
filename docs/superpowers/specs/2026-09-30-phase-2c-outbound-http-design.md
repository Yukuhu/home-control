# Phase 2C: Outbound HTTP for Sources

**Status:** approved in conversation on 2026-09-30, section by section.

**Roadmap:** `docs/superpowers/specs/2026-09-27-architecture-roadmap-design.md`, workstream "2C: Outbound HTTP for
sources". Phase 0.1 (DNS pinning for calendars, the `VettedHttpClients` helper) and 0.4 (one deadline over the body,
`BoundedBody`) prepared it.

## Purpose

Six content-source clients talk to the network, each with its own request loop, about 1,900 lines in all:

| Client | Stack | Pins DNS | Address rules | Redirects | Content-Length first |
| --- | --- | --- | --- | --- | --- |
| `JellyfinClient` | JDK `HttpClient` | no | none | none; a 3xx is an error | yes |
| `TmdbClient` | JDK | no | none | none | no |
| `TheSportsDbClient` | JDK | no | none | none | no |
| `YouTubeHttp` (YouTube API, Google sign-in, Lounge) | JDK | no | none | none | yes |
| `CalendarFetcher` | Apache HttpClient 5 | yes | `CalendarUrlPolicy` | any origin, each hop re-checked | no |
| `WorkflowHttpClient` | Apache HttpClient 5 | yes | `WorkflowUrlPolicy` | same origin only | no |

The copies drifted apart:
- Only workflows unwrap IPv4-mapped IPv6 addresses (`::ffff:127.0.0.1`) before checking them.
- Only workflows have one deadline over the whole exchange. Calendars bound each redirect hop separately.
- Only workflows refuse compressed bodies. TheSportsDB drops the transport exception's cause because the URL
  contains its API key; TMDB attaches it.
- Four URL parsers check scheme, userinfo, query, fragment, port and length each in their own way.
- Five source exceptions each declare a `Kind` enum; they overlap, and only a few kinds are branched on.
- Twenty classes build their own `JsonMapper`; only the workflow one is hardened against hostile JSON.

After this workstream every source reaches the network through one `GuardedHttpClient` in `sources.http`: pinned to
the addresses one `OutboundAddressPolicy` approved, under one deadline, with one body cap and one set of error kinds,
and with messages that name only the host.

## Decisions (the user's, 2026-09-30)

- **Two PRs.** PR 1 builds the client, the address policy, the URL parser and the shared error kinds, and moves
  calendars and workflows onto them. PR 2 moves Jellyfin, TMDB, TheSportsDB and YouTube, deletes `BoundedBody`, adds
  the `JsonMapper` bean and makes the new ArchUnit rules strict.
- **One address policy, loopback per source.** Every source is refused any-local, link-local and multicast addresses
  and may reach the LAN. Jellyfin may reach loopback; the others may not unless their existing allow-loopback setting
  says so.
- **Approach A: one configured client, used by composition.** No base class; each source builds a client from a
  profile and keeps its own exception type.

## Constraints

- Behaviour stays the same unless this spec names the change (the visible changes below).
- Secrets never reach a message, a log or the browser: API keys in paths and queries, bearer tokens, calendar secret
  paths and workflow header values.
- Device adapters, SSDP discovery (`DeviceFetch`) and Sonos keep their own transports: LAN device protocols trust the
  network differently.
- Module switches keep working: a switched-off source builds no client.
- Tests sit in the package of the code they test; fakes build on `testsupport.FakeHttpServer`.
- Frozen ArchUnit violations are never refrozen. The new rules start strict.

## Design

### 1. The shared package, `sources.http` (PR 1)

**`OutboundAddressPolicy`** decides where a source may connect.
- `InetAddress[] addresses(String host)` resolves the host (through an injectable resolver, for tests) and returns
  its addresses when every one is approved; if any is refused it throws `BLOCKED`, as the workflow policy always did
  (a mixed answer prevents any connection). An IP literal is checked the same way. (Amended during PR 2's review:
  the first wording kept the approved addresses of a mixed answer.)
- Always refused: any-local (`0.0.0.0`, `::`), link-local (`169.254.0.0/16`, which includes cloud metadata, and
  `fe80::/10`) and multicast. LAN (site-local and unique-local) addresses are allowed.
- Loopback is allowed only when the policy was built with `allowLoopback`.
- An IPv4-mapped IPv6 address is unwrapped and judged as the IPv4 address it carries.
- It replaces `WorkflowUrlPolicy.addresses`/`literal` and `CalendarUrlPolicy.addresses`.

**`HttpUrls.parse(String raw, Rules rules)`** returns a `URI` or throws `IllegalArgumentException` with a reason a
person can act on. Every rule set requires http or https, a non-blank host without a `%` zone, no userinfo and a port
from 1 to 65535. `Rules` holds what legitimately differs:

| Caller | Query | Fragment | Dot segments | `webcal` → https | Max length |
| --- | --- | --- | --- | --- | --- |
| Calendars | allowed | allowed | allowed | yes | 2,048 |
| Workflow call URLs | allowed | refused | allowed | no | 8,192 |
| Workflow templates | allowed | refused | refused | no | 8,192 |
| Jellyfin server address | refused | refused | allowed | no | none, as today |

Jellyfin's normalizer keeps stripping trailing slashes before parsing. It replaces `CalendarUrlPolicy.parse`,
`WorkflowUrlPolicy.parse`, `JellyfinClient.normalizeServerUrl`'s checks and `WorkflowTemplate.validUri`. Two checks
stay separate because they answer another question: `WorkflowJson.safeArtworkUri` vets a link the browser fetches
(no DNS), and `HostAllowlist`/`CrossOriginGuard` vet inbound requests.

**`GuardedHttpClient`** is built once per source from a `Profile` and a failure factory:
- `Profile(String name, boolean allowLoopback, Redirects redirects, int maxRedirects, int maxBytes,
  Duration connectTimeout, Duration deadline, int maxConcurrent)`, with `Redirects` one of `NONE`, `SAME_ORIGIN` and
  `CHECKED`.
- The failure factory, `Function<OutboundFailure, RuntimeException>`, lets each source throw its own exception type
  (`JellyfinException`, `WorkflowException`, …), so every existing `catch` keeps working.
- `Response send(OutboundRequest request)`: `OutboundRequest` carries the method (GET or POST), URI, headers, an
  optional body and content type, an optional lower body cap for that call, and whether to wait for a free slot or
  fail at once when all `maxConcurrent` are busy. `Response` carries the status, the content type, the body and the
  response headers.
- It runs on Apache HttpClient 5 through the Phase 0.1 helper, so it connects only to the addresses the policy
  approved, and TLS still verifies the host name.
- **One deadline** (the profile's) runs from `send` until the body is read: waiting for a slot, DNS, connecting, every
  redirect and the whole body. The work runs on a virtual-thread worker that is cancelled at the deadline; `close()`
  cancels every exchange in flight. This is `WorkflowHttpClient`'s worker model, generalized, and it keeps that
  client's queueing: a caller with a deadline of its own (a workflow run, through `OutboundRequest.endingBy`) may wait
  for a slot until its own deadline, and once admitted its exchange ends within the profile's deadline or its own,
  whichever comes first. (Amended during PR 1's review.)
- **Body:** it asks for no compression and refuses a `Content-Encoding` other than identity. A Content-Length above the
  cap fails before any of the body is read; otherwise it reads at most the cap plus one byte.
- **Redirects:** only GET follows them. `NONE` returns the 3xx to the caller. `SAME_ORIGIN` follows within the same
  scheme, host and port, and refuses another origin. `CHECKED` follows to any origin, re-parsing each `Location` with
  the source's `HttpUrls` rules and resolving it through the policy again. Both stop after `maxRedirects`.
- **Failures** reach the factory as `OutboundFailure(Kind kind, String host, String reason)`; `describe(name)` gives
  the default message. No failure carries a cause, because JDK and Apache exceptions can carry the URI.

| Failure | Kind | Default message |
| --- | --- | --- |
| DNS, connect or I/O failure | `UNREACHABLE` | Could not reach {name} at {host} ({reason}) |
| Deadline passed | `UNREACHABLE` | Could not reach {name} at {host} (no answer in time) |
| Address refused, other-origin redirect | `BLOCKED` | Home Control does not connect to {host} ({reason}) |
| Body over the cap | `TOO_LARGE` | {name} at {host} sent more than {cap} |
| Compressed body, too many redirects, a redirect without a destination | `BAD_RESPONSE` | {name} at {host} sent a response Home Control cannot read ({reason}) |
| No free slot, fail-fast | `RATE_LIMITED` | Home Control is busy talking to {name}; try again in a moment |

A source may add its own advice (Jellyfin's "Check the address and that Home Control can reach it."). Workflows turn a
failure into a `FETCH`-stage `WorkflowException` whose detail is the reason.

**`Statuses.kindOf(int status)`** maps an unsuccessful status: 401 and 403 → `UNAUTHORIZED`, 404 and 410 →
`NOT_FOUND`, 429 → `RATE_LIMITED`, 5xx → `SERVER_ERROR`, anything else → `BAD_RESPONSE`. A source overrides only its
real exceptions: TheSportsDB's "api key" error text, YouTube's quota and permission cases of 403, Jellyfin's 3xx.

**`ContentSourceException.Kind`** (in `core.content`): `INVALID_INPUT, NOT_CONFIGURED, UNREACHABLE, BLOCKED,
UNAUTHORIZED, REVOKED, FORBIDDEN, NOT_FOUND, RATE_LIMITED, QUOTA_EXHAUSTED, SERVER_ERROR, BAD_RESPONSE, TOO_LARGE`.
`ContentSourceException` gains `kind()` and constructors that take it.

`VettedHttpClients` and `BoundedBody` become details of the client; `BoundedBody` is deleted in PR 2 with its last
user.

### 2. The sources (PR 1: calendars and workflows; PR 2: the rest)

| Source | Loopback | Redirects | Body cap | Deadline |
| --- | --- | --- | --- | --- |
| Jellyfin | allowed | `NONE`; a 3xx still says "enter the final server address" | 2 MB JSON, 10 MB images (per call) | `request-timeout` |
| TMDB | refused | `NONE` | 2 MB | `request-timeout` |
| TheSportsDB | refused | `NONE` | 2 MB | `request-timeout` |
| YouTube and Google sign-in | refused | `NONE` | 2 MB | `request-timeout` |
| Calendars | `sports.calendar.allow-loopback` | `CHECKED`, up to `max-redirects` | `max-bytes` | `request-timeout`, for the whole chain |
| Workflows | `workflows.allow-loopback` | `SAME_ORIGIN`, up to `max-redirects` | `max-bytes` | `request-timeout`, with `max-concurrent-fetches` and Try/Test's fail-fast |

Each source keeps what is protocol, not transport: Jellyfin's `MediaBrowser` authorization header; TMDB's bearer token
or `api_key`; TheSportsDB's key in the path and its "api key" error-text check; YouTube's bearer token, its retry
after a 401, its 403 quota and permission cases, the OAuth forms and the Lounge framing; the calendar's `webcal`
rewrite and ICS parsing; workflows' header deny-list, templates and stages. `YouTubeHttp` and the request loops of
`JellyfinClient`, `TmdbClient`, `TheSportsDbClient`, `CalendarFetcher` and `WorkflowHttpClient` shrink to building
requests and reading responses. All source traffic uses HTTP/1.1, as calendars and workflows already do.

**Visible changes:**
1. Jellyfin, TMDB, TheSportsDB and YouTube connect only to addresses the policy approves, and to exactly those. A
   Jellyfin server at a link-local `169.254.x.x` address stops working; a public API host that DNS points at loopback,
   any-local or link-local is reported as blocked rather than as unreachable. (PR 2)
2. A calendar's redirect chain shares one deadline instead of one per hop. (PR 1)
3. Calendars refuse an IPv4-mapped loopback address (`::ffff:127.0.0.1`) unless loopback is allowed, as workflows
   already do. (PR 1)
4. TMDB treats 410 like 404: an empty result instead of an error. (PR 2)
5. Transport failures read the same in every source (the table in section 1). Messages about statuses and content
   keep their wording. (PR 1 for calendars and workflows, PR 2 for the rest)

### 3. Errors and JSON

**Kinds.** `JellyfinException`, `TmdbException`, `TheSportsDbException`, `YouTubeException` and
`CalendarFetchException` stay, as thin subclasses of `ContentSourceException` without their own `Kind` enums; every
`catch` keeps working (PR 1). Kinds that nothing branches on fold in, and their messages keep the detail:

| Old kind | New kind |
| --- | --- |
| Jellyfin `NOT_JELLYFIN`, `UNSUPPORTED_VERSION` | `BAD_RESPONSE` |
| Jellyfin `USER_NOT_FOUND` | `UNAUTHORIZED` |
| Calendar `NOT_A_CALENDAR` | `BAD_RESPONSE` |
| YouTube `SEARCH_LIMIT` | `QUOTA_EXHAUSTED` |

`REVOKED` and `QUOTA_EXHAUSTED` stay because YouTube's sign-in and subscriptions feed branch on them. `ErrorAdvice`
still answers 502 for any source error. `WorkflowException` keeps its own type and `Stage`; `LoungeException` is
unchanged.

**Nothing leaks.** The client writes every transport, deadline, size and address message itself, from the source's
name and the host, and attaches no cause. A test per source sends each failure kind through its client with a secret
in the path and in the query and asserts that neither the secret nor the path appears in the message or anywhere in
the cause chain.

**One `JsonMapper` (PR 2).** A `JsonMapper` bean in `config` replaces the mappers the sixteen classes outside
`adapters` build today: the source clients and stores, `JellyfinStreams`, `JellyfinVlcExecutor`, `QuotaLedger`,
`LoungeClient`, `WorkflowCodec`, `JsonFileDeviceRegistry`, `JsonFileSourceSettings`, `SecretStore` and
`VersionedJsonFile` (whose owners pass the bean in). It carries the workflow mapper's stream limits for every
response and file: nesting depth 64 and numbers of at most 1,000 characters. `WorkflowJson` derives its mapper from
the bean with `rebuild()` and adds exact decimals. The four adapter mappers (`CastPayloads`, `MpvIpc`, `SsapMessages`,
`TizenMessages`) stay: device protocols are outside 2C, and three of them have no Spring-free protocol package yet
(3E).

### 4. Delivery

**PR 1, `refactor/source-http`, from main `98d7a13`.** One commit per step:
1. `ContentSourceException.Kind`; the five source exceptions adopt it and lose their enums.
2. `OutboundAddressPolicy` and `HttpUrls`; the calendar, workflow, Jellyfin and template parsers and the two address
   policies move onto them.
3. `GuardedHttpClient`, `OutboundRequest`, `Response`, `OutboundFailure` and `Statuses`.
4. Workflows onto the client.
5. Calendars onto the client (visible changes 2 and 3).
6. The ADR "Outbound HTTP for content sources" and the architecture guide's `sources` row.

**PR 2, from main after PR 1.** One commit per step:
1. TMDB and TheSportsDB onto the client (visible change 4).
2. Jellyfin onto the client.
3. YouTube (`YouTubeHttp`) onto the client; `BoundedBody` deleted (visible change 1 complete).
4. The `JsonMapper` bean.
5. Strict ArchUnit rules: inside `sources`, only `sources.http` uses `java.net.http` or `org.apache.hc`; outside
   `adapters`, only `config` calls `JsonMapper.builder()`.
6. The guides: the architecture guide's measures, the security guide (what sources may reach, the two allow-loopback
   settings), and the changelog-facing commit messages for the visible changes.

## Testing

- `OutboundAddressPolicyTest`: a table of IPv4, IPv6, IPv4-mapped and literal addresses, with loopback allowed and
  refused.
- `HttpUrlsTest`: the replaced parsers' cases, per rule set.
- `GuardedHttpClientTest`, against `FakeHttpServer`:
  - DNS rebinding: a resolver whose answer changes after the check, and the client still connects only to the checked
    address;
  - each redirect mode: other origin refused, each hop re-checked, the hop limit, a POST's 3xx returned;
  - the body cap with and without Content-Length; a compressed body refused;
  - one deadline over a slow redirect chain and over a trickled body;
  - the concurrency limit, fail-fast, and `close()` cancelling exchanges in flight;
  - every failure's message names only the host and carries no cause.
- A secret-leak test per source (section 3).
- `SlowBodyDeadlineTest` keeps driving every source end to end, workflows included.
- Existing source tests stay green; only the named visible changes alter them, and each has its own test.
- Each PR runs `scripts/gradle.sh build`, `scripts/e2e.sh -Pe2eBrowsers=chromium`, a final whole-branch review, and CI.

## Measures

| | Before | After |
| --- | ---: | ---: |
| HTTP request loops in sources | 6 | 1 |
| Source address policies | 2 | 1 |
| Outbound URL parsers | 4 | 1 |
| Source error-kind enums | 5 | 1 |
| `JsonMapper` builders | 20 | 5 (the bean and four adapter mappers) |
| Frozen ArchUnit violations | 42 | 42; the two new rules are strict |

## Out of scope

- Device adapters' transports, `DeviceFetch` and Sonos's LAN check (2D and 3E).
- The sports feed restructuring (3D), which builds on this.
- Making `WorkflowException` or `LoungeException` a `ContentSourceException`.
- The workflow artwork link check and the inbound host checks.
- HTTP/2 for sources.
