# Authoring theme packages

A theme package changes Home Control's appearance through typed tokens, optional decorative CSS and local assets.
Default and Cyberpunk are the reference packages. Both are permanent: users can select and export them, but imports
cannot overwrite their IDs and neither can be removed.

## Start from an export

1. Open **Setup → Appearance** and export Default for a token-only starting point, or Cyberpunk for fonts,
   chamfered panels, gradients and animation examples.
2. Unzip the download and change `id` in `theme.json`, for example to `my-neon`. Change the display metadata too.
3. Edit `tokens.json`. Add or edit `theme.css` and files under `assets/` when needed.
4. ZIP the files at the archive root, without a containing directory. Keep `LICENSE` and any asset licence notices.
5. Import the ZIP in Appearance, review its identity, and confirm installation. Select it with the header's theme
   picker. Installation alone does not change any browser's selection.
6. Export the installed theme to share it or test it on another Home Control installation.

An existing imported ID is an explicit update. Review and confirm that replacement; package `version` is a display
string, not an automatic version-ordering rule. If another browser updates the same theme after review, import and
review your ZIP again. Change the ID to keep both versions installed.

Test your theme on the dashboard, setup forms, login, remote controls, play sheet and offline page. Check narrow
screens, keyboard focus, reduced motion, forced colours and missing fonts/images. If saved styling makes controls
unusable, open `/setup/appearance/recovery`. Its login page and management page use Default; **Reset this browser to
Default** clears the saved choice.

## Files and manifest

```text
theme.json
tokens.json
theme.css          optional
preview.png        optional
assets/            optional fonts, images and licence notices
LICENSE
```

Text files use UTF-8. A version 1 manifest contains exactly these fields:

```json
{
  "formatVersion": 1,
  "themeApiVersion": 1,
  "id": "my-neon",
  "name": "My Neon",
  "version": "1.0.0",
  "author": "Your name",
  "description": "Cyan controls with dark panels.",
  "license": "MIT"
}
```

IDs start with a lowercase ASCII letter, then contain only lowercase letters, digits or hyphens, up to 64 characters.
`default` and `cyberpunk` are reserved. There is no author-controlled `builtIn` field. Names and authors are display
metadata, not proof of identity. Name and author allow up to 120 characters, version up to 64, description up to
2,000, and licence up to 200. Author and description may be empty strings; the other text fields must be nonblank.
Control characters are rejected. Both version integers must be `1`.

The manifest licence identifies your package's licence; include its full text in `LICENSE`. Asset notices belong
under `assets/` as `.txt` files. Cyberpunk's Rajdhani font files retain their original `assets/OFL.txt` notice.

## Token contract

[`themes/token-schema.json`](../../src/main/resources/themes/token-schema.json) is the complete version 1 token
index: each key maps to one type name. `tokens.json` maps those same keys to **JSON strings**, including numeric
values. Token keys omit the CSS `--` prefix. The compiler creates the custom properties on the active theme root.

For example, these entries change the page, accent and control corners inside an exported token file:

```json
{
  "theme-color": "#081c25",
  "bg": "#081c25",
  "accent": "#69dded",
  "accent-ink": "#042229",
  "radius-control": ".4rem"
}
```

Omitted keys inherit Default values. Exports contain a complete set so authors can see every role; later additive
keys within API version 1 also fall back to application defaults. Unknown keys are rejected. Each value must be a
single permitted CSS value, at most 2,048 characters, without declarations, braces, CSS escapes or resource URLs.

| Type | Accepted form |
| --- | --- |
| `color` | One hex colour, listed colour name, or numeric comma-form `rgb()`, `rgba()`, `hsl()` or `hsla()`. `theme-color` specifically requires six-digit `#RRGGBB`. |
| `length` | `0`; a signed decimal with `px`, `em`, `rem`, `vh`, `vw`, `dvh`, `dvw`, `vmin`, `vmax`, `ch`, `ex` or `%`; or numeric/unit arithmetic in `calc()`, `min()`, `max()` or three-argument `clamp()`. Variables and identifiers are not length expressions. |
| `number` | A finite signed decimal, such as `600`, `1.6`, `.45` or `-12`. |
| `font` | A font family list, including quoted family names and generic fallbacks. Custom families start with `theme-`. |
| `shadow` | Shadow lengths, colours, `inset` or `none`; multiple shadows may be comma-separated. |
| `paint` | Colours, supported gradients or `var()` references, optionally comma-separated. URLs are excluded. |
| `text` | Lowercase CSS words separated by spaces, such as `dark` or `italic`. |

