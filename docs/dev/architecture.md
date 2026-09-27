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
- **Never refreeze to make a build pass.** `freeze.refreeze` stays `false`.
- **Changing a frozen rule's description** (its `because` text included) makes ArchUnit treat it as a new rule and
  record all of its current violations again. The store diff shows this, so review it like code.

The number of frozen violations only goes down. It is the progress measure for the roadmap's Phase 2 and 3
workstreams, which remove them.
