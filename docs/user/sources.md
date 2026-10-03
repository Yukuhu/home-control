# Content sources

Connecting the services Home Control plays from, and the login that protects them.

## Content sources and login

Without a login password, `/`, `/setup` and the remote work with no login. You can set one at
any time in **Setup → Account**.

A content source such as Jellyfin, YouTube, or a workflow stores a credential, and a credential
needs the login password: if none is set yet, set it on the setup page at the same time you
connect the source. From then on it guards every page, the live-update stream and artwork, for
every client. Disconnecting the last source keeps the password; remove it in **Setup →
Account** once no connected source needs it.

### Dynamic workflows

In **Setup → Workflows**, create a workflow when JSON from one or more web addresses can supply
values for a direct media address. **One tile** shows a title you choose; ordinary Dashboard
loading reads local metadata and makes no request. **Tiles from an entry array** reads an array
during Dashboard refresh and makes a tile for each entry. Each enabled workflow has its own
Dashboard row. Dashboard source settings can hide the source or a row and change the default
15-minute refresh interval.

#### Calls

A workflow is a list of calls, up to 8. A single tile that plays a fixed media address needs no
call at all; a workflow that generates tiles needs at least one, the call that supplies the
entries. Each call is one HTTP(S) GET that returns JSON. In the
**Calls** section, each call is a card. Use **Add call** to add one, and Up, Down and Remove to
change the order.

- **Call name** — a short name such as `list` or `images`: lower-case letters, digits and `_`.
- **Runs** — **Once** runs the call one time. **Once per entry** runs it for every entry, and is
  offered only for tiles from an entry array.
- **Source URL** and **Request headers** — the address and any headers the call sends. On a
  saved call, choose **Keep saved URL** or **Replace URL**, and **Keep saved headers** or
  **Replace headers**.
- **Add value** — a value to take from the response. Fill in **Variable name** and **JSON
  Pointer**. Pointers can be empty to select the root; escape `/` as `~1` and `~` as `~0`. New
  values start checked as **Sensitive value**; uncheck it only for values you want visible in
  Test results.

A call can use the values of the calls above it as `{name}`. Put `{name}` in a path segment or
query value of the URL, or in a header value. A value can never fill the host, so no response can
send a credential to another server. Values are URL encoded for you. In a header value, write
`{{` and `}}` for a literal `{` and `}`. Move a call up if it needs a value from a call below it.

Each card says when the call runs:

- **Runs at refresh** — only tiles use its values, for example an artwork lookup.
- **Runs at Play** — only the media URL uses its values.
- **Runs at refresh and Play** — both use them. The call that supplies the entries always runs at
  both.

The label appears after you Save. Save rejects a call that nothing uses. Calls that do not need
each other run at the same time.

#### Entries

Under **Tiles**, **Entries come from** names the call whose response holds the array of entries.
Set the array pointer, the entry ID pointer and the entry title pointer. **Add entry field**
takes a value from each entry. **Subtitle** and **Artwork** can each be none, a field of the
entry, or a value from a call, for example artwork found by a per-entry call. A sensitive value
cannot be shown on a tile. The ID and the title always come from the entries.

#### Example

Suppose `https://api.example/channels` returns:

```json
{
  "channels": [
    {"id": "news", "title": "News"},
    {"id": "music", "title": "Music"}
  ]
}
```

- Call `list`, **Once**, source URL `https://api.example/channels`. **Entries come from** `list`,
  array pointer `/channels`, ID `/id`, title `/title`. Add the entry field `channel` at `/id`.
- Call `images`, **Once per entry**, source URL `https://images.example/lookup?channel={channel}`,
  with the value `poster` at `/poster` (not a sensitive value). Set **Artwork** to a value from a call,
  named `poster`. It says **Runs at refresh**.
- Call `stream`, **Once per entry**, source URL `https://api.example/stream?channel={channel}`,
  with the value `token` at `/token` (a sensitive value). It says **Runs at Play**.
- The media template `https://media.example/play?id={channel}&token={token}` then uses the
  selected channel and a fresh token when you press Play.

If `images` fails for one entry, that tile shows no artwork and the refresh still succeeds. If a
call that runs once fails, the refresh fails and the Dashboard keeps the last good tiles.

#### Saving, testing and playing

