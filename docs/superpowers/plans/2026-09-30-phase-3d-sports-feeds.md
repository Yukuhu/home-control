# Phase 3D: Sports Feeds Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The two sports feeds share one contract, one result type and one fetch helper; the sports packages form
layers without cycles; no lock is held while a feed downloads; and a removal or key change is never undone by a
download that was already running.

**Architecture:** The types both feeds need move below them, into `sports.feed` (`SportsFeed`, `FeedResult`,
`FeedStatus`, `SportsEvent`, `FeedFetches`) and `sports.settings` (settings, their store, properties, time zones).
`CalendarSchedule` and `TheSportsDbSchedule` implement `SportsFeed`; `SportsSchedule` merges a list of them. Each
schedule's pass fetches through `FeedFetches` (one fetch per key; a second caller joins it) and takes its private lock
only to write an outcome, publish, forget or clear; a generation counter drops outcomes that a forget or clear
overtook.

**Tech Stack:** Java 25, Spring Boot 4.1.1, JUnit 5, AssertJ, Mockito, Awaitility, ArchUnit 1.5.1,
`testsupport.FakeHttpServer`.

**Spec:** `docs/superpowers/specs/2026-09-30-phase-3d-sports-design.md` (commit `daa5e3e`).

## Global Constraints

- Branch `refactor/sports-feeds`, from main `8f3e737`. One pull request.
- Nothing a user sees changes except that waits end sooner and removals stick: the configuration prefix
  `home-control.sports.*`, the settings file and its format, the setup page and templates, the item ids (`ics:…`,
  `tsdb:…`), every error text, the refresh periods, the ten-minute retry backoff and the module switches stay.
- The shared types move; nothing is renamed except `hasCalendars()` / `hasCompetitions()` → `configured()` and the
  two `Result` records → `FeedResult`. A package-private member the root still reaches after a move becomes public;
  nothing else widens.
- No lock is held during a download. Each schedule's `lock` guards only: writing a fetch's outcome, publishing a pass,
  `forget()`, `clear()`.
- `scripts/gradle.sh build` green after every task; `scripts/e2e.sh -Pe2eBrowsers=chromium` green at the end. Frozen
  violations stay 42; never refreeze. The two new ArchUnit rules are strict.
- Stage only the files a task changed. Conventional Commits with the session trailer. Commit order: `refactor:`
  (moves), `refactor:` (contract), `fix:` (lock), `test:` (rules), `docs:`.

**Rulings made while planning (the spec left these open):**
- **`ranOnce` is set when a pass ends, not when it starts.** Today a `find()` that arrives while the first pass after
  a start is still fetching sees `ranOnce` already set and answers empty. The user chose "join the running fetch" so
  that such a lookup finds its event; that needs the flag set at the end, so the lookup runs a pass of its own and
  joins the fetches.
- **An interrupted pass fetches nothing more.** The spec says a waiter that is interrupted "goes on with whatever is
  cached". A pass that goes on to its next calendar with the interrupt flag set would start a download that fails at
  once and records a failure with a ten-minute backoff. So the pass checks the flag before each fetch and, once set,
  only publishes.
- **The backoff constant moves in Task 3, not Task 2.** It lives on `FeedFetches` (spec §3), which Task 3 creates;
  Task 2's commit replaces only the result records.
- **The concurrency tests get classes of their own** (`CalendarScheduleConcurrencyTest`,
  `TheSportsDbScheduleConcurrencyTest`) with a 30-second request timeout. The existing classes use 2 seconds, which
  a held answer would outlast on a slow machine.
- **A private `lock` object, not `this`,** so nothing outside a schedule can hold its monitor.
- **`FakeHttpServer.hold`** is new shared test support: a route that answers once a `CountDownLatch` opens, recording
  the request on arrival. The calendar and TheSportsDB fakes wrap it.

## Review Focus

1. **The first item lookup after a start, arriving while the first pass is still downloading,** waits for that pass
   and finds the event (an item page opened from a pin right after a restart). Pinned in Task 3:
   `CalendarScheduleConcurrencyTest.aFirstLookupDuringTheFirstPassWaitsForIt`,
   `TheSportsDbScheduleConcurrencyTest.aFirstLookupDuringTheFirstPassWaitsForIt`.
2. **A pass interrupted while it waits** (the rail cache stopping) returns at once and starts no further download, so
   no calendar or day records a failure it never had. Pinned in Task 3:
   `…ConcurrencyTest.aWaitingPassThatIsInterruptedStopsFetching` in both classes.
3. **A calendar or league removed while its download runs** does not come back in the result, in `find()` or in the
   status. Pinned in Task 3: `CalendarScheduleConcurrencyTest.aCalendarRemovedDuringItsFetchStaysRemoved`,
   `TheSportsDbScheduleConcurrencyTest.aLeagueForgottenDuringItsFetchIsNotCached`.
4. **A TheSportsDB key change while a day downloads** drops that day, so the next pass fetches it with the new key.
   Pinned in Task 3: `TheSportsDbScheduleConcurrencyTest.aClearDuringAFetchDropsThatDay`.
5. **A calendar added while a pass runs** shows up from its first parse in that pass's result, without a second
   download. Pinned in Task 3: `CalendarScheduleConcurrencyTest.aCalendarAddedDuringAPassIsPublishedFromItsFirstParse`.

---

## File Structure

Main, under `src/main/java/dev/andre/homecontrol/sources/sports/`:

| File | Change | Responsibility |
| --- | --- | --- |
| `feed/SportsFeed.java` | new (Task 2) | the contract of one kind of feed |
| `feed/FeedResult.java` | new (Task 2) | events, errors and counts of one or more feeds |
| `feed/FeedFetches.java` | new (Task 3) | one fetch per key at a time; the retry backoff |
| `feed/SportsEvent.java`, `feed/FeedStatus.java` | moved (Task 1) | unchanged |
| `settings/SportsSettings.java`, `SportsSettingsService.java`, `JsonFileSportsStore.java`, `SportsProperties.java`, `SportsTimeZones.java` | moved (Task 1) | unchanged |
| `SportsSchedule.java` | rewritten (Task 2) | merges a list of feeds |
| `SportsConfiguration.java` | edited (Task 2) | builds the feed list |
| `calendar/CalendarSchedule.java` | Task 2 contract, Task 3 rewrite | the calendar feed |
| `thesportsdb/TheSportsDbSchedule.java` | Task 2 contract, Task 3 rewrite | the TheSportsDB feed |

Test, under `src/test/java/dev/andre/homecontrol/`:

| File | Change |
| --- | --- |
| `sources/sports/settings/JsonFileSportsStoreTest.java`, `JsonFileSportsStoreEdgeCaseTest.java`, `SportsSettingsServiceTest.java`, `SportsTimeZonesTest.java` | moved (Task 1) |
| `sources/sports/feed/FeedResultTest.java`, `sources/sports/SportsScheduleTest.java` | new (Task 2) |
| `sources/sports/SportsContentSourceTest.java` | stub feeds (Task 2) |
| `sources/sports/feed/FeedFetchesTest.java` | new (Task 3) |
| `testsupport/FakeHttpServer.java`, `FakeHttpServerTest.java` | `hold` (Task 3) |
| `sources/sports/calendar/FakeCalendarServer.java`, `sources/sports/thesportsdb/FakeTheSportsDbServer.java` | `hold` wrappers (Task 3) |
| `sources/sports/calendar/CalendarScheduleConcurrencyTest.java`, `sources/sports/thesportsdb/TheSportsDbScheduleConcurrencyTest.java` | new (Task 3) |
| `ArchitectureTest.java` | two rules (Task 4) |

Docs: `docs/dev/architecture.md`, `docs/dev/testing.md` (Task 5).

The executor's helpers (`move.py` with `edit`, `add_import`, `read`, `write`; `extract.py`; `prune.py`) are copied
into this plan's workspace `.superpowers/sdd/2026-09-30-phase-3d-sports-feeds/` at setup. `edit(path, [(old, new[,
count])])` fails without writing when `old` does not occur exactly `count` times (default 1).

---

### Task 1: Move the shared types below the feeds

**Files:**
- Move (main): `SportsEvent` → `feed/`; `calendar/FeedStatus` → `feed/`; `SportsSettings`, `SportsSettingsService`,
  `JsonFileSportsStore`, `SportsProperties`, `SportsTimeZones` → `settings/`.
- Move (test): `JsonFileSportsStoreTest`, `JsonFileSportsStoreEdgeCaseTest`, `SportsSettingsServiceTest`,
  `SportsTimeZonesTest` → `settings/`.
- Modify: every file that imports or names a moved type (imports only).

**Interfaces:**
- Produces: `dev.andre.homecontrol.sources.sports.feed.{SportsEvent, FeedStatus}` and
  `dev.andre.homecontrol.sources.sports.settings.{SportsSettings, SportsSettingsService, JsonFileSportsStore,
  SportsProperties, SportsTimeZones}`, with their current members.

A pure move has no new behaviour to test first; the existing suite is its test (about 3,172 tests on main). The
check that the layers hold is Task 4's rule.

- [ ] **Step 1: Write the move script** to `.superpowers/sdd/2026-09-30-phase-3d-sports-feeds/move_sports_types.py`:

```python
"""Moves the shared sports types into sports.feed and sports.settings and fixes package lines and imports."""
import re
import subprocess
from pathlib import Path

REPO = Path("/home/docker1/home-control/.claude/worktrees/phase-0-defect-fixes")
BASE = "dev.andre.homecontrol.sources.sports"
MAIN = REPO / "src/main/java/dev/andre/homecontrol/sources/sports"
TEST = REPO / "src/test/java/dev/andre/homecontrol/sources/sports"
MAIN_MOVES = {"SportsEvent": ("", "feed"), "FeedStatus": ("calendar", "feed"),
              "SportsSettings": ("", "settings"), "SportsSettingsService": ("", "settings"),
              "JsonFileSportsStore": ("", "settings"), "SportsProperties": ("", "settings"),
              "SportsTimeZones": ("", "settings")}
TEST_MOVES = {"JsonFileSportsStoreTest": ("", "settings"), "JsonFileSportsStoreEdgeCaseTest": ("", "settings"),
              "SportsSettingsServiceTest": ("", "settings"), "SportsTimeZonesTest": ("", "settings")}
# Only files in these packages could name one another without an import before the move.
NEIGHBOURS = {BASE, BASE + ".calendar", BASE + ".feed", BASE + ".settings"}


def package(suffix):
    return BASE + ("." + suffix if suffix else "")


def own_package(text):
    return re.search(r"^package ([\w.]+);", text, re.M).group(1)


def move(tree, name, old, new):
    source = (tree / old if old else tree) / f"{name}.java"
    target = tree / new / f"{name}.java"
    target.parent.mkdir(exist_ok=True)
    subprocess.run(["git", "mv", str(source), str(target)], cwd=REPO, check=True)
    text = target.read_text()
    assert f"package {package(old)};" in text, target
    target.write_text(text.replace(f"package {package(old)};", f"package {package(new)};", 1))


def uses(text, simple):
    code = re.sub(r"^import .*$", "", text, flags=re.M)
    code = re.sub(r"/\*.*?\*/", "", code, flags=re.S)
    code = re.sub(r"//.*$", "", code, flags=re.M)
    return re.search(rf"(?<![\w.]){re.escape(simple)}\b", code) is not None


def add_import(text, qualified):
    line = f"import {qualified};\n"
    imports = list(re.finditer(r"^import (?!static )[\w.]+;\n", text, re.M))
    if not imports:
        end = re.search(r"^package [\w.]+;\n", text, re.M).end()
        return text[:end] + "\n" + line + text[end:]
    before = [m for m in imports if m.group(0) < line]
    anchor = before[-1].end() if before else imports[0].start()
    return text[:anchor] + line + text[anchor:]


