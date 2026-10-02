# 0007: Installable presentation packages with permanent built-in themes

Date: 2026-10-02

Status: Accepted

## Context

Default styling lived in the shared component stylesheet. Cyberpunk overrode those components, and several parts
of the app hardcoded its assets and the two-theme toggle. Users want to export either look, edit files, and install
and share themes without rebuilding the application. Both existing themes must remain permanently available.

## Decision

Use a versioned ZIP package containing metadata, typed appearance tokens, optional decorative CSS, local assets,
and licence information. The same parser, compiler and rendering contract apply to built-in and imported packages.
Default and Cyberpunk are registered from bundled resources and have reserved IDs. Their protected status is
assigned by the application; uploads cannot declare it. Every mutation path rejects removal or replacement of
either built-in.

The application owns HTML, responsive layout and behaviour. The public extension points are appearance tokens and
documented styling hooks. Themes carry no scripts or replacement templates. Use ph-css to parse the CSS into an
AST and validate supported constructs and local resource references before emitting scoped CSS. Upstream
[ph-css documents nesting support](https://github.com/phax/ph-css#news-and-noteworthy); package tests must prove the
constructs used by Cyberpunk survive that pipeline.

Publish immutable compiled revisions before atomically updating a versioned JSON catalog under `/data/themes`.
Stable source digests identify stored packages; public revisions also cover compiler and token defaults, allowing
an app upgrade to recompile a valid package without invalidating its installation or reusing changed CSS URLs.
An update compares the revision the user reviewed. The catalog exposes only exact canonical presentation asset
paths to the login gate. Those assets and catalog descriptors may be public so login and offline pages can show
the selected theme; exports and management follow the normal application login rules.

Selection remains per browser, using the existing `homecontrol.theme.v1` key and built-in IDs. Recovery uses Default
and excludes imported styling even during login. Offline caching is limited to presentation resources; it does not
cache live state or queue commands.

## Consequences

Authors can derive new themes from either built-in and share self-contained packages. Future UI changes must honour
the token/hook contract or explicitly change the theme API version. The app must maintain strict CSS/archive
validation and an independent recovery surface, since valid styling can still produce poor contrast or layout.

Package author metadata is descriptive, not authenticated. Version 1 has no theme marketplace, remote download,
automatic update or in-app visual editor.