Colour names accepted by tokens are `transparent`, `currentColor`, `black`, `white`, `red`, `green`, `blue`, `yellow`,
`gray`, `grey`, `orange`, `purple`, `cyan`, `magenta`, `pink`, `lime`, `navy`, `teal`, `silver`, `maroon`, `olive`,
`aqua`, `fuchsia` and `rebeccapurple`. Typed colour values use numeric comma-form `rgb`, `rgba`, `hsl` and `hsla`.
The wider CSS function vocabulary below applies to decorative CSS, rather than typed colour tokens.

The token index at the end of this guide lists all 155 current keys and Default values. The exported package and
schema are authoritative when an application release adds tokens.

Decorative CSS may override a component's token-based styling. Cyberpunk does this for its chamfers, neon surfaces
and condensed typography. When changing those details, edit the corresponding `theme.css` declaration as well as
the token; Default is the simpler starting point for a theme controlled entirely by tokens.

## Decorative CSS

Write normal selectors using the supported hooks. Use `:root` for your own root; do not write a theme ID into a
selector. The compiler scopes every top-level rule to the selected package, including rules without `:root`.
Nesting is supported:

```css
:root {
    --panel-line: #69dded;

    & .chip-group {
        border-color: var(--panel-line);
        box-shadow: inset 3px 0 0 var(--panel-line);
    }

    & .sheet-device:has(input:checked) {
        background: rgba(105, 221, 237, .12);
    }
}
```

Playback device cards contain native radio inputs. Use `.sheet-device:has(input:checked)`
for their selected appearance. Existing version 1 `aria-checked` selectors remain supported:
the compiler also matches the card's private styling state, preserving selector specificity
and selectors nested inside `:has()`. Legacy `button` and `[role="radio"]` constraints also
match the replacement card, including compounds such as
`button.sheet-device[role="radio"][aria-checked="true"]`. Other roles remain distinct.
The native input supplies the accessible checked state.

Quote attribute values, especially boolean strings such as `"true"`. CSS escapes and unsupported syntax are rejected
rather than repaired. Custom properties use `--` followed by a lowercase letter and up to 63 lowercase letters,
digits or hyphens. Keep local helper names distinct from shared tokens unless you intend to override that token.

Shared CSS owns layout, responsive behaviour, hidden states, hit targets and interaction. Package CSS is compiled
into the `theme` cascade layer, after `foundation` and `components`. The app's protected `accessibility` layer owns
hidden elements, visible focus outlines, reduced motion and forced colours. Do not add `@layer` or `!important` to
package source, remove focus treatment, or rely on an animation to communicate state. Rule and value nesting are
limited to 32 levels, with an additional limit of 64 on overall bracket nesting.

Only `@media`, top-level `@font-face` and top-level `@keyframes` are accepted. There are no imports, namespaces,
`@supports`, scripts, HTML, external requests, `attr()`, `image-set()`, `image()` or `local()` font sources.

Media types are `all`, `screen` and `print`. Supported feature names are listed below. Use the ordinary colon form,
for example `(min-width: 48rem)`; range syntax such as `(width >= 48rem)` is not accepted.

### Fonts, images and animation

Assets may be PNG, JPEG, WebP or WOFF2. Refer to them with a package-relative `assets/...` URL:

```css
@font-face {
    font-family: "theme-panel";
    font-weight: 500;
    font-display: fallback;
    src: url(assets/panel-500.woff2) format("woff2");
}

@keyframes theme-pulse {
    to { opacity: .65; }
}

@media (prefers-reduced-motion: no-preference) {
    :root .connection-status::before {
        animation: theme-pulse 1.6s ease-in-out infinite alternate;
    }
}
```

