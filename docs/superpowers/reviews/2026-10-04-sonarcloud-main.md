# SonarCloud main follow-up, October 4 2026

The analysis of `8c19ecbe15231af9dcd2e0d399ca2751eaef6d6d` at 14:30:37 UTC reports
13 open findings. Its quality gate fails only on the new-code security rating (E,
required A). Current main, `314982224f5d768afee709933417afc9cf3e938f`, differs only
in the Gradle setup action version in `.github/workflows/ci.yml`.

## Eight security false positives

All reported flows for these eight findings pass through `RememberedLogins`.
The HTTP request can trigger a storage operation, but cannot choose its pathname:

- `SecurityConfiguration.rememberedLogins` constructs the store with
  `data.resolve(DataDirectory.LOGINS)`; `LOGINS` is the literal `logins.json`.
- `RememberedLogins` constructs its final `VersionedJsonFile` in its constructor.
  `VersionedJsonFile.file` is a final `Path` assigned only by that constructor.
- `RequestLoginContext.startSession` calls `remembered.remember(version)`.
  That value participates in the JSON document, never in path construction.
  New credential versions are generated from random bytes by `LoginService`.
- `VersionedJsonFile.load` reads that fixed path. Its migration backup is a sibling
  named from the fixed filename and an integer schema version. `AtomicFiles`
  receives those paths and creates temporary files in the same directory.

The analyzer propagates taint through the context's `remembered` field, then the
store's `file` field, and finally the JSON file's `file` field. The reported traces
do not identify an assignment from request input to the actual `Path`.

These are individual FALSE_POSITIVE resolutions, not an exception for all users
of the shared storage helpers. This conclusion applies to the reported remembered-
login flows; it does not claim that arbitrary caller-supplied paths are safe.

| Issue | Rule | Reported location |
| --- | --- | --- |
| [AaEBR3cCBHx8aBxKfuPf](https://sonarcloud.io/project/issues?id=Yukuhu_home-control&branch=main&issues=AaEBR3cCBHx8aBxKfuPf) | `javasecurity:S2083` | `AtomicFiles.java:36`, atomic replacement |
| [AaEBR3cCBHx8aBxKfuPh](https://sonarcloud.io/project/issues?id=Yukuhu_home-control&branch=main&issues=AaEBR3cCBHx8aBxKfuPh) | `javasecurity:S2083` | `AtomicFiles.java:57`, exclusive creation |
| [AaEBR3cCBHx8aBxKfuPe](https://sonarcloud.io/project/issues?id=Yukuhu_home-control&branch=main&issues=AaEBR3cCBHx8aBxKfuPe) | `javasecurity:S2083` | `AtomicFiles.java:66`, directory creation |
| [AaEBR3cCBHx8aBxKfuPg](https://sonarcloud.io/project/issues?id=Yukuhu_home-control&branch=main&issues=AaEBR3cCBHx8aBxKfuPg) | `javasecurity:S2083` | `AtomicFiles.java:68`, POSIX temporary file |
| [AaEBR3cCBHx8aBxKfuPd](https://sonarcloud.io/project/issues?id=Yukuhu_home-control&branch=main&issues=AaEBR3cCBHx8aBxKfuPd) | `javasecurity:S2083` | `AtomicFiles.java:71`, other temporary file |
| [AaEBR3ekBHx8aBxKfuPi](https://sonarcloud.io/project/issues?id=Yukuhu_home-control&branch=main&issues=AaEBR3ekBHx8aBxKfuPi) | `javasecurity:S2083` | `VersionedJsonFile.java:135`, read |
| [AaEBR3ekBHx8aBxKfuPj](https://sonarcloud.io/project/issues?id=Yukuhu_home-control&branch=main&issues=AaEBR3ekBHx8aBxKfuPj) | `javasecurity:S6549` | `VersionedJsonFile.java:129`, existence check |
| [AaEBR3ekBHx8aBxKfuPk](https://sonarcloud.io/project/issues?id=Yukuhu_home-control&branch=main&issues=AaEBR3ekBHx8aBxKfuPk) | `javasecurity:S6549` | `VersionedJsonFile.java:173`, backup existence check |

Suggested comment for each issue:

> Reviewed every reported flow at 8c19ecbe15231af9dcd2e0d399ca2751eaef6d6d.
> All reach RememberedLogins, constructed by SecurityConfiguration with the configured
> data directory plus the constant logins.json. VersionedJsonFile stores that Path
> in a final constructor-assigned field. Request/session values affect JSON contents,
> never the pathname. Backup names derive from that fixed filename and an integer
> schema version; temporary files use its parent directory. The reported object-field
> taint does not represent a request-controlled filesystem path. Resolve this specific
> finding as false positive; retain the rule for other callers.

The user chose to apply these resolutions manually because the connector exposes only
read operations and no signed-in browser is connected. For each linked issue, add the
comment above and choose the False Positive resolution. All eight were OPEN when
reviewed; no remote comments or transitions were submitted by this follow-up.

After applying them, refresh the main quality gate and confirm that these eight issue
keys are resolved as FALSE_POSITIVE. The two HTML findings need an analysis of the
merged code before they close. The other three findings remain outside this change.

## Accessibility fixes

The user approved changing two previously accepted choices from the
[October 2 review](2026-10-02-sonarcloud-triage.md):

- `AaDKnCNpHkjsOnJ6opNX` (`Web:S9379`): remove login autofocus so the page does not
  move focus past its introductory context or automatically open the mobile keyboard.
- `AaDKnCOhHkjsOnJ6opNd` (`Web:S6819`): use labeled native radio inputs for playback
  device choices, with shared group naming and native checked state. Selection changes
  still preview the route, while the existing cards, theme styling and focus cues remain.

Existing version 1 themes can keep their `aria-checked` selectors. The compiler expands
them to include a private styling attribute mirrored from the native radio state. The
alias preserves selector specificity and works inside existing `:has()` selectors.
It also preserves the old `button` element and radio-role constraints: compounds such as
`button.sheet-device[role="radio"][aria-checked="true"]` still select the replacement
card. Role-presence selectors and explicit case-insensitive radio matches work, while
other roles stay distinct. These styling aliases do not add ARIA roles to the labels.
The compilation fingerprint changes so installed source packages recompile with fresh
asset URLs; their source revisions and stored package format stay compatible.

The touchpad, Dockerfile and exception-inheritance findings retain the prior review's
recommendations. This follow-up does not change their code or SonarCloud dispositions.

## Verification

- `scripts/gradle.sh build` passes: 3,921 test cases, no failures/errors, one skipped.
- `scripts/e2e.sh` passes across Chromium, Firefox and WebKit: 278 cases, no
  failures/errors or flaky retries, one skipped. The skip is the existing WebKit
  service-worker CSP case.
- The browser regressions cover labeled native choices, checked state, route previews,
  wrapping arrow keys, existing theme selectors, protected input styling and focus.
  The legacy-theme case also checks compound element/role/state selectors, competing
  specificity, unrelated roles and selectors inside `:has()`.
  The shared keyboard helper preserves wrapping and Home/End because WebKit's native
  radio navigation stops at the group boundary.
- Independent code review found no remaining production issues.

The original checkout's build encounters unrelated Spotless violations in pre-existing
nested `.worktrees/` directories. Both successful full commands ran in a clean temporary
copy of the same base commit with exactly this change applied. All changed source files
were compared byte-for-byte with the working checkout. The other worktrees were untouched.
