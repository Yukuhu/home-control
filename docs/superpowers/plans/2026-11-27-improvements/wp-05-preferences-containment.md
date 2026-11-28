# WP-05: Stored data cannot lock out the UI — Implementation Plan

> **For agentic workers:** work task by task, test first. Steps use checkbox (`- [ ]`) syntax: tick them in the commit
> that does them, and update this package's row in [the index](README.md) in the pull request that lands it.

**Goal:** A release that renames a streaming service, a downgrade or a hand edit of `sources.json` no longer takes the
dashboard and Setup down: stored preferences are read leniently and written strictly, a `sources.json` that cannot be
read stops startup with its reason, and Setup always opens. A rail move is atomic and keeps the places of rails whose
source is off, a skipped pin or calendar survives in a kept copy, and a second Home Control on one `/data` is refused.

**Findings:**

- [DATA-V1](../../reviews/2026-11-27-improvement-catalogue.md#data-v1): all of it. Unknown values are left out on read
  with one WARN and a Setup hint; the constructor stays strict; the rename rule goes into `StreamingProviders`.
- [DATA-3](../../reviews/2026-11-27-improvement-catalogue.md#data-3): what 523459b left open (part 3, the 503, is
  done). Part 1 as a startup read of `sources.json` with a failure analysis; part 2 through DATA-V1's lenient read and
  a Setup section that shows a store failure instead of failing the page; one WARN per distinct ticker failure. The
  "Reset dashboard preferences" button is left out (decision 1).
- [DATA-6](../../reviews/2026-11-27-improvement-catalogue.md#data-6): all of it, as a pure
  `SourcePreferences.withRailMoved` inside `SourcePreferencesService.update`.
- Merged into DATA-6: [SRC-2](../../reviews/2026-11-27-improvement-catalogue.md#src-2) (disabled sources' and
  switched-off modules' keys keep their place; the cap drops instead of throwing),
  [WEB-14](../../reviews/2026-11-27-improvement-catalogue.md#web-14) (the move inside the lock, and an htmx list that
  does not reload Setup: Task 7) and [TEST-14](../../reviews/2026-11-27-improvement-catalogue.md#test-14) (a
  latch-held race test; `Races.together` is left out, decision 8).
- [DATA-13](../../reviews/2026-11-27-improvement-catalogue.md#data-13): the move-aside variant for `pinned.json` and
  `sports.json`, with the skipped count on Setup until the next change.
- [DATA-14](../../reviews/2026-11-27-improvement-catalogue.md#data-14): all of it, plus an image smoke-test check.

**Approach:** Lenient reads live in `core`, beside the strict rules they mirror: a new `SourcePreferences.Unchecked`
record holds stored values unchecked, and `usable(Consumer<String> leftOut)` keeps what the canonical constructor
accepts and names the rest. `JsonFileSourceSettings.preferences()` handles only the JSON shape, caches its result per
stored node so the WARN comes once per read instead of on every `current()`, and exposes `preferencesLeftOut()` for
Setup. The startup check is one call in the `sourceSettings` bean, so `sources.json` joins `devices.json` and
`secrets.json`, plus a `StorageFailureAnalyzer` that words the failure like `UnusableDataDirectoryFailureAnalyzer`.

A move becomes `prefs.update(p -> p.withRailMoved(rail, up, offered, known))`: the controller asks the sources for
their rails before the lock, so no source runs under the preferences monitor, and the pure method merges them into the
stored order inside it. `VersionedJsonFile.keepRejected` copies a file once, before the first write after a read that
skipped entries. `DataDirectoryLock` holds `FileChannel.tryLock()` on `/data/.lock` from the `DataDirectory` bean until
the context closes, shared within one JVM, and names the holder from `/data/.lock-owner`.

**Depends on / touches:** Depends on nothing. Touches WP-17 (DATA-2's backup guide lists `.lock`, `.lock-owner` and
`*.rejected-*.json` as disposable, DATA-1's snapshots skip `.lock*`, TEST-2's format-change rule takes the
`StreamingProviders` line, and its data-file inventory could widen the startup check), WP-26 (a renamed or removed
provider key needs a migration of `sources.json` preferences and `sports.json` providers), WP-16 (DATA-8's
`StorageException.cannotWrite` fits the kept copy's write; DATA-12 also edits `META-INF/spring.factories`), WP-10
(ARCH-2 edits `RailCache`), WP-12 (WEB-3 edits Setup's flash and anchors next to Task 7), WP-15 (WEB-10 checks the rail
list), WP-04 (ARCH-1 also amends ADR 0002), WP-31 (ARCH-7 may ban DNS lookups: the lock owner's host comes from
`HOSTNAME`) and WP-03 (`ForeignText.clean` can replace the `shown` helper). ADR 0002 (decision 6).

**Effort:** M overall. Each of the six pull requests is S.

## Before you start

- Re-check the findings against current main. At 6a253e4, whose code equals 3932f9b (`git diff --stat 3932f9b HEAD --
  src protocols core` is empty), every one holds:
  - DATA-V1: `JsonFileSourceSettings.preferences()` turns any exception from the `SourcePreferences` constructor into a
    `StorageException`; the constructor throws for a provider outside `StreamingProviders.KNOWN` and past
    `MAX_RAIL_ORDER` (200); `VERSION` is still 2.
  - DATA-3: `ErrorAdvice.storage` answers 503. `HomeControlConfiguration.sourceSettings` never reads the file;
    `SourcePreferencesService.current()` and `SourcesSetupAdvice.sourcesSetup()` catch nothing; `RailCache.start`'s
    lambda logs `Rail refresh tick failed` with a stack trace on every tick.
  - DATA-6: `SourcesSetupController.moveRail` builds `order` from `railKeys()` (`allRailsInOrder`, which reads
    `preferences.current()`) before `prefs.update(p -> p.withRailOrder(order))`, and that list lacks the rails of
    unavailable and disabled sources.
  - DATA-13: `JsonFilePinStore.parsePin`, `JsonFileSportsStore.parseCalendar` and `parseCompetition` skip with a WARN,
    and `save` writes with no copy. `QuotaLedger.load` is the move-aside precedent.
  - DATA-14: `HomeControlConfiguration.dataDirectory` only calls `verifyUsable()`; nothing in `src/main` locks a file.

  Repeat with `git log 3932f9b..origin/main` over `core/.../core/content` and the app's `storage`, `content`, `web`,
  `sources/pinned` and `sources/sports` packages, and by reading the methods above.
- Owner decisions. Ask them up front:
  1. **Lenient reads, no Reset button.** Recommended: read each value on its own and leave out only what fails. A
     fallback to the defaults would drop the rail order, hidden rails and disabled sources for one stale key
     (DATA-V1), and each value has its own form. Fits "existing installs upgrade in place"; no product rule changes.
  2. **What the startup check covers.** Recommended: `sources.json` only. Pins, sports, the quota, remembered logins
     and the theme catalog already contain their failure (a problem line, a move-aside, a log-out), and their modules
     can be switched off; a registry of every store waits for WP-17, which needs the same inventory.
  3. **The 200-key cap in a move.** Recommended: never throw; past the cap drop, from the end, keys of unknown sources
     (a module switched off or removed), then of sources that are off, then shown keys. Below it nothing is pruned
     (the analysis's "what not to do").
  4. **DATA-13 move-aside or pass-through.** Recommended: a copy, `<name>.rejected-<epoch second>.json`, before the
     first write that drops skipped entries; no `rejected` array, so no format change.
  5. **The lock in one JVM and on shares.** Recommended: a second context in one JVM shares the lock (tests only; a
     second channel would release the first one's lock when it closes). A file system that refuses locks gets a WARN
     and the app starts: the guard is best effort.
  6. **ADR 0002.** Recommended: an "Amended 2026-11-27" note under Consequences: preferences are read leniently within
     a version, and `/data/.lock` is the one file not written through `AtomicFiles` (it stays empty). **This changes an
     ADR**; coordinate with WP-04, which amends it too.
  7. **WEB-14's no-reload list.** Recommended: here, as the last PR, since it reuses Task 4's endpoint; else WP-12.
  8. **`Races.together` (TEST-14).** Recommended: not now; one latch test needs no helper. WP-32 adds it, with a line
     in `docs/dev/testing.md`, when a second read-modify-write seam needs it.

## Global constraints

- **Existing installs upgrade in place.** No `/data` format changes: `sources.json` stays version 2, `pinned.json` and
  `sports.json` version 1. `.lock`, `.lock-owner` and `*.rejected-*.json` are new files an older release ignores.
- **Read leniently, write strictly.** Only `SourcePreferences.Unchecked.usable` forgives. The constructor and every
  `with…` method keep refusing bad input (`SourcePreferencesServiceTest.anInvalidUpdateWritesNothing` stays unchanged).
- **Secrets never reach the browser.** Left-out values are preference keys and ids; they pass through `shown` (at most
  64 characters, control characters replaced). `sports.json` never holds a calendar URL, and copies are owner-only.
- **Modules can be switched off.** A switched-off module's rail keys stay in `railOrder`.
- **Architecture.** `core` stays JDK-only and the new core methods are pure. No source runs under the
  `SourcePreferencesService` monitor. Every file but the empty `.lock` is written through `AtomicFiles`.
  `ArchitectureTest` does not change.
- **Tests** sit in their code's package and module. Full-app tests extend `FullAppTest` and add nothing to its context
  (`SharedContextRulesTest`); startup tests use `SpringApplicationBuilder` or a child JVM, as
  `LegacyConfigurationStartupTest` and `BluetoothClassLoadingTest` do.
- `scripts/gradle.sh build` is green at the end of every task. Tasks 2, 5 and 7 change Setup: run `scripts/e2e.sh`
  before their pull requests (`-Pe2eBrowsers=chromium --tests '*SetupFlows*'` while iterating).
- Commits follow Conventional Commits (`scripts/check-commits.sh`), scopes `core`, `storage`, `web`. Stage only the
  task's files with `git add <paths>`, never `git add -A`. Each commit ends with the session's `Co-Authored-By` and
  `Claude-Session` lines.

## Review focus

1. **No stored value makes a read throw, and the lenient path accepts nothing the strict one refuses.** Pinned by
   `SourcePreferencesTest.unusableStoredValuesAreLeftOutAndNamed`,
   `JsonFileSourceSettingsTest.preferencesOfTheWrongShapeAreLeftOutNotRefused` and
   `StoredPreferencesEndToEndTest.aRenamedStreamingServiceLeavesTheDashboardAndSetupWorking`.
2. **A damaged or newer `sources.json` stops startup with its reason; a missing one does not.** Pinned by
   `DataFilesStartupTest` and `JsonFileSourceSettingsTest.verifyReadableNamesANewerOrDamagedFile`.
3. **Two close moves both survive, and rails not shown keep their place.** Pinned by
   `SourcesSetupRailMovesTest` and `SourcePreferencesTest`'s `withRailMoved` cases; check that `offeredRailKeys` runs
   before `update`.
4. **The copy is taken once, before the first write that would drop entries, and never otherwise.** Pinned by
   `VersionedJsonFileTest.aWriteAfterASkippingReadKeepsTheOriginalOnce` and `aWriteAfterACleanReadKeepsNoCopy`.
5. **The lock refuses another process, dies with it, and is shared within a JVM.** Pinned by `DataDirectoryLockTest`
   and `DataFilesStartupTest.aSecondApplicationOnAHeldDataDirectoryDoesNotStart`; the smoke test checks a real volume.

## File structure

- **Create** (under `src/main/java/dev/andre/homecontrol/storage/`): `StorageFailureAnalyzer.java`,
  `DataDirectoryLock.java`, `DataDirectoryInUseException.java`, `DataDirectoryInUseFailureAnalyzer.java`.
- **Modify:** `core/src/main/java/dev/andre/homecontrol/core/content/SourcePreferences.java` and
  `StreamingProviders.java` (javadoc). Under `src/main/java/dev/andre/homecontrol/`: `HomeControlConfiguration.java`;
  `storage/JsonFileSourceSettings.java`, `storage/VersionedJsonFile.java`, `storage/DataDirectory.java`;
  `content/SourcePreferencesService.java`, `content/StoredRailPreferences.java`, `content/RailCache.java`;
  `web/SourcesSetupController.java`, `web/SourcesSetupAdvice.java`, `web/SourcesSetupView.java`;
  `sources/pinned/JsonFilePinStore.java`, `PinnedShortcuts.java`, `PinnedSetupSection.java`;
  `sources/sports/settings/JsonFileSportsStore.java`, `SportsSettingsService.java`,
  `sources/sports/SportsSetupSection.java`. Under `src/main/resources/`: `META-INF/spring.factories`,
  `templates/fragments/sources-setup.html`, `pinned-setup.html`, `sports-setup.html`. Also
  `scripts/smoke-test-image.sh`, `docs/user/configuration.md`, `docs/user/sources.md`, `docs/dev/architecture.md` and
  `docs/adr/0002-versioned-data-files-and-device-secrets.md`.
- **Test**, each next to its class: create `src/test/java/dev/andre/homecontrol/DataFilesStartupTest.java`, and under
  its `storage/`, `StorageFailureAnalyzerTest`, `DataDirectoryLockTest` and `DataDirectoryLockHolder` (the child JVM's
  `main`); under `web/`, `SourcesSetupRailMovesTest` and `StoredPreferencesEndToEndTest`. Modify `SourcePreferencesTest`
  (core), `JsonFileSourceSettingsTest`, `VersionedJsonFileTest`, `SourcePreferencesServiceTest`,
  `StoredRailPreferencesTest`, `RailCacheLifecycleTest`, `SourcesSetupControllerTest`, `JsonFilePinStoreTest`,
  `PinnedShortcutsTest`, `JsonFileSportsStoreEdgeCaseTest`, `SportsSettingsServiceTest` and
  `src/e2e/java/dev/andre/homecontrol/e2e/SetupFlowsE2eTest.java`. Must stay green unchanged: `ArchitectureTest`,
  `SharedContextRulesTest`, `FullAppResetTest`, `ModulesOffSmokeTest`, `InlineCodeTest`, `QuotaLedgerTest`.

## Pull requests

| PR | Title | Tasks | Effort |
| --- | --- | --- | --- |
| 1 | `fix(storage): read stored dashboard preferences leniently so a stale key cannot lock out Setup` | 1, 2 | S |
| 2 | `fix(storage): read sources.json at startup and stop with its reason when it cannot be used` | 3 | S |
| 3 | `fix(web): move a rail inside the preferences update and keep the places of rails not shown` | 4 | S |
| 4 | `fix(storage): keep a copy of pinned.json or sports.json before a write drops skipped entries` | 5 | S |
| 5 | `fix(storage): refuse a second Home Control on the same data directory` | 6 | S |
| 6 | `feat(web): move rails on Setup without reloading the page` | 7 | S |

PR 1 lands before PR 2, whose startup read logs PR 1's left-out values. PRs 3 to 5 are independent; PR 6 needs PR 3.

## Tasks

### Task 1: Stored preferences are checked value by value in `core`

**Files:** Modify `SourcePreferences.java`, `StreamingProviders.java`. Test `SourcePreferencesTest`.

**Interfaces:**

- New `public record SourcePreferences.Unchecked(List<String> railOrder, List<String> hiddenRails, List<String>
  disabledSources, Map<String, Integer> refreshMinutes, String locale, String region, List<String> providers)` with
  `public SourcePreferences usable(Consumer<String> leftOut)`. It never throws: it keeps what the constructor accepts
  and reports the rest as `rail order: <key>`, `rail order: <n> rails past the limit of 200` (the first 200 stay),
  `hidden rail: <key>`, `disabled source: <id>`, `refresh interval of <id>: <minutes>`, `language: <tag>` and
  `country: <code>` (both become `null`, which `SourcePreferencesService` fills from the properties) and
  `streaming service: <key>`.
- `requireRailKey`, `requireSourceId` and `requireLocale` get boolean twins (`isRailKey`, `isSourceId`, `isLocale`,
  `isRegion`, `isRefreshMinutes`) that both paths use, so each rule exists once. A private `shown(String)` keeps 64
  characters plus `…` and turns ISO control characters into `?`.

- [ ] **Step 1: Write the failing test.** In `SourcePreferencesTest`:

  ```java
  @Test
  void unusableStoredValuesAreLeftOutAndNamed() {
      List<String> leftOut = new ArrayList<>();
      SourcePreferences read = new SourcePreferences.Unchecked(
              List.of("jellyfin/resume", "Jellyfin/Bad", "jellyfin/resume"), List.of("tube/subs", "nope"),
              List.of("tmdb", "TMDB!"), Map.of("jellyfin", 10, "tube", 0), "english", "Germany",
              List.of("netflix", "wowtv-old")).usable(leftOut::add);

      assertThat(read).isEqualTo(new SourcePreferences(List.of("jellyfin/resume"), Set.of("tube/subs"),
              Set.of("tmdb"), Map.of("jellyfin", 10), null, null, List.of("netflix")));
      assertThat(leftOut).containsExactlyInAnyOrder("rail order: Jellyfin/Bad", "hidden rail: nope",
              "disabled source: TMDB!", "refresh interval of tube: 0", "language: english", "country: Germany",
              "streaming service: wowtv-old");
  }
  ```

  Plus `aRailOrderPastTheLimitIsCutNotRefused` (230 keys: the first 200 and `rail order: 30 rails past the limit of
  200`), `usableValuesComeThroughUnchanged` and `aLeftOutValueIsShownShortAndPrintable` (a 500-character key with a
  line break is reported in at most 80 characters without a control character).
- [ ] **Step 2: Run it to see it fail.** `scripts/gradle.sh :core:test --tests
  'dev.andre.homecontrol.core.content.SourcePreferencesTest'`: `cannot find symbol: Unchecked`.
- [ ] **Step 3: Implement.** `usable` filters each value with the twins, cuts `railOrder` after the filter, and calls
  the canonical constructor once; the existing tests pin that the constructor's messages do not change.
- [ ] **Step 4: Run the tests.** The Step 2 command, `scripts/gradle.sh :core:test`, then `scripts/gradle.sh build`.
- [ ] **Step 5: Update docs.** `StreamingProviders`' javadoc: "Renaming or removing a key needs a migration of the
  stored preferences in `sources.json` and of the providers in `sports.json`; without one, the choice is left out on
  read."
- [ ] **Step 6: Commit.** `feat(core): read stored source preferences value by value`.

### Task 2: `sources.json` preferences are read leniently, and Setup always opens

**Files:** Modify `JsonFileSourceSettings.java`, `SourcePreferencesService.java`, `SourcesSetupAdvice.java`,
`SourcesSetupView.java`, `sources-setup.html`, `docs/user/configuration.md`, `docs/dev/architecture.md`. Create
`StoredPreferencesEndToEndTest`. Test `JsonFileSourceSettingsTest`, `SourcePreferencesServiceTest`,
`SourcesSetupControllerTest`.

**Interfaces:**

- `JsonFileSourceSettings.preferences()` never throws for the `preferences` object; an unreadable file still throws
  from `VersionedJsonFile`. Shape problems are reported per field: `preferences: not an object` (reads as none),
  `rail order: not a list`, `hidden rail: a value that is not text`, `refresh intervals: not an object`, `refresh
  interval of <id>: not a whole number` (test `isInt()`; `isIntegralNumber()` with `asInt()` truncates). The result and
  its left-out list are cached against the identity of the stored `JsonNode`, so one WARN per read of the file says
  `Left out of the dashboard preferences in {}: {}; the next saved change stores them without these`.
- New `public synchronized List<String> JsonFileSourceSettings.preferencesLeftOut()` and
  `SourcePreferencesService.leftOut()`.
- `SourcesSetupView` gains `List<String> leftOut` and `String problem`, and `static SourcesSetupView unreadable(String
  problem)`. `SourcesSetupAdvice.sourcesSetup()` catches a `StorageException` (its message names a file, never a
  secret) or another `RuntimeException` ("The dashboard preferences could not be read; the log says why."), logs it at
  WARN and returns `unreadable(…)`. The template shows `problem` as `p.error`, the left-out values as a `p.hint` ("Some
  saved choices are no longer known and are left out; the next change you save here stores the rest without them:
  …"), and puts the lists and forms in `th:block th:if="${view.problem() == null}"`.

- [ ] **Step 1: Write the failing test.**
  - `JsonFileSourceSettingsTest`: `malformedPreferencesAreANamedStorageException` becomes
    `anIntervalThatIsNoWholeNumberIsLeftOut`; `preferencesOfTheWrongShapeNameWhatIsWrong` becomes
    `preferencesOfTheWrongShapeAreLeftOutNotRefused`, whose three rows expect the entries above in
    `preferencesLeftOut()`. New: `anUnknownProviderAndAnOverlongRailOrderAreReadWithoutThem` (`["netflix",
    "wowtv-renamed"]` and 205 keys read as `[netflix]` and 200 keys), `preferencesThatAreNoObjectReadAsNone`,
    `theLeftOutValuesAreLoggedOncePerRead` (`OutputCaptureExtension`; three reads, one WARN) and
    `aSaveStoresThePreferencesWithoutTheLeftOutValues`.
  - `SourcePreferencesServiceTest.aStaleStoredValueIsLeftOutUntilTheNextChange`.
  - `SourcesSetupControllerTest.aStaleStoredValueIsNamedOnTheSetupPage` (`leftOut()` stubbed) and
    `theSetupPageOpensWhenThePreferencesCannotBeRead` (`current()` throws a `StorageException`: 200, `section(page,
    "sources")` shows its message and no form, and the `pinned` section still renders).
  - `StoredPreferencesEndToEndTest extends FullAppTest`, `aRenamedStreamingServiceLeavesTheDashboardAndSetupWorking`:
    call the `JsonFileSourceSettings` bean's `reset()`, write a version 2 file with `["netflix","wowtv-renamed"]` and
    205 rail keys, adopt a device as `FullAppResetTest` does; `GET /` answers 200, `GET /setup` names
    `wowtv-renamed` with Netflix ticked, and after `POST /setup/sources/preferences/locale` (de-DE, DE, netflix) the
    file holds no `wowtv-renamed`. `FullAppReset` cleans up.
- [ ] **Step 2: Run it to see it fail.** `scripts/gradle.sh test --tests
  'dev.andre.homecontrol.storage.JsonFileSourceSettingsTest' --tests 'dev.andre.homecontrol.content.*' --tests
  'dev.andre.homecontrol.web.SourcesSetupControllerTest' --tests
  'dev.andre.homecontrol.web.StoredPreferencesEndToEndTest'`: reads throw, `/` and `/setup` answer 503, and
  `leftOut()` does not compile.
- [ ] **Step 3: Implement** as in Interfaces: lenient `strings(node, label, leftOut)` and `minutes(node, leftOut)` feed
  an `Unchecked`; cache fields `parsedFrom`, `parsed` and `leftOut` are compared with `==`.
- [ ] **Step 4: Run the tests.** The Step 2 command, `scripts/gradle.sh test --tests 'dev.andre.homecontrol.web.*'
  --tests 'dev.andre.homecontrol.testsupport.*'`, `scripts/e2e.sh -Pe2eBrowsers=chromium --tests '*SetupFlows*'`, then
  `scripts/gradle.sh build`.
- [ ] **Step 5: Update docs.** `docs/user/configuration.md`, after "Edit the files under `/data` only while the app is
  stopped": "A saved dashboard choice this version does not know, such as a streaming service a newer release added,
  is left out with a warning in the log and on Setup; the next change you save stores the rest without it."
  `docs/dev/architecture.md`, `content` row: stored preferences are read value by value and written strictly.
- [ ] **Step 6: Commit.** `fix(storage): read stored dashboard preferences leniently so a stale key cannot lock out
  Setup`.

### Task 3: `sources.json` is read at startup, and a failing tick is logged once

**Files:** Create `StorageFailureAnalyzer.java`, `StorageFailureAnalyzerTest`, `DataFilesStartupTest`. Modify
`JsonFileSourceSettings.java`, `HomeControlConfiguration.java`, `spring.factories`, `RailCache.java`,
`docs/user/configuration.md`, `docs/dev/architecture.md`. Test `JsonFileSourceSettingsTest`, `RailCacheLifecycleTest`.

**Interfaces:**

- New `public synchronized void JsonFileSourceSettings.verifyReadable()`: reads the file (migrating version 1, as the
  first read always did) and its preferences, so a damaged or newer file throws now and the left-out WARN appears at
  startup. A missing file stays missing. `HomeControlConfiguration.sourceSettings` calls it.
- New `public final class StorageFailureAnalyzer extends AbstractFailureAnalyzer<StorageException>`: description
  `cause.getMessage() + "."`, action "Home Control reads this file when it starts. Change files under /data only while
  Home Control is stopped; the data directory section of docs/user/configuration.md explains each file." Listed in
  `META-INF/spring.factories` after `UnusableDataDirectoryFailureAnalyzer`.
- `RailCache`'s scheduled lambda calls a new package-private `void scheduledTick()`: a failure is logged at WARN with
  its stack trace, a repeat of the same class and message at DEBUG, and the first success after a failure at INFO as
  `Rail refresh ticks work again`. Its `lastTickFailure` field is touched only by the one ticker thread and by tests.

- [ ] **Step 1: Write the failing test.**
  - `JsonFileSourceSettingsTest.verifyReadableNamesANewerOrDamagedFile` (`{"version":3,"sources":{}}` gives `was
    written by a newer Home Control (version 3)`, a cut-off file `fix or delete it`) and
    `verifyReadableLeavesAMissingFileMissing`.
  - `StorageFailureAnalyzerTest.aFailedStartSaysWhichFileAndWhatToDo`, built like
    `DataDirectoryTest.aFailedStartSaysWhatIsWrongAndWhatToDoInsteadOfAStackTrace`.
  - `DataFilesStartupTest` (package `dev.andre.homecontrol`, `OutputCaptureExtension`):
    `aNewerSourcesJsonStopsStartupWithItsReason(@TempDir Path data, CapturedOutput output)`: with `{"version":3,
    "sources":{}}` in `sources.json`, `new SpringApplicationBuilder(HomeControlApplication.class).run("--server.port=0",
    "--home-control.data-dir=" + data)` throws with a `StorageException` among its causes, and the output holds
    `APPLICATION FAILED TO START` and `upgrade Home Control or restore a backup`. `aDamagedSourcesJsonStops…` likewise.
  - `RailCacheLifecycleTest.aRepeatedTickFailureIsLoggedOnceUntilATickWorks`: preferences that throw three times and
    then work; four `scheduledTick()` calls log one `Rail refresh tick failed` and one `Rail refresh ticks work again`.
- [ ] **Step 2: Run it to see it fail.** `scripts/gradle.sh test --tests 'dev.andre.homecontrol.DataFilesStartupTest'
  --tests 'dev.andre.homecontrol.storage.*' --tests 'dev.andre.homecontrol.content.RailCacheLifecycleTest'`: the app
  starts, and `verifyReadable` and `scheduledTick` do not compile.
- [ ] **Step 3: Implement** as in Interfaces.
- [ ] **Step 4: Run the tests.** The Step 2 command, then `scripts/gradle.sh build`.
- [ ] **Step 5: Update docs.** `docs/user/configuration.md`: "Home Control reads `devices.json`, `secrets.json` and
  `sources.json` when it starts; if one cannot be read, or a newer version wrote it, it does not start, and its log
  names the file and what to do." `docs/dev/architecture.md`, `storage` row: the same three files.
- [ ] **Step 6: Commit.** `fix(storage): read sources.json at startup and stop with its reason when it cannot be used`.

### Task 4: A rail move is computed from the stored order inside the update

**Files:** Modify `SourcePreferences.java`, `StoredRailPreferences.java`, `SourcesSetupController.java`,
`docs/dev/architecture.md`. Create `SourcesSetupRailMovesTest`. Test `SourcePreferencesTest`,
`StoredRailPreferencesTest`, `SourcesSetupControllerTest`.

**Interfaces:**

- New `public SourcePreferences withRailMoved(String railKey, boolean up, List<String> offered, Set<String>
  knownSourceIds)`. `offered` is every rail key an available source offers, in source order; `knownSourceIds` every
  source that exists. A source is *live* when it offers a rail and is not disabled. The method (1) starts from
  `railOrder`, drops keys of live sources no longer offered (a deleted workflow, a deselected playlist) and appends
  live offered keys that are missing, in `offered` order; (2) finds `railKey` among live keys (hidden rails count) or
  throws `IllegalArgumentException("No rail " + railKey)`; (3) swaps it with the nearest live key above or below, so
  every other key keeps its index, and moves nothing at an end; (4) trims past 200 as decision 3 says and builds the
  result with `withRailOrder`, so the canonical checks still apply.
- New `public List<String> StoredRailPreferences.offeredRailKeys(List<ContentSource> sources)`: the rail keys of every
  available source, through `ContentSources.available` and `rails`, without reading preferences.
- `SourcesSetupController.moveRail` asks before the lock and moves inside it; `railKeys()` goes:

  ```java
  List<ContentSource> all = sources.all();
  List<String> offered = rails.offeredRailKeys(all);
  Set<String> known = all.stream().map(ContentSource::id).collect(Collectors.toUnmodifiableSet());
  prefs.update(p -> p.withRailMoved(rail, direction.equals("up"), offered, known));
  ```

- [ ] **Step 1: Write the failing test.**
  - `SourcePreferencesTest`, with `defaults = SourcePreferences.defaults("de-DE", "DE")`:

    ```java
    @Test
    void aMovedRailSwapsWithItsNearestShownNeighbourAndRailsNotShownKeepTheirPlace() {
        SourcePreferences stored = defaults
                .withRailOrder(List.of("tmdb/trending", "tube/pl", "jellyfin/resume", "workflows/w-1", "tube/subs"))
                .withSourceEnabled("tmdb", false);
        List<String> offered = List.of("tmdb/trending", "tube/subs", "tube/pl"); // Jellyfin's server is off
        Set<String> known = Set.of("tmdb", "tube", "jellyfin");                  // the workflows module is off

        assertThat(stored.withRailMoved("tube/subs", true, offered, known).railOrder())
                .containsExactly("tmdb/trending", "tube/subs", "jellyfin/resume", "workflows/w-1", "tube/pl");
    }
    ```

    Plus `aRailItsSourceNoLongerOffersIsDroppedAndNewRailsAreAppended` (`[workflows/gone, workflows/w-1]`, offered
    `[workflows/w-1, workflows/w-2, tube/subs]`, `workflows/w-2` up: `[workflows/w-2, workflows/w-1, tube/subs]`),
    `theFirstRailMovedUpAndTheLastMovedDownStayPut`, `aRailThatIsNotShownCannotBeMoved`,
    `pastTheLimitKeysOfUnknownSourcesGoFirst` (150 `gone/r-<i>` and 50 `jellyfin/r-<i>`, offered `[tube/subs]`: 200
    keys ending in `tube/subs`, without `gone/r-149`) and `pastTheLimitKeysOfSourcesThatAreOffGoNext`.
  - `SourcesSetupRailMovesTest` (package `web`, no Spring): a real `JsonFileSourceSettings` in a `@TempDir`, the
    service, `StoredRailPreferences`, `ContentSources` over stub sources, and the controller.
    `twoMovesCloseTogetherBothSurvive` (`@Timeout(10)`): source `tube` offers `a`, `b`, `c`, and its next `rails()`
    call blocks on a latch; a virtual thread moves `tube/c` up; once it is held, the test thread moves `tube/b` up; the
    latch opens and the thread is joined. The stored order is `[tube/b, tube/c, tube/a]`; today's code, which read the
    preferences before blocking, writes `[tube/a, tube/c, tube/b]`. `aMoveKeepsThePlacesOfRailsThatAreNotShown`
    (standalone MockMvc): stored `[jellyfin/resume, tube/subs, jellyfin/latest, tube/pl, tube/live, workflows/w-1]`,
    Jellyfin unavailable, no workflows source; `tube/live` up twice gives `[jellyfin/resume, tube/live,
    jellyfin/latest, tube/subs, tube/pl, workflows/w-1]`, and with Jellyfin back `allRailsInOrder` keeps its slots.
  - `StoredRailPreferencesTest.offeredRailKeysListEveryAvailableSourcesRailsInSourceOrder` (a disabled source's rails
    are listed, an unavailable or broken source's are not).
  - `SourcesSetupControllerTest`: `defaults()` stubs `offeredRailKeys`; in
    `movingAnUnknownRailOrTheFirstOneUpChangesNothing` the `never().update` check becomes "the change given to `update`
    throws `No rail unknown/rail`", with the same flash error.
- [ ] **Step 2: Run it to see it fail.** `scripts/gradle.sh :core:test --tests
  'dev.andre.homecontrol.core.content.SourcePreferencesTest'` (`cannot find symbol: withRailMoved`); with the core
  part in place, `scripts/gradle.sh test --tests 'dev.andre.homecontrol.web.Sources*' --tests
  'dev.andre.homecontrol.content.StoredRailPreferencesTest'` shows the lost move and the dropped Jellyfin keys.
- [ ] **Step 3: Implement** as in Interfaces, with a private `sourceOf(String key)` (the part before `/`).
- [ ] **Step 4: Run the tests.** The Step 2 commands, `scripts/e2e.sh -Pe2eBrowsers=chromium --tests '*SetupFlows*'`
  (`aHiddenOrMovedRailChangesTheDashboard` passes unchanged), then `scripts/gradle.sh build`.
- [ ] **Step 5: Update docs.** `docs/dev/architecture.md`, `content` row: "A rail move is computed inside
  `SourcePreferencesService.update`; the rails of sources that are off keep their place."
- [ ] **Step 6: Commit.** `fix(web): move a rail inside the preferences update and keep the places of rails not
  shown`, in two commits if the core method lands first (`feat(core): compute a rail move from the stored order`).
  The body names the 2026-10-03 open question it closes.

### Task 5: Entries skipped on read survive in a kept copy

**Files:** Modify `VersionedJsonFile.java`, `JsonFilePinStore.java`, `PinnedShortcuts.java`, `PinnedSetupSection.java`,
`pinned-setup.html`, `JsonFileSportsStore.java`, `SportsSettingsService.java`, `SportsSetupSection.java`,
`sports-setup.html`, `docs/user/configuration.md`, `docs/user/sources.md`. Test `VersionedJsonFileTest`,
`JsonFilePinStoreTest`, `PinnedShortcutsTest`, `JsonFileSportsStoreEdgeCaseTest`, `SportsSettingsServiceTest`.

**Interfaces:**

- New `public VersionedJsonFile<T> keepRejected(ToIntFunction<T> skipped)`. In `write`, when `snapshot != null`,
  `skipped.applyAsInt(snapshot) > 0` and the file exists, it first copies the file's bytes with `AtomicFiles.write(copy,
  bytes, false)` to `<base>.rejected-<epoch second>.json` and logs WARN `Kept {} as {} before saving: {} entries in it
  could not be read and are left out`. A failed copy is a `StorageException`, and nothing is written. The next
  snapshot counts nothing, so the copy is taken once per read. During a migration `snapshot` is `null`; the
  `.v<n>.json` backup already keeps the original.
- `JsonFilePinStore` holds a private `record Read(List<Pin> pins, int skipped)`: entries in `pins` minus those kept
  (invalid ones and repeated ids). `load()` returns `pins()`, `save` writes `new Read(pins, 0)`, and a new
  `int skipped()` answers the count. `JsonFileSportsStore` likewise for calendars and competitions; values corrected
  inside a kept entry (a cut label, a dropped provider or time zone) do not count.
- New `PinnedShortcuts.skipped()` and `SportsSettingsService.skipped()` (0 while the file cannot be read).
  `PinnedSetupSection.View` and `SportsSetupSection.View` gain `int skipped`, shown as a `p.hint`: "2 saved links in
  pinned.json could not be read and are not shown. The next change keeps a copy of the file beside it." (sports:
  calendars and competitions in sports.json).

- [ ] **Step 1: Write the failing test.**
  - `VersionedJsonFileTest`, with a reader that counts the lines it skips:
    `aWriteAfterASkippingReadKeepsTheOriginalOnce` (after two writes exactly one `notes.rejected-<digits>.json` holds
    the original bytes, owner-only), `aWriteAfterACleanReadKeepsNoCopy` and `aFailedCopyLeavesTheFileUnchanged` (the
    copy's name taken by a directory).
  - `JsonFilePinStoreTest.aSaveAfterSkippedPinsKeepsTheFileTheyCameFrom` (an `htps://` link and a 121-character title:
    `skipped()` is 2; after `save` the copy holds both and `skipped()` is 0), and
    `JsonFileSportsStoreEdgeCaseTest.aSaveAfterASkippedCalendarKeepsTheFileItCameFrom` (a 254-character host).
  - `PinnedShortcutsTest` and `SportsSettingsServiceTest`: `theSkippedCountIsShownUntilTheNextChange`.
- [ ] **Step 2: Run it to see it fail.** `scripts/gradle.sh test --tests 'dev.andre.homecontrol.storage.*' --tests
  'dev.andre.homecontrol.sources.pinned.*' --tests 'dev.andre.homecontrol.sources.sports.*'`: `keepRejected` and
  `skipped` do not compile.
- [ ] **Step 3: Implement** as in Interfaces; both stores call `.keepRejected(Read::skipped)`.
- [ ] **Step 4: Run the tests.** The Step 2 command, `scripts/gradle.sh test --tests 'dev.andre.homecontrol.web.*'`,
  `scripts/e2e.sh -Pe2eBrowsers=chromium --tests '*SetupFlows*'`, then `scripts/gradle.sh build`.
- [ ] **Step 5: Update docs.** `docs/user/configuration.md`, the paragraph on editing files: "An entry the app cannot
  use, such as a pinned link with a mistyped address, is skipped with a warning in the log and on Setup. Before the next
  change saves the file without it, the original is kept beside it as `<name>.rejected-<time>.json`." One sentence in
  `docs/user/sources.md`'s pinned-links and sports paragraphs points to it.
- [ ] **Step 6: Commit.** `fix(storage): keep a copy of pinned.json or sports.json before a write drops skipped
  entries`.

### Task 6: One Home Control per data directory

**Files:** Create `DataDirectoryLock.java`, `DataDirectoryInUseException.java`,
`DataDirectoryInUseFailureAnalyzer.java`, `DataDirectoryLockTest`, `DataDirectoryLockHolder`. Modify
`DataDirectory.java`, `HomeControlConfiguration.java`, `spring.factories`, `scripts/smoke-test-image.sh`,
`DataFilesStartupTest`, `docs/user/configuration.md`, `docs/dev/architecture.md`, ADR 0002.

**Interfaces:**

- `DataDirectory.LOCK = ".lock"`, `LOCK_OWNER = ".lock-owner"`, and new `synchronized` `claim()` and `release()`.
  `HomeControlConfiguration.dataDirectory` becomes `@Bean(destroyMethod = "release")` and calls `claim()` after
  `verifyUsable()`, before any store bean reads, migrates or resets a file.
- New `public final class DataDirectoryLock implements AutoCloseable`, `static DataDirectoryLock acquire(Path
  directory)`: it creates the directory, opens `.lock` owner-only with `CREATE, WRITE`, never writes it, and calls
  `tryLock()`. On `null` it closes the channel and throws `DataDirectoryInUseException` with the owner from
  `.lock-owner`, when readable. On success it writes `host=`, `pid=` and `since=` lines to `.lock-owner` through
  `AtomicFiles`; the host is `HOSTNAME` or the first line of `/etc/hostname`, never a DNS lookup. An `IOException` from
  `tryLock` (a share without locks) logs a WARN and starts unlocked. A static map keyed by `toRealPath()`, reference
  counted, lets a second claim in the JVM share the first's channel; the last `close()` releases the lock and deletes
  `.lock-owner`.
- `DataDirectoryInUseException`: `Another Home Control (host 3f2a9c1b7d4e, pid 1, running since
  2026-11-27T08:15:02Z) is using the data directory /data`, without the parenthesis when the owner is unknown. Its
  `remedy()`: "Stop the other instance first, or give this one its own data directory: two instances on one directory
  overwrite each other's pairings and settings. On an NFS or SMB share this check is best effort." Its analyzer follows
  `UnusableDataDirectoryFailureAnalyzer`.

- [ ] **Step 1: Write the failing test.**
  - `DataDirectoryLockTest` (`@Timeout(value = 2, unit = MINUTES)`). The child JVM runs `DataDirectoryLockHolder` on
    `home-control.test.runtime-classpath`, as `BluetoothClassLoadingTest` runs `ContextSmoke`; it acquires, prints
    `HELD` and waits for stdin to close. `anotherProcessIsRefusedAndNamed` (after `HELD`, `acquire(dir)` throws with
    `pid <child pid>`; once the child exits, it succeeds), `aProcessThatDiesReleasesTheLock` (`destroyForcibly()`),
    `aClaimWritesTheOwnerAndNeverTheLockFile` (`.lock` empty and owner-only, `.lock-owner` holds `pid=<this pid>`
    until `close()`), `aSecondClaimInTheSameJvmSharesTheLock` and `aFailedStartNamesTheOtherInstanceAndWhatToDo`.
  - `DataFilesStartupTest.aSecondApplicationOnAHeldDataDirectoryDoesNotStart` (`@Timeout(4 min)`): with the lock held
    here, `ContextSmoke` in a child JVM on the same directory exits non-zero, printing `APPLICATION FAILED TO START`
    and `pid <this pid>`.
- [ ] **Step 2: Run it to see it fail.** `scripts/gradle.sh test --tests 'dev.andre.homecontrol.storage.DataDirectory*'
  --tests 'dev.andre.homecontrol.DataFilesStartupTest'`: the new classes do not compile.
- [ ] **Step 3: Implement** as in Interfaces. In `scripts/smoke-test-image.sh`, after "Data volume is writable.", run a
  second container with `--volumes-from "$name"`, expect it to exit, grep `is using the data directory /data` after
  `APPLICATION FAILED TO START`, and add `"$name-second"` to `cleanup`.
- [ ] **Step 4: Run the tests.** The Step 2 command, `scripts/gradle.sh build` (every shared context has its own data
  directory), then `docker build -t home-control:smoke . && scripts/smoke-test-image.sh home-control:smoke`.
- [ ] **Step 5: Update docs.** `docs/user/configuration.md`, data directory section: "Only one Home Control may use a
  data directory at a time. A second one stops at startup and names the first (host and process id). The check is best
  effort on NFS and SMB shares. `.lock` and `.lock-owner` need no backup." `docs/dev/architecture.md`, `storage` row:
  the single-instance lock. ADR 0002: decision 6's note.
- [ ] **Step 6: Commit.** `fix(storage): refuse a second Home Control on the same data directory`.

### Task 7: Rail moves on Setup without a reload

**Files:** Modify `SourcesSetupController.java`, `SourcesSetupAdvice.java`, `SourcesSetupView.java`,
`sources-setup.html`. Test `SourcesSetupControllerTest`, `SetupFlowsE2eTest`.

**Interfaces:**

- The rail list moves into a new `<div id="rail-order" th:fragment="railOrder">` that reads the `sourcesSetup` model
  attribute and holds its own `role="status"` message and `role="alert"` error. Each move form keeps `method` and
  `action` (the no-JS fallback) and gains `hx-post` (through `th:attr`, as the deep-link test button does),
  `hx-target="#rail-order"` and `hx-swap="outerHTML"`.
- `SourcesSetupView.RailRow` gains `boolean focusUp, boolean focusDown`: the moved rail's button in its direction, or
  the other one at an end, gets `th:autofocus`, which htmx honours after a swap. `SourcesSetupAdvice` gets a
  package-private static `view(prefs, rails, sources, movedRail, movedUp)` that `sourcesSetup()` calls with `null`.
- `SourcesSetupController.move` takes `@RequestHeader(name = "HX-Request", required = false) String htmx` and a
  `Model`: for `true` it puts the message or error and `sourcesSetup` in the model and returns
  `fragments/sources-setup :: railOrder`; otherwise it redirects as today.

- [ ] **Step 1: Write the failing test.** `SourcesSetupControllerTest.anHtmxMoveAnswersTheListWithTheMovedRailFocused`
  (`HX-Request: true`: 200 with `id="rail-order"`, `Rail order saved`, and `autofocus` on `jellyfin/next-up`'s "Move
  up"; `movesARail` still redirects). `SetupFlowsE2eTest.aRailMovesWithoutReloadingTheSetupPage`: set
  `window.noReload = true`, press Picks' "Move down" twice; `#rail-order` says "Rail order saved", `window.noReload` is
  still `true`, the focused element is a button in the Picks row, and the dashboard lists `e2e/picks` two places lower.
- [ ] **Step 2: Run it to see it fail.** `scripts/gradle.sh test --tests
  'dev.andre.homecontrol.web.SourcesSetupControllerTest'` (a redirect, not 200) and `scripts/e2e.sh
  -Pe2eBrowsers=chromium --tests '*SetupFlows*'` (the reload clears `window.noReload`).
- [ ] **Step 3: Implement** as in Interfaces, with no inline script or style (`InlineCodeTest`).
- [ ] **Step 4: Run the tests.** The Step 2 commands, `scripts/e2e.sh` on all three engines (no CSP violation), then
  `scripts/gradle.sh build`.
- [ ] **Step 5: Update docs.** None; without JavaScript the page behaves as before.
- [ ] **Step 6: Commit.** `feat(web): move rails on Setup without reloading the page`.

## Done when

- Stale or invalid stored preferences (an unknown provider, a bad key or interval, more than 200 rails, a wrong shape)
  leave `/` and `/setup` working, with one WARN and a Setup hint, until the next saved change writes them out.
- A damaged or newer `sources.json` stops startup with "APPLICATION FAILED TO START", the file and what to do; Setup
  shows any store failure in its section; a failing tick logs one WARN, not one every 15 s.
- Two close rail moves both survive; a move keeps the places of rails whose source is off, drops rails a live source
  no longer offers, and never fails on the cap.
- The first write after a read that skipped entries keeps the original once as `<name>.rejected-<time>.json`.
- A second Home Control on one data directory stops at startup naming the first; a crash releases the lock.
- With PR 6, a rail moves without a reload and keeps focus in its row; without JavaScript the forms work as before.
- The guides, `StreamingProviders`' javadoc and ADR 0002 say this; `scripts/gradle.sh build` and `scripts/e2e.sh` are
  green. Every finding above is resolved or left out with its reason below.

## Out of scope

- **A "Reset dashboard preferences" button** (DATA-3 part 2): nothing is left to reset (decision 1).
- **A startup check of every store through a data-file registry** (DATA-3 part 1, DATA-11): decision 2, WP-17.
- **A `rejected` array written back verbatim** (DATA-13's alternative): decision 4.
- **Kept copies for left-out preferences**: preferences, not lost data; the WARN and hint name them (DATA-V1).
- **The format-change rule in `AGENTS.md`** (DATA-4 via TEST-2): WP-17 adds it and takes the `StreamingProviders` line.
- **Pruning switched-off modules' rail keys** below the cap: the analysis's "what not to do".
- **`Races.together`** (TEST-14): decision 8, WP-32.
- **Renaming a streaming-service key**, and its migration: WP-26.
- **The quota ledger and workflow store refusing a newer file** (DATA-1's outliers): WP-17.