Set the `font-body` token to a family list beginning with `"theme-panel"`, retaining system fallbacks. Declared font
families and animation names must start with `theme-`; the compiler adds package identity and revision to prevent
collisions and rewrites their references, including token references. Do not add that generated prefix yourself.
`animation` and `animation-name` use literal `theme-*` names; `var()` is not accepted there. Timing functions such as
`steps()`, `cubic-bezier()` and `linear()` are supported. Font `src` accepts direct packaged WOFF2 URLs, optionally
followed by `format("woff2")`; variables and other asset types are not font sources.

All binary assets except `preview.png` must be referenced by the CSS. Images are limited to 4,096 × 4,096 pixels;
animated WebP is outside version 1. Preview images must be PNG. Asset paths use ASCII letters, digits, underscores
and hyphens in directory names; filenames may also contain dots and use lowercase supported extensions. Spaces,
percent encoding, absolute paths, backslashes, `..`, empty segments, symlinks and encrypted entries are rejected.

Font licence notices remain exportable source files. Only compiled CSS and validated images/fonts are public
presentation endpoints; metadata is public so login and offline presentation can use it. Keep credentials and
private data out of theme files.

| Package limit | Maximum |
| --- | --- |
| Compressed ZIP | 10 MiB |
| Expanded archive | 30 MiB |
| ZIP entries | 256 |
| `theme.css` | 256 KiB |
| Each JSON file | 128 KiB |
| Each licence text | 128 KiB |
| Installed imported themes | 32 |
| Imported storage, including retained revisions | 256 MiB |

Servlet upload limits live in `web.ThemeUploadConfiguration`: files spool to disk immediately, each file is
capped at 10 MiB and the whole multipart request at 11 MiB. The narrow `java:S5693` exception preserves that
package contract above Sonar's generic 8 MiB recommendation. The controller separately bounds reads to 10 MiB,
retains at most eight pending reviews across all sessions, and expires them after ten minutes.

For application maintainers: stored source digests are independent of public CSS revisions. Startup verifies the
source and compiles it again. Public revisions include source bytes, token schema/defaults and the compiler
fingerprint in `ThemeArchive.revision`. Bump that fingerprint whenever compiler changes can alter emitted CSS;
keep the source digest contract stable. Changes that break public hooks or token meanings require explicit theme
API compatibility handling.

## Version 1 CSS vocabulary

The lists below reflect [`ThemeCss`](../../src/main/java/dev/andre/homecontrol/themes/ThemeCss.java). Class and ID
hooks form the appearance API; incidental markup, device IDs, content-source selectors and credential attributes
do not. Names without a leading dot in the class list are still class selectors, for example `.chip-group`.

### Class hooks

`active`, `app`, `app-header`, `art`, `auth-card`, `auth-footer`, `auth-page`, `badge`, `brand`,
`brand-light`, `brand-mark`, `button`, `chip`, `chip-group`, `compact`, `connected`, `connection-status`,
`control-icon`, `count`, `danger`, `dashboard-heading`, `device-icon`, `device-section`, `devices`, `down`,
`dpad`, `drawer`, `drawer-close`, `drawer-header`, `drawer-toggle`, `empty-art`, `empty-state`, `error`,
`essential`, `eyebrow`, `header-nav`, `hint`, `host-checks`, `inputs`, `keyboard-help`, `left`,
`library-heading`, `link-help`, `meta`, `mode-switch`, `name`, `ok`, `onboarding`, `open-link`,
`page-heading`, `pairing-code`, `placeholder`, `primary`, `problem`, `progress`, `rail`, `rail-empty`,
`rail-error`, `rail-preferences`, `rail-stale`, `rails-empty`, `refreshing`, `remote-body`,
`remote-connection`, `remote-controls`, `retry`, `right`, `row`, `search`, `search-icon`, `search-on-demand`,
`search-pending`, `secondary`, `section-heading`, `selected`, `setup`, `setup-category`, `setup-content`,
`setup-group-heading`, `setup-layout`, `setup-nav`, `setup-steps`, `setup-welcome`, `sheet`, `sheet-close`,
`sheet-close-form`, `sheet-device`, `sheet-devices`, `sheet-item`, `sheet-label`, `sheet-pin`, `sheet-route`,
`skeleton`, `skip-link`, `source`, `source-links`, `source-preferences`, `speaker-group`, `strip`, `subtitle`,
`table-scroll`, `theme-actions`, `theme-boot`, `theme-card`, `theme-grid`, `theme-library`, `theme-meta`,
`theme-picker`, `theme-preview`, `theme-review`, `theme-select`, `theme-swatch`, `theme-toggle`,
`theme-toggle-label`, `tile`, `tile-play`, `tiles`, `title`, `toast-action`, `touchpad`, `unroutable`, `up`,
`user-code`, `visually-hidden`, `volume`, `welcome-card`, `workflow-call`, `workflow-call-header`,
`workflow-checkbox`, `workflow-editor`, `workflow-field`, `workflow-phase`, `workflow-row`, `workflow-sample`.

