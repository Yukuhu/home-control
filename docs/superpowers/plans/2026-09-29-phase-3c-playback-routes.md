# Phase 3C: Playback Routes — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `core.playback` defines only the shared references and device routes plus two open interfaces. One generic
strategy on one preference ladder plans every route. The planner answers with one `Plan`, each route knows its key,
`CastMessage` is defined once, and ADR 0004 records where device control from sources belongs.

**Architecture:**
- `PlayableRef` is sealed over `AppLink`, `StreamUrl`, `CastLoad`, `CastMessage` and the non-sealed `SourceRef`.
- `Route` is sealed over the sealed `DeviceRoute` (five records with `action()`), the non-sealed `DelegatedRoute` and
  `Unroutable`.
- `RouteStrategy` declares a `Rung`, and `PlaybackPlanner` sorts by it.
- `RefStrategy` replaces seven strategy classes.
- Jellyfin, YouTube and workflows keep their references, routes and strategies in their own modules.

**Tech Stack:** Java 25 (sealed and non-sealed interfaces, records, pattern switches), Spring Boot 4.1.1, JUnit 5,
AssertJ.

**Spec:** `docs/superpowers/specs/2026-09-29-phase-3c-playback-routes-design.md`

## Global Constraints

- **What plays where, and in which order, does not change.** Unroutable reasons keep their wording.
- **Route keys do not change:** `app-link`, `cast:<app>`, `cast-message:<app>`, `render`, `local-audio`,
  `jellyfin-session`, `jellyfin-app`, `jellyfin-vlc`, `youtube-lounge`, `workflow-cast`, `unroutable`.
- **No `/data` format changes.**
- References and routes that may carry a token keep printing without it.
- `scripts/gradle.sh build` is green after every task, and the test count only rises (2,940 at `d48144e`).
- Commits follow Conventional Commits; stage only the files you changed.

## Review Focus

1. **A browser-visible route key that drifts.** The browser skips a failed route by its key, so a renamed key would
   replay the same route. Task 1 pins all eleven keys and `optimistic()` in one test.
2. **The ladder's order.** Jellyfin's app comes before app links, which come before Cast, renderers and the Bluetooth
   speaker. A planner built from strategies in any order must still plan the same. Task 5 pins it with the production
   strategies handed over reversed.
3. **A reason that changes wording.** When nothing routes, people read "this device is not a Cast receiver" or "the
   Jellyfin app cannot be started on this device". Task 4 and Task 6 keep `PlaybackPlannerReasonsTest`'s sentences,
   and each source's own references pin theirs.
4. **A token printed after a move.** `CastMessage` and the Jellyfin references and routes can carry an access token or
   an API key. Task 3 keeps the `toString` tests, and Task 6 moves them with the types.
5. **A delegated route whose module is off.** It must say "<device>: Jellyfin is switched off on this server", not
   fail with a missing executor. Task 2 pins it in `PlaybackServiceTest`.

---

### Task 1: Each route knows its key

**Files:**
- Modify: `src/main/java/dev/andre/homecontrol/core/playback/Route.java`
- Delete: `core/playback/RouteKeys.java`
- Modify the users: `core/playback/PlaybackPlanner.java`, `playback/PlaybackService.java`, `web/RouteView.java`
- Test:
  - `core/playback/RouteKeysTest.java` becomes `RouteKeyTest.java`;
  - update `MediaRendererStrategyTest`, `PlaybackPlannerTest`, `WorkflowCastStrategyTest` and `PlaybackServiceTest`.

**Interfaces:**
- Produces, on `Route`:

```java
    /** A stable, browser-visible identifier; never contains a payload, which may carry a token. */
    String key();

    /** True when success only means "the device accepted it" (spec §5.3: the app may not be installed). */
    default boolean optimistic() {
        return false;
    }
```

  | Route | `key()` | `optimistic()` |
  | --- | --- | --- |
  | `OpenAppLink` | `"app-link"` | true |
  | `WorkflowCast` | `"workflow-cast"` | false |
  | `Cast` | `"cast:" + receiverAppId` | false |
  | `CastMessage` | `"cast-message:" + receiverAppId` | false |
  | `JellyfinSession` | `"jellyfin-session"` | false |
  | `JellyfinVlc` | `"jellyfin-vlc"` | true |
  | `JellyfinApp` | `"jellyfin-app"` | false |
  | `YouTubeLounge` | `"youtube-lounge"` | false |
  | `Render` | `"render"` | false |
  | `PlayLocally` | `"local-audio"` | false |
  | `Unroutable` | `"unroutable"` | false |

