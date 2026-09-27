# Architecture

Phase 1.2 of the architecture roadmap extends this page with the package map and a dependency diagram.

## Package rules

`src/test/java/dev/andre/homecontrol/ArchitectureTest.java` checks these rules on every build.

| Rule | Status |
| --- | --- |
| `core` depends only on the JDK | strict |
| Content sources are independent of each other, apart from the shared `sources.http` | strict |
| Device adapters are independent of each other, apart from the shared `adapters.net`, `adapters.links` and `adapters.support`, and Sonos using `adapters.upnp.protocol` | strict |
| `java.net.http`, Apache HttpClient 5, jmDNS and D-Bus are used only in `adapters`, `sources` and `discovery` | strict |
| `..protocol..` packages depend on neither Spring nor any application package other than `adapters.net` and other protocol packages | frozen: 46 |
| No cycles between the top-level packages | frozen: 6 |
| `sources` does not depend on `adapters` | frozen: 6 |
| `adapters` depends on neither `sources` nor `web` | frozen: 1 |
| `web` does not depend on `adapters` | frozen: 6 |
| `jakarta.servlet` is used only in `web`, `security`, controllers and controller advice | frozen: 24 |

## Frozen violations

A frozen rule records the violations it had when it was frozen in `src/test/archunit-store/`. It fails only on new
ones.

- **Fixing a violation** removes it from the store the next time the tests run. Commit the smaller store with the fix.
  CI fails when a build leaves the store changed.
- **A frozen violation whose text changes** is reported as new, and the failing run drops the old entry from the store.
  This happens when a method with a frozen violation is renamed or gains a parameter. Copy the violation from the
  failure message into that rule's store file, in place of the old line and in the same commit as the change, so the
  review sees a one-for-one swap. `stored.rules` names each rule's file, and each violation is one line.
- **After a failing run,** restore the store (`git checkout -- src/test/archunit-store`) before running again, unless
  you meant to change it: a failing run can already have removed entries.
- **Never refreeze to make a build pass.** `freeze.refreeze` stays `false`.
- **Changing a frozen rule's description** (its `because` text included) makes ArchUnit treat it as a new rule and
  record all of its current violations again. Delete the old rule's line in `stored.rules` and its file in the same
  commit. The store diff shows the change, so review it like code.
- **Package cycles are frozen per cycle**, by the packages they run through (`CycleViolations`). ArchUnit lists up to
  20 concrete dependencies under each cycle, and an unrelated edit along the cycle would otherwise make a known cycle
  look new. So a new dependency along an already frozen cycle is not reported; only a new cycle is. Removing a
  back-edge breaks its cycle and removes the entry.

The number of frozen violations only goes down. It is the progress measure for the roadmap's Phase 2 and 3
workstreams, which remove them.
