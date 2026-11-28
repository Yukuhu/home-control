# WP-06: Survive memory pressure — Implementation Plan

> **For agentic workers:** work task by task, test first. Steps use checkbox (`- [ ]`) syntax: tick them in the commit
> that does them, and update this package's row in [the index](README.md) in the pull request that lands it.

**Goal:** A 1 GB Raspberry Pi, or a CasaOS box with a 512 MB limit, can hold the full 256 MiB of imported themes. The
theme catalog keeps descriptors and compiled stylesheets in memory, serves images and fonts from their files under
`/data/themes`, checks an image without holding its pixels, and removes what a crashed install left behind at the next
start. If the JVM still runs out of memory, the container exits with code 3 and Docker starts it again, instead of
answering `/health` while a device session or the rail ticker has stopped for good.

**Findings:**

- [OPS-2](../../reviews/2026-11-27-improvement-catalogue.md#ops-2): the OOM policy, all of it.
  `-XX:+ExitOnOutOfMemoryError` goes into the `ENTRYPOINT`, `JDK_JAVA_OPTIONS` stays unset and is documented for heap
  tuning, and a deployment test and a smoke-test step pin both. Heap and GC defaults wait for WP-21 (decision 7).
- [DATA-7](../../reviews/2026-11-27-improvement-catalogue.md#data-7): what 8e64157 and c0eedf7 left open. (1) ST1: an
  imported revision's images and fonts are read from their files, exports read the stored source, and nothing is
  copied per response; the compiled CSS stays in memory (decision 1). (2) Narrowing `ThemeViewAdvice` is left out
  (decision 3). (3) ST3: staging directories, a stray temporary `catalog.json` and unnamed revisions go at start, and
  an update near the quota counts the revision it frees. (4) ST4: every swallowed cause is logged at WARN. ADR 0007
  gets its note.
- [OPS-6](../../reviews/2026-11-27-improvement-catalogue.md#ops-6) (merged into DATA-7): its details. `ThemePackage`
  holds each file once, `ThemeAsset` stops cloning, retained revisions cost no asset bytes, and nothing catches an
  `OutOfMemoryError`. Its ST2 part is fixed.
- [WEB-15](../../reviews/2026-11-27-improvement-catalogue.md#web-15) (merged into DATA-7, fixed at 3932f9b): only the
  optional narrowing was left, and it is left out with DATA-7 (2).
- Found while planning, not in the catalogue: `ThemeAssets.image` decodes every PNG and JPEG at full size, at install
  and at every start. A 4096 x 4096 image allocates a 48-64 MiB raster, the likeliest OOM on a 128 MiB heap once the
  bytes have left it. PR 2 decodes it row by row (decision 5).

**Approach:** `ThemePackage` stays the validated result of an upload or a stored revision. It lives only while a
request reviews or installs it, or while startup checks one revision, and holds each file once. What the catalog keeps
is a new package-private `StoredTheme`: descriptor, tokens, source revision and an index of path to `ThemeAsset`.
`ThemeAsset` becomes a final class with a content type, a length and `open()`. It is in memory (the compiled CSS, and
the two bundled themes, about 90 KB) or stored: a file under `packages/<id>/<source revision>/source/`, opened without
following a symbolic link. Those files are the validated source, which the source digest covers, so nothing new is
written and the layout on disk stays (decision 2). `ThemeAssetsController` answers with a new `web.ThemeAssetResource`,
so Spring streams the file, sets `Content-Length` and answers `Range`. Exports read the stored source and check its
digest.

Startup then holds one revision's bytes at a time, no more than an install needs. A revision is deleted only after a
view that no longer names it is published. `ThemeCatalog` gains a logger and a startup sweep that touches only
`.staging-*`, `.catalog.json-*.tmp` and revision-shaped directories, and the latter only when `catalog.json` was read.
The Dockerfile change is one option. It lands after the heap fixes, so that a large import cannot become a restart.

**Depends on / touches:** Depends on nothing. It touches:

- WP-21 (OPS-11, OPS-9): may set heap or GC options after measuring, and change the `ENTRYPOINT` for a layered image
  or an AOT cache. It keeps `-XX:+ExitOnOutOfMemoryError`, which `DockerImageTest` pins.
- WP-09 (OPS-5, OPS-7, SEC-5, SEC-6), which the roadmap orders after this package, edits the compose files and the
  smoke test, and so does WP-05. Whichever lands second rebases. A read-only root filesystem is unaffected: nothing
  here writes outside `/data`.
- WP-16 (OPS-3, DATA-8) may reword the theme 507 text for a full disk; the WARN lines added here stay. WP-17 (DATA-1,
  TEST-2): its whole-`/data` upgrade fixtures should hold a theme with a retained revision, which the sweep keeps.
  WP-20 (OPS-4) may show theme storage on Setup. WP-08 (BUILD-7): if `deploymentTest` lands first, run
  `DockerImageTest` there.
- [ADR 0007](../../../adr/0007-theme-packages.md) is amended (Tasks 2 and 4).

**Effort:** M overall. PR 1: M. PR 2: S. PR 3: S. PR 4: S.

## Before you start

- Re-check against current main. At 6a253e4, `git diff --stat 3932f9b HEAD -- src Dockerfile scripts casaos
  compose.yaml` is empty, and every finding holds:
  - `ThemePackage`'s constructor (lines 18-29) clones every file into `files` and wraps each presentation file in a
    `ThemeAsset`, which clones it again (`ThemeAsset.java:7`). `bytes()` clones per call (line 8), once per response
    in `ThemeAssetsController.asset` (lines 39-48).
  - `ThemeCatalog.load` (lines 207-231) keeps every current and retained revision in `installed`, `retained` and
    `publicAssets`, and `install` adds both presentations (lines 131-132). `export` (lines 86-93) reads the heap.
  - `publish` (lines 256-271) removes its staging directory in a `finally` that a crash never runs, and `storedBytes`
    (lines 273-284) counts what is left. The quota check (line 120) counts the revision line 133 then reclaims.
  - `load` (lines 211, 221, 228) and `reclaim` (line 309) swallow their causes, and
    `grep -rn Logger src/main/java/dev/andre/homecontrol/themes` prints nothing.
  - `Dockerfile:44` is `ENTRYPOINT ["java", "-jar", "/app/app.jar", "--home-control.data-dir=/data"]`.
    `grep -rn 'JDK_JAVA_OPTIONS\|JAVA_TOOL_OPTIONS' Dockerfile compose*.yaml casaos` prints nothing.
  - Fixed, so not planned: the volatile `View` (lines 49-57, 74-93), `publishView` (lines 167-178) and the inspection
    outside the monitor (lines 95-105). `ThemeViewAdvice` (lines 10-25) is still unscoped and costs two volatile reads.
  - `ThemeCatalogTest.reloadRecompilesSourceStoredByAnEarlierCompilerWithoutTrustingItsCss` pins that the stored
    `theme.css` is never served. Decision 1 keeps it.

  Repeat with `git log --oneline 3932f9b..origin/main -- src/main/java/dev/andre/homecontrol/themes
  src/main/java/dev/andre/homecontrol/web/Theme* Dockerfile scripts/smoke-test-image.sh`.
- Owner decisions. Ask these up front:
  1. **Should the compiled CSS also leave the heap?** DATA-7 proposes serving the stored `theme.css` after a digest
     check against `catalog.json`. Recommended: no, keep compiling it at start. Its source is capped at 256 KiB and is
     usually a few KiB. The alternative changes a `/data` format (product rule: upgrade in place), reverses the pinned
     test above and gives ADR 0007's "an app upgrade recompiles" a second path.
  2. **Serve from `source/`, or write a second copy at publish?** (Changes ADR 0007.) DATA-7 says "write every
     presentation asset at publish time". Recommended: serve the validated source files. They are the presentation
     assets byte for byte, the source digest covers them, disk use stays the same, and after a rollback an older image
     reads the same directories.
  3. **Restrict `ThemeViewAdvice` to page controllers?** Recommended: no. Each call is two volatile reads since
     8e64157. `annotations = Controller.class` also matches every `@RestController` (it is meta-annotated), so the
     narrowing needs a hand-kept `assignableTypes` list: a guard that fails open, and a page controller left off it
     renders without `availableThemes`.
  4. **May startup delete theme storage on its own?** (Changes ADR 0007.) Recommended: yes, within three limits.
     `.staging-*` directories and `.catalog.json-*.tmp` always go, because nothing names them. A revision directory
     goes only when `catalog.json` exists, reads cleanly and names neither it nor its theme's other revision. Nothing
     else is touched. The theme design (`docs/superpowers/specs/2026-10-02-theme-packages-design.md`) already says
     "unreferenced staging files are never served and can be cleaned up".
  5. **Take the full-size image decode, which the catalogue missed, into this package?** Recommended: yes, as PR 2.
     Without it, OPS-2's flag turns one large preview image into a restart on a small heap. It is about five lines.
  6. **Land order.** Recommended: PR 4 (OPS-2) after PRs 1 and 2; PR 3 at any time. This is the analysis's "DATA-7
     first or together".
  7. **A heap default (`-XX:MaxRAMPercentage`) or another GC now?** Recommended: no. OPS-2 calls it a judgement call
     until OPS-11 measures a 1 GB arm64 host (WP-21). Document `JDK_JAVA_OPTIONS` instead.
  8. **The rest of ST3: a Remove button for a damaged entry, and `problems` that only grow until a restart?**
     Recommended: leave them out. DATA-7 does not take them, and `POST /setup/appearance/<id>/remove` already removes
     a damaged entry.

## Global constraints

- **A LAN appliance.** The app serves the assets from `/data`; there is no new service, cache or volume.
- **State is JSON under `/data`, written atomically.** `catalog.json` stays version 1 with `current` and `previous`,
  and a revision stays `packages/<id>/<source revision>/` with `source/` and the unread `theme.css`. Nothing new is
  written and nothing migrates: an existing install serves its assets from the files it already has.
- **The public allowlist comes only from validated packages.** `PublicAssetPaths` and the asset route look the raw path
  up as an exact key of the index. A request path never reaches the file system, and `theme.json`, `tokens.json`,
  `LICENSE` and `.txt` files are never served.
- **A revision's files are deleted only after a published view no longer names them.** A request that looked an asset
  up just before the swap may still miss its file; the theme design's fallback covers it ("individual missing fonts or
  images use the component's font or image fallback").
- **Do not catch `OutOfMemoryError`** (analysis, "What not to do"), and do not add `-XX:+HeapDumpOnOutOfMemoryError`,
  which writes a dump as large as the heap into `/app`, where uid 1000 cannot write, or onto the SD card.
- **Names that never change** are untouched; the `ENTRYPOINT` keeps `--home-control.data-dir=/data`.
- **Architecture.** `themes` stays free of web types: the Spring adapter lives in `web`, which already depends on
  `themes`. No package rule changes.
- **Tests** sit in the package of the code they test: `src/test/java/dev/andre/homecontrol/themes/`, `…/web/` and
  `…/deployment/`. Packages are built from `ThemeTestPackages`; no fake or recording is needed.
- **Sonar.** `ThemeAsset` holds an array, so it is a final class, not a record (java:S6218).
- `scripts/gradle.sh build` is green at the end of every task. PR 1 serves what browsers load, so it also runs
  `scripts/e2e.sh --tests 'dev.andre.homecontrol.e2e.ThemeE2eTest' --tests
  'dev.andre.homecontrol.e2e.OfflineUiE2eTest'`. PR 4 runs the smoke test on a Linux Docker host.
- Conventional Commits, with the scope `themes` the log uses. Stage only the task's files (`git add <paths>`, never
  `-A`). Each commit ends with the session's `Co-Authored-By` and `Claude-Session` lines.

## Review focus

1. **An imported revision's asset bytes are not on the heap.** Pinned by
   `ThemeCatalogTest.anImportedAssetIsReadFromItsStoredFile` (the file is deleted, and `open()` fails) and
   `aRestartServesEachRevisionFromItsOwnFiles`.
2. **The allowlist and the file system stay apart.** Pinned by
   `ThemeCatalogTest.exposesOnlyExactCommittedPresentationPaths`, `aStoredAssetThatBecameASymbolicLinkIsNotFollowed`,
   `ThemeAssetTest.aStoredAssetDoesNotFollowASymbolicLink` and the unchanged
   `LoginGatingTest.theLoginPageCanShowTheChosenThemeBeforeLoggingIn`.
3. **An export is what was validated.** Pinned by `ThemeCatalogTest.exportReadsTheStoredSource`,
   `anExportOfAStoredSourceThatChangedIsRefused` and `anExportOfASourceThatIsGoneIsNotFound`.
4. **The sweep is conservative.** Pinned by `ThemeCatalogTest.aRestartWithoutACatalogKeepsEveryRevision`,
   `aRestartWithADamagedCatalogRemovesOnlyStaging`, `aRestartLeavesNamesItDoesNotKnow` and
   `aRestartKeepsBothRevisionsTheCatalogNames`.
5. **An image is still read to its last row, at a fraction of its memory.** Pinned by
   `ThemeAssetsTest.anImageCutShortInItsLastRowsIsStillRejected` and `checkingALargeImageAllocatesFarLessThanItsPixels`.
6. **No option can switch the OOM exit off.** Pinned by `DockerImageTest.theJvmExitsWhenItRunsOutOfMemory`,
   `noManifestReplacesTheEntrypoint` and the smoke-test step, which passes `-XX:-ExitOnOutOfMemoryError` in
   `JDK_JAVA_OPTIONS` and still expects exit code 3.

## File structure

- **Create:** `src/main/java/dev/andre/homecontrol/themes/StoredTheme.java`,
  `src/main/java/dev/andre/homecontrol/web/ThemeAssetResource.java`
- **Modify**
  - `src/main/java/dev/andre/homecontrol/themes/`: `ThemeAsset.java`, `ThemePackage.java`, `ThemeCatalog.java`,
    `ThemeAssets.java`
  - `src/main/java/dev/andre/homecontrol/web/`: `ThemeAssetsController.java`, `ThemeController.java`
  - `Dockerfile`, `scripts/smoke-test-image.sh`
  - `docs/adr/0007-theme-packages.md`, `docs/dev/themes.md`, `docs/user/configuration.md`,
    `docs/dev/ci-and-releases.md`
- **Test**
  - `src/test/java/dev/andre/homecontrol/themes/`: `ThemeAssetTest.java` (rewritten), `ThemeCatalogTest.java`,
    `ThemeTestPackages.java`, `ThemeAssetsTest.java`, `ThemeCssTest.java`, `ThemeArchiveTest.java`
  - `src/test/java/dev/andre/homecontrol/web/`: `StaticAssetsTest.java`, `ThemePageTest.java`
  - `src/test/java/dev/andre/homecontrol/deployment/DockerImageTest.java`
  - Unchanged and green: `LoginGatingTest`, `ThemeArchiveBoundaryTest`, `ThemeArchiveMetadataTest`,
    `BundledThemeResourcesTest`, `ArchitectureTest`, `BluetoothDeploymentTest`, `CasaOsManifestTest`, and in
    `src/e2e`, `ThemeE2eTest`, `OfflineUiE2eTest` and `LoginGatingE2eTest`.

## Pull requests

| PR | Title | Tasks | Effort |
| --- | --- | --- | --- |
| 1 | `fix(themes): serve imported theme assets from their stored files` | 1, 2 | M |
| 2 | `fix(themes): check theme images without decoding them at full size` | 3 | S |
| 3 | `fix(themes): clean up after a crashed install at startup and log why a theme fails` | 4, 5 | S |
| 4 | `fix: stop the app when it runs out of memory, so Docker restarts it` | 6 | S |

PRs 1, 2 and 3 merge in any order; PR 3 rebases on PR 1 where both edit `ThemeCatalog`. PR 4 waits for PRs 1 and 2
(decision 6). It is a `fix`, not a `build`, so that it releases an image.

## Tasks

Full paths are under File structure.

### Task 1: A theme asset is read when it is served, not copied

**Files:** Modify `ThemeAsset`, `ThemePackage`, `ThemeCatalog` and `ThemeAssetsController`. Create
`ThemeAssetResource`. Test `ThemeAssetTest`, `ThemeTestPackages`, `ThemeCatalogTest`, `ThemeCssTest`,
`ThemeArchiveTest` and `StaticAssetsTest`.

**Interfaces:** `ThemeAsset` stops being a record. `bytes()`, `equals` and `hashCode` go, and `toString` becomes
`ThemeAsset[contentType=…, length=…]`:

```java
/** A presentation asset the catalog serves: its type and length are known, its bytes are read when it is served. */
public final class ThemeAsset {
    /** Copies {@code bytes} once; nothing copies them again. */
    static ThemeAsset inMemory(String contentType, byte[] bytes)
    public String contentType()
    public long length()
    /** A new stream over the whole asset, which the caller closes. */
    public InputStream open() throws IOException
}
```

New: `web.ThemeAssetResource extends AbstractResource`, overriding `getInputStream()` (`asset.open()`),
`contentLength()` (`asset.length()`), `exists()` (`true`) and `getDescription()`. `ThemeTestPackages` gains
`public static byte[] read(ThemeAsset asset)`. `ThemePackage` keeps the compiled CSS in a `byte[] compiled` field, with
a package-private `compiledCss()` for `ThemeCatalog.publish`.

- [ ] **Step 1: Write the failing test.**
  - `ThemeAssetTest` replaces both tests: `anAssetCannotBeChangedThroughTheArrayItWasMadeFrom` (the input array changes
    after `inMemory`; `read` still gives `{1, 2}` and `length()` 2), `eachOpenReadsTheWholeAssetAgain` and
    `toStringNamesTheTypeAndLengthOnly` (`ThemeAsset[contentType=text/css, length=2]`).
  - Every `.bytes()` caller uses `read`: `ThemeCatalogTest` (its `assetAndPackageByteArraysCannotMutateInstalledState`
    changes the array `read` returned, and a second `read` equals the first),
    `ThemeCssTest.compilesNestedRulesGradientsAndNamespacedAnimations` and
    `ThemeArchiveTest.rewritesLocalImagesAndRejectsDisguisedOrUnusedBinaryAssets`.
  - `StaticAssetsTest.servesTheThemeCatalogAndItsImmutablePresentationAssets` compares the body with `read(asset)`, and
    expects `header().longValue("Content-Length", asset.length())` and `Accept-Ranges: bytes`.
  - New: `StaticAssetsTest.aThemeAssetIsServedInRanges` asks for Cyberpunk's `assets/rajdhani-500.woff2` with
    `Range: bytes=0-3` and expects 206, `Content-Range: bytes 0-3/<length>` and the body `wOF2`. An
    `InputStreamResource` would answer 416, which is why the adapter reports its length.
- [ ] **Step 2: Run it to see it fail.** Run `scripts/gradle.sh test --tests 'dev.andre.homecontrol.themes.*' --tests
  'dev.andre.homecontrol.web.StaticAssetsTest'`. Compilation fails: there is no `ThemeAsset.inMemory` or `length()`.
- [ ] **Step 3: Implement.** `ThemePackage` builds its presentation with `ThemeAsset.inMemory`, and `storedSize()`
  adds `compiled.length`. `ThemeAssetsController.asset` returns `ResponseEntity<Resource>`; Spring's
  `ResourceHttpMessageConverter` sets `Content-Length` from `contentLength()`, and Spring MVC answers `Range`:

  ```java
  return themes.asset(rawPath)
          .map(asset -> ResponseEntity.ok().contentType(MediaType.parseMediaType(asset.contentType()))
                  .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
                  .<Resource>body(new ThemeAssetResource(asset)))
          .orElseGet(() -> ResponseEntity.notFound().build());
  ```

- [ ] **Step 4: Run the tests.** Run the Step 2 command, then `scripts/gradle.sh test --tests
  'dev.andre.homecontrol.web.ThemePageTest' --tests 'dev.andre.homecontrol.web.LoginGatingTest'` and
  `scripts/gradle.sh build`.
- [ ] **Step 5: Update docs.** None; Task 2 covers both.
- [ ] **Step 6: Commit.** `fix(themes): stop copying a theme asset for every response`. The body names ST1's
  per-response clone.

### Task 2: The catalog keeps no theme file in memory

**Files:** Create `StoredTheme`. Modify `ThemeAsset`, `ThemePackage`, `ThemeCatalog`, `ThemeController`, ADR 0007 and
`docs/dev/themes.md`. Test `ThemeAssetTest`, `ThemeTestPackages`, `ThemeCatalogTest` and `ThemePageTest`.

**Interfaces:**

- `ThemeAsset` gains `static ThemeAsset stored(String contentType, Path file, long length)`, whose `open()` is
  `Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)`.
- New, package-private `final class StoredTheme`, what the catalog keeps of a published revision: `descriptor()`,
  `id()`, `revision()`, `sourceRevision()`, `builtIn()`, `tokens()`, `presentation()` (`Map<String, ThemeAsset>`) and
  `bundledFiles()`, which copies a built-in's files (they ship in the jar) and throws `IllegalStateException` for an
  imported theme.
- `ThemePackage`: the constructor keeps the map it is given (`Collections.unmodifiableMap(new LinkedHashMap<>(files))`)
  without cloning each array; its javadoc says that its callers in the package (`ThemeArchive.read`, `readRevision`,
  `bundled`) hand over a fresh map. New: `public Optional<byte[]> file(String name)`, a copy of one file; `files()`
  stays for the tests. New, package-private: `StoredTheme bundled()`, with every asset in memory, and
  `StoredTheme storedIn(Path source)`, with the compiled CSS in memory and every other asset as
  `ThemeAsset.stored(type, source.resolve(name), length)`. `presentation()` goes.
- `ThemeCatalog`: `builtins`, `installed` and `retained` hold `StoredTheme`, and `View.packages` becomes
  `Map<String, StoredTheme> stored`. New private helpers: `sourcePath(id, revision)`, `readSource(id, revision)` (the
  walk and checks `readRevision` does today, which `readRevision` then validates), and a `record Unused(String id,
  String revision)` list of revisions to reclaim.

- [ ] **Step 1: Write the failing test.** `ThemeTestPackages` gains `static byte[] font()` (the bundled
  `rajdhani-500.woff2`) and `static Map<String, byte[]> withFont(Map<String, byte[]> files)`, which adds
  `assets/font.woff2` and `@font-face { font-family: theme-test; src: url(assets/font.woff2); }` to `theme.css`.
  - `ThemeCatalogTest.anImportedAssetIsReadFromItsStoredFile`: install `withFont(files("custom", ""))`. The font asset
    reads as `font()`, its `length()` is the stored file's size, and once
    `themes/packages/custom/<source revision>/source/assets/font.woff2` is deleted, `open()` throws
    `NoSuchFileException` while the stylesheet still reads.
  - `aRestartServesEachRevisionFromItsOwnFiles`: install, update and restart. Both fonts read, and after the older
    revision's font file is deleted only the newer one does.
  - `aStoredAssetThatBecameASymbolicLinkIsNotFollowed`: the stored font becomes a link to a file outside `data`, and
    `open()` throws `FileSystemException`.
  - `exportReadsTheStoredSource`: `unzip(export("custom"))` holds the installed files, the font as `font()`, and
    `tokens.json` with its defaults, as before. `anExportOfAStoredSourceThatChangedIsRefused`: after `source/LICENSE`
    is rewritten, `export` throws `ThemeException` 409. `anExportOfASourceThatIsGoneIsNotFound`: after `source/` is
    deleted, 404. `readsNeverWaitForAnInstallOrRemovalInProgress` stays: `export` reads the disk without the monitor.
  - `ThemeAssetTest.aStoredAssetIsReadFromItsFileEachTime` (the file is rewritten between two reads) and
    `aStoredAssetDoesNotFollowASymbolicLink`.
  - `ThemePageTest.anImportedThemesFontsAreServedFromTheDataDirectory`: install a derivative of Cyberpunk. Each font
    path of its descriptor answers 200 with the uploaded file's bytes and `Content-Length`.
- [ ] **Step 2: Run it to see it fail.** Run `scripts/gradle.sh test --tests
  'dev.andre.homecontrol.themes.ThemeCatalogTest' --tests 'dev.andre.homecontrol.themes.ThemeAssetTest'`.
  Compilation fails: there is no `ThemeAsset.stored`. To watch ST1 itself, add only
  `anImportedAssetIsReadFromItsStoredFile` on top of Task 1: the open after the delete succeeds from the heap.
- [ ] **Step 3: Implement.**
  - `load` puts `readRevision(id, revision.current()).storedIn(sourcePath(id, revision.current()))` into `installed`,
    and the same into `retained`. The `ThemePackage` is garbage at once, so startup holds one revision's bytes at a
    time. `install` puts `candidate.storedIn(sourcePath(id, candidate.sourceRevision()))` into `installed` and
    `publicAssets` after `registry.write`. When `publish` found the revision on disk, `readRevision` has already
    checked that it is this source.
  - `install` and `removeInstalled` queue `Unused` entries instead of calling `reclaim`. The failure path of `install`
    (`if (published) reclaim(…)`) stays immediate, because no view named that revision. The public methods end:

    ```java
    } finally {
        publishView();
        // Only now: no published view names these files any more.
        if (reclaimUnused()) publishView(); // a revision that could not be removed shows on Appearance
    }
    ```

  - `export` takes a built-in's `bundledFiles()`, and an imported theme's `storedSource(theme)`. `CHANGED_ON_DISK` is
    "This theme's stored files have changed. Remove it and install it again.":

    ```java
    /** The stored source as it was validated: a file that changed is refused, a theme that is gone is not found. */
    private Map<String, byte[]> storedSource(StoredTheme theme) {
        try {
            Map<String, byte[]> files = readSource(theme.id(), theme.sourceRevision());
            if (ThemeArchive.sourceRevision(files).equals(theme.sourceRevision())) return files;
        } catch (NoSuchFileException e) {
            throw new ThemeException(404, "Theme was not found.", e);
        } catch (IOException | ThemeException e) {
            throw new ThemeException(409, CHANGED_ON_DISK, e);
        }
        throw new ThemeException(409, CHANGED_ON_DISK);
    }
    ```

  - `ThemeController.preview` reads `candidate.file("preview.png")` instead of copying every file through `files()`.
- [ ] **Step 4: Run the tests.** Run `scripts/gradle.sh test --tests 'dev.andre.homecontrol.themes.*' --tests
  'dev.andre.homecontrol.web.Theme*' --tests 'dev.andre.homecontrol.web.StaticAssetsTest' --tests
  'dev.andre.homecontrol.web.LoginGatingTest' --tests 'dev.andre.homecontrol.ArchitectureTest'`, then
  `scripts/gradle.sh build` and the two browser classes named under Global constraints. In
  `ThemeE2eTest.aThemeExportCanBeEditedImportedSelectedAndSharedAgain`, three browsers load fonts from the data
  directory.
- [ ] **Step 5: Update docs.**
  - ADR 0007: `**Amended:** <merge date> — imported assets are served from their stored files.` under its date. After
    "Publish immutable compiled revisions before atomically updating a versioned JSON catalog under `/data/themes`.":
    "The catalog keeps descriptors, compiled CSS and an index of each revision's presentation files in memory. Images
    and fonts are served from `packages/<id>/<source revision>/source/`, and an export reads that source and checks it
    against its source digest." Consequences gains: "A theme's memory cost no longer grows with its images and fonts."
  - `docs/dev/themes.md`, the maintainers' paragraph: "Startup verifies the source and compiles it again" becomes
    "Startup verifies each stored revision in turn and compiles it again. It keeps the compiled CSS and an index of the
    presentation files in memory, and serves images and fonts from their stored files."
- [ ] **Step 6: Commit.** `fix(themes): serve imported theme assets from their stored files`. The body names ST1 and
  the startup crash loop it removes.

### Task 3: An image is checked without decoding it at full size

**Files:** Modify `ThemeAssets`. Test `ThemeAssetsTest`.

- [ ] **Step 1: Write the failing test.** In `ThemeAssetsTest`:

  ```java
  @Test void checkingALargeImageAllocatesFarLessThanItsPixels() throws IOException {
      var threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
      assumeTrue(threads.isThreadAllocatedMemorySupported() && threads.isThreadAllocatedMemoryEnabled());
      byte[] png = image("png", 4096, 4096); // 48 MiB of pixels when decoded whole
      long before = threads.getCurrentThreadAllocatedBytes();
      assertThat(ThemeAssets.validate(Map.of("assets/large.png", png))).containsEntry("assets/large.png", "image/png");
      assertThat(threads.getCurrentThreadAllocatedBytes() - before).isLessThan(16L * 1024 * 1024);
  }
  ```

  The guard `anImageCutShortInItsLastRowsIsStillRejected` takes a 256 x 256 PNG of random pixels, which barely
  compresses, cuts its last 1024 bytes and expects `ThemeException` "cannot be decoded", before and after the change.
- [ ] **Step 2: Run it to see it fail.** Run `scripts/gradle.sh test --tests
  'dev.andre.homecontrol.themes.ThemeAssetsTest'`. The allocation test fails at about 48 MiB; the guard passes.
- [ ] **Step 3: Implement.** In `ThemeAssets.image`, after `dimensions(…)`:

  ```java
  ImageReadParam param = reader.getDefaultReadParam();
  // Every row is decoded, so damage anywhere is still found, but only one pixel of each is kept: a 4096 x 4096
  // image costs a 1 x 4096 raster instead of up to 64 MiB.
  param.setSourceSubsampling(reader.getWidth(0), 1, 0, 0);
  if (reader.read(0, param) == null) throw ThemeException.invalid("Theme image cannot be decoded.");
  ```

- [ ] **Step 4: Run the tests.** Run `scripts/gradle.sh test --tests 'dev.andre.homecontrol.themes.*'` and
  `scripts/gradle.sh build`.
- [ ] **Step 5: Update docs.** None.
- [ ] **Step 6: Commit.** `fix(themes): check theme images without decoding them at full size`. The body gives the
  4096 x 4096 numbers and says the catalogue did not list it.

### Task 4: Startup removes what a crashed install left behind

**Files:** Modify `ThemeCatalog`, ADR 0007 and `docs/dev/themes.md`. Test `ThemeCatalogTest`.

**Interfaces:** none public. A private `sweep()` runs in the constructor under the monitor, after `load()` and before
`publishView()`. `storedBytes()` becomes `bytesUnder(Path root)`.

- [ ] **Step 1: Write the failing test.** In `ThemeCatalogTest`, each test prepares `data/themes`, then constructs
  `catalog()`:
  - `aRestartRemovesStagingLeftByACrashedInstall`: after installing `custom`, `packages/custom/.staging-123/source/`
    and `packages/other/.staging-9/` exist. Both go, and `custom` still serves its stylesheet.
  - `aRestartRemovesRevisionsTheCatalogNoLongerNames`: `custom` installed twice, its retained revision copied to
    `packages/custom/<64 times b>`, and a `packages/ghost/<64 hex>/source/`. Both copies and the empty `ghost` go.
  - `aRestartKeepsBothRevisionsTheCatalogNames` (a guard): both revisions stay, and both stylesheets serve.
  - `aRestartRemovesATemporaryCatalogLeftByACrashedWrite`: `themes/.catalog.json-42.tmp` goes, `catalog.json` stays.
  - `aRestartWithoutACatalogKeepsEveryRevision`: with no `catalog.json`, a revision directory stays and a staging
    directory beside it goes.
  - `aRestartWithADamagedCatalogRemovesOnlyStaging`: with `broken original` in `catalog.json`, only staging goes, and
    the file is unchanged.
  - `aRestartLeavesNamesItDoesNotKnow`: `packages/custom/notes.txt`, `packages/Bad Id/` and `themes/uncommitted.bin`
    stay.
  - `anUpdateAtTheQuotaCountsTheRevisionItReplaces`: install v1 and v2 of `custom`. A sparse `themes/filler.bin`
    (`RandomAccessFile.setLength`) brings the store to `256 MiB - inspect(v3).storedSize() + size(v1)`, where
    `size(v1)` is the bytes under v1's directory. Installing v3 succeeds, and v1's directory is gone.
- [ ] **Step 2: Run it to see it fail.** Run `scripts/gradle.sh test --tests
  'dev.andre.homecontrol.themes.ThemeCatalogTest'`. The removal tests fail, the quota test fails with 507, and the keep
  tests pass as guards.
- [ ] **Step 3: Implement.**
  - `sweep()` deletes regular files `themes/.catalog.json-*.tmp`. Under each `packages/<id>` whose name passes
    `ThemeArchive.validId` and is not reserved, it deletes every `.staging-*` directory with `deleteTree`.
  - If `!damagedRegistry && Files.isRegularFile(registry.file(), NOFOLLOW_LINKS)`, it also deletes each directory
    matching `REVISION_PATTERN` that is neither `current` nor `previous` of `entries.get(id)`, and then `packages/<id>`
    when it is empty and no entry names it.
  - It touches nothing else, follows no link (`deleteTree` and `noLinks` as today) and counts what it removed.
  - `install` works out once `replaced`, the revision line 133 reclaims, and refuses only when
    `bytesUnder(directory) - (replaced == null ? 0 : bytesUnder(revisionPath(id, replaced))) + added > MAX_STORAGE`.
- [ ] **Step 4: Run the tests.** Run `scripts/gradle.sh test --tests 'dev.andre.homecontrol.themes.*' --tests
  'dev.andre.homecontrol.web.Theme*'` and `scripts/gradle.sh build`.
- [ ] **Step 5: Update docs.** ADR 0007's amendment line gains "; startup removes what a crashed install left
  behind", and after the Task 2 sentence: "At startup the catalog removes `.staging-*` directories and a leftover
  temporary `catalog.json`; when `catalog.json` exists and can be read, it also removes revision directories it does
  not name." The maintainers' paragraph of `docs/dev/themes.md` gains: "An update counts the revision it replaces as
  freed, and startup removes unfinished installs and revisions the catalog no longer names."
- [ ] **Step 6: Commit.** `fix(themes): remove what a crashed theme install left behind at startup`. The body names
  ST3.

### Task 5: A theme that fails says why in the log

**Files:** Modify `ThemeCatalog`. Test `ThemeCatalogTest`.

**Interfaces:** `private static final Logger log = LoggerFactory.getLogger(ThemeCatalog.class)`. Each line logs
`e.toString()`, with the stack at DEBUG. The `problems()` texts on Appearance stay as they are. Rejected uploads (400,
409, 413) are not logged: the person who uploaded sees the reason.

| Where | Level | Line |
| --- | --- | --- |
| `load`, catalog unreadable | WARN | `Could not read the theme catalog {}: {}` |
| `load`, current revision | WARN | `Imported theme {} is excluded: {}` |
| `load`, retained revision | WARN | `Revision {} of theme {} is unavailable: {}` |
| `reclaim` | WARN | `Could not remove unused revision {} of theme {}: {}` |
| `install`, the 507 catch | WARN | `Could not save theme {}: {}` |
| `removeInstalled`, the 507 catch | WARN | `Could not remove theme {} from the catalog: {}` |
| `sweep`, one entry | WARN | `Could not remove leftover theme storage {}: {}` |
| `sweep`, at the end | INFO | `Removed {} unfinished theme installs and unused revisions`, when there were any |

- [ ] **Step 1: Write the failing test.** With `@ExtendWith(OutputCaptureExtension.class)` and `CapturedOutput`, as in
  `SessionLoopTest`, each on the setup of the existing test named:
  - `aDamagedThemeIsLoggedWithItsCause` (`damagedPackageIsExcludedWithoutLosingBuiltinsOrOtherImports`): `WARN`,
    `Imported theme custom is excluded` and `Theme JSON is malformed.`
  - `anUnreadableCatalogIsLoggedWithItsCause` (`damagedCatalogKeepsBuiltinsAndRefusesMutationWithoutOverwriting`):
    `Could not read the theme catalog`.
  - `aRevisionThatCannotBeReclaimedIsLoggedWithItsCause`
    (`removalNeverFollowsAReplacedPackageDirectoryOutsideThemeStorage`): `Could not remove unused revision` and
    `symbolic link`.
  - `aCatalogThatCannotBeWrittenIsLoggedWithItsCause` (`failedCatalogWriteKeepsOldRevisionAndDoesNotExposeCandidate`):
    `Could not save theme custom`.
- [ ] **Step 2: Run it to see it fail.** Run `scripts/gradle.sh test --tests
  'dev.andre.homecontrol.themes.ThemeCatalogTest'`. All four fail: nothing in `themes` logs.
- [ ] **Step 3: Implement** the table. The `catch (… _)` clauses name their exception.
- [ ] **Step 4: Run the tests.** Run the Step 2 command, then `scripts/gradle.sh build`.
- [ ] **Step 5: Update docs.** None.
- [ ] **Step 6: Commit.** `fix(themes): log why a theme is excluded or cannot be saved`. The body names ST4.

### Task 6: The app stops when it runs out of memory

**Files:** Modify `Dockerfile`, `scripts/smoke-test-image.sh`, `docs/user/configuration.md` and
`docs/dev/ci-and-releases.md`. Test `DockerImageTest`.

- [ ] **Step 1: Write the failing test.** In `DockerImageTest`:

  ```java
  @Test
  void theJvmExitsWhenItRunsOutOfMemory() throws Exception {
      // Exit code 3 lets `restart: unless-stopped` recover an app whose session loops or rail ticker an
      // OutOfMemoryError ended. It sits on the command line, after JDK_JAVA_OPTIONS, so no option there drops it.
      String runtime = runtime(Files.readString(Path.of("Dockerfile")));

      assertThat(runtime).contains("\nENTRYPOINT [\"java\", \"-XX:+ExitOnOutOfMemoryError\", \"-jar\", "
              + "\"/app/app.jar\", \"--home-control.data-dir=/data\"]")
              .doesNotContain("JDK_JAVA_OPTIONS").doesNotContain("JAVA_TOOL_OPTIONS");
  }
  ```

  The guard `noManifestReplacesTheEntrypoint` loads `compose.yaml`, `compose.bluetooth.yaml`,
  `casaos/docker-compose.yml` and `casaos/docker-compose.bluetooth.yml`: the `shield-remote` service has no
  `entrypoint`, and its `environment` sets neither `JDK_JAVA_OPTIONS` nor `JAVA_TOOL_OPTIONS`, which are the user's.
- [ ] **Step 2: Run it to see it fail.** Run `scripts/gradle.sh test --tests
  'dev.andre.homecontrol.deployment.DockerImageTest'`. `theJvmExitsWhenItRunsOutOfMemory` fails on the `ENTRYPOINT`.
- [ ] **Step 3: Implement.**
  - `Dockerfile`: a comment ("Out of memory, the JVM exits with code 3 and the restart policy starts it again.
    JDK_JAVA_OPTIONS, which comes first, takes heap options and cannot switch this off."), then
    `ENTRYPOINT ["java", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/app.jar", "--home-control.data-dir=/data"]`.
  - `scripts/smoke-test-image.sh`: the header gains "and must stop when it runs out of memory, so the restart policy
    brings it back", and `cleanup` also removes `"$name-oom"`. After "Data volume is writable.", for both variants:

    ```bash
    # Out of memory, the app must stop with exit code 3. The -XX:- comes before the image's own option on the command
    # line, so it cannot switch the exit off, and 12 MiB of heap runs out while the context starts.
    status=0
    timeout "$timeout" docker run --rm --name "$name-oom" \
      --env 'JDK_JAVA_OPTIONS=-XX:-ExitOnOutOfMemoryError -Xmx12m' "$image" > "$work/oom" 2>&1 || status=$?
    if [[ "$status" != 3 ]] || ! grep -q 'Terminating due to java.lang.OutOfMemoryError' "$work/oom"; then
      tail -n 50 "$work/oom" >&2
      fail "running out of memory did not stop the app with exit code 3 (it ended with $status)"
    fi
    echo "Running out of memory stops the app with exit code 3."
    ```

- [ ] **Step 4: Run the tests.** Run the Step 2 command and `scripts/gradle.sh build`. On a Linux Docker host, run
  `docker build -t home-control:smoke . && scripts/smoke-test-image.sh home-control:smoke`, and the `-bluetooth`
  variant as the script's header shows. CI runs both on amd64 and arm64.
- [ ] **Step 5: Update docs.**
  - `docs/user/configuration.md`: the first line below goes into the table after `SERVER_PORT`, and the section before
    `## Renamed settings`:

    ```markdown
    | `JDK_JAVA_OPTIONS` | unset | Java options, such as the heap size; see [Java options](#java-options) |

    ## Java options

    Java sizes its heap at about a quarter of the container's memory limit, or of the host's memory without one. To
    change that, set `JDK_JAVA_OPTIONS` under `environment:` in `compose.yaml`, for example to
    `-XX:MaxRAMPercentage=50` or `-Xmx256m`. If the app runs out of memory, it stops with exit code 3 and
    `restart: unless-stopped` starts it again, instead of running on with part of it stopped. An option in
    `JDK_JAVA_OPTIONS` cannot switch that off.
    ```

  - `docs/dev/ci-and-releases.md`, the "Smoke-test the image" row: after "waits until its healthcheck reports it
    healthy" add ", and checks that running out of memory stops it with exit code 3".
- [ ] **Step 6: Commit.** `fix: stop the app when it runs out of memory, so Docker restarts it`. The body describes
  OPS-2's zombie: `/health` answered 200 while a `SessionLoop` task or the rail ticker had ended.

## Done when

- [OPS-2](../../reviews/2026-11-27-improvement-catalogue.md#ops-2) is resolved; only the heap and GC defaults are left
  out, for WP-21 (decision 7).
- [DATA-7](../../reviews/2026-11-27-improvement-catalogue.md#data-7) is resolved with the details of
  [OPS-6](../../reviews/2026-11-27-improvement-catalogue.md#ops-6). Left out with their reasons: the compiled-CSS
  digest (decision 1), the narrowing of `ThemeViewAdvice`, all that was left of
  [WEB-15](../../reviews/2026-11-27-improvement-catalogue.md#web-15) (decision 3), and the rest of ST3 (decision 8).
- No imported revision's file bytes stay on the heap, and no response copies an asset. `catalog.json` and the revision
  layout are unchanged, and no migration was added.
- Every test named under Review focus exists and passes; the existing theme tests changed only from `.bytes()` to
  `ThemeTestPackages.read`.
- ADR 0007, `docs/dev/themes.md`, `docs/user/configuration.md` and `docs/dev/ci-and-releases.md` describe the change.
- `scripts/gradle.sh build` is green after every commit, the theme browser tests pass for PR 1, the smoke test passes
  for PR 4, and `scripts/check-commits.sh` accepts the commits.
- This package's row in [the index](README.md) is updated, with the follow-ups below that the owner wants kept.

## Out of scope

- **Heap and GC defaults** (`-XX:MaxRAMPercentage`, SerialGC) go to WP-21, after OPS-11 measures a 1 GB arm64 host.
- **Serving the stored `theme.css` after a digest check** (decision 1), **restricting `ThemeViewAdvice`** (decision
  3), and **a Remove button for a damaged entry, or `problems` that clear without a restart** (decision 8): left out.
  No package plans them.
- **Catching `OutOfMemoryError`** is the analysis's "What not to do"; **a heap dump on OOM** is not done (Global
  constraints).
- **Other `Error`s that end a `SessionLoop` task** (a `StackOverflowError`), and a `/health` that reports stopped
  loops: not covered. OOM is the error the finding names, and the JVM now exits on it.
- **A full `/data` named in the theme 507 text** goes to WP-16 (DATA-8), **an example `JDK_JAVA_OPTIONS` line in the
  compose files** may come with WP-09's `TZ` and log settings, and **exit code 3 on a troubleshooting page** goes to
  WP-18 (OPS-10).
- **Seen while planning, not catalogued, left as they are:** `ThemeController` keeps up to eight pending reviews of up
  to 10 MiB on the heap for ten minutes (login-only, and pinned by
  `ThemePageTest.abandonedReviewsCannotRetainUnboundedUploads`), the review page inlines `preview.png` as base64, and
  `ThemeArchive.read` buffers each entry twice while it unpacks. Each is bounded per request; the index can list them
  as follow-ups.
