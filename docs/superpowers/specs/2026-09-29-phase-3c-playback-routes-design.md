# Phase 3C: Playback Routes

**Status:** approved in conversation on 2026-09-29, section by section.

**Roadmap:** `docs/superpowers/specs/2026-09-27-architecture-roadmap-design.md`, workstream "3C: Playback routes", and
finding 4 ("device and playback contracts leak"). The roadmap left one decision to this spec: the extension point for
source-specific references and routes.

## Purpose

`core.playback` knows every source:
- Six of its ten `PlayableRef` variants and five of its eleven `Route` variants are Jellyfin-, YouTube- or
  workflow-only, and each is built by exactly one source. Adding `WorkflowCast` touched seven files outside its module.
- `CastMessage` is defined three times: as an `Action`, a `PlayableRef` and a `Route`.
- Seven of the nine route strategies repeat one loop: required capability, then the first reference of a type, then a
  route. Their preference order is a hand-written list in `HomeControlConfiguration`.
- The planner is called twice when nothing routes: once for the routes and once for the reason.
- Route keys and "optimistic" are a switch in `RouteKeys`, away from the routes.

After this workstream:
- core defines only the shared references and device routes, plus two open interfaces that sources implement;
- one generic strategy and one preference ladder plan every route;
- the planner answers with one `Plan`;
- each route knows its key;
- an ADR records where device control from sources belongs.

## Decisions (the user's, 2026-09-29)

1. **Open subtypes are the extension point.** Core keeps sealed types for what it shares, and adds two non-sealed
   interfaces that source modules implement: `SourceRef` and `DelegatedRoute`. Sources contribute their own
   `RouteStrategy` beans.
2. The design sections below (types, planning, execution, delivery) were approved as presented.

## Constraints

- **What plays where, and in which order, does not change.** Unroutable reasons keep their wording.
- **Route keys do not change.** The browser sends them back to skip routes: `app-link`, `cast:<app>`,
  `cast-message:<app>`, `render`, `local-audio`, `jellyfin-session`, `jellyfin-app`, `jellyfin-vlc`,
  `youtube-lounge`, `workflow-cast`, `unroutable`.
- **No `/data` format changes.**
- References and routes that may carry a token keep printing without it.
- `./gradlew build` stays green, the test count only rises, and frozen ArchUnit violations only fall.

## Design

### 1. Types

**`PlayableRef`**
- A sealed interface over the shared references `AppLink`, `StreamUrl`, `CastLoad` and `CastMessage`, and one open
  branch, `SourceRef`.
- `SourceRef` is a non-sealed interface for a source's own references. Besides `kindLabel()`, it declares
  `unroutableReason()`: the sentence the planner shows when nothing routes it, for example "the Jellyfin app cannot be
  started on this device".

**`Route`**
- A sealed interface over `DeviceRoute`, `DelegatedRoute` and `Unroutable`.
- Every route has `key()`, the stable browser-visible identifier, and `optimistic()`, which defaults to false. It also
  has `describe()` and `describe(ContentKind)`.
- `RouteKeys` goes.
- `DeviceRoute` is a sealed interface over the five device routes: `OpenAppLink`, `Cast`, `CastMessage`, `Render` and
  `PlayLocally`. Each keeps its record, its description and its token-safe `toString`, and gains `Action action()`.
  `OpenAppLink` is optimistic.
- `DelegatedRoute` is a non-sealed interface for routes a source's `RouteExecutor` runs. It adds `source()`, the
  source's display name ("Jellyfin", "YouTube", "Workflows"), for the "switched off on this server" message.

**`CastMessage` is defined once**
- `Action.CastMessage(receiverAppId, namespace, message)` is the only definition of the message.
- `PlayableRef.CastMessage(Action.CastMessage message, String receiverLabel)` and `Route.CastMessage(Action.CastMessage
  message, String receiverLabel)` carry it with the label a person sees.

**What moves out of core**

| Source | References (`SourceRef`) | Routes (`DelegatedRoute`) |
| --- | --- | --- |
| Jellyfin | `JellyfinItem`, `JellyfinSession`, `JellyfinApp`, `JellyfinVlc` | session, app, VLC |
| YouTube | `YouTubeLounge` | Lounge |
| Workflows | `WorkflowCast` | workflow Cast |

### 2. Planning

- **The preference ladder.** `RouteStrategy` gains `Rung rung()`. A new enum, `Rung`, in `core.playback` holds the
  order of spec §5.3, and only there: `NATIVE_APP`, `APP_LINK`, `CAST_APP`, `CAST_MESSAGE`, `CAST_LOAD`, `CAST_STREAM`,
  `RENDERER`, `LOCAL_SINK`.
- **Sorting.** `PlaybackPlanner` sorts its strategies by rung, keeping the given order within a rung, so the order they
  arrive in does not matter. Only source strategies share a rung (workflow Cast and YouTube Lounge, both `CAST_APP`).
  An item comes from one source, so the two never compete.
- **The generic strategy.** `RefStrategy<R extends PlayableRef>` is a record of five parts: rung, needed capability,
  reference type, a filter, and a mapper from the reference and the item to a route. It routes the first reference of
  its type that passes the filter, on a device with the capability.
