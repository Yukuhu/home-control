# Dynamic Dashboard workflows

Date: 2026-09-22

Status: Accepted by the user on 2026-09-22.

## Purpose and agreed scope

Let a household create Dashboard entries from a configurable sequence:

```text
Fetch URL A → Parse JSON → Map fields → Build URL B → Chromecast
```

The user confirmed that URL B is a URL template, not a webpage to automate, and
that both of these modes are required:

- **Single tile:** show a saved title and artwork; fetch fresh values when Play
  is pressed.
- **Generated tiles:** fetch a JSON array, turn its entries into Dashboard
  tiles, and fetch fresh values for the selected entry when Play is pressed.

The approved interface is an ordered step editor under **Setup → Workflows**,
with nested field mappings, encoded URL substitutions, a masked test preview,
and errors identifying the failed step. Playback targets the device currently
selected on the Dashboard.

URL B is assumed to be a direct media address that the Cast receiver can fetch.
This version supports one HTTP GET returning JSON followed by one Cast action.
Multiple upstream requests, POST bodies, browser automation, scripts, branches,
pagination, automatic casting, custom Cast receivers, DRM, and media proxying
are outside this version. The steps have a required dependency order; the editor
edits their settings and mapping rows without introducing a node graph.

## User experience

Setup gains a Workflows link and section following existing source styling. It
lists saved workflows with their mode, enabled state, and Edit, Test, Enable /
Disable, and Remove controls. A dedicated editor at `/setup/workflows/new` or
`/setup/workflows/{id}` keeps long forms off the main Setup page. It works on
phones, uses visible labels and keyboard-accessible controls, and retains
non-secret draft fields after a validation error.

The editor presents these settings in order:

1. **Dashboard:** workflow name, single/generated mode, and video/audio kind.
   Single mode accepts a tile title, optional subtitle, and optional artwork.
2. **Fetch JSON:** HTTP(S) URL A and optional static request headers. Parsing
   JSON is automatic and is shown as a separate stage in test results.
3. **Choose entries:** generated mode supplies an array pointer and pointers
   for stable ID, title, optional subtitle, and optional artwork. Single mode
   uses the whole response document as its field-mapping context.
4. **Map fields:** named variables with a JSON pointer, root/item context, and
   a Sensitive switch, on by default. Add and remove mapping rows in the UI.
5. **Build media URL:** URL B template and explicit media type, with presets
   for HLS, DASH, MP4, MP3 and an editable MIME type. An extension is not required.
6. **Action:** Chromecast on the Dashboard's selected device. Saving a workflow
   never starts playback.

Save validates the definition without fetching the upstream service. **Test**
uses the saved definition, fetches once, displays stage results and sample tiles,
and builds sample media URLs without sending anything to a device. In generated
mode the preview shows at most five entries and the total entry count. The button
explains that it fetches fresh data, so testing can request a new upstream token.
The ordinary Dashboard play-sheet preview is separate and makes no workflow HTTP
requests. No GET page visit starts a test or playback.

The Workflows content source offers one named Dashboard row per enabled workflow,
including a row containing one tile for single mode. Existing Dashboard settings
control row order/visibility and the source refresh interval, defaulting to
15 minutes. A workflow Disable switch removes its row and prevents playback;
Dashboard row hiding only controls visibility, matching existing preferences.
Single-mode row refreshes read local metadata without fetching URL A.

## JSON selection and URL construction

Use JSON Pointer notation already supported by the application's JSON tree API:
`/data/items`, `/auth/token`, `/streams/0/quality`, and the empty pointer for the
document root. The editor includes examples and explains `~0` and `~1` escaping.
There are no expressions, wildcard selectors, or executable code.

Generated mode's array pointer is relative to the response root. Its metadata
pointers are relative to each array element. Each variable separately selects
the response root or current entry as its context, so a shared token can be
combined with an entry's media ID. Single mode exposes only root context.

For example, URL A may return:

```json
{
  "auth": {"token": "example-token"},
  "channels": [
    {"id": "news", "title": "News", "quality": "hd"},
    {"id": "music", "title": "Music", "quality": "sd"}
  ]
}
```

With array `/channels`, ID `/id`, title `/title`, and these mappings:

| Variable | Context | Pointer | Sensitive |
|---|---|---|---|
| A | Entry | `/id` | No |
| C | Root | `/auth/token` | Yes |
| D | Entry | `/quality` | No |

the template `https://media.example/play?id={A}&token={C}&quality={D}`
produces a News tile whose test preview is
`https://media.example/•••?id=news&token=•••&quality=hd`
(the saved literal path is masked as described below).
The real token is substituted only in server memory and passed to the Cast
receiver on an explicit Play action.

Variables have unique, case-sensitive names matching
`[A-Za-z][A-Za-z0-9_]{0,31}`. A mapping must select a non-null string, number, or
boolean. Missing values, objects, and arrays are errors; they never become the
strings `null` or an empty fallback. Numeric and boolean values use their JSON
scalar spelling. Strings are interpreted as raw values, not pre-encoded URLs.

Placeholders may occur in path segments and query values, including within a
value such as `file-{A}.mp4`. They cannot change the scheme, host, port, query
parameter names, or URL fragment. Each substituted value is UTF-8 percent-encoded
as a component: reserved delimiters, spaces, percent signs, and slashes cannot
alter URL structure. Reject dot-only path segments after substitution to avoid
path traversal. Literal URL portions must already form a valid encoded URI;
unknown placeholders and unmatched braces are save-time errors. URL B must be
HTTP(S), have a fixed host, and contain neither userinfo nor a fragment. Validate
the MIME type as a parameter-free `type/subtype`, at most 100 ASCII characters;
it is a declaration for the receiver, not a promise of codec compatibility.

Generated entry IDs must be nonempty strings or integral numbers and unique
within the selected array. Preserve array order. Missing/duplicate IDs or invalid
required titles fail the refresh with an entry index and field name. Names and
titles are nonempty and limited to 120 characters; subtitles to 240. Optional
metadata may be absent; an invalid artwork URL is omitted with a test warning.
Artwork uses only public HTTPS addresses without userinfo or query strings, or
the existing tile placeholder. Artwork requiring credentials is out of scope.
Titles/subtitles/artwork are explicitly public display fields and never inherit
a sensitive variable automatically.

## Runtime architecture

Add a cohesive `sources.workflows` module following the existing source,
configuration, setup-advice, controller, and storage-service patterns. Separate
definition validation, HTTP fetching, JSON mapping/template construction,
catalog snapshots, and test-result redaction into focused components. Reuse
Jackson, `ContentSource`, `RailCache`, `ContentChangedEvent`, and the existing
Cast sender; no workflow platform or scripting runtime is required.

`WorkflowContentSource` has source ID `workflows`. `rails()` is local-only.
`rail(workflowId)` reads local single-tile metadata or fetches the generated
catalog, returning display metadata and an opaque workflow reference. Generated
catalog snapshots contain only display metadata, public item keys, and definition
revision; raw JSON, credentials, variable values, and resolved media URLs are
discarded after the operation.

Single tile IDs use the workflow ID. Generated tile IDs combine that ID with
SHA-256 of the typed upstream stable ID, encoded as hexadecimal. Thus changes
to title, list order, or token do not change a tile's identity. String `"1"` and
integer `1` are distinct upstream IDs. Never use a row index as identity.

`item(itemId)` performs no HTTP requests. It returns saved single-mode metadata
or an item from the most recent published generated catalog. An unavailable
snapshot returns no item; the Dashboard must refresh before that item can be
opened. Generated refresh failures retain the last successful snapshot and use
the existing row failure/retry UI. Revision checks prevent a stale snapshot from
executing an edited definition.

### Defer workflow execution until Play