for name, (old, new) in MAIN_MOVES.items():
    move(MAIN, name, old, new)
for name, (old, new) in TEST_MOVES.items():
    move(TEST, name, old, new)

# Qualified names of moved main types, in imports and in code, anywhere under src/.
for path in (REPO / "src").rglob("*.java"):
    text = path.read_text()
    changed = text
    for name, (old, new) in MAIN_MOVES.items():
        changed = re.sub(rf"\b{re.escape(package(old))}\.{name}\b", f"{package(new)}.{name}", changed)
    if changed != text:
        path.write_text(changed)

# In the neighbouring packages: drop imports of the own package, import what now lives next door.
files = [p for tree in (MAIN, TEST) for p in tree.rglob("*.java") if own_package(p.read_text()) in NEIGHBOURS]
types = {p.stem: own_package(p.read_text()) + "." + p.stem for p in files}
for path in files:
    text = path.read_text()
    original = text
    mine = own_package(text)
    text = re.sub(rf"^import {re.escape(mine)}\.\w+;\n", "", text, flags=re.M)
    for simple, qualified in sorted(types.items()):
        if qualified.rsplit(".", 1)[0] == mine or re.search(rf"^import [\w.]+\.{simple};", text, re.M):
            continue
        if uses(text, simple):
            text = add_import(text, qualified)
            print(f"{path.relative_to(REPO)}: import {qualified}")
    if text != original:
        path.write_text(text)
```

- [ ] **Step 2: Run it.** `python3 .superpowers/sdd/2026-09-30-phase-3d-sports-feeds/move_sports_types.py`.
  Expected: eleven `git mv`s without error, then a list of added imports: root classes (`SportsConfiguration`,
  `SportsContentSource`, `SportsSetupSection`, `SportsItems`, `LiveTodayRail`, …) and root tests importing from
  `feed` and `settings`; `CalendarSchedule` and `CalendarScheduleTest` importing `feed.FeedStatus`. Read the list:
  an import added because a type's name appears only in a string literal is removed by hand.

- [ ] **Step 3: Compile.** `scripts/gradle.sh compileJava compileTestJava compileE2eJava`. Expected: BUILD
  SUCCESSFUL. A failure naming a package-private member of a moved type is fixed by making that member public and
  recording a `Ruling:` in the ledger.

- [ ] **Step 4: Check the direction.** `grep -rn "import dev.andre.homecontrol.sources.sports\.[A-Z]"
  src/main/java/dev/andre/homecontrol/sources/sports/{calendar,thesportsdb,feed,settings,ics}`.
  Expected: no output (no feed, shared or library class imports the root package).

- [ ] **Step 5: Run the build.** `scripts/gradle.sh build`. Expected: green, the same test count as main.

- [ ] **Step 6: Commit** every path the move touched (renames included) with:

```
refactor: move the shared sports types below the feeds

SportsEvent and FeedStatus move to sports.feed; SportsSettings, SportsSettingsService,
JsonFileSportsStore, SportsProperties and SportsTimeZones to sports.settings. The calendar and
TheSportsDB feeds no longer import the root sports package, which ends the two package cycles
between them. Moves and imports only.
```

---

### Task 2: One feed contract and one result

**Files:**
- Create: `src/main/java/dev/andre/homecontrol/sources/sports/feed/SportsFeed.java`,
  `src/main/java/dev/andre/homecontrol/sources/sports/feed/FeedResult.java`
- Rewrite: `src/main/java/dev/andre/homecontrol/sources/sports/SportsSchedule.java`
- Modify: `SportsConfiguration.java` (the `sportsSchedule` bean), `calendar/CalendarSchedule.java`,
  `thesportsdb/TheSportsDbSchedule.java`
- Test (new): `src/test/java/dev/andre/homecontrol/sources/sports/feed/FeedResultTest.java`,
  `src/test/java/dev/andre/homecontrol/sources/sports/SportsScheduleTest.java`
- Test (modify): `SportsContentSourceTest.java`, `calendar/CalendarScheduleTest.java`,
  `thesportsdb/TheSportsDbScheduleTest.java`, `thesportsdb/TheSportsDbScheduleRetryTest.java`

**Interfaces:**
- Consumes: Task 1's packages.
- Produces:
  - `interface SportsFeed { String itemPrefix(); boolean configured(); FeedResult events(); Optional<SportsEvent> find(String itemId); }`
  - `record FeedResult(List<SportsEvent> events, List<String> errors, int feeds, int succeeded)` with
    `static final FeedResult NONE`, `FeedResult plus(FeedResult other)`, `boolean allFailed()`.
  - `SportsSchedule(List<SportsFeed> feeds)` with `hasFeeds()`, `List<SportsEvent> events()`,
    `Optional<SportsEvent> find(String itemId)`.
  - `CalendarSchedule implements SportsFeed` (`itemPrefix()` = `"ics:"`), `TheSportsDbSchedule implements
    SportsFeed` (`"tsdb:"`); `hasCalendars()` and `hasCompetitions()` are gone.

- [ ] **Step 1: Write the failing tests.**

```java
// File: src/test/java/dev/andre/homecontrol/sources/sports/feed/FeedResultTest.java
package dev.andre.homecontrol.sources.sports.feed;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FeedResultTest {

    private static final SportsEvent MATCH = new SportsEvent("ics:c-3f9a1c2b7d4e:069e696917c4a665",
            "calendar:c-3f9a1c2b7d4e", "SV Werder Bremen – FC Augsburg", Instant.parse("2026-09-19T13:30:00Z"),
            Instant.parse("2026-09-19T15:25:00Z"), null, null, SportsEvent.Status.SCHEDULED);
    private static final SportsEvent FIXTURE = new SportsEvent("tsdb:2601002", "thesportsdb:4328",
            "Arsenal vs Chelsea", Instant.parse("2026-09-19T16:30:00Z"), Instant.parse("2026-09-19T18:30:00Z"),
            null, null, SportsEvent.Status.SCHEDULED);

    @Test
    void plusKeepsTheOrderAndSumsTheCounts() {
        FeedResult calendars = new FeedResult(List.of(MATCH), List.of("Weekly sport: 127.0.0.1 answered HTTP 500"), 2, 1);
        FeedResult competitions = new FeedResult(List.of(FIXTURE), List.of("German Bundesliga: limited"), 1, 1);

        FeedResult both = calendars.plus(competitions);

        assertThat(both.events()).containsExactly(MATCH, FIXTURE);
        assertThat(both.errors()).containsExactly("Weekly sport: 127.0.0.1 answered HTTP 500",
                "German Bundesliga: limited");
        assertThat(both.feeds()).isEqualTo(3);
        assertThat(both.succeeded()).isEqualTo(2);
    }

    @Test
    void noneAddsNothing() {
        FeedResult one = new FeedResult(List.of(MATCH), List.of(), 1, 1);

        assertThat(FeedResult.NONE.plus(one)).isEqualTo(one);
    }

    @Test
    void allFailedNeedsFeedsNoSuccessAndAReason() {
        assertThat(new FeedResult(List.of(), List.of("Weekly sport: down"), 1, 0).allFailed()).isTrue();
        assertThat(new FeedResult(List.of(), List.of("Weekly sport: down"), 2, 1).allFailed()).isFalse();
        assertThat(new FeedResult(List.of(), List.of(), 1, 0).allFailed()).isFalse();
        assertThat(FeedResult.NONE.allFailed()).isFalse();
    }

    @Test
    void theListsAreCopied() {
        List<String> errors = new ArrayList<>(List.of("Weekly sport: down"));
        FeedResult result = new FeedResult(List.of(), errors, 1, 0);

        errors.clear();

        assertThat(result.errors()).containsExactly("Weekly sport: down");
    }
}
```

```java
// File: src/test/java/dev/andre/homecontrol/sources/sports/SportsScheduleTest.java
package dev.andre.homecontrol.sources.sports;

import dev.andre.homecontrol.core.content.ContentSourceException;
import dev.andre.homecontrol.sources.sports.feed.FeedResult;
import dev.andre.homecontrol.sources.sports.feed.SportsEvent;
import dev.andre.homecontrol.sources.sports.feed.SportsFeed;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SportsScheduleTest {

    /** A feed with a fixed answer; {@code known} is what {@link #find} can return. */
    private record StubFeed(String itemPrefix, boolean configured, FeedResult result, List<SportsEvent> known)
            implements SportsFeed {

        @Override
        public FeedResult events() {
            return result;
        }

        @Override
        public Optional<SportsEvent> find(String itemId) {
            return known.stream().filter(event -> event.itemId().equals(itemId)).findFirst();
        }
    }

    private static final SportsEvent MATCH = event("ics:c-3f9a1c2b7d4e:069e696917c4a665", "calendar:c-3f9a1c2b7d4e");
    private static final SportsEvent FIXTURE = event("tsdb:2601002", "thesportsdb:4328");

    private static SportsEvent event(String itemId, String competitionKey) {
        return new SportsEvent(itemId, competitionKey, "Match", Instant.parse("2026-09-19T13:30:00Z"),
                Instant.parse("2026-09-19T15:30:00Z"), null, null, SportsEvent.Status.SCHEDULED);
    }

    @Test
    void mergesTheFeedsInTheirOrder() {
        SportsSchedule schedule = new SportsSchedule(List.of(
                new StubFeed("ics:", true, new FeedResult(List.of(MATCH), List.of("Weekly sport: down"), 2, 1), List.of()),
                new StubFeed("tsdb:", true, new FeedResult(List.of(FIXTURE), List.of(), 1, 1), List.of())));

        assertThat(schedule.events()).containsExactly(MATCH, FIXTURE);
    }

    @Test
    void failsWithTheFirstReasonWhenNoFeedLoaded() {
        SportsSchedule schedule = new SportsSchedule(List.of(
                new StubFeed("ics:", true,
                        new FeedResult(List.of(), List.of("Weekly sport: 127.0.0.1 answered HTTP 500"), 1, 0), List.of()),
                new StubFeed("tsdb:", true,
                        new FeedResult(List.of(), List.of("German Bundesliga: limited"), 1, 0), List.of())));

        assertThatThrownBy(schedule::events)
                .isInstanceOfSatisfying(ContentSourceException.class,
                        e -> assertThat(e.kind()).isEqualTo(ContentSourceException.Kind.BAD_RESPONSE))
                .hasMessage("Weekly sport: 127.0.0.1 answered HTTP 500");
    }

    @Test
    void oneFeedThatLoadedIsEnough() {
        SportsSchedule schedule = new SportsSchedule(List.of(
                new StubFeed("ics:", true, new FeedResult(List.of(), List.of("Weekly sport: down"), 1, 0), List.of()),
                new StubFeed("tsdb:", true, new FeedResult(List.of(FIXTURE), List.of(), 1, 1), List.of())));

        assertThat(schedule.events()).containsExactly(FIXTURE);
    }

    @Test
    void findAsksOnlyTheFeedWhosePrefixMatches() {
        SportsEvent misplaced = event("tsdb:9", "calendar:c-3f9a1c2b7d4e");
        SportsSchedule schedule = new SportsSchedule(List.of(
                new StubFeed("ics:", true, FeedResult.NONE, List.of(MATCH, misplaced)),
                new StubFeed("tsdb:", true, FeedResult.NONE, List.of(FIXTURE))));

        assertThat(schedule.find(MATCH.itemId())).contains(MATCH);
        assertThat(schedule.find(FIXTURE.itemId())).contains(FIXTURE);
        assertThat(schedule.find("tsdb:9")).isEmpty();
        assertThat(schedule.find("yt:dQw4w9WgXcQ")).isEmpty();
    }

    @Test
    void hasFeedsWhenAnyFeedIsConfigured() {
        StubFeed noCalendars = new StubFeed("ics:", false, FeedResult.NONE, List.of());
        StubFeed competitions = new StubFeed("tsdb:", true, FeedResult.NONE, List.of());

        assertThat(new SportsSchedule(List.of(noCalendars, competitions)).hasFeeds()).isTrue();
        assertThat(new SportsSchedule(List.of(noCalendars)).hasFeeds()).isFalse();
        assertThat(new SportsSchedule(List.of()).hasFeeds()).isFalse();
    }
}
```

- [ ] **Step 2: Run them.** `scripts/gradle.sh test --tests 'dev.andre.homecontrol.sources.sports.*'`.
  Expected: compilation fails (`FeedResult`, `SportsFeed` and the list constructor do not exist).

- [ ] **Step 3: Write the contract and the result.**

```java
// File: src/main/java/dev/andre/homecontrol/sources/sports/feed/SportsFeed.java
package dev.andre.homecontrol.sources.sports.feed;