**Save** validates and encrypts the definition and never starts playback. Saving an enabled
generated workflow can trigger its Dashboard catalog refresh, which requests the source; a
single-tile Dashboard refresh stays local. Saving, switching off or removing one workflow
refreshes only that workflow's tiles; the others keep theirs. The first Save creates the
household login password, even for a public feed; later edits and Tests require login. Saved source URLs, header values,
and media templates are hidden on the edit page. Choose **Keep saved URL**, **Keep saved
headers** or **Keep saved template** to retain them, or **Replace URL**, **Replace headers** or
**Replace template** to enter new values. **Test** runs the calls once and shows up to five sample tiles with
sensitive values and literal URL parts masked; it names the call that failed and sends nothing
to a device. Opening a Dashboard tile previews its route without a workflow fetch. **Play** runs
the calls the media address needs, again and freshly, builds the media address, and sends a Cast
LOAD to the device selected in the play sheet. A failed Play does not retry automatically.

Workflows saved by an earlier release are converted when they are read: their one request
becomes a call named `main`, and the next Save stores the new format. A release from before this
change cannot read a workflow saved since. A converted definition is a little longer, so one
close to the size limit may need shortening before it saves again.

The selected device needs a Cast receiver. Its Default Media Receiver must reach the direct
media URL itself; Home Control does not proxy the media, add download headers, or guarantee
that the receiver supports a particular codec. Workflows make HTTP(S) JSON GET calls and one
Cast action, with no scripts or pagination. Private LAN sources are allowed,
but loopback, link-local, multicast, and unspecified addresses are blocked by default. Set
`HOME_CONTROL_WORKFLOWS_ALLOW_LOOPBACK=true` only when a feed on this same host is needed.
`HOME_CONTROL_WORKFLOWS_ENABLED=false` removes the editor, source, Test and execution while
retaining encrypted definitions and the login requirement.

#### Limits

Default limits are 50 workflows, 8 calls per workflow, 64 values per workflow, 16 static request
headers per call, and 16,384 characters per stored definition. A catalog holds 200 entries, or 50
when a per-entry call runs at refresh; a longer list fails the refresh. Each call is limited to a
2 MiB JSON response, 64 JSON nesting levels and 10 seconds, a Play to 20 seconds and a refresh to
60 seconds. Source and expanded media URLs are limited to 8,192 characters and pointers to 512.
JSON numeric tokens are limited to 1,000 characters, and whole-number entry IDs to 1,000 decimal
digits after exponent expansion; extreme exponent IDs are rejected before expansion. Numeric
values retain exact decimal precision, and numeric `1` and `1.0` identify the same entry while
string `"1"` is distinct. Numeric values may use scientific notation. Connections time out after 5
seconds; at most three same-origin redirects and eight simultaneous workflow calls are allowed.
See [Configuration](configuration.md) for the timeouts you can change.

### Connecting Jellyfin

On the setup page, under **Jellyfin**, give:

- **Server address** — the URL the app itself reaches Jellyfin at, e.g. `http://192.168.1.20:8096`.
- **Address for TVs and speakers** (optional) — only needed when that differs from the server
  address, for example when Jellyfin is reached by the container as `http://jellyfin:8096` over
  a Docker network but TVs must use the LAN address instead.
- **Sign in with** a user name and password (recommended) or an administrator API key. A user
  login is preferred because Home Control never needs administrator rights, and because an API
  key is handed to Cast receivers as-is — a user login is exchanged for a token scoped to that
  session instead.

