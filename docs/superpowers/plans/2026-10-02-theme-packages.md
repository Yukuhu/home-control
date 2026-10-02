# Theme Packages Implementation Plan

> **For agentic workers:** Use the implementation and verification skills task by task. Independent files may be
> developed in parallel using superpowers:dispatching-parallel-agents; the integration owner runs shared builds.

**Goal:** Users can install and share appearance packages while Default and Cyberpunk remain permanent built-ins.

**Architecture:** A themes service validates and compiles packages, atomically stores revisions, and exposes a
catalog. Web controllers provide management and exact public asset serving. One synchronous browser bootstrap
selects themes across all pages; the service worker caches only presentation resources.

**Tech Stack:** Java 25, Spring Boot, Jackson 3, Thymeleaf, ES modules/classic theme bootstrap, ph-css 8.2.2.

**Spec:** `docs/superpowers/specs/2026-10-02-theme-packages-design.md`.

## Global constraints

- Default and Cyberpunk can be selected/exported, never removed or overwritten through import.
- ZIP limits: 10 MiB compressed, 30 MiB expanded, 256 entries; CSS 256 KiB, each JSON 128 KiB.
- At most 32 imports and 256 MiB expanded imported storage, including retained revisions.
- Version 1 IDs: lowercase ASCII letters/digits/hyphens, initial letter, maximum 64 characters.
- Package and theme API versions are 1. Images: PNG/JPEG/WebP up to 4096x4096; fonts: WOFF2.
- Preserve `homecontrol.theme.v1`, no application JS/HTML in packages, no external asset requests.
- JSON state stays under `/data`; publish a complete immutable revision before its atomic catalog entry.
- Worktree: `.worktrees/theme-packages`, branch `feat/theme-packages`. No publishing or merging in this task.

## Review focus

- CSS escapes, nested functions and strings carrying URLs must not bypass local-resource checks (task 2).
- Concurrent stale update confirmations must not replace a newer revision (tasks 2 and 4).
- A saved broken theme must not style the recovery login or recovery page (tasks 4 and 5).
- Old asynchronous stylesheet loads must not override a later selection (task 5).
- Missing font/image assets and offline custom-theme eviction must leave working Default controls (tasks 5 and 6).

## Shared interfaces

Backend classes are in `dev.andre.homecontrol.themes`, independent of servlet and security packages.

- `ThemeManifest`: record `int formatVersion, int themeApiVersion, String id, String name, String version,
  String author, String description, String license`.
- `ThemeDescriptor`: record `String id, String name, String version, String author, String description,
  String license, boolean builtIn, String revision, String stylesheet, String preview, String themeColor,
  List<String> assets`.
- `ThemeAsset`: record `String contentType, byte[] bytes`.
- `ThemePackage`: validated package; exposes `manifest()`, `revision()`, `tokens()`, `files()` and
  `descriptor(boolean builtIn)`. Files retain source bytes for export.
- `ThemeCatalog(DataDirectory data)`; `themes(): List<ThemeDescriptor>`, `require(String): ThemeDescriptor`,
  `inspect(byte[]): ThemePackage`, `install(byte[], String expectedRevision): ThemeDescriptor`,
  `remove(String): void`, `export(String): byte[]`, `asset(String rawPath): Optional<ThemeAsset>`,
  `problems(): List<String>`, `defaultColor(): String`.
- `ThemeException` carries a safe message and `status(): int` (400 invalid, 404 absent, 409 protected/conflict,
  413 too large, 507 storage quota/write failure).
- Built-ins live at `src/main/resources/themes/{default,cyberpunk}/`, fixed source filenames from the spec.
  `themes/token-schema.json` maps token names to kinds; built-in tokens are JSON string values. Token names omit
  CSS `--`. Compiler emits `--name: value` under the active root scope.
- Author CSS uses `:root` for its own root (no hardcoded ID). Compiler scopes selectors and rewrites relative
  `assets/...` URLs. `theme-` prefixes are reserved for author font/animation identifiers.
- Asset URLs: `/themes/packages/{id}/{revision}/theme.css` and the package asset path after that prefix.
- Bootstrap GET `/themes/catalog.js` sets `window.homeControlThemes` to `{defaultId:'default', themes:[...]}`.
  GET `/themes/catalog.json` returns the same object for refreshes. Both are public presentation metadata.
- HTML head contains optional `<meta name="theme-recovery" content="true">`, Default fallback stylesheet,
  `/themes/catalog.js`, then `/js/theme.js`. Theme picker is `<select data-theme-picker>` with ID-valued options.
- Theme controller dispatches `homecontrol:themechange` with the descriptor as event detail and exposes
  `window.homeControlTheme` with `select(id)` and `refresh()` for management integration.
- Management: GET `/setup/appearance`, GET `/setup/appearance/recovery`, POST `/setup/appearance/preview`
  (`package` multipart), POST `/setup/appearance/install` (`token`), POST `/setup/appearance/{id}/remove`,
  GET `/setup/appearance/{id}/export`. Recovery reset is a browser button with `data-theme-reset`.