- [ ] **Step 1:** `git mv` `RouteKeysTest` to `RouteKeyTest`, rewrite it to call `route.key()` and `route.optimistic()`
  for all eleven routes as tabled, and change every other test's `RouteKeys.key(x)` to `x.key()`.
- [ ] **Step 2:** `scripts/gradle.sh test --tests 'dev.andre.homecontrol.core.playback.*'`. Expected: compile failure.
- [ ] **Step 3: Implement.** Add the methods, delete `RouteKeys`, and switch the three main users.
- [ ] **Step 4:** `scripts/gradle.sh build`. Expected: green.
- [ ] **Step 5: Commit** `refactor: give each playback route its own key`.

---

### Task 2: Device routes carry an action; delegated routes go to the executor for their key

**Files:**
- Create: `core/playback/DeviceRoute.java`, `core/playback/DelegatedRoute.java`
- Modify:
  - `core/playback/Route.java`: `sealed … permits DeviceRoute, DelegatedRoute, Route.Unroutable`, with the five device
    records implementing `DeviceRoute` and the five source records implementing `DelegatedRoute` (they stay in core
    until Task 6);
  - `core/playback/RouteExecutor.java`;
  - `playback/PlaybackService.java`;
  - the four executors: `sources/jellyfin/JellyfinRouteExecutor.java`, `JellyfinVlcExecutor.java`,
    `sources/youtube/YouTubeLoungeRouteExecutor.java`, `sources/workflows/WorkflowCastRouteExecutor.java`.
- Test: `playback/PlaybackServiceTest.java` and the four executors' tests.

**Interfaces:**
- Produces:

```java
/** A route a device adapter carries: its action goes through DeviceCommands. */
public sealed interface DeviceRoute extends Route
        permits Route.OpenAppLink, Route.Cast, Route.CastMessage, Route.Render, Route.PlayLocally {

    Action action();
}
```

```java
/** A route a source's own RouteExecutor runs, found by its key (see ADR 0004). */
public non-sealed interface DelegatedRoute extends Route {

    /** The source's display name, for "<device>: <source> is switched off on this server". */
    String source();
}
```

```java
/** Runs the delegated routes whose keys it lists (e.g. a Jellyfin session's PlayNow). */
public interface RouteExecutor {

    Set<String> keys();

    /** Throws {@code ActionFailedException} with a user-facing reason when the command is refused. */
    void execute(DelegatedRoute route, Device device);
}
```

  - `source()`: `"Jellyfin"` for the three Jellyfin routes, `"YouTube"` for the Lounge, `"Workflows"` for workflow
    Cast.
  - `PlaybackService` builds a `Map<String, RouteExecutor>` from `keys()` in its canonical constructor. A second
    executor claiming a key throws `IllegalStateException("Two route executors claim <key>")`.
  - `execute` becomes:

```java
        switch (route) {
            case DeviceRoute device -> commands.execute(target.id(), device.action());
            case DelegatedRoute delegated -> Optional.ofNullable(executors.get(delegated.key()))
                    .orElseThrow(() -> new UnroutableException(
                            target.name() + ": " + delegated.source() + " is switched off on this server"))
                    .execute(delegated, target);
            case Route.Unroutable(var reason) -> throw new UnroutableException(target.name() + ": " + reason);
        }
```

- [ ] **Step 1: Write the failing tests** in `PlaybackServiceTest`:
  - `aDelegatedRouteGoesToTheExecutorForItsKey`: a stub `RouteExecutor` with keys `jellyfin-session` receives a
    `Route.JellyfinSession`.
  - `aDelegatedRouteWithoutItsExecutorSaysItsSourceIsOff`: no executor; `play` of a Jellyfin session item throws
    `UnroutableException` "Shield: Jellyfin is switched off on this server".
  - `twoExecutorsForOneKeyFailConstruction`.
  - Move the four executors' tests from `executes(...)` to `keys()`.
- [ ] **Step 2:** run `playback.*` and `sources.*`. Expected: compile failure.
- [ ] **Step 3: Implement** the interfaces, the `Route` permits clause, `PlaybackService`, and the four executors:
  - each lists its keys: Jellyfin `jellyfin-session` and `jellyfin-app`; VLC `jellyfin-vlc`; YouTube
    `youtube-lounge`; workflows `workflow-cast`;
  - each takes a `DelegatedRoute`, keeping its body's pattern match on its own route records.
- [ ] **Step 4:** `scripts/gradle.sh build`. Expected: green.
- [ ] **Step 5: Commit** `refactor: split playback routes into device routes and delegated routes run by key`.

---

### Task 3: `CastMessage` is defined once