Both `ContentSource.item()` and `PlayableResolver` are called by route previews
today, so neither may execute the playback workflow. Introduce:

- `PlayableRef.WorkflowCast(workflowId, revision, entryKey)` containing only
  opaque identifiers, with no resolved URL or credentials.
- A matching `Route.WorkflowCast` and pure strategy requiring `CAST_RECEIVER`.
- A `WorkflowCastRouteExecutor`, dispatched through the existing executor
  mechanism from `PlaybackService.execute()`.

The route description is “Cast with the Default Media Receiver” and its stable
browser-visible key is `workflow-cast`. Update exhaustive route handling,
including route keys and playback dispatch. Workflow items carry only this
reference; they cannot fall back to opening an app, DLNA, or a Bluetooth speaker.
This also ensures a merged Android TV/Cast device uses its Cast receiver.

Execution does the following once per explicit Play request:

1. Verify the source/workflow is enabled, the saved revision still matches,
   and the selected device can Cast before fetching.
2. Fetch URL A and parse its JSON under the configured limits.
3. In generated mode, find exactly one entry matching the stable entry key in
   the fresh response. A removed or ambiguous entry fails without casting.
4. Extract required values, build and validate URL B, and create a Cast LOAD
   using `CastLoads.defaultMediaReceiver` and the configured MIME type/title.
5. Recheck definition revision/enabled state at dispatch and send through
   `DeviceManager` to the existing Default Media Receiver (`CC1AD845`).

A local immutable execution context owns its response and extracted values;
concurrent catalog refreshes cannot change them. A definition change/removal
observed before dispatch cancels the run with “Workflow changed; reopen this
item.” Mutation and final dispatch eligibility are serialized per workflow. An
edit after dispatch does not cancel a Cast already sent. Device capability and
connection errors use the current playback failure behavior. Do not automatically
retry a workflow or Cast action; a user retry resolves fresh values again.

The server does not fetch URL B. The Cast receiver fetches it, including any
media redirects or segments. An accepted LOAD is reported with the existing Cast
semantics and does not prove that the device can decode the media. The editor's
media-type help explains that browser pages, receiver-inaccessible addresses,
and streams needing custom HTTP headers cannot be played by this action.

## Persistence, credentials, and network boundaries

Store each complete, versioned definition as one encrypted `SecretStore` value
named `workflow.<workflowId>`. Read/list definitions through `SecretStore` and
coordinate authentication and mutations through `LoginService`. Derive the saved
list from these keys; no plaintext workflow file or separate manifest is needed.
One atomic secret-store replacement updates the entire definition and revision.
Create uses a stable `w-` ID followed by 12 random hexadecimal characters, checks
for collisions, and edit increments a positive revision.
Reject stale editor submissions rather than overwriting newer edits.

Because source URLs, headers, and URL templates can contain credentials, saving
the first workflow sets up the household password using the existing source-login
flow, even for a public feed. Later create/edit/test requests require that login.
The first Save includes password and confirmation when needed. Tests use a saved
workflow and therefore never create an unauthenticated fetch endpoint.

Stored request URLs, templates, and header values are not returned in plaintext
on GET editor pages. Show masked values and Keep/Replace controls; untouched
fields retain their saved values. Mapping definitions and non-secret display
metadata remain editable normally. Definition DTOs and exceptions must not print
secret fields. Test results show values only for variables explicitly marked
non-sensitive; sensitive values are masked before they reach the browser.
Preview URLs are rendered from the template with masked sensitive variables;
literal query values and literal path segments are masked too, while delimiters,
query names, and explicitly non-sensitive substitutions remain visible. Raw JSON
responses, cookies, and header values never appear in test output or logs.

Removal deletes the encrypted definition through `LoginService.removeSecrets`.
Disabling preserves it. Removing the last secret across the entire `SecretStore`
also removes the household login, following existing behavior. Removing the last
workflow preserves login if another source still has secrets. Publish
`ContentChangedEvent` after
successful mutations and invalidate workflow catalogs so Dashboard rows update
through the existing event stream. Reject unrecognized persisted schema versions
without overwriting data. An unreadable definition makes that workflow unavailable
with a sanitized Setup error and does not prevent other sources from loading.