- SW GET `/sw.js` remains static; it imports the catalog bootstrap and obtains base offline assets from there or
  uses the fixed app shell list. Cache publication includes full descriptors only after their assets are cached.

## Tasks

### Task 1: Runtime and baseline

- [x] Add Docker/Podman selection shared by `scripts/gradle.sh` and `scripts/e2e.sh`; retain `HC_GRADLE_IMAGE`.
- [x] Run the existing build via Podman and record baseline failures separately from feature failures.

### Task 2: Package engine and persistence

Files: create `themes/ThemeManifest.java`, `ThemeDescriptor.java`, `ThemeAsset.java`, `ThemePackage.java`,
`ThemeException.java`, `ThemeArchive.java`, `ThemeTokens.java`, `ThemeCss.java`, `ThemeCatalog.java`, and focused
storage helpers as needed. Add ph-css to the version catalog/build and reviewed dependency verification metadata.
Tests: `src/test/java/dev/andre/homecontrol/themes/*Test.java`.

- [x] Write failing tests for reserved IDs, ZIP round trips, malicious archive paths, limits, malformed tokens,
  strict CSS parsing, recursive resource validation and both existing CSS constructs.
- [x] Implement the shared interfaces above using real CSS AST traversal; never serve original unvalidated CSS.
- [x] Write failing lifecycle tests for restart, atomic update publication, stale expected revisions, retained
  revisions, quotas, damaged registry/packages and removal protection for both built-ins.
- [x] Implement the catalog/store; verify focused theme tests. Asset lookup must use exact raw canonical paths.

### Task 3: Shared CSS and reference packages

Files: `static/app.css`, `resources/themes/token-schema.json`, `resources/themes/{default,cyberpunk}/**`;
tests under `themes/BundledThemeResourcesTest.java`.

- [x] Preserve current styles as the visual baseline, then write a test for complete token schemas and exports.
- [x] Extract Default appearance into tokens and optional CSS. Replace appearance literals in shared component
  rules with semantic variables; retain structural responsive styling and protected visibility/accessibility.
- [x] Migrate Cyberpunk to the same source format, root selectors and relative assets; preserve font licences.
- [x] Verify both resource packages contain complete schemas and every referenced local asset. Integration owner
  runs rendered visual/browser checks after the web surface is wired.

### Task 4: Web management and security integration

Files: root `ThemeConfiguration.java`; `web/ThemeController.java`, `ThemeViewAdvice.java`, offline controller;
`security/LoginGateFilter.java`, `SecurityConfiguration.java`; shared layout, theme picker, setup navigation,
appearance/recovery templates; production multipart limits; shared test context reset support.

- [x] Add failing MockMvc tests for catalog delivery, exports, first-run and authenticated management, reserved
  ID rejection, import review/confirmation, stale confirmations and recovery login styling.
- [x] Wire the catalog and public asset lookup without a security/themes package cycle.
- [x] Stage one bounded validated upload per session with an expiring random confirmation token; install validates
  again and compares the revision shown at review. Escape all metadata and clean up consumed/expired candidates.
- [x] Serve Default fallback CSS and both public catalog representations with revalidation; serve revision assets
  immutably. Render `/offline.html` with shared fragments and no private model state.
- [x] Verify focused web tests and update old two-state-toggle expectations to the picker contract.

### Task 5: Browser selection and offline presentation

Files: `static/js/theme.js`, `static/sw.js`; `ThemeE2eTest.java`, `OfflineUiE2eTest.java` and theme-related existing
browser assertions. The browser implementer owns the two JS files and the Java browser tests; the integration owner schedules shared builds.

- [x] Add failing browser cases for a third imported theme, stable reloads, storage denial, failed stylesheet loads,
  rapid switching, cross-tab sync and recovery reset; preserve no-device-key leakage from theme controls.
- [x] Implement synchronous catalog selection with bounded first-paint gating, atomic live stylesheet replacement,
  unchanged persistence key, current-page state on storage errors, catalog refresh and race protection.
- [x] Implement presentation-only offline caching: both built-ins plus one complete custom revision; cache keys
  derive from content revisions and cleanup touches only owned caches. Preserve live request/write failures offline.
- [x] Run browser scenarios in Chromium, Firefox and WebKit where available; inspect desktop/mobile screenshots.

### Task 6: Documentation, acceptance and review

- [x] Add ADR for the package API/permanent built-ins, user Appearance/recovery guidance and author contract with
  exact token/selector/property/function vocabulary implemented by task 2.
- [x] Exercise export-edit-import on both reference packages and verify neither built-in can be removed/replaced.
- [x] Run `scripts/gradle.sh build` and relevant `scripts/e2e.sh` suites; resolve regressions without weakening
  architecture rules or unrelated assertions.
- [x] Review the complete change, fix material findings, record validation evidence and leave the feature branch
  ready for the user to review. Commit only this task's paths with Conventional Commit messages.

## Completion evidence

All tasks completed on `feat/theme-packages`. The full build, 105 selected browser tests across all three engines,
executable JAR smoke test and independent review are recorded in
[the implementation review](../reviews/2026-10-02-theme-packages.md).