**Files:**
- Modify: `core/playback/PlayableRef.java`, `core/playback/Route.java`, `core/playback/CastMessageStrategy.java`,
  `sources/jellyfin/JellyfinCastMessages.java`
- Test: `PlayableRefTest`, `PlaybackPlannerTest`, `PlaybackPlannerReasonsTest`, `RouteKeyTest`, `PlaybackServiceTest`,
  `web/ContentPlayPreviewTest`, `web/ContentPlayControllerTest`, `JellyfinCastMessagesTest`,
  `JellyfinPlayableResolverTest`

**Interfaces:**
- Produces:
  - `PlayableRef.CastMessage(Action.CastMessage message, String receiverLabel)`, with `kindLabel()` "cast" and
    `toString()` "CastMessage[receiverAppId=…, namespace=…]";
  - `Route.CastMessage(Action.CastMessage message, String receiverLabel)`, with `action()` returning `message`, `key()`
    `"cast-message:" + message.receiverAppId()`, `describe()` `"Cast with " + receiverLabel`, and the same
    `toString()`.

  `Action.CastMessage` keeps its validation (the namespace starts with `urn:x-cast:`) and its unmodifiable copy of the
  body.

- [ ] **Step 1:** move every test's constructor calls to the new shape, for example `new PlayableRef.CastMessage(new
  Action.CastMessage("F007D354", "urn:x-cast:x", Map.of()), "a receiver")`. Keep every assertion, the token-safe
  `toString` ones included.
- [ ] **Step 2:** run them. Expected: compile failure.
- [ ] **Step 3: Implement.** `JellyfinCastMessages.playable` builds the `Action.CastMessage` and wraps it.
  `CastMessageStrategy` maps `ref.message()` and `ref.receiverLabel()` to the route.
- [ ] **Step 4:** `scripts/gradle.sh build`. Expected: green.
- [ ] **Step 5: Commit** `refactor: define a Cast message once, as the action the references and routes carry`.

---

### Task 4: The planner answers with one `Plan`

**Files:**
- Create: `core/playback/Plan.java`
- Modify: `core/playback/PlaybackPlanner.java` (`plan` returns `Plan`; `routes(…)` goes), `playback/PlaybackService.java`
- Test:
  - every test that calls `planner.plan(…)` gets `.first()`, or reads `.routes()` where it called `routes(…)`;
  - new `core/playback/PlanTest.java`;
  - `PlaybackServiceTest`.

**Interfaces:**
- Produces:

```java
/** The planner's answer: every route in preference order, or the reason there is none. */
public record Plan(List<Route> routes, String reason) {

    public Plan {
        routes = List.copyOf(routes);
    }