A **Jellyfin apps** list lets you link a device's own Jellyfin app session to a paired device
when Jellyfin cannot be matched to it automatically (see
[Docker-networked Jellyfin](#docker-networked-jellyfin)). It loads after the setup page itself,
so a NAS that is still waking up does not hold the page; it lists only apps open right now.

Jellyfin 10.9 or newer is required. Turn the whole module off with
`HOME_CONTROL_JELLYFIN_ENABLED=false` — this does not remove a stored token; disconnect Jellyfin
first to drop it.

### Play routes

Under **Setup → Jellyfin → Device playback**, choose **Jellyfin app** (the default) or **VLC**
for each paired Shield / Android TV. Preferences survive reconnecting Jellyfin and are independent
of the session links below them.

With **VLC** selected, Home Control obtains an authenticated original stream from Jellyfin,
wakes the device, and sends a `vlc://https://…` (or HTTP) link through Remote v2. Install VLC
and complete its first-run setup on the TV once. Jellyfin's TV app does not need to be open;
its own external-player setting is not used. The stream uses the configured **Address for TVs
and speakers**, so that address must be reachable from the Shield.

VLC playback starts from the beginning and does not update Jellyfin watched status or resume
progress. Jellyfin audio and subtitle preferences, including separate subtitle files, are not
passed to VLC; choose available embedded tracks in VLC. This route sends the original media
(including MKV) without transcoding; items requiring a live-stream opening step are rejected.
A successful response means the link was sent, not that VLC confirmed playback. Physical Shield
launch compatibility still needs validation. Stream credentials are resolved only when Play is
pressed and are not included in route previews.

While VLC stays in front, the device strip shows the title and length of the item Home Control
launched. Remote v2 reports the foreground app only, so this is inferred: there is no position,
pausing is not visible, and playback started on the TV itself still shows as the app name. The
title clears when another app comes to the front, the device powers off, or the item's length
plus 30 minutes has passed.

Playing a Jellyfin item on a device tries, in order:

1. **The selected device player** — VLC follows the flow above. With **Jellyfin app** selected,
   **the device's own Jellyfin app** — on a paired Shield / Android TV, Play checks the remote
   connection, wakes the device if asleep, opens Jellyfin if needed, and waits for its app and
   controllable session before playing at the saved position. Opening the play sheet only
   previews this route; it never wakes the TV. On other devices, an already-open Jellyfin
   session is used when available.
2. **The Jellyfin receiver on a Cast device** (a Chromecast, or a device with a merged Cast side)
   — on devices without the Android TV app route or a matching Jellyfin session, a
   `Cast with the Jellyfin receiver` message starts playback there instead. A Shield with a
   merged Cast receiver also offers this as a retry option if native app startup fails;
   casting starts only when you choose that option.
3. **A direct stream to the device** — the device fetches a stream URL that Jellyfin builds. Wi-Fi
   speakers and media renderers play it as "Stream directly to this device (DLNA/UPnP)", and a
   Bluetooth speaker plays audio items as "Play through the server on this Bluetooth speaker"; see
   [Wi-Fi speakers](devices.md#wi-fi-speakers-dlnaupnp-and-sonos) and
   [Bluetooth speakers](devices.md#bluetooth-speakers-optional). Cast devices never get here: the
   Jellyfin receiver always wins for them.
4. Otherwise there is no route: `/devices/<id>/route` and `/devices/<id>/play` answer 422 with
   the reason.

Install Jellyfin for Android TV and sign in on the Shield once. If the app asks you to choose a
user each time, configure its automatic login on the TV. Startup waits up to
`home-control.jellyfin.startup-timeout` (`30s` by default); a failure explains whether the
Shield could not connect, did not wake, or Jellyfin did not become ready. Playback is sent once
and is not queued for later. Unconfirmed wake and launch commands are retried during the startup
budget, including after a remote reconnect; playback itself is never retried. Startup logs show
connection, power and foreground app changes, launch attempts and the stage that timed out.
If session matching fails, link the app under **Setup → Jellyfin
apps** while it is open, then try Play again.

App startup uses the Remote v2 package-launch link, `market://launch?id=org.jellyfin.androidtv`,
as used by [androidtvremote2](https://github.com/tronikos/androidtvremote2/blob/main/src/androidtvremote2/androidtv_remote.py).
Package launching depends on the Shield's firmware and Google Play Store;
[known limitations](https://www.home-assistant.io/integrations/androidtv_remote/#launching-apps)
can prevent it from opening the app. Home Control waits for Jellyfin to report ready and shows
an error if launch fails. If the Shield stays on its dashboard, open Jellyfin manually and retry
Play, or choose the Jellyfin Cast receiver when available. Retrying cannot fix a Play Store
version that rejects package launching. Wake and launch still need validation on physical hardware.

If Jellyfin closes back to the Shield dashboard when remote playback starts, disable
**Settings → Playback → Use external player** in the Jellyfin TV app and retry with its built-in
player. [Jellyfin Android TV issue #5731](https://github.com/jellyfin/jellyfin-androidtv/issues/5731)
reports this `PlayNow` crash on Shield with version 0.19.9. This is separate from app launch
failure; remote reconnect messages alone do not identify a Jellyfin crash.

`GET /devices/<id>/route?source=jellyfin&item=<id>` reports which of these a device would use,
without playing anything.

### Docker-networked Jellyfin

When Jellyfin runs behind Docker bridge networking, the sessions it reports name a gateway
address rather than the device's real LAN address, so Home Control cannot match a paired
device to its Jellyfin app session automatically. Link them once on the setup page's
**Jellyfin apps** list; rung 1 above then works normally.

### Netflix, Prime Video and DAZN

TMDB supplies titles, artwork, a weekly trending list and where a title streams. Netflix, Prime
Video and DAZN have no public APIs for this, so Home Control only ever opens their apps — it has
no access to what you watch, your watchlist or "continue watching".

**Setup:** create a free account at [themoviedb.org](https://www.themoviedb.org/), then under
**Settings → API** copy the *API Read Access Token* (recommended) or the v3 API key. Paste it
into **Setup → TMDB**. This stores a secret, so — exactly like connecting Jellyfin or YouTube — a
login password is set the first time and required for every client from then on (see
[Secrets](security.md#secrets)). Under **Setup → Content** choose your language, region and
which streaming services your household subscribes to.

**Trending on your services:** the "Trending on your services" rail lists TMDB's weekly trending
movies and series that are available with a subscription (JustWatch data) on the services you
picked, in your region. Tapping a tile opens the matching service's app at its home screen (Home
Control cannot deep-link into a title through TMDB alone); paste the title's own link in the
play sheet — see below — to make it open directly next time.

**Pinned links:** paste a link from the service's own app or site under **Setup → Pinned links**,
or from the "paste a link to open this title directly" prompt the play sheet's preview already
offers for a trending title that only opens an app's home screen — no need to play it first. Home
Control never fetches the pasted page — only the URL itself is parsed and stored. Links that work:

- Netflix: `https://www.netflix.com/title/<id>` or `.../watch/<id>` (any locale prefix or query
  string is stripped to the canonical title link).
- Prime Video: a share link from the Prime Video app or `primevideo.com`
  (`.../detail/<gti-or-id>...`), or `https://www.amazon.<tld>/gp/video/detail/<ASIN>`.
- YouTube, DAZN, or any other web link — opened as-is.

**What each device does with these links** (expected; unverified on hardware — see the
[streaming launchers acceptance checklist](../superpowers/reviews/2026-09-16-streaming-launchers-acceptance.md)):

| Link | Android TV (Shield) | LG webOS | Samsung Tizen |
|---|---|---|---|
| Netflix title link | opens that title | opens that title (ConnectSDK `contentId`) | opens the Netflix app only |
| "Open Netflix" (app home) | opens the Netflix app | opens the Netflix app | opens the Netflix app |
| Prime Video link (share link or ASIN) | opens that title | opens the Prime Video app only | opens the Prime Video app |
| "Open Prime Video" (app home) | opens the Prime Video app | opens the Prime Video app | opens the Prime Video app |
| DAZN link | opens the app | opens in the TV browser | refused — Samsung cannot open web links |

**Privacy:** posters are loaded directly from `image.tmdb.org` by the browser, so TMDB's CDN sees
the viewing device's IP address, not Home Control's. Set `HOME_CONTROL_TMDB_IMAGE_BASE_URL` to a
mirror if that matters to you. No screen shows a personalised Netflix, Prime Video or DAZN feed —
the trending rail is always labelled as coming from TMDB, and the TMDB credential never reaches
the browser, a log line, or `sources.json` (it lives only in encrypted `secrets.json`, the same
as other content sources' secrets). Pinned links are stored, unencrypted (they are not secret),
in `/data/pinned.json`. If that file is damaged, or was written by a newer Home Control, the
Pinned rail and **Setup → Pinned links** say so, and pinning is refused until it is fixed or
deleted; the rest of the dashboard keeps working.

*This product uses the TMDB API but is not endorsed or certified by TMDB. Streaming availability
data by JustWatch.*

Turn TMDB off with `HOME_CONTROL_TMDB_ENABLED=false`, or pinned links off with
`HOME_CONTROL_PINNED_ENABLED=false` (existing pins are kept, just not shown or usable, while off).

### YouTube

YouTube shows three kinds of rails, all read-only:

- **New from your subscriptions** — the newest uploads across your subscribed channels,
  refreshed hourly.
- **Watch Later** (optional, off by default) — YouTube stopped sharing this playlist with other
  apps for most accounts in 2016; switch it on to find out whether yours still works, otherwise
  the rail explains why it is empty. Save videos to a playlist of your own instead.
- **Playlists you choose** — load your playlists on the setup page and pick which ones become
  rails; they are shown sorted by title (case-insensitive), not in the order you pick them.

Search is on request only, through the **Search YouTube** button next to the unified search
box, and never runs automatically. There is no recommendations or home-feed rail: YouTube's Data
API does not expose one.

#### Setting up your own Google Cloud project

YouTube needs its own OAuth client in *your own* Google Cloud project — never a shared one — so
your quota and consent screen are yours alone. On the setup page, under **YouTube**:

1. Open [console.cloud.google.com](https://console.cloud.google.com/) and create a project, e.g.
   “Home Control”.
2. **APIs & Services → Library** → enable “YouTube Data API v3”.
3. **APIs & Services → OAuth consent screen** (Branding/Audience): user type External, app name
   “Home Control”, your e-mail as support and developer contact; add the
   `.../auth/youtube.readonly` scope under Data Access.
4. **Audience**: add your Google account as a test user, then press **Publish app** so the status
   is “In production” — in “Testing”, Google ends the authorization after 7 days. Google will
   warn “Google hasn't verified this app”; that is expected for your own project.
5. For browser sign-in, open Home Control through an HTTPS domain (or `http://localhost:8080`
   when the browser runs on the server). In **Clients → Create client**, choose **Web application**.
   Add the exact **Authorized redirect URI** displayed in **Setup → YouTube**, for example
   `https://home.example.com/setup/sources/youtube/callback`. Copy the client ID and secret into
   the form, then press **Sign in with Google**. Google requires the callback to match exactly,
   including its scheme, hostname, port and path.
6. Choose the household Google account and allow read-only YouTube access. Google returns you
   to Setup, which shows the connected channel. This connects one account for the whole household;
   sign in again to replace it. Choosing a new account clears the previous account's selected
   playlists and cached library. **Sign in with saved Web client** reuses the stored credentials.
7. Usage against your project's quota is shown on the setup page from then on.

**LAN-only alternative:** Google does not accept a plain HTTP LAN address such as
`http://192.168.1.10:8080` as a browser callback. Create an OAuth client of type **TVs and Limited
Input devices**, paste that client's ID and secret, and press **Use a device code**. Enter the
displayed code at [google.com/device](https://www.google.com/device) and grant access. The two
buttons require their matching Google client type; a Web client cannot request device codes.

**HTTPS reverse proxy:** open Setup at the same external address you will use to sign in.
Set `HOME_CONTROL_TRUSTED_PROXIES` to the proxy's IP address so the displayed callback uses the
external scheme, host and port the proxy reports in `X-Forwarded-Proto`, `X-Forwarded-Host` and
`X-Forwarded-Port` (see [Security](security.md)), and `HOME_CONTROL_SECURE_COOKIE=true`. Without
`X-Forwarded-Port` the callback has the scheme's default port, 443 for HTTPS: a proxy on another
external port must send it. Add the external hostname to `HOME_CONTROL_ALLOWED_HOSTS`.
Register the displayed callback in Google Cloud. A public HTTPS hostname may resolve only on
your LAN: the browser needs to reach the callback, not Google's servers.

**Troubleshooting:** `redirect_uri_mismatch` means the displayed callback is missing or differs
from the one registered on the Web client. `access_denied` may mean your account is not an
allowed test user. An expired, cancelled or already-used sign-in must be started again from
Setup in the same browser; browser requests expire after 10 minutes and also end on server
restart. If Google does not return a refresh token, accept the consent request when retrying.
The household login password protects Home Control; it is never sent to Google.

The flows follow Google's [web-server OAuth guide](https://developers.google.com/youtube/v3/guides/auth/server-side-web-apps)
and [device authorization guide](https://developers.google.com/youtube/v3/guides/auth/devices).

Turn the whole module off with `HOME_CONTROL_YOUTUBE_ENABLED=false`.

#### Quota

Google gives each Cloud project 10 000 YouTube Data API units a day, reset at midnight Pacific
time. With the defaults, refreshing subscriptions costs about 30 units an hour and each search
costs 100 units, capped at 20 searches a day — comfortably inside the daily budget for one
household. Usage so far today, and how it resets, is shown on the setup page. Tunable through
`home-control.youtube.daily-quota-units`, `searches-per-day`, `channels-per-refresh` and
`refresh-interval`.

#### Privacy

The OAuth client secret and refresh token are encrypted at rest in `/data/secrets.json`, the
same as other content sources' secrets (see [Secrets](security.md#secrets)); the access token itself is never
written to disk, only kept in memory. Thumbnails are proxied through Home Control, so a browser
never talks to `i.ytimg.com` directly. Disconnecting revokes the authorization at Google as well
as removing the stored secrets.

#### Playing

On Android TV, LG webOS and Samsung Tizen, playing a YouTube item opens the real YouTube app
with that video — the same route as any other app link. Cast-only devices (a plain Chromecast,
or the Cast side of a merged device) cannot open app links, so for them Home Control offers
**YouTube Cast**, a **best-effort** route through YouTube's unofficial "Lounge" remote-control
interface (the one phones use to cast). It is **off by default for every device** — switch it on
per device under **YouTube Cast (best effort)** on the setup page. Google does not document this
interface and can change or break it without notice; when it fails, the play sheet reports why
and never retries automatically. It never replaces the app route: a device that can open the
YouTube app always tries that first.

## Sport and DAZN

Home Control shows a "Live now / Today" rail built from two kinds of feed you choose yourself:
calendar links you add, and competitions you pick from TheSportsDB. DAZN has no public schedule
API, so Home Control never knows in advance what DAZN is showing — it only opens the app.

**Calendars.** Setup → Sports → Calendars accepts `https://`, `http://` and `webcal://` links
(the last is rewritten to `https://`). A calendar link can contain a private token, so it is
stored as a secret; adding the first one sets the household login password if none exists yet.
LAN calendar servers (Nextcloud, Radicale, a NAS) work over plain `http://`. Links to this
machine (`localhost`, `127.0.0.1`) are refused unless `HOME_CONTROL_SPORTS_CALENDAR_ALLOW_LOOPBACK=true`
(only turn this on if the calendar really is served on this same box); link-local and metadata
addresses (`169.254.0.0/16`) are always refused. The refusal reads "Home Control does not connect
to 127.0.0.1 (address not allowed): that address belongs to this machine or its network link" and
names that setting. Home Control reads each event's start, end and title; weekly and daily
repeats are expanded, other repeat rules are shown once. Calendars refresh every 6 hours.

**TheSportsDB.** Find a competition by country and sport, or enter its numeric id directly. The
documented free key shows at most 3 matches per competition per day; entering your own
TheSportsDB-supporter key raises that limit and is stored as a secret the same way a calendar
link is. Fixtures are cached for a day per competition. This is a community-maintained database
and can be wrong or incomplete; the setup page credits "Data from TheSportsDB."

**Where you watch it.** Per calendar or competition, you tell Home Control which streaming
service you use for it — this is always your own setting, never broadcast-rights data, and every
place it is shown says so ("(your setting)"). A competition mapped to DAZN, Netflix or Prime Video
opens that service's app, but only to its home screen, not the specific event; the play sheet then
offers to pin the actual link ("Paste the DAZN link for this event to open it directly"). For a
competition with no mapped service, the play sheet instead says "Home Control cannot open this
event directly. Paste a link to it (for example the event's page on your streaming service) to
pin it." Either way, the pasted link also appears under Pinned links so it can be reused or
removed later. A calendar event keeps its pinned link when its kick-off moves: the link belongs to
the event (its calendar UID), not to its start time.

**Time zone.** Setup → Sports lets you choose the time zone kick-off times are shown in; it
defaults to the container's `TZ`. Set `TZ` in your Compose file, or choose a zone in setup if you
cannot change the container's environment.

**On each TV:** an Android TV app link opens the DAZN/Netflix/Prime Video app directly; on LG
webOS a DAZN link opens in the TV's browser (webOS has no way to prefer an installed app for a
web link); Samsung Tizen refuses web links outright ("Samsung TVs cannot open web links …").

**Security notes:** the server fetches every calendar link you add, so treat calendar URLs like
any other credential. Which addresses a calendar may resolve to, and how the server keeps to the
address it checked, is described under
[What content sources may connect to](security.md#what-content-sources-may-connect-to). Event
artwork from TheSportsDB loads directly from `r2.thesportsdb.com` in the browser (not proxied),
so that host sees the browser's IP address for artwork requests. Sports settings (calendar
labels/hosts, chosen competitions, the "where you watch it" mapping, but never a calendar URL or
API key) live in `/data/sports.json`.
