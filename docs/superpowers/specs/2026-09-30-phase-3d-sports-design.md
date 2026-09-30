# Phase 3D: Sports Feeds

**Status:** approved in conversation on 2026-09-30, section by section.

**Roadmap:** `docs/superpowers/specs/2026-09-27-architecture-roadmap-design.md`, workstream "3D: Sports". Phase 2C
moved calendars and TheSportsDB onto `GuardedHttpClient`, which this workstream builds on.

## Purpose

The sports source has two feeds, ICS calendars and TheSportsDB competitions. They grew side by side and share more
than their packages admit:

- `CalendarSchedule.Result` and `TheSportsDbSchedule.Result` are the same record, and each schedule declares its own
  ten-minute `RETRY_BACKOFF`.
- `FeedStatus` lives in `calendar` but TheSportsDB uses it too, so `thesportsdb` depends on `calendar`.
- The packages form two cycles. The feeds import `SportsEvent`, `SportsProperties`, `SportsSettings`,
  `SportsSettingsService` and `SportsTimeZones` from the root package; the root's `SportsSchedule`,
  `SportsConfiguration`, `SportsSetupSection` and `SportsSetupController` import the feeds. ArchUnit's cycle rule
  only compares top-level packages, so it never saw them.
- Both `events()` methods are `synchronized` and fetch while holding the lock: every stale calendar one after
  another, or every stale league and day, each up to its request timeout. Every other caller of `events()` waits,
  including the first `find()` or `status()` after a start, which the setup page makes.
- A pass that is running can bring back what a removal just dropped. `forget()` and `clear()` do not take the lock,
  so a fetch that finishes after them writes its calendar, league or day back into the cache, and a calendar pass
  re-publishes the removed calendar's events. They stay until the next pass.

`ics/` already imports nothing but the JDK; the roadmap's "Spring-free library" needs only a rule that keeps it so.

After this workstream the feeds share one contract, one result type and one fetch helper; the sports packages form
layers without cycles; no lock is held while a feed waits on the network; and a removal or key change is never undone
by a fetch that was already running.

## Decisions (the user's, 2026-09-30)

- **Both feeds fetch outside their lock, and a caller joins a fetch that is already running** for the same calendar
  or the same league and day, instead of starting a second one or taking the last copy. Every result still reflects
  a fresh attempt at each feed that was due.
- **Approach A: the shared types move below the feeds** into two new packages, `sports.feed` and `sports.settings`.
  The root keeps the content source, the rail, the setup page and the wiring, and still names the two concrete feeds.
  (Rejected: approach B, where each feed brings its own configuration and setup rows and the root never names a feed.
  It pays off only with a third feed, which is not planned, and it would reopen the setup page 2E just rebuilt.)
- **One pull request** on `refactor/sports-feeds`.

## Constraints