### ID hooks

`sheet-play`, `search-q`, `toast`.

### Element selectors

`*`, `body`, `button`, `a`, `input`, `select`, `textarea`, `option`, `label`, `legend`, `fieldset`, `section`,
`header`, `footer`, `nav`, `main`, `aside`, `article`, `dialog`, `form`, `h1`, `h2`, `h3`, `h4`, `p`, `div`,
`span`, `strong`, `small`, `em`, `b`, `i`, `kbd`, `code`, `pre`, `table`, `thead`, `tbody`, `tr`, `th`, `td`,
`ul`, `ol`, `li`, `img`, `svg`, `path`, `summary`, `details`, `progress`, `meter`.

### Pseudo-classes and pseudo-elements

`:root`, `:hover`, `:focus`, `:focus-visible`, `:focus-within`, `:active`, `:disabled`, `:enabled`,
`:checked`, `:indeterminate`, `:target`, `:open`, `:modal`, `:first-child`, `:last-child`, `:only-child`,
`:empty`, `:required`, `:optional`, `:valid`, `:invalid`, `:placeholder-shown`, `::before`, `::after`,
`::placeholder`, `::backdrop`, `::marker`, `::selection`.

Selector functions also include `:is()`, `:where()`, `:not()` and `:has()`, containing only the supported
selectors. `:nth-child()`, `:nth-last-child()`, `:nth-of-type()` and `:nth-last-of-type()` accept ordinary `n`
expressions, `odd` or `even`. Combinators, comma-separated selectors and nested `&` are supported.

### Attribute selectors

`type`, `open`, `hidden`, `disabled`, `checked`, `aria-current`, `aria-selected`, `aria-checked`,
`aria-expanded`, `aria-disabled`, `role`.

Presence tests and exact equality (`=`) are supported. Substring, prefix and suffix operators and arbitrary
`data-*` attributes are excluded. Do not target `data-theme`; the compiler supplies that scope.

### Media feature names

`min-width`, `max-width`, `width`, `min-height`, `max-height`, `height`, `orientation`,
`prefers-reduced-motion`, `forced-colors`, `prefers-color-scheme`, `hover`, `any-hover`, `pointer`,
`any-pointer`.

### Appearance properties

`color`, `color-scheme`, `background`, `background-color`, `background-image`, `background-origin`,
`background-position`, `background-size`, `background-repeat`, `background-clip`, `background-blend-mode`,
`border`, `border-color`, `border-width`, `border-style`, `border-top`, `border-right`, `border-bottom`,
`border-left`, `border-top-color`, `border-right-color`, `border-bottom-color`, `border-left-color`,
`border-top-width`, `border-right-width`, `border-bottom-width`, `border-left-width`, `border-radius`,
`border-top-left-radius`, `border-top-right-radius`, `border-bottom-left-radius`,
`border-bottom-right-radius`, `box-shadow`, `text-shadow`, `text-transform`, `text-decoration`,
`text-decoration-color`, `text-decoration-thickness`, `text-underline-offset`, `font-family`, `font-size`,
`font-weight`, `font-style`, `font-variant`, `font-variant-numeric`, `line-height`, `letter-spacing`,
`word-spacing`, `outline`, `outline-color`, `outline-offset`, `accent-color`, `caret-color`, `opacity`,
`filter`, `backdrop-filter`, `clip-path`, `scrollbar-color`, `animation`, `animation-name`,
`animation-duration`, `animation-delay`, `animation-timing-function`, `animation-iteration-count`,
`animation-direction`, `animation-fill-mode`, `animation-play-state`, `transition`, `transition-property`,
`transition-duration`, `transition-delay`, `transition-timing-function`, `transform`, `transform-origin`,
`padding-left`.