- **Core's six instances.** `RouteStrategies` builds them:

  | Strategy | Rung | Needs | Routes |
  | --- | --- | --- | --- |
  | `appLink()` | `APP_LINK` | `APP_LINK` | `AppLink` → `OpenAppLink` |
  | `castMessage()` | `CAST_MESSAGE` | `CAST_RECEIVER` | `CastMessage` → `Route.CastMessage` |
  | `castLoad()` | `CAST_LOAD` | `CAST_RECEIVER` | `CastLoad` → `Cast` |
  | `castStream()` | `CAST_STREAM` | `CAST_RECEIVER` | `StreamUrl` → `Cast` on the Default Media Receiver |
  | `renderer()` | `RENDERER` | `MEDIA_RENDERER` | `StreamUrl` → `Render` |
  | `localSink()` | `LOCAL_SINK` | `LOCAL_AUDIO_SINK` | http(s) audio `StreamUrl` → `PlayLocally` |

- **Source strategies.**
  - YouTube Lounge and workflow Cast become `RefStrategy` beans in their modules, at `CAST_APP`.
  - Jellyfin's strategy stays its own class, because it picks among VLC, an open session and starting the app. It
    moves into the Jellyfin module at `NATIVE_APP`.
  - The nine strategy classes in core go.
- **Wiring.** The planner bean takes every `RouteStrategy` bean. `HomeControlConfiguration` declares core's six, and
  each module's configuration declares its own. A switched-off module contributes none.
- **`Plan`.** `planner.plan(item, capabilities)` returns `Plan(List<Route> routes, String reason)`, where `reason` is
  null when a route exists. `PlaybackService.plan`, `preview` and `attempt` use that one `Plan`. The wording stays as
  today: resolver notes first, then one reason per reference. Shared references keep their fixed sentences, and a
  `SourceRef` gives its `unroutableReason()`.

### 3. Execution

- `PlaybackService.execute` handles three cases:
  - a `DeviceRoute` is sent with `commands.execute(device.id(), route.action())`;
  - a `DelegatedRoute` goes to the `RouteExecutor` registered for its `key()`;
  - `Unroutable` throws as today.
- `RouteExecutor` becomes `Set<String> keys()` and `void execute(DelegatedRoute route, Device device)`:

  | Executor | Keys |
  | --- | --- |
  | Jellyfin route executor | `jellyfin-session`, `jellyfin-app` |
  | Jellyfin VLC executor | `jellyfin-vlc` |
  | YouTube Lounge executor | `youtube-lounge` |
  | Workflow Cast executor | `workflow-cast` |

- Two executors that claim one key fail startup.
- A delegated route without an executor throws `UnroutableException("<device>: <source()> is switched off on this
  server")`, today's message.

### 4. ADR 0004: device control from sources

- **A content service's own playback API stays in its source,** as a `DelegatedRoute` and a `RouteExecutor`. Jellyfin
  sessions and the YouTube Lounge are such APIs.
- **Anything that commands a device goes through `DeviceCommands`,** as an `Action` or a receiver question, and never
  through a protocol client inside a source:
  - workflow Cast sends a `CastLoad`;
  - Jellyfin wakes a device and launches its app with `OpenAppLink`;
  - the Lounge asks the Cast receiver through `query`.
- The ADR records today's behaviour; no code moves for it.

## Delivery

One PR, `refactor/playback-routes`, from main `d48144e`. One commit per step, the build green after each:
1. `key()` and `optimistic()` on `Route`; `RouteKeys` goes.
2. The `DeviceRoute`/`DelegatedRoute` split, `DeviceRoute.action()`, and executors found by key.
3. `CastMessage` defined once.
4. `Plan`: one planning pass, and the reason comes with it.
5. `Rung`, `RefStrategy` and `RouteStrategies`, the planner sorting by rung, and the strategy beans wired.
6. The Jellyfin, YouTube and workflow references, routes, strategies and tests move into their modules, with
   `SourceRef.unroutableReason()`.
7. ADR 0004 and the architecture guide's `core` row.

## Testing

- **Keys:** one test pins every route's key and `optimistic()`, so the browser-visible keys cannot drift.
- **Planner:**
  - it builds the same plan whatever order the strategies arrive in;
  - `Plan.reason` is null when a route exists, and otherwise carries today's wording;
  - a `SourceRef`'s reason appears in it.
- **`RefStrategy`:** it needs its capability, takes the first reference of its type that passes the filter, and
  ignores other types.
- **`PlaybackService`:**
  - a device route is sent through `DeviceCommands`;
  - a delegated route reaches the executor for its key;
  - a missing executor gives the "switched off" message;
  - a duplicate key fails construction.
- **Existing tests** of the planner, playback and sources keep their assertions. Tests of source strategies move with
  them.

## Measures

- `core.playback` names no Jellyfin, YouTube or workflow type.
- Route strategy classes: from nine to two, `RefStrategy` in core and Jellyfin's own.
- `CastMessage` definitions: from three to one.
- Test count only rises; frozen ArchUnit violations only fall.

## Out of scope

- What plays where, or in which order (Constraints).
- Resolvers (`PlayableResolver`) beyond moving the references they build.
- The device contract (3B, done) and the adapter layering (3E).