- Nothing a user sees changes except shorter waits: the configuration prefix `home-control.sports.*`, the settings
  file and its format, the setup page and its templates, the item ids (`ics:…`, `tsdb:…`), the error texts, the
  refresh and retry periods, and the module switches (`home-control.sports.enabled`, TheSportsDB's own switch) stay
  as they are.
- `JsonFileSportsStore` writes its JSON by hand, so moving classes changes nothing under `/data`.
- Only adapters speak device protocols and only sources speak content APIs; the sports feeds keep using
  `GuardedHttpClient` exactly as 2C left them.
- No frozen ArchUnit violation involves sports. The store stays at 42 lines; the new rules are strict.

## Design

### 1. Packages

```
sources.sports               SportsContentSource, LiveTodayRail, SportsItems, SportsProviders, EventPhase,
                             SportsSchedule, SportsSetupSection, SportsSetupController, SportsConfiguration
  ├─ sports.calendar         CalendarSchedule, SportsCalendars, CalendarFetcher, CalendarLinks,
  │                          CalendarFetchException
  ├─ sports.thesportsdb      unchanged class list
  ├─ sports.feed        new  SportsFeed, FeedResult, FeedStatus (from calendar), SportsEvent, FeedFetches
  ├─ sports.settings    new  SportsSettings, SportsSettingsService, JsonFileSportsStore, SportsProperties,
  │                          SportsTimeZones
  └─ sports.ics              unchanged, JDK only
```

Dependencies point one way: the root uses `calendar` and `thesportsdb`; both feeds use `feed` and `settings`;
`calendar` also uses `ics`; `feed`, `settings` and `ics` use nothing above them, and `settings` does not use `feed`.
`calendar` and `thesportsdb` never use each other.

Tests move with their classes (`JsonFileSportsStoreTest`, `JsonFileSportsStoreEdgeCaseTest`,
`SportsSettingsServiceTest` and `SportsTimeZonesTest` to `settings`). Test helpers outside sports (`FullAppReset`,
`WebSliceTest`, `SlowBodyDeadlineTest`) only change imports. A package-private member the root still reaches after a
move becomes public; nothing else widens.

The developer guide's `sources` row in `docs/dev/architecture.md` gains a sentence on the sports layers.

### 2. The feed contract

```java
package dev.andre.homecontrol.sources.sports.feed;

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

public record FeedResult(List<SportsEvent> events, List<String> errors, int feeds, int succeeded) {
    public static final FeedResult NONE = new FeedResult(List.of(), List.of(), 0, 0);
    // compact constructor copies both lists
    public FeedResult plus(FeedResult other);   // both lists concatenated, counts summed
    public boolean allFailed();                 // feeds > 0 && succeeded == 0 && !errors.isEmpty()
}
```

- `CalendarSchedule` (prefix `ics:`) and `TheSportsDbSchedule` (prefix `tsdb:`) implement `SportsFeed`.
  `configured()` replaces `hasCalendars()` and `hasCompetitions()`. Both nested `Result` records are deleted.
- `status(id)` stays on each concrete feed and returns `FeedStatus`: calendar ids and league ids differ, and the
  setup page shows each feed's own rows.
- `SportsSchedule(List<SportsFeed> feeds)`: `hasFeeds()` is true when any feed is `configured()`; `events()` folds
  the results with `plus` and throws `ContentSourceException(BAD_RESPONSE, first error)` when the sum `allFailed()`,
  as today; `find(itemId)` asks the feed whose `itemPrefix()` the id starts with and is empty for any other id.
- `SportsConfiguration` builds the list explicitly: the calendar feed first, then TheSportsDB's when its module is
  on. That keeps today's order of events and of the error that is shown, without relying on Spring's bean order.

### 3. Fetching outside the lock

```java
package dev.andre.homecontrol.sources.sports.feed;

/** At most one fetch per key at a time; a second caller waits for the running one instead of starting another. */
public final class FeedFetches<K> {
    /** How long a feed that failed is left alone before it is tried again. */
    public static final Duration RETRY_BACKOFF = Duration.ofMinutes(10);

    /** Runs {@code fetch} for {@code key}, or waits for the fetch already running for it; returns when that is done. */
    public void run(K key, Runnable fetch);
}
```

- The caller that finds no fetch running for the key runs it on its own thread; no threads are started. A fetch
  writes its outcome into the feed's own cache, and every caller reads the cache afterwards.
- A fetch that throws (the schedules catch the content-source failures they expect, so this is a bug) frees the key,
  and the exception reaches the caller that ran it and every caller that waited.
- A waiter that is interrupted stops waiting, keeps its interrupt flag, and goes on with whatever is cached.

Each `events()` pass then has three steps, in both feeds:

1. **Snapshot and prune.** Read the settings, drop cached state of feeds that are no longer configured and, for
   TheSportsDB, days that have left the window.
2. **Fetch.** For each calendar, or each league and UTC date, that is due: `fetches.run(key, task)`. The task first
   checks again that the feed is still configured and still due, so a caller arriving just after a fetch finished
   does not repeat it. Calendars are still fetched one after another. TheSportsDB keeps its rule that once a request
   of a pass is rate-limited, the rest of that pass stays on the cache.
3. **Publish.** Under the schedule's monitor, build the `FeedResult` from the cache for the feeds configured at that
   moment and, for calendars, replace the item index `byItemId`.

The monitor remains, but nothing waits on the network while holding it. It guards four short steps: a fetch writing
its outcome (cache entry, failure time, error text), the publish step, `forget()` and `clear()`. `forget()` and
`clear()` also advance a generation counter; a fetch remembers the generation it started under and drops its outcome
if it has changed. So:

- a calendar or league removed while its fetch runs does not come back into the cache, the result or `find()`;
- a TheSportsDB day fetched with a key the user just replaced is not kept.

Unchanged: `prime()` (a new calendar's first parse, written as today); `find()` and `status()` still run a first
pass when none has run, but join its fetches instead of queueing on the lock; the refresh periods, the ten-minute
backoff (now `FeedFetches.RETRY_BACKOFF` in both feeds) and every error text.

### 4. Rules

Two strict ArchUnit rules in `ArchitectureTest`:

- **The sports layers**, a `layeredArchitecture()` over `dev.andre.homecontrol.sources.sports..` only:

  | Layer | Packages | May be used by |
  | --- | --- | --- |
  | Source | `sources.sports` (the root package itself) | no other layer |
  | Calendars | `sports.calendar` | Source |
  | Competitions | `sports.thesportsdb` | Source |
  | Shared | `sports.feed`, `sports.settings` | Source, Calendars, Competitions |
  | Ics | `sports.ics` | Calendars |

- **`ics` is a library:** its classes depend only on `java..` and on `ics` itself.

## Testing

New:
- `FeedResultTest`: `plus` concatenates and sums; `allFailed` only with feeds, no success and an error.
- `SportsScheduleTest`, with two stub feeds: events and errors merged in list order; the all-failed exception carries
  the first error; `find` routes by prefix and is empty for an unknown prefix; `hasFeeds`.
- `FeedFetchesTest`: a second caller joins the running fetch and the fetch runs once; a fetch that throws reaches
  the runner and every waiter and frees the key; an interrupted waiter returns with its flag set.
- `CalendarScheduleTest`, with a calendar route that holds its answer until the test releases it: a second
  `events()` during the fetch sends no second request; `status()` of another calendar and `forget()` return while
  the fetch is held; a calendar removed during its fetch is in neither the result nor `find()`.
- `TheSportsDbScheduleTest`, with a held day: two passes at once send one request per day; `clear()` during a fetch
  drops that day; a league forgotten during its fetch is not cached.
- `ArchitectureTest`: the two rules.

Kept green, with imports and `Result` → `FeedResult` changed: `CalendarScheduleTest`'s ten-minute retry,
`TheSportsDbScheduleRetryTest`, `SportsStatusTextTest` and the other sports tests. The browser tests run once, since
the setup page reads moved types.

## Delivery

One pull request, `refactor/sports-feeds`, in this commit order:

1. `refactor:` move the shared sports types into `sports.feed` and `sports.settings` (moves and imports only).
2. `refactor:` `SportsFeed` and `FeedResult` replace the two result records and the two backoffs.
3. `fix:` fetch sports feeds outside the schedule lock (shorter waits; a removal or key change is no longer undone).
4. `test:` the sports layer and `ics` library rules.
5. `docs:` the architecture guide.

No ADR: a package layout is easy to reverse.

## Measures

| | Before | After |
| --- | --- | --- |
| Package cycles inside sports | 2 | 0 |
| Feed result records | 2 | 1 |
| Retry backoff constants | 2 | 1 |
| Schedule methods that fetch while holding a lock | 2 | 0 |

## Out of scope

- Fetching calendars in parallel; each pass still sends its requests one after another.
- A rate limit that outlives one TheSportsDB pass.
- `SportsCalendars.add/remove` and `SportsCompetitions`' methods keep their own locks: they serialize the user's own
  edits (the duplicate check, new ids, the calendar limit), and no feed pass waits on them.
- Feeds that plug into the root without it naming them (approach B).