    /** The preferred route, or {@link Route.Unroutable} with the reason. */
    public Route first() {
        return routes.isEmpty() ? new Route.Unroutable(reason) : routes.getFirst();
    }
}
```

- `PlaybackPlanner.plan(item, capabilities)` returns `new Plan(List.of(), "This item has nothing playable")` for an item
  with no playables. It returns `new Plan(routes, null)` when something routes, and otherwise `new Plan(List.of(),
  String.join("; ", reasons))`.
- `PlaybackService` has one private `Plan plan(Resolved)` that adds resolver notes as today:
  - no playables: the notes, or "This item has nothing playable";
  - nothing routes: the notes, then "; ", then the planner's reason.

  `plan`, `preview` and `attempt` use it, and `explain` goes.

- [ ] **Step 1:** write `PlanTest`:
  - `first()` of an empty plan is `Unroutable(reason)`;
  - `first()` of a plan with routes is its first route;
  - the routes list cannot be modified.

  Then change the tests' `planner.plan(…)` to `planner.plan(…).first()` and `planner.routes(…)` to
  `planner.plan(…).routes()`.
- [ ] **Step 2:** run `core.playback.*` and `playback.*`. Expected: compile failure.
- [ ] **Step 3: Implement.**
- [ ] **Step 4:** `scripts/gradle.sh build`. Expected: green. `PlaybackServiceTest`'s preview and attempt reasons are
  unchanged.
- [ ] **Step 5: Commit** `refactor: let the playback planner answer with one plan and its reason`.

---

### Task 5: One preference ladder and a generic strategy

**Files:**
- Create: `core/playback/Rung.java`, `core/playback/RefStrategy.java`, `core/playback/RouteStrategies.java`
- Modify:
  - `core/playback/RouteStrategy.java`: adds `Rung rung()`;
  - `core/playback/PlaybackPlanner.java`: sorts by rung;
  - `YouTubeLoungeStrategy.java`, `WorkflowCastStrategy.java`, `JellyfinSessionStrategy.java`: each declares its rung;
    they stay classes in core until Task 6;
  - `HomeControlConfiguration.java`: strategy beans, and a planner bean taking `List<RouteStrategy>`.
- Delete: `AppLinkStrategy`, `CastLoadStrategy`, `CastMessageStrategy`, `CastStreamStrategy`, `MediaRendererStrategy`
  and `LocalAudioSinkStrategy`. `LocalAudioSinkStrategy.playable` moves to `RouteStrategies.playsLocally`.
- Test: new `core/playback/RefStrategyTest.java` and `PlaybackPlannerLadderTest.java`; every test that builds a strategy
  with `new XStrategy()` or calls `new HomeControlConfiguration().playbackPlanner()`.

**Interfaces:**
- Produces:

```java
/** The preference ladder of spec §5.3, most preferred first. The planner sorts strategies by it. */
public enum Rung {
    NATIVE_APP, APP_LINK, CAST_APP, CAST_MESSAGE, CAST_LOAD, CAST_STREAM, RENDERER, LOCAL_SINK
}
```

```java
/** Routes the first reference of one type that passes {@code accepts}, on a device that has {@code needs}. */
public record RefStrategy<R extends PlayableRef>(Rung rung, Capability needs, Class<R> type, Predicate<R> accepts,
                                                 BiFunction<R, ContentItem, Route> toRoute) implements RouteStrategy {

    public static <R extends PlayableRef> RefStrategy<R> of(Rung rung, Capability needs, Class<R> type,
                                                            BiFunction<R, ContentItem, Route> toRoute) {
        return new RefStrategy<>(rung, needs, type, ref -> true, toRoute);
    }

    @Override
    public Optional<Route> route(ContentItem item, Set<Capability> capabilities) {
        if (!capabilities.contains(needs)) {
            return Optional.empty();
        }
        return item.playables().stream().filter(type::isInstance).map(type::cast).filter(accepts).findFirst()
                .map(ref -> toRoute.apply(ref, item));
    }
}
```

  `RouteStrategies` builds core's six strategies with the rungs, capabilities and routes in the spec's table:
  `appLink()`, `castMessage()`, `castLoad()`, `castStream()`, `renderer()` and `localSink()`. `core()` returns all six,
  and the static `playsLocally(PlayableRef.StreamUrl)` holds the old filter.

  Rungs of the remaining classes: `JellyfinSessionStrategy` is `NATIVE_APP`, and `YouTubeLoungeStrategy` and
  `WorkflowCastStrategy` are `CAST_APP`.
- `PlaybackPlanner`'s constructor sorts a copy with `Comparator.comparing(RouteStrategy::rung)`. The sort is stable, so
  the given order holds within a rung.
- `HomeControlConfiguration`:
  - one `@Bean` per core strategy, returning `RouteStrategies.xxx()`, typed `RouteStrategy`;
  - the three source strategy beans, until Task 6;
  - `playbackPlanner(List<RouteStrategy> strategies)`.

- [ ] **Step 1: Write the failing tests.**
  - `RefStrategyTest`:
    - without its capability the strategy routes nothing;
    - it routes the first reference of its type;
    - it skips references its filter refuses;
    - it ignores other types.
  - `PlaybackPlannerLadderTest.anyOrderOfStrategiesPlansTheSame`: `RouteStrategies.core()` plus the three source
    strategies, reversed and shuffled, plan the same routes as the spec's order for an item carrying an app link, a
    Cast message, a `CastLoad` and a stream, on a Cast receiver that can open app links.
  - Move the tests off the deleted classes: `new AppLinkStrategy()` becomes `RouteStrategies.appLink()`, and likewise
    for the others.
  - `new HomeControlConfiguration().playbackPlanner()` becomes a planner over `RouteStrategies.core()` plus
    `new JellyfinSessionStrategy()`, `new YouTubeLoungeStrategy()` and `new WorkflowCastStrategy()`, through a test
    helper.
- [ ] **Step 2:** run `core.playback.*`. Expected: compile failure.
- [ ] **Step 3: Implement.** The strategy classes go, and the planner sorts.
- [ ] **Step 4:** `scripts/gradle.sh build`. Expected: green.
- [ ] **Step 5: Commit** `refactor: plan playback on one preference ladder with a generic strategy`.

---

### Task 6: Source references, routes and strategies live in their modules

**Files:**
- Create:
  - `core/playback/SourceRef.java`.
  - Jellyfin:
    - `sources/jellyfin/JellyfinPlayable.java`: a sealed interface extending `SourceRef`, with records `Item`,
      `Session`, `App` and `Vlc`;
    - `JellyfinRoute.java`: a sealed interface extending `DelegatedRoute`, with records `Session`, `App` and `Vlc`;
    - `JellyfinSessionStrategy.java`, moved from core.
  - YouTube: `sources/youtube/YouTubeLoungeRef.java`, `YouTubeLoungeRoute.java`.
  - Workflows: `sources/workflows/WorkflowCastRef.java`, `WorkflowCastRoute.java`.
- Modify:
  - `core/playback/PlayableRef.java`: `permits AppLink, StreamUrl, CastLoad, CastMessage, SourceRef`; the six source
    records go;
  - `core/playback/Route.java`: the five source records go;
  - `core/playback/PlaybackPlanner.java`: its reason switch uses `case SourceRef ref -> ref.unroutableReason()`;
  - `DeviceRoute.java`, `DelegatedRoute.java`: Javadoc;
  - `HomeControlConfiguration.java`: the three source strategy beans go;
  - `JellyfinConfiguration`, `YouTubeConfiguration`, `WorkflowConfiguration`: each declares its strategy bean;
  - every source class that built or matched the old variants: the resolver, `JellyfinItemMapper`, the executors,
    `YouTubeLoungeResolver`, `WorkflowCatalogs`.
- Delete: `core/playback/YouTubeLoungeStrategy.java`, `WorkflowCastStrategy.java`, `JellyfinSessionStrategy.java`
- Test:
  - `core/playback/YouTubeRoutesTest.java` moves to `sources/youtube/`;
  - `WorkflowCastStrategyTest.java` moves to `sources/workflows/`;
  - `PlaybackPlannerReasonsTest` keeps its shared-reference sentences and one test with a local `SourceRef`. Its source
    sentences move to new `JellyfinPlayableTest`, `YouTubeLoungeRefTest` and `WorkflowCastRefTest`;
  - every other test the compiler names: `web/*`, `core/content/PinOffersTest`, `playback/PlaybackServiceTest`,
    `sources/*`.

**Interfaces:**
- Produces:

```java
/** A reference only one source builds and routes; it lives in that source's module (see ADR 0004). */
public non-sealed interface SourceRef extends PlayableRef {

    /** Why nothing routed this reference, shown when no route exists, e.g. "the Jellyfin app cannot be started". */
    String unroutableReason();
}
```

  The sentences stay as today:

  | Reference | `unroutableReason()` |
  | --- | --- |
  | `JellyfinPlayable.Item` | "Jellyfin is switched off on this server" |
  | `JellyfinPlayable.Session` | "the open Jellyfin app cannot be controlled" |
  | `JellyfinPlayable.Vlc` | "VLC cannot be opened on this device" |
  | `JellyfinPlayable.App` | "the Jellyfin app cannot be started on this device" |
  | `YouTubeLoungeRef`, `WorkflowCastRef` | "this device is not a Cast receiver" |

  - Record components, `kindLabel()`, `describe()`, keys, `optimistic()` and `toString()` move unchanged.
  - The YouTube and workflow strategies become `RefStrategy.of(Rung.CAST_APP, Capability.CAST_RECEIVER, …)` beans in
    their configurations.

- [ ] **Step 1:** move the tests first, to the new types and packages, keeping every assertion. Add the three module
  reason tests and the local-`SourceRef` reason test.
- [ ] **Step 2:** run `core.playback.*` and `sources.*`. Expected: compile failure.
- [ ] **Step 3: Implement** the moves.
- [ ] **Step 4:** `scripts/gradle.sh build`, then check the measure: `grep -rn "Jellyfin\|YouTube\|Workflow"
  src/main/java/dev/andre/homecontrol/core/playback` names no type (a comment on the ladder may name a source).
  Expected: green.
- [ ] **Step 5: Commit** `refactor: move each source's playable references, routes and strategy into its module`.

---

### Task 7: ADR 0004 and the architecture guide

**Files:**
- Create: `docs/adr/0004-device-control-from-sources.md`, following `docs/adr/README.md`'s template, with the
  content of the spec's section 4.
- Modify: `docs/adr/README.md` (its index), and `docs/dev/architecture.md`. In the `core` row, `core.playback` holds
  shared references, device routes, the open `SourceRef`/`DelegatedRoute` extension points and the planner's ladder.

- [ ] **Step 1:** write the ADR and edit the docs.
- [ ] **Step 2:** `scripts/gradle.sh build`, `scripts/gradle.sh compileE2eJava` and `scripts/e2e.sh
  -Pe2eBrowsers=chromium`. Expected: green.
- [ ] **Step 3: Commit** `docs: record where device control from content sources belongs`.