import java.util.Optional;

/** One kind of sports feed: every configured calendar, or every chosen competition. */
public interface SportsFeed {

    /** The prefix of this feed's item ids, such as {@code "ics:"}. */
    String itemPrefix();

    /** Whether anything of this kind is configured. */
    boolean configured();

    /** Every configured feed's events, after refreshing those that are due. */
    FeedResult events();

    Optional<SportsEvent> find(String itemId);
}
```

```java
// File: src/main/java/dev/andre/homecontrol/sources/sports/feed/FeedResult.java
package dev.andre.homecontrol.sources.sports.feed;

import java.util.ArrayList;
import java.util.List;

/** What one or more feeds gave: their events, why some failed, how many were asked and how many loaded. */
public record FeedResult(List<SportsEvent> events, List<String> errors, int feeds, int succeeded) {

    /** Nothing configured. */
    public static final FeedResult NONE = new FeedResult(List.of(), List.of(), 0, 0);

    public FeedResult {
        events = List.copyOf(events);
        errors = List.copyOf(errors);
    }

    /** This result followed by {@code other}: events and errors in order, the counts summed. */
    public FeedResult plus(FeedResult other) {
        List<SportsEvent> allEvents = new ArrayList<>(events);
        allEvents.addAll(other.events);
        List<String> allErrors = new ArrayList<>(errors);
        allErrors.addAll(other.errors);
        return new FeedResult(allEvents, allErrors, feeds + other.feeds, succeeded + other.succeeded);
    }

    /** Feeds were asked, none loaded, and at least one said why. */
    public boolean allFailed() {
        return feeds > 0 && succeeded == 0 && !errors.isEmpty();
    }
}
```

- [ ] **Step 4: Rewrite `SportsSchedule` and its bean.**

```java
// File: src/main/java/dev/andre/homecontrol/sources/sports/SportsSchedule.java
package dev.andre.homecontrol.sources.sports;

import dev.andre.homecontrol.core.content.ContentSourceException;
import dev.andre.homecontrol.sources.sports.feed.FeedResult;
import dev.andre.homecontrol.sources.sports.feed.SportsEvent;
import dev.andre.homecontrol.sources.sports.feed.SportsFeed;

import java.util.List;
import java.util.Optional;

/** Every configured feed as one list of events. Only fails when no feed has anything to show. */
public class SportsSchedule {

    private final List<SportsFeed> feeds;

    public SportsSchedule(List<SportsFeed> feeds) {
        this.feeds = List.copyOf(feeds);
    }

    public boolean hasFeeds() {
        return feeds.stream().anyMatch(SportsFeed::configured);
    }

    public List<SportsEvent> events() {
        FeedResult all = FeedResult.NONE;
        for (SportsFeed feed : feeds) {
            all = all.plus(feed.events());
        }
        if (all.allFailed()) {
            throw new ContentSourceException(ContentSourceException.Kind.BAD_RESPONSE, all.errors().getFirst());
        }
        return all.events();
    }

    public Optional<SportsEvent> find(String itemId) {
        return feeds.stream()
                .filter(feed -> itemId.startsWith(feed.itemPrefix()))
                .findFirst()
                .flatMap(feed -> feed.find(itemId));
    }
}
```

  In `SportsConfiguration`, replace the `sportsSchedule` bean (and import `java.util.ArrayList`, `java.util.List`,
  `dev.andre.homecontrol.sources.sports.feed.SportsFeed`):

```java
    /** Calendars first, then TheSportsDB when its module is on: the order of events and of the error shown. */
    @Bean
    public SportsSchedule sportsSchedule(CalendarSchedule calendars, ObjectProvider<TheSportsDbSchedule> competitions) {
        List<SportsFeed> feeds = new ArrayList<>();
        feeds.add(calendars);
        competitions.ifAvailable(feeds::add);
        return new SportsSchedule(feeds);
    }
```

- [ ] **Step 5: Put both schedules on the contract.** Save as
  `.superpowers/sdd/2026-09-30-phase-3d-sports-feeds/task2_contract.py` and run it:

```python
import sys
sys.path.insert(0, "/home/docker1/home-control/.claude/worktrees/phase-0-defect-fixes/.superpowers/sdd/2026-09-30-phase-3d-sports-feeds")
from move import MAIN, TEST, edit, add_import, read, write

SPORTS = MAIN + "sources/sports/"
FEED = "dev.andre.homecontrol.sources.sports.feed."
RESULT_RECORD = """    public record Result(List<SportsEvent> events, List<String> errors, int feeds, int succeeded) {
        public Result {
            events = List.copyOf(events);
            errors = List.copyOf(errors);
        }
    }

"""
FIND = ("    public Optional<SportsEvent> find(String itemId) {",
        "    @Override\n    public Optional<SportsEvent> find(String itemId) {")


def contract(prefix):
    return f"""    @Override
    public String itemPrefix() {{
        return "{prefix}";
    }}

    @Override
    public boolean configured() {{"""


calendar = SPORTS + "calendar/CalendarSchedule.java"
edit(calendar, [
    ("public class CalendarSchedule {", "public class CalendarSchedule implements SportsFeed {"),
    (RESULT_RECORD, ""),
    ("    public boolean hasCalendars() {", contract("ics:")),
    ("    public synchronized Result events() {", "    @Override\n    public synchronized FeedResult events() {"),
    ("        return new Result(events, errors, settings.calendars().size(), succeeded);",
     "        return new FeedResult(events, errors, settings.calendars().size(), succeeded);"),
    FIND,
])
competitions = SPORTS + "thesportsdb/TheSportsDbSchedule.java"
edit(competitions, [
    ("public class TheSportsDbSchedule {", "public class TheSportsDbSchedule implements SportsFeed {"),
    (RESULT_RECORD, ""),
    ("    public boolean hasCompetitions() {", contract("tsdb:")),
    ("    public synchronized Result events() {", "    @Override\n    public synchronized FeedResult events() {"),
    ("        return new Result(allEvents, errorList, settings.competitions().size(), succeeded);",
     "        return new FeedResult(allEvents, errorList, settings.competitions().size(), succeeded);"),
    ("    private Result keyUnavailable(", "    private FeedResult keyUnavailable("),
    ("        return new Result(List.of(), errorList, settings.competitions().size(), 0);",
     "        return new FeedResult(List.of(), errorList, settings.competitions().size(), 0);"),
    FIND,
])
for path in (calendar, competitions):
    add_import(path, FEED + "FeedResult")
    add_import(path, FEED + "SportsFeed")

for name in ("calendar/CalendarScheduleTest.java", "thesportsdb/TheSportsDbScheduleTest.java",
             "thesportsdb/TheSportsDbScheduleRetryTest.java"):
    path = TEST + "sources/sports/" + name
    text = read(path)
    assert "Schedule.Result " in text, path
    write(path, text.replace("CalendarSchedule.Result ", "FeedResult ").replace("TheSportsDbSchedule.Result ", "FeedResult "))
    add_import(path, FEED + "FeedResult")

content = TEST + "sources/sports/SportsContentSourceTest.java"
edit(content, [
    ("import dev.andre.homecontrol.sources.sports.calendar.CalendarSchedule;\n", ""),
    ("import dev.andre.homecontrol.sources.sports.thesportsdb.TheSportsDbSchedule;\n", ""),
    ("        CalendarSchedule calendarSchedule = mock(CalendarSchedule.class);\n"
     "        given(calendarSchedule.hasCalendars()).willReturn(false);",
     "        SportsFeed calendarSchedule = feed(\"ics:\", false);", 2),
    ("        CalendarSchedule calendarSchedule = mock(CalendarSchedule.class);\n"
     "        given(calendarSchedule.hasCalendars()).willReturn(true);",
     "        SportsFeed calendarSchedule = feed(\"ics:\", true);", 3),
    ("        TheSportsDbSchedule competitions = mock(TheSportsDbSchedule.class);\n"
     "        given(competitions.hasCompetitions()).willReturn(true);",
     "        SportsFeed competitions = feed(\"tsdb:\", true);"),
    ("        TheSportsDbSchedule competitions = mock(TheSportsDbSchedule.class);",
     "        SportsFeed competitions = feed(\"tsdb:\", true);"),
    ("new SportsSchedule(calendarSchedule, null)", "new SportsSchedule(List.of(calendarSchedule))", 4),
    ("new SportsSchedule(calendarSchedule, competitions)",
     "new SportsSchedule(List.of(calendarSchedule, competitions))", 2),
    ("    @Test\n    void isUnavailableWithoutFeeds() {",
     """    private static SportsFeed feed(String itemPrefix, boolean configured) {
        SportsFeed feed = mock(SportsFeed.class);
        given(feed.itemPrefix()).willReturn(itemPrefix);
        given(feed.configured()).willReturn(configured);
        return feed;
    }

    @Test
    void isUnavailableWithoutFeeds() {"""),
])
add_import(content, FEED + "SportsFeed")
```

- [ ] **Step 6: Run the sports tests.** `scripts/gradle.sh test --tests 'dev.andre.homecontrol.sources.sports.*'`.
  Expected: PASS, including `FeedResultTest` (4) and `SportsScheduleTest` (5).

- [ ] **Step 7: Run the build.** `scripts/gradle.sh build`. Expected: green; `grep -rn "hasCalendars\|hasCompetitions\|Schedule.Result" src` prints nothing.

- [ ] **Step 8: Commit** the files of this task with:

```
refactor: one SportsFeed contract and FeedResult for calendars and TheSportsDB

CalendarSchedule and TheSportsDbSchedule implement SportsFeed, and one FeedResult replaces
their two identical Result records. SportsSchedule merges any list of feeds and routes a
lookup by the feed's item prefix; SportsConfiguration lists calendars first, then TheSportsDB
when its module is on, which keeps the order of events and of the error shown.
```

---

### Task 3: Fetch outside the lock

**Files:**
- Create: `src/main/java/dev/andre/homecontrol/sources/sports/feed/FeedFetches.java`
- Rewrite: `calendar/CalendarSchedule.java`, `thesportsdb/TheSportsDbSchedule.java`
- Modify (test support): `testsupport/FakeHttpServer.java`, `sources/sports/calendar/FakeCalendarServer.java`,
  `sources/sports/thesportsdb/FakeTheSportsDbServer.java`
- Test (new): `sources/sports/feed/FeedFetchesTest.java`, `sources/sports/calendar/CalendarScheduleConcurrencyTest.java`,
  `sources/sports/thesportsdb/TheSportsDbScheduleConcurrencyTest.java`
- Test (modify): `testsupport/FakeHttpServerTest.java`

**Interfaces:**
- Consumes: Task 2's `SportsFeed`, `FeedResult`; Task 1's packages.
- Produces:
  - `final class FeedFetches<K>`: `static final Duration RETRY_BACKOFF` (10 minutes), `void run(K key, Runnable fetch)`.
  - `FakeHttpServer hold(String method, String path, CountDownLatch release, Response answer)` and
    `hold(String method, String path, Predicate<Request> when, CountDownLatch release, Response answer)`.
  - `FakeCalendarServer holdFixture(String path, String fixtureName, CountDownLatch release)`.
  - `FakeTheSportsDbServer hold(String endpoint, Map<String, String> query, int status, String fixture, CountDownLatch release)`.

- [ ] **Step 1: A held route in the shared fake.** Add to `FakeHttpServerTest` (import `java.util.concurrent.TimeUnit`):

```java
    @Test
    void aHeldRouteRecordsTheRequestAtOnceAndAnswersWhenReleased() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        server.hold(ANY_METHOD, "/held", release, Response.of(200, "text/plain", "late"));

        CompletableFuture<HttpResponse<String>> answer = client.sendAsync(
                HttpRequest.newBuilder(server.url("/held")).build(), HttpResponse.BodyHandlers.ofString());
        await().until(() -> server.count(ANY_METHOD, "/held") == 1);
        assertThat(answer).isNotDone();

        release.countDown();

        assertThat(answer.get(5, TimeUnit.SECONDS).body()).isEqualTo("late");
    }