### Decorative pseudo-element properties

`content`, `position`, `inset`, `top`, `right`, `bottom`, `left`, `width`, `height`, `z-index`,
`pointer-events`, `display`, `margin`, `vertical-align`.

These geometry properties are restricted to selectors ending in `::before`, `::after` or `::backdrop`.
`position: relative` is also allowed on the originating component. Decorative `content` must be an empty string
or `none`; `pointer-events` must be `none`. Use decoration to enhance the interface while keeping controls usable.

### Font-face descriptors

`font-family`, `src`, `font-weight`, `font-style`, `font-stretch`, `font-display`, `size-adjust`,
`ascent-override`, `descent-override`, `line-gap-override`, `unicode-range`.

### Value functions

`var`, `calc`, `min`, `max`, `clamp`, `rgb`, `rgba`, `hsl`, `hsla`, `hwb`, `lab`, `lch`, `oklab`, `oklch`,
`color`, `color-mix`, `linear-gradient`, `repeating-linear-gradient`, `radial-gradient`,
`repeating-radial-gradient`, `conic-gradient`, `repeating-conic-gradient`, `polygon`, `inset`, `circle`,
`ellipse`, `round`, `rect`, `xywh`, `steps`, `cubic-bezier`, `linear`, `translate`, `translateX`,
`translateY`, `translateZ`, `translate3d`, `rotate`, `rotateX`, `rotateY`, `rotateZ`, `scale`, `scaleX`,
`scaleY`, `skew`, `skewX`, `skewY`, `matrix`, `matrix3d`, `perspective`, `blur`, `brightness`, `contrast`,
`drop-shadow`, `grayscale`, `hue-rotate`, `invert`, `opacity`, `saturate`, `sepia`, `format`.

`url()` is handled separately and accepts only existing packaged binary assets under `assets/`. It is never
allowed in tokens. Functions are checked recursively, including custom-property values and math expressions.

## Complete token index

These are the 155 current API version 1 keys. Lengths used for responsive geometry, safe-area insets, hit targets
and visibility remain application-owned rather than theme tokens.

