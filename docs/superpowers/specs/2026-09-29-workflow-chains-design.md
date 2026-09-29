# Workflow chains: several calls, date values, filters and a field picker

Date: 2026-09-29

Status: Accepted by the user on 2026-09-29.

Extends: [Dynamic Dashboard workflows](2026-09-22-dynamic-workflows-design.md). Everything that spec says still
holds unless this one changes it.

## Purpose and agreed scope

A workflow today is one HTTP GET returning JSON, a set of JSON-Pointer mappings typed by hand, a media URL template
and a Cast action. The user wants two improvements:

1. **Interactive field picking.** Enter a REST URL in the editor, fetch it, see the structured response and click the
   fields to use, instead of typing pointers.
2. **Several calls per workflow**, both in parallel (for example artwork from one API and the stream URL from
   another) and in series (a later call uses fields from an earlier one, such as a list followed by a detail call per
   entry).

The user also wants **date values in request URLs** (`from={today}`, `to={today+7d}`) and **filtering entries** after
the fetch (only entries starting today, only `status == "live"`). The API does the calendar work; the application
supplies dates and filters results. It does not implement a calendar of its own.

Agreed decisions:

| Question | Decision |
|---|---|
| Kinds of chains | A list followed by per-entry detail calls; independent APIs merged into one tile or list. |
| Calendar support | Date values in URLs and headers, and entry filters. |
| When a call runs | Derived from what uses it (see [Phases](#phases)), not chosen per call. |
| HTTP methods | GET only. No request bodies. |
| Picker values | Full response values are shown to a logged-in editor. Nothing is stored or logged. |
| Merging parallel results | Shared calls (values for every entry) and per-entry calls (a lookup per entry). No list join. |
| Filter power | A list of conditions with one all/any switch. No nested groups. |
| Model | An ordered list of calls. Parallel and serial execution follow from which variables a call uses. |

Out of scope: POST and other methods, request bodies, list joins between two array responses, nested filter groups,
pagination, sorting, scripts or expressions, a node-graph editor, and any action other than Cast. The end action
stays "build a media URL and Cast it on the selected device".

## Model (schema version 2)

A workflow definition holds:

- **Settings:** name, enabled, content kind (video/audio), mode (single tile or generated tiles) and a time zone.
  The time zone is an IANA ID; it defaults to the container's zone when empty.
- **Date values:** up to 16 named values. Each has an expression and a format.
  - Expressions: `now` or `today`, optionally followed by one offset of a signed integer and a unit `m`, `h`, `d` or
    `w`, such as `today+7d` or `now-2h`. `today` is midnight at the start of the current day in the workflow's time
    zone. Day and week offsets are calendar days, so they keep the wall-clock time across a daylight-saving change.
  - Formats: `ISO_DATE` (`2026-09-29`), `ISO_DATE_TIME` (offset date-time, `2026-09-29T20:15:00+02:00`),
    `UNIX_SECONDS`, `UNIX_MILLIS`, or a pattern of up to 64 characters in `java.time.format.DateTimeFormatter`
    notation, such as `yyyyMMdd`.
- **Calls:** an ordered list of 1 to 8 calls. Each call has:
  - a name matching `[a-z][a-z0-9_]{0,23}`, unique within the workflow;
  - a scope: `SHARED` (runs once per run) or `ENTRY` (runs once per entry, generated mode only);
  - a URL template and up to 16 header templates, which may use variables;
  - the variables it extracts: name, JSON Pointer, sensitive switch (on by default). A pointer is relative to the
    call's response root.
- **Entry source** (generated mode only): exactly one `SHARED` call is marked as the source of entries. It holds the
  array pointer; the ID, title, subtitle and artwork pointers relative to each entry; and the entry variables
  (name, pointer relative to the entry, sensitive switch).
- **Filters** (generated mode only): up to 16 rows and a switch for `ALL` or `ANY`. See [Filters](#filters).
- **Tile:** in single mode, a saved title, subtitle and artwork, as today; single mode makes no refresh requests, so
  these stay saved text. In generated mode, the entry source's pointers, or for subtitle and artwork the name of a
  variable a refresh produces instead, so a picture can come from a different API. Title and ID never come from a
  variable (see [Refresh](#refresh)).
- **Cast:** the media URL template and MIME type, unchanged.

### Variables

Variable names follow today's rule, `[A-Za-z][A-Za-z0-9_]{0,31}`, and are **unique across the whole workflow**:
date values, call variables and entry variables share one namespace. Templates use them as `{name}`, as today, so
existing templates keep working. At most 64 variables per workflow, counting date values.

A template may use:

- date values, anywhere;
- variables of calls **above** the template's own call in the list (for a call's URL and headers), or of any call
  (for tile fields and the Cast template);
- entry variables, only in `ENTRY` calls, generated-mode tile fields, filters and the Cast template.

A variable from a call below, an unknown variable, or a reference that would form a cycle is rejected at Save. The
ordering rule makes a cycle impossible; the check is kept as a safeguard.

A variable from an `ENTRY` call can be used by tile fields and the Cast template, and by later `ENTRY` calls.
It cannot be used by a `SHARED` call or a filter.

### Phases

Each call's phase is derived from what uses it and shown in the editor as a label:

- **Runs at refresh:** the call is needed, directly or through calls that depend on it, only by the entry source, a
  filter or a tile field. An artwork lookup is the typical case.
- **Runs at Play:** the call is needed only by the Cast template.
- **Runs at refresh and Play:** both. The entry source is always in this phase.
- **Unused:** nothing refers to the call. Save rejects an unused call, so a definition never makes a request whose
  result is ignored. A stored definition may still contain one: a v1 single-tile workflow whose media URL uses no
  mapping fetched its URL at Play anyway, so an unused call in a stored definition runs at Play, as before, until the
  workflow is next saved.

In single mode there is no refresh fetch, as today: single-tile metadata is saved text, so every call runs at Play.

A tile field may not name a variable marked sensitive: tile fields are public display text.

### Storage and migration

The definition stays one encrypted `SecretStore` value named `workflow.<id>`, with the 16,384-character limit.
Save rejects a larger definition with "This workflow is too large to save; remove calls, headers or variables."

`schemaVersion` becomes 2. The codec reads both versions:

- A **v1** definition becomes a v2 definition with one `SHARED` call named `main`, which carries v1's URL, headers
  and root-scoped variables. In generated mode that call is the entry source, and v1's entry-scoped variables become
  entry variables. Braces in v1 header values are doubled, because they are now template syntax. Time zone is empty, and there are no date values or filters. The derived phases match v1's
  behaviour exactly.
- The migrated definition is written as v2 on the next Save. Reading alone never rewrites it, following the rule that
  existing installs upgrade in place.
- An unknown version is refused without overwriting it, as today.

Changing an existing workflow's revision rules, IDs, or tile ID derivation is out of scope: a migrated generated
workflow keeps the same tile IDs.

## Running a workflow

Every run (refresh, Play or Test) is one immutable execution context, as today:

1. Record **now** once. All date values and date filters in the run use this instant.
2. Resolve the date values.
3. Work out the calls the run needs (see below) and their dependency graph from the variables each call's templates
   use.
4. Start each call as soon as every call it depends on has finished. Independent calls run in parallel. A failed
   call cancels the calls that have not started and fails the run, with the exception described in
   [Refresh](#refresh).

A call's URL template is expanded with the same rules as the Cast template today: variables may fill path segments
and query values, each value is percent-encoded as a component, and the scheme, host and port are fixed literals.
The expanded URL then goes through the existing address policy, pinned connection, redirect and TLS rules. Header
templates are expanded without encoding; a value containing CR, LF or another control character, or longer than
1,024 characters, fails the call.

### Refresh

Refresh applies to generated mode only; single mode reads saved metadata, as today.

1. Run the `SHARED` calls that run at refresh, including the entry source.
2. Select entries from the entry source's array, then apply the filters. Keep the array's order.
3. Apply the entry cap to the filtered list: **200**, or **50** when the workflow has an `ENTRY` call that runs at
   refresh. More entries fail the refresh with "The list has N entries after filtering; the limit is 50."
4. For each entry, run the `ENTRY` calls that run at refresh.
5. Build tiles. **Stable ID and title always come from the entry source**, so a tile's identity never depends on a
   second API and tile IDs keep today's derivation. Subtitle and artwork may come from variables.

When an `ENTRY` call fails for one entry, that entry's variable-based fields are left out: its subtitle is empty
and its artwork falls back to the placeholder. The refresh still succeeds. Test reports each such failure as a
warning. A `SHARED` call failure fails the refresh and keeps the last good snapshot, as today.

Catalog snapshots still hold only display metadata, entry keys and the revision. Values of `ENTRY` calls other than
display fields are discarded.

### Play

1. Check that the workflow is enabled, the revision matches and the device can Cast, as today.
2. Run every `SHARED` call the Cast template needs, directly or indirectly, freshly. A call that also ran at refresh runs
   again, so tokens are always fresh.
3. In generated mode, find the pressed entry again by its stable ID in the fresh entry source, and **evaluate the
   filters again**. An entry that is gone, ambiguous, or no longer passes the filters fails with "This item is no
   longer available; refresh the Dashboard."
4. Run that entry's `ENTRY` calls that the Cast template needs.
5. Build and check the media URL, recheck the revision, and send the Cast LOAD, as today. Nothing is retried.

### Test

Test runs a refresh (in generated mode, with the refresh deadline) and then the Play steps for up to five sample
entries (once in single mode, with the Play deadline), without sending
anything to a device. It reports one line per call and entry, such as `images · entry "News" · HTTP 404`, together
with the existing stages, the total entry count before and after filtering, and the masked sample URLs.

### Limits and concurrency

| Limit | Value |
|---|---|
| Deadline per call (connect, redirects and body) | 10 s (was 15 s) |
| Deadline per run | Play 20 s; refresh 60 s; Test uses each for its part |
| Workflow fetches at once, across all workflows | 8 (was 4) |
| Fetches at once for one refresh | 3 |
| Fetches at once for one Play or Test | 4 |

The global pool is today's semaphore, enlarged. A run waits for a free permit only until its own deadline. When
the deadline passes first, the run fails with "busy; try again later", so nothing waits beyond the request that
started it. The per-run caps stop one refresh with many entries from taking every permit while someone presses
Play. Existing limits stay: 2 MiB per response, JSON depth 64, 512-character pointers, 8,192-character URLs, three
same-origin redirects, 50 workflows.

## Filters

A filter row is `field` `operator` `value`:

- **field** is a pointer relative to the entry.
- **value** is literal text, a date value name, or a `SHARED` variable name. Operators that take no value ignore it.

| Type | Operators |
|---|---|
| Text | `equals`, `not equals`, `contains`, `is empty`, `is not empty` |
| Number | `=`, `≠`, `<`, `≤`, `>`, `≥` |
| Date/time | `before`, `after`, `between` (two values, inclusive start, exclusive end) |

Each row declares its type. For a date/time row, the row also names a format; the formats are the same as for date
values, and an ISO date-time without an offset is read in the workflow's time zone. Text comparison is exact and
case-sensitive; `contains` has an **ignore case** switch.

A field that is missing, null, or cannot be read as the row's type fails the row for that entry. Test reports how
many entries each row rejected for that reason, so a pointer mistake is visible and does not silently hide
entries: "Filter 2: `start` is not a date in 12 entries."

With `ALL`, an entry is kept when every row matches; with `ANY`, when at least one does. No rows keeps every entry.

## Editor and field picker

The editor stays one page at `/setup/workflows/new` and `/setup/workflows/{id}`, in this order: Settings, Date
values, Calls, Filters, Tile, Cast. Calls are cards that can be added, removed and moved up or down, and each card
shows its derived phase. Every pointer remains an editable text field, so the picker is a shortcut, not the only
way to fill them. The page still works without the picker for keyboard and screen-reader users.

### Try

Each call card has a **Try** button. It posts the unsaved editor form, the call's index and an editor-session token
to `POST /setup/workflows/try`:

- The request needs a logged-in session and passes the existing same-origin checks. When no household password
  exists yet, the editor asks the user to set one first, as the first Save does.
- The server validates the draft enough to run the call and the calls it depends on, and reports validation errors
  in the card.
- It runs the calls this one depends on, reusing results from the same editor session when their settings have not
  changed, then runs this call under the normal address policy, deadlines and fetch pool (it counts as a Test).
- An `ENTRY` call needs a **sample entry**, chosen from a list of the entries that passed the filters in the last
  Try of the entry source.
- The response goes back as a trimmed tree: strings cut to 200 characters, arrays showing their first 20 items and
  their length, depth capped at 64. Response headers are never returned.

Keep/Replace fields hold saved secrets, so the browser never receives saved URL or header values. The server fills
in the saved value for every field left on Keep.

### Try sessions

Earlier results are kept **server-side, in memory only**, in a per-editor session:

- It is bound to the login session and to a random token the editor page receives when it opens.
- A call's cached result is keyed by a hash of the call's effective settings, including the values of the variables
  it used; any change invalidates it and everything depending on it.
- A session expires after 15 minutes without a Try, when the workflow is saved, and when the login session ends.
  At most 16 sessions exist at once; the oldest is dropped first.
- Sessions are never written to disk, logged, or included in error messages. A restart discards them.

### Picker

The trimmed tree is rendered as text nodes only, never as HTML, in a collapsible view. Selecting a node offers:

- on a scalar: **Add as variable** (name suggested from the key, made unique, sensitive on); **Filter on this
  field**; and inside the entry list, **Use as ID / title / subtitle / artwork**;
- on an array in a `SHARED` call: **Use as entry list**, which marks the call as the entry source and sets the
  array pointer. Pointers picked inside that array afterwards are made relative to the entry.

The editor shows live feedback computed from the last Try results, in the browser, without new requests:

- the filter section shows "12 of 40 entries match";
- the Cast section shows the media URL built so far, with sensitive values and literal path and query parts masked
  as in Test.

On a phone the tree takes the full width under the card and collapses to one line when closed. Every node and
action can be reached by keyboard.

## Security

Unchanged: encryption at rest, the address policy with pinned addresses, same-origin redirects only, the forbidden
headers, strict TLS, Keep/Replace for secrets, masked Test output, and error messages that never contain URLs,
header values or response bodies.

New:

- **No credential forwarding through a response.** A variable in a call URL can fill path segments and query values
  only. The host of every call is a literal fixed at Save, so a value from one API cannot send another call's
  headers or token to a different server.
- **Header values from variables** are checked as described in [Running a workflow](#running-a-workflow).
- **Try shows response values.** This relaxes the previous rule that raw response values never reach the browser,
  for one endpoint and one audience: a logged-in household member editing a workflow, who configured these
  credentials in the first place. Test, the Dashboard, catalog refreshes, logs and errors keep masking as today.
- **Try fetches unsaved URLs.** This is a new way to make the server fetch an address. It requires login, uses the
  same address policy and fetch pool, and a Try request never sends anything to a device.

This change of rule and the v2 format are recorded in ADR 0003, written with the first implementation step.

## Errors

Errors name the call, the entry where there is one, and a safe reason. They never name a URL, a header value or a
response excerpt:

- `Call images · entry "News": server returned HTTP 404`
- `Call token: /access_token has no value`
- `Filter 2: start is not a date in 12 entries`
- `Call details uses {streamId}, which is defined by a call further down`

Validation errors stay in the editor, Try failures in the call card, Test failures in the test panel, Play failures
in `PlayAttempt.Failed` and refresh failures in the existing stale-row/retry display.

## Module switch

`home-control.workflows.enabled` covers all of this. No new flag.

## Verification

Unit tests:

- dependency graph, ordering rule and cycle safeguard;
- phase derivation, including unused calls and single mode;
- date expressions and formats, including time zones, daylight-saving changes and month ends;
- every filter operator and type, `ALL`/`ANY`, and unreadable fields counted rather than dropped silently;
- v1 → v2 migration, with an unchanged tile ID for a migrated generated workflow;
- URL and header expansion: encoding, host fixed, CR/LF rejected;
- size and count limits.

Integration tests with `FakeHttpServer`:

- independent calls run in parallel and dependent calls in order;
- one request per entry per `ENTRY` call at refresh, and the cap of 50;
- a failing `ENTRY` call gives placeholder artwork and a warning, while the refresh succeeds;
- a failing `SHARED` call keeps the last snapshot;
- Play re-fetches, re-filters, and refuses an entry that no longer passes;
- run deadlines, the per-run caps and the busy result;
- Try needs login, reuses cached results within a session, never returns saved secrets or response headers, and
  sends nothing to a device;
- single-mode Dashboard loads and route previews still make zero requests.

Browser tests (`scripts/e2e.sh`):

- build a two-call chain with the picker, add a date value and a filter, see the match count, save, Test, and Play
  on the fake Cast device;
- open a migrated v1 workflow and save it unchanged;
- the picker on a phone-sized viewport and by keyboard.

## Build order

Each step is shippable on its own:

1. **Model and runner:** schema v2, migration, several calls with derived phases, dependency-driven execution, new
   limits, the editor's call cards, and ADR 0003.
2. **Date values and filters:** the model fields, evaluation, re-filtering at Play, and their editor sections.
3. **Try and picker:** the endpoint, Try sessions, the tree view, picker actions and live feedback.