```

  Run `scripts/gradle.sh test --tests 'dev.andre.homecontrol.testsupport.FakeHttpServerTest'`. Expected: compilation
  fails (`hold`). Then add to `FakeHttpServer` (import `java.util.concurrent.CountDownLatch`), after `handle`:

```java
    /** Answers {@code method path} with {@code answer} once {@code release} opens; the request is recorded on arrival. */
    public FakeHttpServer hold(String method, String path, CountDownLatch release, Response answer) {
        return hold(method, path, request -> true, release, answer);
    }

    /** Like {@link #hold(String, String, CountDownLatch, Response)}, for requests {@code when} accepts. */
    public FakeHttpServer hold(String method, String path, Predicate<Request> when, CountDownLatch release,
                               Response answer) {
        return route(method, path, when, exchange -> {
            try {
                release.await();
            } catch (InterruptedException _) {
                // The server is closing: the exchange ends unanswered.
                Thread.currentThread().interrupt();
                return;
            }
            write(exchange, answer);
        });
    }
```

  Run the test again. Expected: PASS.

- [ ] **Step 2: The two feed fakes wrap it.** In `FakeCalendarServer` (import `java.util.concurrent.CountDownLatch`),
  replace `respondFixture` and add `holdFixture` and `fixtureBytes`:

```java
    public FakeCalendarServer respondFixture(String path, String fixtureName) {
        return respondBytes(path, 200, "text/calendar; charset=utf-8", fixtureBytes(fixtureName));
    }

    /** Answers {@code path} with the fixture only once {@code release} opens; the request is recorded on arrival. */
    public FakeCalendarServer holdFixture(String path, String fixtureName, CountDownLatch release) {
        server.hold(ANY_METHOD, path, release, Response.of(200, "text/calendar; charset=utf-8", fixtureBytes(fixtureName)));
        return this;
    }

    private static byte[] fixtureBytes(String fixtureName) {
        try (InputStream in = FakeCalendarServer.class.getResourceAsStream("/fixtures/ics/" + fixtureName)) {
            if (in == null) {
                throw new IllegalArgumentException("No fixture " + fixtureName);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
```

  In `FakeTheSportsDbServer` (import `java.util.concurrent.CountDownLatch`), after `respondJson`:

```java
    /** Like {@link #respond}, but the answer waits until {@code release} opens; the request is recorded on arrival. */
    public FakeTheSportsDbServer hold(String endpoint, Map<String, String> query, int status, String fixture,
                                      CountDownLatch release) {
        Map<String, String> expected = Map.copyOf(query);
        for (String key : KEYS) {
            server.hold(ANY_METHOD, PREFIX + key + "/" + endpoint, request -> request.query().equals(expected), release,
                    json(status, fixtureBytes(fixture)));
        }
        return this;
    }
```

- [ ] **Step 3: Write `FeedFetchesTest`.**

```java
// File: src/test/java/dev/andre/homecontrol/sources/sports/feed/FeedFetchesTest.java
package dev.andre.homecontrol.sources.sports.feed;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class FeedFetchesTest {

    private final FeedFetches<String> fetches = new FeedFetches<>();
    private final CountDownLatch release = new CountDownLatch(1);
    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicInteger runs = new AtomicInteger();

    @AfterEach
    void tearDown() {
        release.countDown();
        pool.shutdownNow();
    }

    /** A fetch that counts itself and then waits for the test. */
    private void heldFetch() {
        runs.incrementAndGet();
        try {
            release.await();
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    private Future<?> startHeld(String key) {
        Future<?> running = pool.submit(() -> fetches.run(key, this::heldFetch));
        await().until(() -> runs.get() == 1);
        return running;
    }

    private static void awaitStillWaiting(Future<?> waiter) {
        await().during(Duration.ofMillis(200)).atMost(Duration.ofSeconds(2)).until(() -> !waiter.isDone());
    }

    @Test
    void aSecondCallerWaitsForTheRunningFetchInsteadOfStartingOne() throws Exception {
        Future<?> first = startHeld("calendar");
        AtomicInteger secondRuns = new AtomicInteger();
        Future<?> second = pool.submit(() -> fetches.run("calendar", secondRuns::incrementAndGet));
        awaitStillWaiting(second);

        release.countDown();
        first.get(5, TimeUnit.SECONDS);
        second.get(5, TimeUnit.SECONDS);

        assertThat(runs).hasValue(1);
        assertThat(secondRuns).hasValue(0);
    }

    @Test
    void anotherKeyDoesNotWait() {
        startHeld("calendar");
        AtomicInteger other = new AtomicInteger();

        fetches.run("competition", other::incrementAndGet);

        assertThat(other).hasValue(1);
    }

    @Test
    void aCallerAfterTheFetchEndedRunsItsOwn() {
        fetches.run("calendar", runs::incrementAndGet);
        fetches.run("calendar", runs::incrementAndGet);

        assertThat(runs).hasValue(2);
    }

    @Test
    void aFailingFetchReachesTheRunnerAndEveryWaiterAndFreesTheKey() throws Exception {
        Future<?> first = pool.submit(() -> fetches.run("calendar", () -> {
            heldFetch();
            throw new IllegalStateException("parser bug");
        }));
        await().until(() -> runs.get() == 1);
        Future<?> second = pool.submit(() -> fetches.run("calendar", runs::incrementAndGet));
        awaitStillWaiting(second);

        release.countDown();

        assertThatThrownBy(() -> first.get(5, TimeUnit.SECONDS))
                .hasCauseInstanceOf(IllegalStateException.class).hasRootCauseMessage("parser bug");
        assertThatThrownBy(() -> second.get(5, TimeUnit.SECONDS))
                .hasCauseInstanceOf(IllegalStateException.class).hasRootCauseMessage("parser bug");
        fetches.run("calendar", runs::incrementAndGet);
        assertThat(runs).hasValue(2);
    }

    @Test
    void anInterruptedWaiterReturnsWhileTheFetchRunsAndKeepsItsFlag() throws Exception {
        startHeld("calendar");
        AtomicBoolean stillInterrupted = new AtomicBoolean();
        Thread waiter = Thread.ofVirtual().start(() -> {
            fetches.run("calendar", runs::incrementAndGet);
            stillInterrupted.set(Thread.currentThread().isInterrupted());
        });
        await().during(Duration.ofMillis(200)).atMost(Duration.ofSeconds(2)).until(waiter::isAlive);

        waiter.interrupt();

        assertThat(waiter.join(Duration.ofSeconds(5))).isTrue();
        assertThat(stillInterrupted).isTrue();
        assertThat(runs).hasValue(1);
    }
}
```

  Run `scripts/gradle.sh test --tests 'dev.andre.homecontrol.sources.sports.feed.*'`. Expected: compilation fails
  (`FeedFetches`).

- [ ] **Step 4: Write `FeedFetches`.**

```java
// File: src/main/java/dev/andre/homecontrol/sources/sports/feed/FeedFetches.java
package dev.andre.homecontrol.sources.sports.feed;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

/**
 * At most one fetch per key at a time. A caller that finds its key's fetch running waits for that one instead of
 * starting another. The fetch runs on the thread of the caller that started it; no lock is held while anyone waits.
 */
public final class FeedFetches<K> {

    /** How long a feed that failed is left alone before it is tried again. */
    public static final Duration RETRY_BACKOFF = Duration.ofMinutes(10);

    private final ConcurrentHashMap<K, FutureTask<Void>> running = new ConcurrentHashMap<>();

    /**
     * Runs {@code fetch} for {@code key}, or waits for the fetch already running for it, and returns when that one is
     * done. What the fetch throws reaches the caller that ran it and every caller that waited. A waiter that is
     * interrupted returns at once, with its interrupt flag set.
     */
    public void run(K key, Runnable fetch) {
        FutureTask<Void> mine = new FutureTask<>(fetch, null);
        FutureTask<Void> theirs = running.putIfAbsent(key, mine);
        if (theirs != null) {
            await(theirs);
            return;
        }
        try {
            mine.run();
        } finally {
            running.remove(key, mine);
        }
        await(mine);
    }

    private static void await(FutureTask<Void> task) {
        try {
            task.get();
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            switch (e.getCause()) {
                case RuntimeException runtime -> throw runtime;
                case Error error -> throw error;
                default -> throw new IllegalStateException(e.getCause());
            }
        }
    }
}
```

  Run the feed tests again. Expected: PASS (5 in `FeedFetchesTest`, 4 in `FeedResultTest`).

- [ ] **Step 5: Write `CalendarScheduleConcurrencyTest`.** The two tests marked "guard" hold today too (the lock
  serialised them); they pin that the rewrite keeps one download per calendar and never blocks `status`/`forget`.

```java
// File: src/test/java/dev/andre/homecontrol/sources/sports/calendar/CalendarScheduleConcurrencyTest.java
package dev.andre.homecontrol.sources.sports.calendar;

import dev.andre.homecontrol.sources.http.OutboundAddressPolicy;
import dev.andre.homecontrol.sources.sports.feed.FeedResult;
import dev.andre.homecontrol.sources.sports.feed.SportsEvent;
import dev.andre.homecontrol.sources.sports.ics.IcsParser;
import dev.andre.homecontrol.sources.sports.settings.JsonFileSportsStore;
import dev.andre.homecontrol.sources.sports.settings.SportsProperties;
import dev.andre.homecontrol.sources.sports.settings.SportsSettings;
import dev.andre.homecontrol.sources.sports.settings.SportsSettingsService;
import dev.andre.homecontrol.sources.sports.settings.SportsTimeZones;
import dev.andre.homecontrol.storage.SecretStore;
import dev.andre.homecontrol.testsupport.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/** Passes that meet: downloads run without the schedule's lock, and a second caller joins a running one. */
class CalendarScheduleConcurrencyTest {

    private static final String BUNDESLIGA = "c-3f9a1c2b7d4e";
    private static final String WEEKLY = "c-00000000000a";
    private static final String BUNDESLIGA_PATH = "/private/token-abc123/bl.ics";
    private static final String WEEKLY_PATH = "/weekly.ics";
    private static final String BUNDESLIGA_ITEM = "ics:c-3f9a1c2b7d4e:069e696917c4a665";

    @TempDir
    Path dir;

    private final CountDownLatch release = new CountDownLatch(1);
    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
    private FakeCalendarServer server;
    private SportsSettingsService settingsService;
    private MutableClock clock;
    private CalendarSchedule schedule;

    @BeforeEach
    void setUp() throws IOException {
        server = new FakeCalendarServer();
        server.respondFixture(BUNDESLIGA_PATH, "bundesliga.ics");
        server.respondFixture(WEEKLY_PATH, "recurring.ics");

        SecretStore secrets = mock(SecretStore.class);
        given(secrets.secret("sports.calendar." + BUNDESLIGA)).willReturn(Optional.of(server.url(BUNDESLIGA_PATH).toString()));
        given(secrets.secret("sports.calendar." + WEEKLY)).willReturn(Optional.of(server.url(WEEKLY_PATH).toString()));

        settingsService = new SportsSettingsService(new JsonFileSportsStore(dir.resolve("sports.json")),
                mock(ApplicationEventPublisher.class));
        settingsService.update(s -> s.withCalendars(List.of(
                new SportsSettings.CalendarEntry(BUNDESLIGA, "Bundesliga 2026/27", "127.0.0.1", null, Instant.EPOCH),
                new SportsSettings.CalendarEntry(WEEKLY, "Weekly sport", "127.0.0.1", null, Instant.EPOCH))));

        SportsTimeZones zones = mock(SportsTimeZones.class);
        given(zones.effective()).willReturn(ZoneId.of("Europe/Berlin"));
        clock = MutableClock.at(Instant.parse("2026-09-19T14:00:00Z"));
        // A held answer waits for the test, so the request timeout must outlast any test.
        SportsProperties properties = new SportsProperties(true, "", 30, 10, 10, Duration.ofMinutes(120),
                new SportsProperties.Calendar(Duration.ofHours(6), Duration.ofSeconds(1), Duration.ofSeconds(30),
                        5242880, 3, true),
                new SportsProperties.TheSportsDb(true, URI.create("http://127.0.0.1:9/api/v1/json"), "123",
                        Duration.ofHours(24), Duration.ofSeconds(1), Duration.ofSeconds(2), null, true));
        schedule = new CalendarSchedule(settingsService,
                new CalendarFetcher(properties.calendar(), new OutboundAddressPolicy(true)), secrets, properties,
                zones, clock);
    }

    @AfterEach
    void tearDown() {
        release.countDown();
        pool.shutdownNow();
        server.close();
    }

    private Future<FeedResult> passHeldAt(String path, int requestsSoFar) {
        Future<FeedResult> pass = pool.submit(schedule::events);
        await().until(() -> server.count(path) == requestsSoFar);
        return pass;
    }

    private static void awaitStillWaiting(Future<?> waiter) {
        await().during(Duration.ofMillis(200)).atMost(Duration.ofSeconds(2)).until(() -> !waiter.isDone());
    }

    @Test
    void aSecondPassJoinsTheRunningFetchInsteadOfSendingAnother() throws Exception {  // guard
        server.holdFixture(WEEKLY_PATH, "recurring.ics", release);
        Future<FeedResult> first = passHeldAt(WEEKLY_PATH, 1);
        Future<FeedResult> second = pool.submit(schedule::events);
        awaitStillWaiting(second);

        release.countDown();

        assertThat(first.get(10, TimeUnit.SECONDS).succeeded()).isEqualTo(2);
        assertThat(second.get(10, TimeUnit.SECONDS).succeeded()).isEqualTo(2);
        assertThat(server.count(WEEKLY_PATH)).isEqualTo(1);
    }

    @Test
    void aFirstLookupDuringTheFirstPassWaitsForIt() throws Exception {
        server.holdFixture(BUNDESLIGA_PATH, "bundesliga.ics", release);
        Future<FeedResult> pass = passHeldAt(BUNDESLIGA_PATH, 1);
        Future<Optional<SportsEvent>> found = pool.submit(() -> schedule.find(BUNDESLIGA_ITEM));
        awaitStillWaiting(found);

        release.countDown();

        assertThat(found.get(10, TimeUnit.SECONDS)).isPresent();
        pass.get(10, TimeUnit.SECONDS);
        assertThat(server.count(BUNDESLIGA_PATH)).isEqualTo(1);
    }

    @Test
    void aWaitingPassThatIsInterruptedStopsFetching() throws Exception {
        server.holdFixture(BUNDESLIGA_PATH, "bundesliga.ics", release);
        Future<FeedResult> first = passHeldAt(BUNDESLIGA_PATH, 1);
        AtomicReference<FeedResult> secondResult = new AtomicReference<>();
        AtomicBoolean stillInterrupted = new AtomicBoolean();
        Thread second = Thread.ofVirtual().start(() -> {
            secondResult.set(schedule.events());
            stillInterrupted.set(Thread.currentThread().isInterrupted());
        });
        await().during(Duration.ofMillis(200)).atMost(Duration.ofSeconds(2)).until(second::isAlive);

        second.interrupt();

        assertThat(second.join(Duration.ofSeconds(10))).as("returns while the download is held").isTrue();
        assertThat(stillInterrupted).isTrue();
        assertThat(secondResult.get().succeeded()).isZero();
        assertThat(secondResult.get().errors()).isEmpty();
        assertThat(server.count(WEEKLY_PATH)).isZero();

        release.countDown();
        assertThat(first.get(10, TimeUnit.SECONDS).succeeded()).isEqualTo(2);
    }

    @Test
    void aCalendarRemovedDuringItsFetchStaysRemoved() throws Exception {
        server.holdFixture(BUNDESLIGA_PATH, "bundesliga.ics", release);
        Future<FeedResult> pass = passHeldAt(BUNDESLIGA_PATH, 1);

        // What SportsCalendars.remove does: settings first, then the schedule.
        settingsService.update(s -> s.withCalendars(List.of(s.calendars().get(1))));
        schedule.forget(BUNDESLIGA);
        release.countDown();

        FeedResult result = pass.get(10, TimeUnit.SECONDS);
        assertThat(result.feeds()).isEqualTo(1);
        assertThat(result.events()).noneMatch(e -> e.competitionKey().equals("calendar:" + BUNDESLIGA));
        assertThat(schedule.find(BUNDESLIGA_ITEM)).isEmpty();
        assertThat(schedule.status(BUNDESLIGA)).isEmpty();
    }

    @Test
    void aCalendarAddedDuringAPassIsPublishedFromItsFirstParse() throws Exception {
        server.holdFixture(WEEKLY_PATH, "recurring.ics", release);
        Future<FeedResult> pass = passHeldAt(WEEKLY_PATH, 1);

        // What SportsCalendars.add does: settings first, then the first parse.
        String added = "c-00000000000b";
        settingsService.update(s -> s.withCalendars(List.of(s.calendars().get(0), s.calendars().get(1),
                new SportsSettings.CalendarEntry(added, "Cup", "127.0.0.1", null, Instant.EPOCH))));
        schedule.prime(added, IcsParser.parse(fixture("bundesliga.ics")));
        release.countDown();

        FeedResult result = pass.get(10, TimeUnit.SECONDS);
        assertThat(result.feeds()).isEqualTo(3);
        assertThat(result.errors()).isEmpty();
        assertThat(result.events()).anyMatch(e -> e.competitionKey().equals("calendar:" + added));
    }

    @Test
    void statusAndForgetDoNotWaitForARunningFetch() throws Exception {  // guard
        schedule.events();
        clock.advance(Duration.ofHours(6));
        server.holdFixture(WEEKLY_PATH, "recurring.ics", release);
        Future<FeedResult> pass = passHeldAt(WEEKLY_PATH, 2);

        assertThat(pool.submit(() -> schedule.status(BUNDESLIGA)).get(5, TimeUnit.SECONDS)).isPresent();
        pool.submit(() -> schedule.forget(BUNDESLIGA)).get(5, TimeUnit.SECONDS);
        assertThat(pass).isNotDone();

        release.countDown();
        pass.get(10, TimeUnit.SECONDS);
    }

    private static String fixture(String name) throws IOException {
        try (InputStream in = CalendarScheduleConcurrencyTest.class.getResourceAsStream("/fixtures/ics/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
```

- [ ] **Step 6: Run it.** `scripts/gradle.sh test --tests 'dev.andre.homecontrol.sources.sports.calendar.CalendarScheduleConcurrencyTest'`.
  Expected: the two guards PASS; four FAIL today:
  `aFirstLookupDuringTheFirstPassWaitsForIt` (the lookup answers at once, empty),
  `aWaitingPassThatIsInterruptedStopsFetching` (the second pass stays blocked on the monitor; `join` is false),
  `aCalendarRemovedDuringItsFetchStaysRemoved` (feeds 2, the removed calendar's events are back),
  `aCalendarAddedDuringAPassIsPublishedFromItsFirstParse` (feeds 2, no events of the new calendar).

- [ ] **Step 7: Rewrite `CalendarSchedule`.** `refresh`, `parse`, `status`, `prime`, `toEvent` and `hashHex16` keep
  their bodies; `find` gains `@Override`. The whole file:

```java
// File: src/main/java/dev/andre/homecontrol/sources/sports/calendar/CalendarSchedule.java
package dev.andre.homecontrol.sources.sports.calendar;

import dev.andre.homecontrol.core.content.ContentSourceException;
import dev.andre.homecontrol.sources.sports.feed.FeedFetches;
import dev.andre.homecontrol.sources.sports.feed.FeedResult;
import dev.andre.homecontrol.sources.sports.feed.FeedStatus;
import dev.andre.homecontrol.sources.sports.feed.SportsEvent;
import dev.andre.homecontrol.sources.sports.feed.SportsFeed;
import dev.andre.homecontrol.sources.sports.ics.IcsCalendar;
import dev.andre.homecontrol.sources.sports.ics.IcsFormatException;
import dev.andre.homecontrol.sources.sports.ics.IcsOccurrence;
import dev.andre.homecontrol.sources.sports.ics.IcsOccurrences;
import dev.andre.homecontrol.sources.sports.ics.IcsParser;
import dev.andre.homecontrol.sources.sports.settings.SportsProperties;
import dev.andre.homecontrol.sources.sports.settings.SportsSettings;
import dev.andre.homecontrol.sources.sports.settings.SportsSettingsService;
import dev.andre.homecontrol.sources.sports.settings.SportsTimeZones;
import dev.andre.homecontrol.storage.SecretStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Every configured calendar, refetched on a schedule and expanded into a rolling window. Only fails a
 * calendar; a calendar keeps its last good parse across a transient failure.
 *
 * <p>No lock is held while a calendar downloads: {@link FeedFetches} runs one download per calendar at a time, and a
 * pass that finds one running waits for it. {@link #lock} guards only short steps: writing a download's outcome,
 * publishing a pass, and {@link #forget}. {@code forget} advances {@link #generation}, and a download that started
 * under an older generation drops its outcome, so a removal is never undone by a download that was already running.
 */
public class CalendarSchedule implements SportsFeed {

    private static final Logger log = LoggerFactory.getLogger(CalendarSchedule.class);
    private static final Duration WINDOW_BEFORE = Duration.ofDays(1);
    private static final Duration WINDOW_AFTER = Duration.ofDays(8);

    private record Cached(IcsCalendar calendar, Instant fetchedAt, Instant lastAttempt, String error) {
    }

    private final SportsSettingsService settingsService;
    private final CalendarFetcher fetcher;
    private final SecretStore secrets;
    private final SportsProperties properties;
    private final SportsTimeZones zones;
    private final Clock clock;

    private final Object lock = new Object();
    private final ConcurrentHashMap<String, Cached> cache = new ConcurrentHashMap<>();
    private final FeedFetches<String> fetches = new FeedFetches<>();
    private final AtomicLong generation = new AtomicLong();
    /** Replaced under {@link #lock}, by a pass's publish step and by {@link #forget}; read without it. */
    private final AtomicReference<Map<String, SportsEvent>> byItemId = new AtomicReference<>(Map.of());
    /** Set when a pass ends, so a lookup that arrives during the first pass runs one too and joins its downloads. */
    private volatile boolean ranOnce;

    public CalendarSchedule(SportsSettingsService settingsService, CalendarFetcher fetcher, SecretStore secrets,
                            SportsProperties properties, SportsTimeZones zones, Clock clock) {
        this.settingsService = settingsService;
        this.fetcher = fetcher;
        this.secrets = secrets;
        this.properties = properties;
        this.zones = zones;
        this.clock = clock;
    }

    @Override
    public String itemPrefix() {
        return "ics:";
    }

    @Override
    public boolean configured() {
        return !settingsService.current().calendars().isEmpty();
    }

    @Override
    public FeedResult events() {
        try {
            return pass();
        } finally {
            ranOnce = true;
        }
    }

    private FeedResult pass() {
        SportsSettings settings = settingsService.current();
        Instant now = clock.instant();
        Set<String> known = settings.calendars().stream()
                .map(SportsSettings.CalendarEntry::id).collect(Collectors.toSet());
        cache.keySet().removeIf(id -> !known.contains(id));
        for (SportsSettings.CalendarEntry entry : settings.calendars()) {
            // A pass whose wait was interrupted downloads nothing more; it still publishes what is cached.
            if (Thread.currentThread().isInterrupted()) {
                break;
            }
            if (due(cache.get(entry.id()), now)) {
                fetches.run(entry.id(), () -> refreshIfStillDue(entry, now));
            }
        }
        return publish(now);
    }

    private boolean due(Cached cached, Instant now) {
        boolean stale = cached == null || cached.fetchedAt() == null
                || !cached.fetchedAt().plus(properties.calendar().refresh()).isAfter(now);
        boolean cooledDown = cached == null || cached.error() == null || cached.lastAttempt() == null
                || !cached.lastAttempt().plus(FeedFetches.RETRY_BACKOFF).isAfter(now);
        return stale && cooledDown;
    }

    /** One download: checks again, downloads without a lock, and writes the outcome unless a forget came between. */
    private void refreshIfStillDue(SportsSettings.CalendarEntry entry, Instant now) {
        long started = generation.get();
        if (settingsService.current().calendar(entry.id()).isEmpty()) {
            return;
        }
        Cached previous = cache.get(entry.id());
        if (!due(previous, now)) {
            return;
        }
        Cached next = refresh(entry, previous, now);
        synchronized (lock) {
            if (generation.get() == started) {
                cache.put(entry.id(), next);
            }
        }
    }

    /** The result for the calendars configured now, from the cache; also replaces the item index. */
    private FeedResult publish(Instant now) {
        synchronized (lock) {
            SportsSettings settings = settingsService.current();
            Instant windowStart = now.minus(WINDOW_BEFORE);
            Instant windowEnd = now.plus(WINDOW_AFTER);
            List<SportsEvent> events = new ArrayList<>();
            List<String> errors = new ArrayList<>();
            int succeeded = 0;
            Map<String, SportsEvent> byId = new HashMap<>();
            for (SportsSettings.CalendarEntry entry : settings.calendars()) {
                Cached cached = cache.get(entry.id());
                if (cached != null && cached.calendar() != null) {
                    succeeded++;
                    IcsOccurrences.Result expanded = IcsOccurrences.expand(cached.calendar(), zones.effective(),
                            windowStart, windowEnd, properties.defaultEventDuration());
                    for (IcsOccurrence occurrence : expanded.occurrences()) {
                        SportsEvent event = toEvent(entry.id(), occurrence);
                        events.add(event);
                        byId.put(event.itemId(), event);
                    }
                }
                if (cached != null && cached.error() != null) {
                    errors.add(entry.label() + ": " + cached.error());
                }
            }
            byItemId.set(Map.copyOf(byId));
            return new FeedResult(events, errors, settings.calendars().size(), succeeded);
        }
    }

    private Cached refresh(SportsSettings.CalendarEntry entry, Cached previous, Instant now) {
        IcsCalendar keep = previous == null ? null : previous.calendar();
        Instant keptFetchedAt = previous == null ? null : previous.fetchedAt();
        Optional<String> secret = secrets.secret(SportsCalendars.secretName(entry.id()));
        if (secret.isEmpty()) {
            log.warn("Calendar {} could not be refreshed ({})", entry.id(), "MISSING_SECRET");
            return new Cached(keep, keptFetchedAt, now,
                    "The link for " + entry.label() + " is missing; remove the calendar and add it again");
        }
        try {
            IcsCalendar calendar = parse(fetcher.fetch(URI.create(secret.get())));
            return new Cached(calendar, now, now, null);
        } catch (ContentSourceException e) {
            String kind = e instanceof CalendarFetchException cfe ? cfe.kind().name() : "ERROR";
            log.warn("Calendar {} could not be refreshed ({})", entry.id(), kind);
            return new Cached(keep, keptFetchedAt, now, e.getMessage());
        }
    }

    private static IcsCalendar parse(String text) {
        try {
            return IcsParser.parse(text);
        } catch (IcsFormatException e) {
            throw new CalendarFetchException(ContentSourceException.Kind.BAD_RESPONSE, e.getMessage());
        }
    }

    @Override
    public Optional<SportsEvent> find(String itemId) {
        if (!ranOnce) {
            events();
        }
        return Optional.ofNullable(byItemId.get().get(itemId));
    }

    public Optional<FeedStatus> status(String calendarId) {
        if (!ranOnce) {
            events();
        }
        SportsSettings settings = settingsService.current();
        if (settings.calendar(calendarId).isEmpty()) {
            return Optional.empty();
        }
        Cached cached = cache.get(calendarId);
        if (cached == null) {
            return Optional.of(new FeedStatus(null, 0, null, 0, 0, 0));
        }
        int events = 0;
        int unsupported = 0;
        int unknownZones = 0;
        int skipped = 0;
        if (cached.calendar() != null) {
            Instant now = clock.instant();
            IcsOccurrences.Result expanded = IcsOccurrences.expand(cached.calendar(), zones.effective(),
                    now.minus(WINDOW_BEFORE), now.plus(WINDOW_AFTER), properties.defaultEventDuration());
            events = expanded.occurrences().size();
            unsupported = expanded.unsupportedRules();
            unknownZones = expanded.unknownZones();
            skipped = cached.calendar().skippedEvents();
        }
        return Optional.of(new FeedStatus(cached.fetchedAt(), events, cached.error(), unsupported, unknownZones, skipped));
    }

    public void prime(String calendarId, IcsCalendar calendar) {
        Instant now = clock.instant();
        cache.put(calendarId, new Cached(calendar, now, now, null));
    }

    public void forget(String calendarId) {
        synchronized (lock) {
            generation.incrementAndGet();
            cache.remove(calendarId);
            String key = SportsSettings.calendarKey(calendarId);
            byItemId.updateAndGet(current -> {
                Map<String, SportsEvent> next = new HashMap<>(current);
                next.values().removeIf(event -> event.competitionKey().equals(key));
                return Map.copyOf(next);
            });
        }
    }


    public static SportsEvent toEvent(String calendarId, IcsOccurrence occurrence) {
        String stripped = occurrence.summary() == null ? "" : occurrence.summary().strip();
        String title = stripped.isBlank() ? "Event" : stripped;
        if (title.length() > 200) {
            title = title.substring(0, 199) + "…";
        }
        String hashInput = occurrence.uid() != null
                ? occurrence.uid() + "|" + occurrence.startsAt().getEpochSecond()
                : "no-uid|" + occurrence.summary() + "|" + occurrence.startsAt().getEpochSecond();
        String itemId = "ics:" + calendarId + ":" + hashHex16(hashInput);
        return new SportsEvent(itemId, SportsSettings.calendarKey(calendarId), title, occurrence.startsAt(),
                occurrence.endsAt(), occurrence.allDayDate(), null, SportsEvent.Status.SCHEDULED);
    }

    private static String hashHex16(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
```

- [ ] **Step 8: Run the calendar tests.** `scripts/gradle.sh test --tests 'dev.andre.homecontrol.sources.sports.calendar.*'`.
  Expected: PASS, all six concurrency tests and the existing `CalendarScheduleTest` (including
  `aFailureKeepsTheLastGoodCopyAndRetriesAfterTenMinutes`), `SportsCalendarsTest`.

- [ ] **Step 9: Write `TheSportsDbScheduleConcurrencyTest`.** Fetch order in one pass: 4331 on 2026-09-18, 4331 on
  2026-09-19, 4328 on 2026-09-18, 4328 on 2026-09-19.

```java
// File: src/test/java/dev/andre/homecontrol/sources/sports/thesportsdb/TheSportsDbScheduleConcurrencyTest.java
package dev.andre.homecontrol.sources.sports.thesportsdb;

import dev.andre.homecontrol.sources.sports.feed.FeedResult;
import dev.andre.homecontrol.sources.sports.feed.SportsEvent;
import dev.andre.homecontrol.sources.sports.settings.JsonFileSportsStore;
import dev.andre.homecontrol.sources.sports.settings.SportsProperties;
import dev.andre.homecontrol.sources.sports.settings.SportsSettings;
import dev.andre.homecontrol.sources.sports.settings.SportsSettingsService;
import dev.andre.homecontrol.sources.sports.settings.SportsTimeZones;
import dev.andre.homecontrol.storage.SecretStore;
import dev.andre.homecontrol.testsupport.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/** Passes that meet: downloads run without the schedule's lock, and a second caller joins a running one. */
class TheSportsDbScheduleConcurrencyTest {

    private static final String EVENTS_DAY = "eventsday.php";
    /** An event on 2026-09-19 in the German Bundesliga (league 4331). */
    private static final String BUNDESLIGA_ITEM = "tsdb:2508365";

    @TempDir
    Path dir;

    private final CountDownLatch release = new CountDownLatch(1);
    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
    private FakeTheSportsDbServer server;
    private SportsSettingsService settingsService;
    private TheSportsDbSchedule schedule;

    @BeforeEach
    void setUp() throws IOException {
        server = new FakeTheSportsDbServer().withStandardResponses();
        settingsService = new SportsSettingsService(new JsonFileSportsStore(dir.resolve("sports.json")),
                mock(ApplicationEventPublisher.class));
        settingsService.update(s -> s.withCompetitions(List.of(
                new SportsSettings.CompetitionEntry("4331", "German Bundesliga", "Soccer", "Germany", null, null, Instant.EPOCH),
                new SportsSettings.CompetitionEntry("4328", "English Premier League", "Soccer", "England", null, null, Instant.EPOCH))));

        SportsTimeZones zones = mock(SportsTimeZones.class);
        given(zones.effective()).willReturn(ZoneId.of("Europe/Berlin"));
        // A held answer waits for the test, so the request timeout must outlast any test.
        SportsProperties properties = new SportsProperties(true, "", 30, 10, 10, Duration.ofMinutes(120),
                new SportsProperties.Calendar(Duration.ofHours(6), Duration.ofSeconds(1), Duration.ofSeconds(2),
                        5242880, 3, true),
                new SportsProperties.TheSportsDb(true, server.apiBase(), "123", Duration.ofHours(24),
                        Duration.ofSeconds(1), Duration.ofSeconds(30), null, true));
        MutableClock clock = MutableClock.at(Instant.parse("2026-09-19T14:00:00Z"));
        TheSportsDbClient client = new TheSportsDbClient(properties.theSportsDb());
        TheSportsDbKeys keys = new TheSportsDbKeys(settingsService, mock(SecretStore.class), properties);
        schedule = new TheSportsDbSchedule(client, keys, settingsService, properties, zones, clock);
    }

    @AfterEach
    void tearDown() {
        release.countDown();
        pool.shutdownNow();
        server.close();
    }

    private void holdDay(String date, String leagueId, String fixture) {
        server.hold(EVENTS_DAY, Map.of("d", date, "l", leagueId), 200, fixture, release);
    }

    private Future<FeedResult> passHeldAfter(int requestsSoFar) {
        Future<FeedResult> pass = pool.submit(schedule::events);
        await().until(() -> server.count(EVENTS_DAY) == requestsSoFar);
        return pass;
    }

    private static void awaitStillWaiting(Future<?> waiter) {
        await().during(Duration.ofMillis(200)).atMost(Duration.ofSeconds(2)).until(() -> !waiter.isDone());
    }

    @Test
    void twoPassesAtOnceSendOneRequestPerDay() throws Exception {  // guard
        holdDay("2026-09-19", "4331", "eventsday-2026-09-19-4331.json");
        Future<FeedResult> first = passHeldAfter(2);
        Future<FeedResult> second = pool.submit(schedule::events);
        awaitStillWaiting(second);

        release.countDown();

        assertThat(first.get(10, TimeUnit.SECONDS).succeeded()).isEqualTo(2);
        assertThat(second.get(10, TimeUnit.SECONDS).succeeded()).isEqualTo(2);
        assertThat(server.count(EVENTS_DAY)).isEqualTo(4);
    }

    @Test
    void aFirstLookupDuringTheFirstPassWaitsForIt() throws Exception {
        holdDay("2026-09-19", "4331", "eventsday-2026-09-19-4331.json");
        Future<FeedResult> pass = passHeldAfter(2);
        Future<Optional<SportsEvent>> found = pool.submit(() -> schedule.find(BUNDESLIGA_ITEM));
        awaitStillWaiting(found);

        release.countDown();

        assertThat(found.get(10, TimeUnit.SECONDS)).isPresent();
        pass.get(10, TimeUnit.SECONDS);
        assertThat(server.count(EVENTS_DAY)).isEqualTo(4);
    }

    @Test
    void aWaitingPassThatIsInterruptedStopsFetching() throws Exception {
        holdDay("2026-09-18", "4331", "eventsday-2026-09-18-4331.json");
        Future<FeedResult> first = passHeldAfter(1);
        AtomicReference<FeedResult> secondResult = new AtomicReference<>();
        AtomicBoolean stillInterrupted = new AtomicBoolean();
        Thread second = Thread.ofVirtual().start(() -> {
            secondResult.set(schedule.events());
            stillInterrupted.set(Thread.currentThread().isInterrupted());
        });
        await().during(Duration.ofMillis(200)).atMost(Duration.ofSeconds(2)).until(second::isAlive);

        second.interrupt();

        assertThat(second.join(Duration.ofSeconds(10))).as("returns while the download is held").isTrue();
        assertThat(stillInterrupted).isTrue();
        assertThat(secondResult.get().succeeded()).isZero();
        assertThat(secondResult.get().errors()).isEmpty();
        assertThat(server.count(EVENTS_DAY)).isEqualTo(1);

        release.countDown();
        assertThat(first.get(10, TimeUnit.SECONDS).succeeded()).isEqualTo(2);
    }

    @Test
    void aClearDuringAFetchDropsThatDay() throws Exception {
        holdDay("2026-09-19", "4331", "eventsday-2026-09-19-4331.json");
        Future<FeedResult> pass = passHeldAfter(2);

        schedule.clear();   // what a key change does
        release.countDown();
        pass.get(10, TimeUnit.SECONDS);

        assertThat(schedule.find(BUNDESLIGA_ITEM)).isEmpty();
        assertThat(schedule.status("4331").orElseThrow().events()).isZero();
        schedule.events();
        assertThat(server.count(EVENTS_DAY)).isEqualTo(6);
    }

    @Test
    void aLeagueForgottenDuringItsFetchIsNotCached() throws Exception {
        holdDay("2026-09-19", "4331", "eventsday-2026-09-19-4331.json");
        Future<FeedResult> pass = passHeldAfter(2);

        // What SportsCompetitions.remove does: settings first, then the schedule.
        settingsService.update(s -> s.withCompetitions(List.of(s.competitions().get(1))));
        schedule.forget("4331");
        release.countDown();

        FeedResult result = pass.get(10, TimeUnit.SECONDS);
        assertThat(result.feeds()).isEqualTo(1);
        assertThat(result.events()).noneMatch(e -> e.competitionKey().equals("thesportsdb:4331"));
        assertThat(schedule.find(BUNDESLIGA_ITEM)).isEmpty();
    }
}
```

- [ ] **Step 10: Run it.** `scripts/gradle.sh test --tests 'dev.andre.homecontrol.sources.sports.thesportsdb.TheSportsDbScheduleConcurrencyTest'`.
  Expected: `twoPassesAtOnceSendOneRequestPerDay` PASSES; four FAIL today:
  `aFirstLookupDuringTheFirstPassWaitsForIt` (answers at once, empty),
  `aWaitingPassThatIsInterruptedStopsFetching` (blocked on the monitor),
  `aClearDuringAFetchDropsThatDay` (the day is written back after the clear; the next pass sends 5, not 6),
  `aLeagueForgottenDuringItsFetchIsNotCached` (the forgotten league's events are in the result and the cache).

- [ ] **Step 11: Rewrite `TheSportsDbSchedule`.** The constructor, `utcDates`, `Round`, `dropUnknownAndOld`,
  `keyUnavailable` (returning `FeedResult` since Task 2), `status` and the key helpers keep their bodies; `find` gains
  `@Override`. The whole file:

```java
// File: src/main/java/dev/andre/homecontrol/sources/sports/thesportsdb/TheSportsDbSchedule.java
package dev.andre.homecontrol.sources.sports.thesportsdb;

import dev.andre.homecontrol.core.content.ContentSourceException;
import dev.andre.homecontrol.sources.sports.feed.FeedFetches;
import dev.andre.homecontrol.sources.sports.feed.FeedResult;
import dev.andre.homecontrol.sources.sports.feed.FeedStatus;
import dev.andre.homecontrol.sources.sports.feed.SportsEvent;
import dev.andre.homecontrol.sources.sports.feed.SportsFeed;
import dev.andre.homecontrol.sources.sports.settings.SportsProperties;
import dev.andre.homecontrol.sources.sports.settings.SportsSettings;
import dev.andre.homecontrol.sources.sports.settings.SportsSettingsService;
import dev.andre.homecontrol.sources.sports.settings.SportsTimeZones;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Daily TheSportsDB fixtures for every chosen competition, cached per (league, UTC date) pair.
 *
 * <p>No lock is held while a day downloads: {@link FeedFetches} runs one download per day at a time, and a pass that
 * finds one running waits for it. {@link #lock} guards only short steps: writing a download's outcome, publishing a
 * pass, {@link #forget} and {@link #clear}. Those two advance {@link #generation}, and a download that started under
 * an older generation drops its outcome, so neither a removal nor a new key is undone by a download already running.
 */
public class TheSportsDbSchedule implements SportsFeed {

    private static final Logger log = LoggerFactory.getLogger(TheSportsDbSchedule.class);
    private static final String LIMITED = "TheSportsDB is limiting requests; try again in a minute";

    private record Entry(List<SportsEvent> events, Instant fetchedAt) {
    }

    private final TheSportsDbClient client;
    private final TheSportsDbKeys keys;
    private final SportsSettingsService settingsService;
    private final SportsProperties properties;
    private final SportsTimeZones zones;
    private final Clock clock;

    private final Object lock = new Object();
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastFailure = new ConcurrentHashMap<>();
    private final Map<String, String> errors = new ConcurrentHashMap<>();
    private final FeedFetches<String> fetches = new FeedFetches<>();
    private final AtomicLong generation = new AtomicLong();
    /** Set when a pass ends, so a lookup that arrives during the first pass runs one too and joins its downloads. */
    private volatile boolean ranOnce;

    public TheSportsDbSchedule(TheSportsDbClient client, TheSportsDbKeys keys, SportsSettingsService settingsService,
                               SportsProperties properties, SportsTimeZones zones, Clock clock) {
        this.client = client;
        this.keys = keys;
        this.settingsService = settingsService;
        this.properties = properties;
        this.zones = zones;
        this.clock = clock;
    }

    @Override
    public String itemPrefix() {
        return "tsdb:";
    }

    @Override
    public boolean configured() {
        return !settingsService.current().competitions().isEmpty();
    }

    public static Set<LocalDate> utcDates(Instant now, ZoneId zone) {
        LocalDate localDay = LocalDate.ofInstant(now, zone);
        Instant from = localDay.atStartOfDay(zone).toInstant().minus(Duration.ofHours(6));
        Instant to = localDay.plusDays(1).atStartOfDay(zone).toInstant();
        LocalDate first = LocalDate.ofInstant(from, ZoneOffset.UTC);
        LocalDate last = LocalDate.ofInstant(to.minusNanos(1), ZoneOffset.UTC);
        Set<LocalDate> dates = new LinkedHashSet<>();
        for (LocalDate d = first; !d.isAfter(last); d = d.plusDays(1)) {
            dates.add(d);
        }
        return dates;
    }

    @Override
    public FeedResult events() {
        try {
            return pass();
        } finally {
            ranOnce = true;
        }
    }

    private FeedResult pass() {
        SportsSettings settings = settingsService.current();
        Instant now = clock.instant();
        ZoneId zone = zones.effective();
        Set<LocalDate> dates = utcDates(now, zone);
        dropUnknownAndOld(settings, dates);

        String key;
        try {
            key = keys.current();
        } catch (TheSportsDbException e) {
            return keyUnavailable(settings, e);
        }
        refreshDue(settings.competitions(), dates, new Round(key, now, zone));
        return publish(dates);
    }

    /** One {@link #events()} pass: once TheSportsDB rate-limits a request, the rest of the pass stays on the cache. */
    private static final class Round {

        private final String key;
        private final Instant now;
        private final ZoneId zone;
        private boolean rateLimited;

        Round(String key, Instant now, ZoneId zone) {
            this.key = key;
            this.now = now;
            this.zone = zone;
        }
    }

    private void dropUnknownAndOld(SportsSettings settings, Set<LocalDate> dates) {
        LocalDate firstDate = dates.stream().min(LocalDate::compareTo).orElse(null);
        Set<String> known = new LinkedHashSet<>();
        settings.competitions().forEach(c -> known.add(c.leagueId()));
        cache.keySet().removeIf(key -> !known.contains(leagueIdOf(key))
                || (firstDate != null && dateOf(key).isBefore(firstDate.minusDays(1))));
        lastFailure.keySet().removeIf(key -> !known.contains(leagueIdOf(key)));
        errors.keySet().removeIf(leagueId -> !known.contains(leagueId));
    }

    private FeedResult keyUnavailable(SportsSettings settings, TheSportsDbException e) {
        List<String> errorList = new ArrayList<>();
        for (SportsSettings.CompetitionEntry competition : settings.competitions()) {
            errors.put(competition.leagueId(), e.getMessage());
            errorList.add(competition.name() + ": " + e.getMessage());
        }
        return new FeedResult(List.of(), errorList, settings.competitions().size(), 0);
    }

    private void refreshDue(List<SportsSettings.CompetitionEntry> competitions, Set<LocalDate> dates, Round round) {
        for (SportsSettings.CompetitionEntry competition : competitions) {
            for (LocalDate date : dates) {
                // A pass whose wait was interrupted downloads nothing more; it still publishes what is cached.
                if (Thread.currentThread().isInterrupted()) {
                    return;
                }
                refreshIfDue(competition, date, round);
            }
        }
    }

    /** Downloads the day when it is stale and no recent failure or rate limit holds it back. */
    private void refreshIfDue(SportsSettings.CompetitionEntry competition, LocalDate date, Round round) {
        String cacheKey = cacheKey(competition.leagueId(), date);
        Entry entry = cache.get(cacheKey);
        if (fresh(entry, round.now)) {
            return;
        }
        if (!failedRecently(cacheKey, round.now) && !round.rateLimited) {
            fetches.run(cacheKey, () -> fetchIfStillDue(competition, date, cacheKey, round));
        } else if (round.rateLimited && entry == null) {
            errors.put(competition.leagueId(), LIMITED);
        }
    }

    private boolean fresh(Entry entry, Instant now) {
        return entry != null && entry.fetchedAt().plus(properties.theSportsDb().fixturesTtl()).isAfter(now);
    }

    private boolean failedRecently(String cacheKey, Instant now) {
        Instant failedAt = lastFailure.get(cacheKey);
        return failedAt != null && failedAt.plus(FeedFetches.RETRY_BACKOFF).isAfter(now);
    }

    /** One download: checks again, downloads without a lock, and writes unless a forget or clear came between. */
    private void fetchIfStillDue(SportsSettings.CompetitionEntry competition, LocalDate date, String cacheKey,
                                 Round round) {
        long started = generation.get();
        if (settingsService.current().competition(competition.leagueId()).isEmpty()
                || fresh(cache.get(cacheKey), round.now) || failedRecently(cacheKey, round.now)) {
            return;
        }
        try {
            List<JsonNode> raw = client.eventsDay(round.key, date, competition.leagueId());
            List<SportsEvent> mapped = new ArrayList<>();
            for (JsonNode node : raw) {
                TheSportsDbEventMapper.toEvent(node, competition.leagueId(), competition.badge(), round.zone,
                        sport -> properties.theSportsDb().durationFor(sport, properties.defaultEventDuration()))
                        .ifPresent(mapped::add);
            }
            synchronized (lock) {
                if (generation.get() == started) {
                    cache.put(cacheKey, new Entry(mapped, round.now));
                    lastFailure.remove(cacheKey);
                    errors.remove(competition.leagueId());
                }
            }
        } catch (TheSportsDbException e) {
            synchronized (lock) {
                if (generation.get() == started) {
                    lastFailure.put(cacheKey, round.now);
                    errors.put(competition.leagueId(), e.getMessage());
                }
            }
            log.warn("TheSportsDB fixtures for competition {} on {} failed ({})",
                    competition.leagueId(), date, e.kind());
            if (e.kind() == ContentSourceException.Kind.RATE_LIMITED) {
                round.rateLimited = true;
            }
        }
    }

    /** The result for the competitions configured now, from the cache. */
    private FeedResult publish(Set<LocalDate> dates) {
        synchronized (lock) {
            SportsSettings settings = settingsService.current();
            List<String> errorList = new ArrayList<>();
            List<SportsEvent> allEvents = new ArrayList<>();
            int succeeded = 0;
            for (SportsSettings.CompetitionEntry competition : settings.competitions()) {
                boolean hasEntry = false;
                for (LocalDate date : dates) {
                    Entry entry = cache.get(cacheKey(competition.leagueId(), date));
                    if (entry != null) {
                        hasEntry = true;
                        allEvents.addAll(entry.events());
                    }
                }
                if (hasEntry) {
                    succeeded++;
                }
                String competitionError = errors.get(competition.leagueId());
                if (competitionError != null) {
                    errorList.add(competition.name() + ": " + competitionError);
                }
            }
            return new FeedResult(allEvents, errorList, settings.competitions().size(), succeeded);
        }
    }

    @Override
    public Optional<SportsEvent> find(String itemId) {
        if (!ranOnce) {
            events();
        }
        for (Entry entry : cache.values()) {
            for (SportsEvent event : entry.events()) {
                if (event.itemId().equals(itemId)) {
                    return Optional.of(event);
                }
            }
        }
        return Optional.empty();
    }

    public Optional<FeedStatus> status(String leagueId) {
        if (!ranOnce) {
            events();
        }
        if (settingsService.current().competition(leagueId).isEmpty()) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        Set<LocalDate> dates = utcDates(now, zones.effective());
        Instant latest = null;
        int events = 0;
        for (LocalDate date : dates) {
            Entry entry = cache.get(cacheKey(leagueId, date));
            if (entry != null) {
                events += entry.events().size();
                if (latest == null || entry.fetchedAt().isAfter(latest)) {
                    latest = entry.fetchedAt();
                }
            }
        }
        return Optional.of(new FeedStatus(latest, events, errors.get(leagueId), 0, 0, 0));
    }

    public void forget(String leagueId) {
        synchronized (lock) {
            generation.incrementAndGet();
            cache.keySet().removeIf(key -> leagueIdOf(key).equals(leagueId));
            lastFailure.keySet().removeIf(key -> leagueIdOf(key).equals(leagueId));
            errors.remove(leagueId);
        }
    }

    public void clear() {
        synchronized (lock) {
            generation.incrementAndGet();
            cache.clear();
            lastFailure.clear();
            errors.clear();
        }
    }

    private static String cacheKey(String leagueId, LocalDate date) {
        return leagueId + "|" + date;
    }

    private static String leagueIdOf(String key) {
        return key.substring(0, key.indexOf('|'));
    }

    private static LocalDate dateOf(String key) {
        return LocalDate.parse(key.substring(key.indexOf('|') + 1));
    }
}
```

  Gone: the old `entry(...)` and `fetch(...)` methods and the `RETRY_BACKOFF` constant; the rate-limit text is the
  constant `LIMITED`.

- [ ] **Step 12: Run the TheSportsDB tests.** `scripts/gradle.sh test --tests 'dev.andre.homecontrol.sources.sports.thesportsdb.*'`.
  Expected: PASS, including `TheSportsDbScheduleRetryTest.aFailedDayIsRetriedOnlyAfterTheBackoff`,
  `TheSportsDbScheduleTest.rateLimitingStopsTheRound` and all five concurrency tests.

- [ ] **Step 13: Run the build.** `scripts/gradle.sh build`. Expected: green; `grep -rn "synchronized" src/main/java/dev/andre/homecontrol/sources/sports/{calendar/CalendarSchedule,thesportsdb/TheSportsDbSchedule}.java`
  shows only `synchronized (lock)` blocks.

- [ ] **Step 14: Commit** the files of this task with:

```
fix: fetch sports feeds outside the schedule lock

Calendars and TheSportsDB days now download without their schedule's lock. FeedFetches runs one
download per calendar or day at a time, and a caller that finds one running waits for it. The
lock only guards writing an outcome, publishing a pass, forget and clear.

What changes for a user:
- A calendar or competition removed while it downloads no longer comes back until the next
  pass, and a day downloaded with a TheSportsDB key that was just replaced is dropped.
- An item opened right after a start, while the first load is still running, waits for it
  instead of reporting that the item does not exist.
- A calendar added during a pass shows in that pass's result.
- A pass that is interrupted while it waits returns at once and downloads nothing more.

Both feeds share the ten-minute retry backoff, FeedFetches.RETRY_BACKOFF.
```

---

### Task 4: Rules that keep the layers

**Files:**
- Modify: `src/test/java/dev/andre/homecontrol/ArchitectureTest.java`

**Interfaces:**
- Consumes: the packages of Tasks 1–3.
- Produces: `ArchitectureTest.sportsFeedsSitBelowTheSource`, `ArchitectureTest.icsIsALibrary`.

- [ ] **Step 1: Add the rules** after `sourcesReachTheNetworkOnlyThroughTheGuardedClient` (import
  `static com.tngtech.archunit.library.Architectures.layeredArchitecture`):

```java
    @ArchTest
    static final ArchRule sportsFeedsSitBelowTheSource = layeredArchitecture()
            .consideringOnlyDependenciesInAnyPackage("dev.andre.homecontrol.sources.sports..")
            .layer("Source").definedBy("dev.andre.homecontrol.sources.sports")
            .layer("Calendars").definedBy("dev.andre.homecontrol.sources.sports.calendar..")
            .layer("Competitions").definedBy("dev.andre.homecontrol.sources.sports.thesportsdb..")
            .layer("Shared").definedBy("dev.andre.homecontrol.sources.sports.feed..",
                    "dev.andre.homecontrol.sources.sports.settings..")
            .layer("Ics").definedBy("dev.andre.homecontrol.sources.sports.ics..")
            .whereLayer("Source").mayNotBeAccessedByAnyLayer()
            .whereLayer("Calendars").mayOnlyBeAccessedByLayers("Source")
            .whereLayer("Competitions").mayOnlyBeAccessedByLayers("Source")
            .whereLayer("Shared").mayOnlyBeAccessedByLayers("Source", "Calendars", "Competitions")
            .whereLayer("Ics").mayOnlyBeAccessedByLayers("Calendars")
            .because("the feeds build on the shared sports types and the source builds on the feeds; an edge back "
                    + "up would tie them into a cycle again");

    @ArchTest
    static final ArchRule icsIsALibrary = classes()
            .that().resideInAPackage("dev.andre.homecontrol.sources.sports.ics..")
            .should().onlyDependOnClassesThat().resideInAnyPackage("java..", "dev.andre.homecontrol.sources.sports.ics..")
            .because("the calendar parser is a library: it takes text and returns values, and knows nothing of "
                    + "Spring or the app");
```

- [ ] **Step 2: Run them.** `scripts/gradle.sh test --tests 'dev.andre.homecontrol.ArchitectureTest'`. Expected: PASS.

- [ ] **Step 3: Watch each rule catch a violation.** Add to `FeedResult` the line
  `static final Class<?> BACK_EDGE = dev.andre.homecontrol.sources.sports.SportsSchedule.class;` and to `IcsParser`
  the line `static final Class<?> SPRING = org.springframework.util.StringUtils.class;`, run the same command.
  Expected: FAIL, `sportsFeedsSitBelowTheSource` naming `FeedResult` → `SportsSchedule`, and `icsIsALibrary` naming
  `IcsParser` → `StringUtils`. Remove both lines and run again. Expected: PASS; `git diff --stat` shows only
  `ArchitectureTest.java`.

- [ ] **Step 4: Run the build.** `scripts/gradle.sh build`. Expected: green; `src/test/archunit-store` unchanged.

- [ ] **Step 5: Commit** `ArchitectureTest.java` with:

```
test: keep the sports packages layered and ics a library

Two strict ArchUnit rules: the sports source uses the feeds, the feeds use the shared feed and
settings packages, only calendars use ics, and nothing points back up; ics depends on the JDK
alone.
```

---

### Task 5: Guides, browser tests and the full check

**Files:**
- Modify: `docs/dev/architecture.md` (the `sources` row), `docs/dev/testing.md` (the helpers list)

- [ ] **Step 1: The architecture guide.** In the `sources` row of the package table, after the sentence ending
  "the one parser for outbound links; see [ADR 0005](../adr/0005-outbound-http-for-content-sources.md).", add:

```
Inside `sports`, the feeds (`calendar`, `thesportsdb`) build on `sports.feed` (the `SportsFeed` contract, `FeedResult`, `FeedFetches`) and `sports.settings`, and only calendars use `sports.ics`, a parser that depends on the JDK alone; ArchUnit keeps these layers.
```

- [ ] **Step 2: The testing guide.** Append this sentence to the bullet on shared helpers, after "… TV, mpv) stays
  protocol-specific.":

```
A test that needs a request to stay open until it has checked something holds the answer with `FakeHttpServer.hold` and a `CountDownLatch` (the calendar and TheSportsDB fakes wrap it); the request is recorded on arrival, so the test can wait for it.
```

- [ ] **Step 3: Run the build and the browser tests.** `scripts/gradle.sh build`, then
  `scripts/e2e.sh -Pe2eBrowsers=chromium`. Expected: build green, about 3,200 tests; browser tests 68/68 (the setup
  page reads the moved types).

- [ ] **Step 4: Commit** the two guides with:

```
docs: describe the sports layers and held test answers
```