| Token | Type | Default value |
| --- | --- | --- |
| `theme-color` | `color` | `#101917` |
| `bg` | `color` | `#101917` |
| `surface` | `color` | `#182320` |
| `surface-raised` | `color` | `#202e29` |
| `fg` | `color` | `#eef3ec` |
| `muted` | `color` | `#a6b5ab` |
| `border` | `color` | `#304039` |
| `accent` | `color` | `#b7e6a0` |
| `accent-ink` | `color` | `#18301b` |
| `danger` | `color` | `#ffb8ac` |
| `btn` | `color` | `#202e29` |
| `focus` | `color` | `#b7e6a0` |
| `color-scheme` | `text` | `dark` |
| `font-body` | `font` | `Inter, ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif` |
| `font-placeholder` | `font` | `Georgia, serif` |
| `font-mono` | `font` | `ui-monospace, monospace` |
| `font-placeholder-style` | `text` | `italic` |
| `device-selected-bg` | `paint` | `linear-gradient(120deg, #29392b, #1b2a22)` |
| `art-bg` | `paint` | `linear-gradient(145deg, #3e5140, #1b2b28)` |
| `placeholder-bg` | `paint` | `radial-gradient(ellipse at bottom right, #a1af6929, transparent 70%)` |
| `placeholder-alternate-bg` | `paint` | `linear-gradient(145deg, #5c514a, #2d2928)` |
| `placeholder-third-bg` | `paint` | `linear-gradient(145deg, #47525b, #232d34)` |
| `empty-bg` | `paint` | `radial-gradient(ellipse at top, #31412a55, transparent 70%), var(--surface)` |
| `touchpad-grid` | `paint` | `radial-gradient(#62745655 1px, transparent 1px)` |
| `auth-bg` | `paint` | `radial-gradient(ellipse at 50% 20%, #3c512d44, transparent 65%), var(--bg)` |
| `connection-shadow` | `shadow` | `0 0 0 4px #b7e6a009` |
| `drawer-shadow` | `shadow` | `0 12px 70px #0009` |
| `sheet-shadow` | `shadow` | `0 20px 100px #0008` |
| `toast-shadow` | `shadow` | `0 10px 40px #0006` |
| `device-icon-bg` | `color` | `#d7efbd0a` |
| `drawer-toggle-bg` | `color` | `#d5ebbd12` |
| `tile-play-bg` | `color` | `#101917a6` |
| `tile-play-border` | `color` | `#ffffff35` |
| `dialog-backdrop` | `color` | `#06100bd9` |
| `step-bg` | `color` | `#b7e6a020` |
| `button-hover-bg` | `color` | `#2b3c33` |
| `button-hover-border` | `color` | `#5d7764` |
| `primary-hover-bg` | `color` | `#cef5bb` |
| `input-border` | `color` | `#3a4b42` |
| `input-bg` | `color` | `#111c17` |
| `input-placeholder` | `color` | `#82958a` |
| `brand-border` | `color` | `#68815e` |
| `nav-active-bg` | `color` | `#26342b` |
| `connection-pending` | `color` | `#d2b87c` |
| `device-selected-border` | `color` | `#82996b` |
| `device-icon-fg` | `color` | `#aabd9d` |
| `status-warning` | `color` | `#e6bdaa` |
| `status-ok` | `color` | `#b8d7a7` |
| `art-border` | `color` | `#435344` |
| `placeholder-fg` | `color` | `#d5e7bb` |
| `placeholder-alternate-fg` | `color` | `#f0d6bd` |
| `placeholder-third-fg` | `color` | `#c1dae6` |
| `rail-error-border` | `color` | `#654c3b` |
| `rail-error-bg` | `color` | `#2c2920` |
| `rail-warning-fg` | `color` | `#e3c7aa` |
| `empty-art-border` | `color` | `#5f7254` |
| `empty-art-bg` | `color` | `#263728` |
| `empty-art-fg` | `color` | `#d1e7bd` |
| `empty-art-selected-bg` | `color` | `#b7e6a0` |
| `empty-art-selected-fg` | `color` | `#254527` |
| `drawer-bg` | `color` | `#18231e` |
| `dialog-border` | `color` | `#526449` |
| `mode-switch-bg` | `color` | `#0d1712` |
| `mode-selected-bg` | `color` | `#34452e` |
| `dpad-bg` | `color` | `#2a382b` |
| `dpad-border` | `color` | `#415139` |
| `touchpad-border` | `color` | `#506048` |
| `touchpad-bg` | `color` | `#1f2d23` |
| `touchpad-crosshair` | `color` | `#a2b391` |
| `sheet-bg` | `color` | `#1b2820` |
| `sheet-device-bg` | `color` | `#132017` |
| `sheet-device-selected-bg` | `color` | `#2a3d27` |
| `sheet-route-bg` | `color` | `#101d15` |
| `sheet-route-fg` | `color` | `#cedbc7` |
| `toast-border` | `color` | `#78534b` |
| `toast-bg` | `color` | `#382c27` |
| `toast-ok-bg` | `color` | `#273c27` |
| `toast-ok-border` | `color` | `#6d8559` |
| `setup-target-border` | `color` | `#8fa573` |
| `setup-heading-fg` | `color` | `#d3dfcb` |
| `label-fg` | `color` | `#c9d5c4` |
| `code-fg` | `color` | `#d4dfbf` |
| `error-border` | `color` | `#704f45` |
| `error-bg` | `color` | `#392a24` |
| `error-fg` | `color` | `#ffd0c0` |
| `welcome-border` | `color` | `#6a7f50` |
| `welcome-bg` | `color` | `#273724` |
| `empty-compact-border` | `color` | `#506045` |
| `progress-bg` | `color` | `#0008` |
| `tile-play-fg` | `color` | `white` |
| `font-size-body` | `length` | `15px` |
| `font-size-mobile-input` | `length` | `16px` |
| `font-size-badge` | `length` | `.6rem` |
| `font-size-eyebrow` | `length` | `.65rem` |
| `font-size-caption` | `length` | `.7rem` |
| `font-size-small` | `length` | `.75rem` |
| `font-size-control` | `length` | `.8rem` |
| `font-size-secondary` | `length` | `.85rem` |
| `font-size-device` | `length` | `.9rem` |
| `font-size-section` | `length` | `.95rem` |
| `font-size-heading-small` | `length` | `1rem` |
| `font-size-heading-tertiary` | `length` | `1.05rem` |
| `font-size-brand` | `length` | `1.15rem` |
| `font-size-setup-heading` | `length` | `1.3rem` |
| `font-size-heading-secondary` | `length` | `1.35rem` |
| `font-size-title` | `length` | `1.5rem` |
| `font-size-code-small` | `length` | `1.6rem` |
| `font-size-library-heading` | `length` | `1.75rem` |
| `font-size-mark` | `length` | `1.8rem` |
| `font-size-auth-heading` | `length` | `2rem` |
| `font-size-placeholder` | `length` | `4rem` |
| `font-size-heading` | `length` | `clamp(2rem, 4vw, 3.3rem)` |
| `font-size-empty-heading` | `length` | `clamp(1.4rem, 3vw, 2rem)` |
| `font-weight-thin` | `number` | `200` |
| `font-weight-normal` | `number` | `400` |
| `font-weight-strong` | `number` | `600` |
| `font-weight-brand` | `number` | `650` |
| `font-weight-bold` | `number` | `700` |
| `line-height-icon` | `number` | `1` |
| `line-height-heading` | `number` | `1.2` |
| `line-height-control` | `number` | `1.4` |
| `line-height-body` | `number` | `1.6` |
| `tracking-heading` | `length` | `-.035em` |
| `tracking-brand` | `length` | `-.05em` |
| `tracking-section` | `length` | `-.02em` |
| `tracking-eyebrow` | `length` | `.16em` |
| `tracking-badge` | `length` | `.045em` |
| `tracking-code` | `length` | `.1em` |
| `tracking-pairing` | `length` | `.2em` |
| `radius-control` | `length` | `.75rem` |
| `radius-input` | `length` | `.65rem` |
| `radius-nav` | `length` | `.55rem` |
| `radius-mark` | `length` | `12px` |
| `radius-round` | `length` | `50%` |
| `radius-badge` | `length` | `6px` |
| `radius-panel` | `length` | `1rem` |
| `radius-device-icon` | `length` | `9px` |
| `radius-source` | `length` | `5px` |
| `radius-tile` | `length` | `.8rem` |
| `radius-art` | `length` | `.85rem` |
| `radius-drawer` | `length` | `1.2rem` |
| `radius-dpad` | `length` | `18px` |
| `radius-kbd` | `length` | `4px` |
| `radius-sheet` | `length` | `1.4rem` |
| `radius-compact` | `length` | `.6rem` |
| `radius-fieldset` | `length` | `.7rem` |
| `radius-auth` | `length` | `1.5rem` |
| `radius-skip` | `length` | `.5rem` |
| `link-underline-offset` | `length` | `.25em` |
| `opacity-disabled` | `number` | `.45` |
| `art-backdrop-blur` | `length` | `5px` |
| `sheet-backdrop-blur` | `length` | `6px` |
| `empty-art-rotation-start` | `number` | `-12` |
| `empty-art-rotation-middle` | `number` | `2` |
| `empty-art-rotation-end` | `number` | `14` |