Use a dedicated outbound fetch policy based on the existing calendar policy's
LAN-friendly behavior. Permit HTTP(S) and private LAN addresses. Reject userinfo,
fragments, unspecified, loopback, link-local, and multicast destinations by
default; an explicit server setting may allow loopback for local feeds. Validate
every resolved address and each redirect, and ensure connections use validated
addresses rather than an unchecked second DNS resolution. Final media URL
validation applies the same address restrictions, but makes no media request.

Fetch headers are static: no cookies from the Dashboard session and no automatic
cookie jar. Disallow Host, Cookie, transport/framing headers, proxy headers, and
hop-by-hop overrides. On a redirect follow only the same origin; cross-origin
redirects fail with an instruction to configure the final source URL, preventing
credential forwarding. Use strict TLS verification for HTTPS fetches.

Bound resource usage: at most 50 workflows, 16,384 characters per serialized
definition (the existing secret-value limit), 32 mappings, 16 headers, 200 catalog
entries, and a 2 MiB JSON response. Limit pointer length to 512 characters, JSON
nesting to 64 levels, and both configured and expanded URLs to 8,192 characters.
Use a 5-second connection timeout, 15-second total fetch/body deadline, and at
most three same-origin redirects within that deadline. Apply a shared limit of
four active workflow fetches; catalog refreshes, tests, and Play requests all
count. Reject oversized responses/catalogs instead of silently truncating them.
An exhausted fetch limit returns a clear busy result without an unbounded queue.

## Errors, module controls, and verification

Errors name the stage and safe context, for example “Map fields: C has no value
at /auth/token” or “Fetch JSON: server returned HTTP 403.” Do not echo upstream
response bodies, URLs, header values, parser excerpts, or exception causes that
can contain credentials. Validation errors remain in the editor; test failures
remain in the test panel; playback failures use `PlayAttempt.Failed`; generated
catalog failures use the existing stale-row/retry behavior. No error sends a
partial Cast command.

`home-control.workflows.enabled` defaults to true; the source is unavailable
until configured. Setting it false removes workflow UI, source, tests, and
execution while retaining saved definitions and their login requirement. Cast
module/device availability is checked separately. Workflows remain editable when
Cast is off, but playback previews explain that no Cast route is available.

Verification for the implementation must cover:

- JSON pointer escaping, root versus entry context, scalar handling, missing
  values, duplicate identities, stable tile IDs, and all resource limits.
- URL component encoding with Unicode, spaces, slashes, ampersands, percent
  signs, dot segments, repeated variables, and forbidden placeholder positions.
- HTTP timeout/body limits, redirect and address policy, header restrictions,
  redacted failures, and malformed/oversized JSON using local fixture servers.
- Atomic save/edit/remove, optimistic revision checks, restart persistence,
  first-source login, disabled modules, and keeping secrets out of responses.
- Single-mode Dashboard loads and all route-preview paths make zero workflow
  HTTP requests; generated refresh fetches once and preserves stale tiles on
  failure. Setup Test fetches but sends zero device actions.
- Play re-fetches once, selects the same stable entry from reordered/fresh JSON,
  substitutes a new token, and emits the exact expected Default Media Receiver
  LOAD. Wrong device, missing item, bad mappings, edits/removals, and upstream
  failures emit no Cast action. Concurrent refresh cannot mix execution values.
- Browser flows for creating/editing both modes, masked test output, Dashboard
  tile appearance, device selection, preview, and playback failure recovery,
  including a narrow phone viewport and keyboard use.

Run the relevant unit/web integration suites and the project's separate browser
task during implementation. A hardware acceptance check should exercise one
single tile and one generated tile against a real Cast device with a reachable
media URL; record that separately from automated fake-device tests.
