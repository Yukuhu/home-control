# Home Control

A small Spring Boot web app that controls the devices on your home network from one
page. Today: NVIDIA Shield and other Android TV devices — a browser remote per device
with live state, and an open-link form that starts a YouTube, Netflix or Prime Video
link in the matching app on the TV.

## Running

```bash
docker compose up --build
```

The bundled `compose.yaml` stores persistent state in `./data` beside the Compose
file. Preserve that directory when updating or recreating the ordinary Docker
deployment.

Then open `http://<host>:8080`. Set `SERVER_PORT` to listen elsewhere — under
the bundled host-networking setup that is the only change required. In the
commented bridge-mode alternative you must update the `ports:` mapping to match,
or the container will publish 8080 while the app listens on your chosen port.

## Running a prebuilt image

CI publishes a multi-arch image (`linux/amd64` and `linux/arm64`) to
`ghcr.io/yukuhu/home-control:latest` on every push to `main`, so you can skip
building from source:

```bash
docker pull ghcr.io/yukuhu/home-control:latest
```

Swap `build: .` for `image: ghcr.io/yukuhu/home-control:latest` in
`compose.yaml` to use it.

| Tag | Points at |
|---|---|
| `latest` | The newest release |
| `0.1.0`, `0.1` | A specific release |
| `sha-<commit>` | One exact commit |

Every releasable commit on `main` is released immediately, so `latest` is both
the newest release and the newest code.

## CasaOS

Import the CasaOS manifest directly from:

```text
https://raw.githubusercontent.com/Yukuhu/home-control/main/casaos/docker-compose.yml
```

The manifest uses host networking so mDNS discovery works and persists `/data` at
`/DATA/AppData/$AppID/data` on the CasaOS host. The important files are:

- `/DATA/AppData/$AppID/data/keystore.p12` — the Remote v2 client credential;
- `/DATA/AppData/$AppID/data/devices.json` — the paired-device registry.

An older CasaOS deployment that had no volume mapping cannot recover data from an
already discarded anonymous container. Pair once after installing this manifest;
future container replacements and image updates will reuse the bind-mounted pairing.

The default keystore password is stable and intentionally omitted from the CasaOS
manifest. It protects the local PKCS12 file; it is not a web login or network
authentication. If you set `SHIELD_KEYSTORE_PASSWORD` yourself, keep the same value
for every redeployment.

## Pairing

1. Open `/setup`.
2. Pick your Shield from the discovered list, or type its IP address.
3. The TV displays a six character code. Type it in and submit.

Pairing can be repeated for several devices — each one appears as its own chip in the
device strip on the dashboard.

The app stores its Remote v2 client certificate in `data/keystore.p12` and its
paired-device registry in `data/devices.json`. **The certificate is the pairing
credential** — losing it means the Shield must be paired again. Keep the whole
data directory mounted persistently and keep any custom keystore password stable.

Each device's current foreground package is shown on its chip in the device strip as
connection context. Remote v2 does not expose a reliable way to derive a launchable deep link
from that package — see "Opening links on a device" below for how to start an app
from the remote instead.

## On your phone

The dashboard at `/` shows rails of content from every connected source; tap a tile to see
how it will play and on which device before committing. The remote for the currently selected
device opens from its **Remote** button in the device strip. On a desktop it opens beside
the library; on a phone it opens as a bottom panel. Press Escape or use the close button to
return to browsing.

The remote drawer has a **Touchpad** mode alongside the usual buttons: tap the pad for OK,
swipe to move (a longer swipe sends more steps, up to four at once), and hold for a long press
where the device supports one. The choice between Buttons and Touchpad is remembered per
browser.

To install Home Control on a phone or tablet's home screen, open **Setup** and use the
**Install on this phone or tablet** section — the exact wording depends on the browser. Over
plain HTTP the installed icon opens the dashboard like a bookmark; offline support and the
installed app's own icon need HTTPS, typically through a reverse proxy.

The **Setup** page groups devices, content connections, dashboard preferences, and app
installation with section navigation. Forms stack on smaller screens and have visible labels.

Keyboard shortcuts on a desktop browser work when no button or form field is focused: arrow keys and Enter drive the D-pad,
Backspace is Back, Space is Play/Pause, `h` is Home and `m` toggles mute, aimed at whichever
device is selected.

## Opening links on a device

Paste an `https://` link into the **Open a link on this device** box and press Play. The
device opens whichever app claims the link — for example a YouTube watch URL starts in the
YouTube app. The app cannot tell whether the target app is installed: if nothing happens on
the TV, install the app or open the link another way.

## Cast devices

Chromecasts, TVs and speakers with Google Cast — including the Shield's built-in Cast — need
no pairing.

- Open **Setup**. Receivers found on the network appear under **Ready to add**; press **Add**.
- A receiver at the same address, or with the same name, as a paired Android TV is added to
  that TV automatically, so the Shield shows up once with remote keys *and* Cast volume.
- If that guess is wrong, use **Split** on the device, or **Merge devices** to join two
  entries. An Android TV pairing always stays with its own entry: merge the Cast entry into
  the TV, not the other way round.
- A Cast device's drawer has a volume slider, **Mute**, **Unmute** and **Stop casting**.
- Paste a direct media link (`.mp4`, `.m4v`, `.mkv`, `.webm`, `.m3u8`, `.mpd`, `.mp3`, `.m4a`,
  `.aac`, `.flac`, `.ogg`, `.wav`) into **Open a link on this device** to play it with Google's
  Default Media Receiver. The receiver downloads the URL itself, so it must be reachable from
  the TV or speaker. On a device that can also open app links (an Android TV) the app link is
  tried first; split off its Cast entry if you want to cast such links instead.
- The device strip shows what a Cast device is playing, including casts started from a phone.
- Cast discovery needs `network_mode: host`, like Android TV discovery. Cast groups are not
  shown yet.
- A receiver whose address changed (DHCP renewal, a new access point) is picked up
  automatically the next time it announces itself over mDNS: the paired device is reconnected
  at the new address, with nothing to redo in Setup.
- Switch the whole Cast module off with `HOME_CONTROL_CAST_ENABLED=false`. Timings live under
  `home-control.cast.*` in `application.yaml`.

## Smart TVs (LG webOS, Samsung Tizen)

LG and Samsung TVs are paired by accepting a request on the TV itself.

- Open **Setup**. TVs found on the network appear under **Devices on this network**; press
  **Pair**, or enter the TV's address under **Add a smart TV by address**.
- The TV shows a prompt ("allow Home Control"). Accept it with the TV remote. The setup page
  waits for your answer — up to 60 seconds for LG, 30 seconds for Samsung — and then opens the
  dashboard for the new TV. Declining shows "declined" and stores nothing.
- A TV at the same address as an already registered device (for example its Cast receiver) is
  added to that device instead of appearing twice.

What works on each brand:

| | LG webOS | Samsung Tizen |
|---|---|---|
| Remote keys (d-pad, OK, Back, Home, Menu, media keys) | yes | yes |
| Volume up / down / mute | yes | yes |
| Absolute volume slider | yes | no |
| Inputs (HDMI …) listed in the drawer | yes | no — use the TV's Source button |
| Power off | yes | yes |
| Power on | Wake-on-LAN | Wake-on-LAN |
| YouTube video link | opens that video | opens that video (DIAL) |
| Netflix title link | opens that title (firmware permitting) | opens the Netflix app only |
| Prime Video link | opens the app | opens the app, if installed |
| Other web links | open in the TV browser | refused |

**Switching a TV on.** Power sends a Wake-on-LAN packet. The TV must allow it: on LG enable
"Turn on via Wi-Fi" or "Mobile TV On"; on Samsung enable "Power On with Mobile" (network
standby). The TV's MAC address is learned automatically while the TV is on; if it is not, type
it into the device's **Wake-on-LAN MAC** field on the setup page. On a host with several
networks set `home-control.wake-on-lan.broadcast-address` to the subnet broadcast
(e.g. `192.168.1.255`).

**Test deep link.** Each device that opens app links has a **Test deep link** button on the
setup page. It opens a YouTube test video and reports what the device told us: LG, Android TV
and Cast report the app in front as it changes; Samsung is polled and only recognises YouTube,
Netflix and Prime Video (and some models not even those). No device reports *which* video
plays, so always check the screen.

**Networking.** Discovery uses SSDP (UDP 1900 multicast) and Wake-on-LAN uses UDP broadcasts;
both need `network_mode: host`. On bridge networking pair by address; switching the TV on may
not work.

Switch a module off with `HOME_CONTROL_WEBOS_ENABLED=false` or `HOME_CONTROL_TIZEN_ENABLED=false`.
An LG TV that forgot this server (factory reset, stored key rejected) shows **UNPAIRED**; pair it
again from **Setup**. A Samsung TV cannot tell "forgot this server" apart from "still booting":
it simply does not answer, so the device shows **DISCONNECTED**, keeps its pairing and is retried
at growing intervals of up to 5 minutes — each retry may put the Allow prompt back on the TV
screen. Only choosing **Deny** on the TV makes a Samsung device **UNPAIRED**. If the prompt keeps
reappearing, choose Allow once or pair the TV again from **Setup**.

## Wi-Fi speakers (DLNA/UPnP and Sonos)

Speakers (and TVs or AV receivers with a DLNA renderer inside) are found over SSDP — UDP 1900
multicast, so the container needs `network_mode: host`.

- Open **Setup**. Renderers and Sonos rooms appear under **Devices on this network**; press
  **Add**. No pairing is needed. A renderer inside a TV that is already registered (same address)
  is merged into that TV automatically as soon as it is discovered — no Add needed.
- Sonos rooms appear once by room name; the partner of a stereo pair, subs and surrounds are
  part of their room, not devices of their own. Any one announcing player lists the whole
  household.

What works:

- Play a direct link (`.mp3`, `.flac`, `.m4a`, `.ogg` …) or a Jellyfin track — the play sheet
  names the route "Stream directly to this device (DLNA/UPnP)". Cast still wins on a device
  that is both a Cast receiver and a renderer.
- Pause, Play and Stop, the volume slider and mute in the device drawer.
- Now playing (title, position, duration), including playback started from another app.
- Sonos grouping from the drawer: **Join** another room's group or **Leave group**. Playing on
  a grouped room plays on its whole group; volume stays per room.
- Jellyfin adds **Recently played music** and **Latest music** rails.

Limits:

- The speaker fetches the stream itself, so it must reach the URL. For Jellyfin set the address
  TVs and speakers should use when connecting Jellyfin (the setup page shows "TVs and speakers
  use …").
- A speaker that lists its formats (ConnectionManager) is only sent those; anything else is
  refused with "cannot play <type>". A speaker that does not list them is sent every stream, and
  may then stay silent or report its own error.
- Now playing refreshes every 2 s while something plays and every 10 s when idle (speakers are
  polled; UPnP eventing is not used).
- Bonded Sonos speakers show as one room; group volume, queues and Sonos music services are not
  supported.
- Two renderers on one IP address are not supported.
- Device descriptions are only read from the registered speaker's own address (and must name
  its UDN), and its control addresses must be on that same host. A renderer whose IP address
  changed is added again from **Setup**. Of a Sonos household, only the announcing player itself
  is merged automatically; the other rooms it lists wait for **Add**.

Switch a module off with `HOME_CONTROL_UPNP_ENABLED=false` or `HOME_CONTROL_SONOS_ENABLED=false`
(without the Sonos module, Sonos players show up as plain UPnP renderers).

## Bluetooth speakers (optional)

Home Control can play music on Bluetooth speakers paired with the machine it runs on. It plays
the stream itself with `mpv`, through the host's PipeWire or PulseAudio — the speaker never talks
to the network directly. The module is **off by default**; it needs a host with BlueZ, a
Bluetooth adapter and an audio server (a Raspberry Pi class machine works; most NAS boxes do
not).

- Start it with `docker compose -f compose.yaml -f compose.bluetooth.yaml up -d --build`, the
  image tag `latest-bluetooth`, or `casaos/docker-compose.bluetooth.yml` on CasaOS (instead of the
  default manifest, not alongside it).
- Pair a speaker on **Setup → Bluetooth speakers**: put it into pairing mode, **Scan for
  speakers**, then **Pair and add**.

What works: direct audio links (`.mp3`, `.flac`, `.m4a`, `.ogg` …) and Jellyfin music; Pause,
Play and Stop, the volume slider and mute; now playing (title, position, duration).

Limits: audio only, no video; the *server* must be able to reach the stream URL (not the
speaker); the speaker's hardware volume is not changed, only mpv's own; music stops the moment
the speaker disconnects, so it never continues on the host's own audio output; one stream per
speaker (no simultaneous playback on the same speaker).

See `docs/bluetooth-speakers.md` for the full host checklist and every failure mode's fix.

## Discovery does not work

mDNS is multicast and does not cross a Docker bridge network. Either run with
`network_mode: host` as the bundled compose file does, or add the device by address.

## Content sources and login

Device-only deployments (no content source connected) are unchanged: `/`, `/setup` and the
remote work with no login, exactly as before this feature.

Connecting a content source such as Jellyfin, YouTube, or a workflow stores a secret, so from that point on a
login password guards every page, the live-update stream and artwork, for every client. Set
the login password on the setup page at the same time you connect the source.

### Dynamic workflows

In **Setup → Workflows**, create a workflow when one JSON address can supply values for a
direct media address. **One tile** shows a title you choose; ordinary Dashboard loading reads
local metadata and fetches JSON only when you press Play. **Tiles from an entry array** reads an array during Dashboard refresh and makes a
tile for each entry. Each enabled workflow has its own Dashboard row. Dashboard source settings
can hide the source or a row and change the default 15-minute refresh interval.

For example, suppose the source URL returns:

```json
{
  "auth": {"token": "example-token"},
  "channels": [
    {"id": "news", "title": "News", "quality": "hd"},
    {"id": "music", "title": "Music", "quality": "sd"}
  ]
}
```

Choose **Tiles from an entry array**, set the array pointer to `/channels`, entry ID to `/id`,
and title to `/title`. Add mappings `A` from the current entry at `/id`, `C` from the whole
response at `/auth/token` (Sensitive), and `D` from the current entry at `/quality`. A media
template such as `https://media.example/play?id={A}&token={C}&quality={D}` then uses the
selected channel's stable ID and a fresh token when you press Play. JSON Pointers can also be
empty to select the root; escape `/` as `~1` and `~` as `~0` within a pointer segment.
New mappings start Sensitive; uncheck it only for values you want visible in Test results.
Switching to one tile resets every mapping to Whole response, so review its pointer. Switching
back to generated tiles requires choosing Current entry again for those mappings.

**Save** validates and encrypts the definition and never starts playback. Saving an enabled
generated workflow can trigger its Dashboard catalog refresh, which requests the source; a
single-tile Dashboard refresh stays local. The first Save creates the household login password, even for a public feed; later
edits and Tests require login. Saved source URLs, header values, and media templates are hidden
on the edit page. Choose **Keep** to retain them or **Replace** to enter new values. **Test**
fetches once and shows up to five sample tiles with sensitive values and literal URL parts
masked; it sends nothing to a device. Opening a Dashboard tile previews its route without a
workflow fetch. **Play** fetches fresh JSON once, builds the media address, and sends a Cast
LOAD to the device selected in the play sheet. A failed Play does not retry automatically.

The selected device needs a Cast receiver. Its Default Media Receiver must reach the direct
media URL itself; Home Control does not proxy the media, add download headers, or guarantee
that the receiver supports a particular codec. Workflows make one HTTP(S) JSON GET and one
Cast action, with no scripts, pagination, or chained requests. Private LAN sources are allowed,
but loopback, link-local, multicast, and unspecified addresses are blocked by default. Set
`HOME_CONTROL_WORKFLOWS_ALLOW_LOOPBACK=true` only when a feed on this same host is needed.
`HOME_CONTROL_WORKFLOWS_ENABLED=false` removes the editor, source, Test and execution while
retaining encrypted definitions and the login requirement.

Default limits are 50 workflows, 32 mappings, 16 static request headers, 200 entries per
generated catalog, a 2 MiB JSON response, 64 JSON nesting levels, and 16,384 characters per
stored definition. Source and expanded media URLs are limited to 8,192 characters and pointers
to 512. JSON numeric tokens are limited to 1,000 characters, and whole-number entry IDs to
1,000 decimal digits after exponent expansion; extreme exponent IDs are rejected before
expansion. Numeric values retain exact decimal precision, and numeric `1` and `1.0` identify
the same entry while string `"1"` is distinct. Numeric mappings may use scientific notation.
Connections time out after 5 seconds and the whole JSON fetch after 15 seconds; at
most three same-origin redirects and four simultaneous workflow fetches are allowed.

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
when Jellyfin cannot be matched to it automatically (see "Docker-networked Jellyfin" below).

Jellyfin 10.9 or newer is required. Turn the whole module off with
`HOME_CONTROL_JELLYFIN_ENABLED=false` — this does not remove a stored token, so the login
requirement stays; disconnect Jellyfin first, or delete `secrets.json`, to drop it.

### Play routes

Playing a Jellyfin item on a device tries, in order:

1. **The device's own open Jellyfin app** — if Jellyfin reports a session for that device, Home
   Control tells the app to play, resuming at the saved position.
2. **The Jellyfin receiver on a Cast device** (a Chromecast, or a device with a merged Cast side)
   — if the device has no matching Jellyfin app session but does accept Cast messages, a
   `Cast with the Jellyfin receiver` message starts playback there instead.
3. Otherwise there is no route: `/devices/<id>/route` and `/devices/<id>/play` answer 422 with
   the reason.

A fourth option, a direct stream URL Jellyfin builds for the device to fetch itself, exists in
the code (`JellyfinStreams`) but is groundwork for Wi-Fi/media-renderer speakers (sub-project I)
— no device kind in this release advertises that capability, so Cast devices never use it (the
receiver message always wins for them).

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
login password is set the first time and required for every client from then on (see "Secrets"
below). Under **Setup → Content** choose your language, region and which streaming services your
household subscribes to.

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
[streaming launchers acceptance checklist](docs/superpowers/reviews/2026-09-16-streaming-launchers-acceptance.md)):

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
in `/data/pinned.json`.

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
Set `SERVER_FORWARD_HEADERS_STRATEGY=framework` so the displayed callback uses the proxy's
external scheme, host and port, and `HOME_CONTROL_SECURE_COOKIE=true`. Configure the proxy to
overwrite forwarded headers, and allow only that trusted proxy to reach the backend when
forwarded-header support is enabled. Add the external hostname to `HOME_CONTROL_ALLOWED_HOSTS`.
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
same as other content sources' secrets (see "Secrets" below); the access token itself is never
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

### Secrets

Secrets (session tokens, the login password hash) live in `/data/secrets.json`, encrypted at
rest. The key is either:

- a random `/data/secret.key` created next to it the first time a secret is stored (the
  default), or
- the `HOME_CONTROL_SECRET` environment variable, if set.

**Honest limit:** with the default key file, copying the whole `/data` directory (a backup, a
migration) copies the key along with the encrypted secrets — anyone with that copy can decrypt
them. Set `HOME_CONTROL_SECRET` if that risk matters to you; a copy of `/data` alone is then
useless without it. Once you start the app with `HOME_CONTROL_SECRET` set, changing or losing
that value stops the app from starting until the original value is restored — there is no
partial recovery.

**Forgotten login password:** stop the container, delete `/data/secrets.json`, and reconnect
your content sources. This clears every stored secret and login password; there is no other
way to reset just the password.

Behind an HTTPS reverse proxy, set `HOME_CONTROL_SECURE_COOKIE=true` so the login cookie is
marked `Secure`. If the proxy rewrites the `Host` header, also set
`HOME_CONTROL_TRUSTED_ORIGINS` (see "Configuration" below) to the origin your browser actually
sees, or requests will be refused as cross-site.

## Sport and DAZN

Home Control shows a "Live now / Today" rail built from two kinds of feed you choose yourself:
calendar links you add, and competitions you pick from TheSportsDB. DAZN has no public schedule
API, so Home Control never knows in advance what DAZN is showing — it only opens the app.

**Calendars.** Setup → Sports → Calendars accepts `https://`, `http://` and `webcal://` links
(the last is rewritten to `https://`). A calendar link can contain a private token, so it is
stored as a secret; adding the first one sets the household login password if none exists yet.
LAN calendar servers (Nextcloud, Radicale, a NAS) work over plain `http://`. Links to this
machine (`localhost`, `127.0.0.1`) and to link-local/metadata addresses (`169.254.0.0/16`) are
refused with a "belongs to this machine or its network link" message unless
`HOME_CONTROL_SPORTS_CALENDAR_ALLOW_LOOPBACK=true` (only turn this on if the calendar really is
served on this same box). Home Control reads each event's start, end and title; weekly and daily
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
removed later.

**Time zone.** Setup → Sports lets you choose the time zone kick-off times are shown in; it
defaults to the container's `TZ`. Set `TZ` in your Compose file, or choose a zone in setup if you
cannot change the container's environment.

**On each TV:** an Android TV app link opens the DAZN/Netflix/Prime Video app directly; on LG
webOS a DAZN link opens in the TV's browser (webOS has no way to prefer an installed app for a
web link); Samsung Tizen refuses web links outright ("Samsung TVs cannot open web links …").

**Security notes:** the server fetches every calendar link you add, so treat calendar URLs like
any other credential; there is a known, accepted residual risk that a malicious calendar's DNS
name could resolve to a blocked address between the address check and the actual connection
(DNS rebinding) — the JDK's HTTP client does not support pinning the resolved address. Event
artwork from TheSportsDB loads directly from `r2.thesportsdb.com` in the browser (not proxied),
so that host sees the browser's IP address for artwork requests. Sports settings (calendar
labels/hosts, chosen competitions, the "where you watch it" mapping, but never a calendar URL or
API key) live in `/data/sports.json`.

## Configuration

| Property | Default | Meaning |
|---|---|---|
| `SERVER_PORT` | `8080` | Port the web UI listens on |
| `shield.data-dir` | `/data` in Docker | Where the keystore and device registry live |
| `SHIELD_KEYSTORE_PASSWORD` | `shield` | Keystore password |
| `shield.discovery-enabled` | `true` | Turn mDNS off entirely |
| `shield.stale-timeout-seconds` | `10` | No inbound message for this long means the connection is dead |
| `shield.reconnect-max-delay-seconds` | `60` | Upper bound on reconnect backoff |
| `HOME_CONTROL_CAST_ENABLED` | `true` | Turn the Cast module off entirely; Android TV devices keep working |
| `home-control.cast.*` | see `CastProperties` | Cast receiver heartbeat interval, stale timeout, reconnect backoff, and command/load timeouts |
| `home-control.ssdp.enabled` | `true` | SSDP discovery for smart TVs |
| `home-control.webos.enabled` | `true` | LG webOS module (`HOME_CONTROL_WEBOS_ENABLED`) |
| `home-control.webos.pairing-timeout-seconds` | `60` | How long pairing waits for the prompt |
| `home-control.webos.liveness-interval-seconds` | `30` | How often a connected LG TV is checked; no answer means it is gone |
| `home-control.tizen.enabled` | `true` | Samsung Tizen module (`HOME_CONTROL_TIZEN_ENABLED`) |
| `home-control.tizen.client-name` | `Home Control` | Name shown in the Samsung Allow prompt |
| `home-control.tizen.poll-interval-seconds` | `5` | How often Samsung state is polled |
| `home-control.wake-on-lan.broadcast-address` | `255.255.255.255` | Use the subnet broadcast on multi-homed hosts |
| `home-control.deep-link-test.youtube-url` | Big Buck Bunny on YouTube | Video the test button opens |
| `home-control.deep-link-test.timeout` | `10s` | How long the test button watches for the app to change (the setup page says so) |
| `HOME_CONTROL_SECRET` | unset | Passphrase that encrypts `secrets.json`; without it a random `secret.key` is created next to it on first use |
| `HOME_CONTROL_TRUSTED_ORIGINS` | empty | Comma-separated origins allowed to send changes, e.g. `https://home.example.org` behind a reverse proxy; their host names are also allowed |
| `HOME_CONTROL_ALLOWED_HOSTS` | empty | Comma-separated extra host names the app answers to: exact names, or `*.example.org` for its subdomains |
| `HOME_CONTROL_SECURE_COOKIE` | `false` | Mark the login cookie `Secure` when the app is only reached over HTTPS |
| `HOME_CONTROL_JELLYFIN_ENABLED` | `true` | Turn the Jellyfin module off entirely |
| `HOME_CONTROL_YOUTUBE_ENABLED` | `true` | Turn the YouTube module off entirely |
| `HOME_CONTROL_WORKFLOWS_ENABLED` | `true` | Turn the workflow UI, source, Test and Cast execution off while retaining encrypted definitions |
| `HOME_CONTROL_WORKFLOWS_ALLOW_LOOPBACK` | `false` | Allow workflow source and media URLs to use this host's loopback address |
| `home-control.youtube.daily-quota-units` | `10000` | Your Cloud project's daily YouTube Data API budget |
| `home-control.youtube.searches-per-day` | `20` | On-demand searches allowed per day (100 quota units each) |
| `home-control.youtube.channels-per-refresh` | `30` | Subscribed channels read per subscriptions refresh |
| `home-control.youtube.refresh-interval` | `60m` | How often every YouTube rail (subscriptions, Watch Later, chosen playlists) refreshes in the background |
| `HOME_CONTROL_TMDB_ENABLED` | `true` | Turn the TMDB module off entirely |
| `HOME_CONTROL_TMDB_API_BASE_URL` | `https://api.themoviedb.org/3` | TMDB API base URL |
| `HOME_CONTROL_TMDB_IMAGE_BASE_URL` | discovered from TMDB's `/configuration` | Override the poster CDN, e.g. with a mirror, for privacy |
| `HOME_CONTROL_TMDB_PROVIDER_IDS_NETFLIX` | `8,1796` | TMDB watch-provider ids counted as Netflix |
| `HOME_CONTROL_TMDB_PROVIDER_IDS_PRIMEVIDEO` | `9,119,2100` | TMDB watch-provider ids counted as Prime Video |
| `HOME_CONTROL_PINNED_ENABLED` | `true` | Turn pinned shortcuts off entirely |
| `HOME_CONTROL_PINNED_MAX_PINS` | `200` | How many links a household can pin |
| `home-control.upnp.enabled` | `true` | DLNA/UPnP media renderer module (`HOME_CONTROL_UPNP_ENABLED`) |
| `home-control.upnp.poll-interval-seconds` | `2` | State polling while something plays |
| `home-control.upnp.idle-poll-interval-seconds` | `10` | State polling while idle |
| `home-control.sonos.enabled` | `true` | Sonos module (`HOME_CONTROL_SONOS_ENABLED`; off: players appear as plain renderers) |
| `home-control.sonos.topology-interval-seconds` | `30` | How often group topology is re-read |
| `HOME_CONTROL_SPORTS_ENABLED` | `true` | Turn the sports module off entirely |
| `HOME_CONTROL_SPORTS_TIME_ZONE` | empty | Time zone for kick-off times when none is chosen in setup; falls back to the container's `TZ` |
| `HOME_CONTROL_SPORTS_RAIL_SIZE` | `30` | Items kept in the "Live now / Today" rail |
| `HOME_CONTROL_SPORTS_MAX_CALENDARS` | `10` | Calendars a household can add |
| `HOME_CONTROL_SPORTS_MAX_COMPETITIONS` | `10` | TheSportsDB competitions a household can add |
| `HOME_CONTROL_SPORTS_DEFAULT_EVENT_DURATION` | `120m` | Assumed length when a calendar event has no end time or duration |
| `HOME_CONTROL_SPORTS_CALENDAR_REFRESH` | `6h` | How often each calendar is refetched |
| `HOME_CONTROL_SPORTS_CALENDAR_ALLOW_LOOPBACK` | `false` | Allow calendar links that resolve to this machine's own address (only if a calendar is served here) |
| `HOME_CONTROL_SPORTS_THESPORTSDB_ENABLED` | `true` | Turn TheSportsDB fixtures off; calendars keep working |
| `HOME_CONTROL_SPORTS_THESPORTSDB_API_BASE_URL` | `https://www.thesportsdb.com/api/v1/json` | TheSportsDB API base URL |
| `HOME_CONTROL_SPORTS_THESPORTSDB_FIXTURES_TTL` | `24h` | How long a competition's daily fixtures are cached |
| `home-control.bluetooth.enabled` | `false` | Bluetooth speaker module |
| `home-control.bluetooth.dbus-address` | `unix:path=/run/dbus/system_bus_socket` | Host D-Bus system bus |
| `home-control.bluetooth.adapter` | *(first powered)* | Adapter MAC or id such as `hci0` |
| `home-control.bluetooth.scan-seconds` | `10` | Length of a scan |
| `home-control.bluetooth.mpv-path` | `mpv` | Player executable |
| `home-control.bluetooth.audio-device-template` | *(blank: find the speaker's sink)* | e.g. `alsa/bluealsa:DEV={mac},PROFILE=a2dp` |
| `home-control.bluetooth.default-volume` | `50` | Player volume until changed |

The app only answers to host names that cannot be pointed at it by someone else's DNS
(DNS rebinding): IP addresses, `localhost`, single-label names such as `nas`, and names
ending in `.local`, `.lan`, `.home.arpa` or `.internal`. Any other name gets
`421 Misdirected Request`. If you reach it under a real domain, for example through a
reverse proxy, add that name to `HOME_CONTROL_ALLOWED_HOSTS` (or its origin to
`HOME_CONTROL_TRUSTED_ORIGINS`). Changes (POST and other non-read requests) from another
site's page are refused with `403`, whether or not a login exists.

An older `devices.json` (from before multi-device support) is upgraded in place on
first start; the upgrade keeps existing pairings, so no re-pairing is needed after
updating. The upgrade is one-way: an older image cannot read the new file. The original
is kept once as `devices.v1.json` in the same directory — to roll back, stop the app,
restore that file as `devices.json`, and start the older image.

## CI quality gate

The `Build and test` job runs the Gradle build, scans with SonarCloud, and waits for the
quality gate. A failed scan, failed gate, or missing `SONAR_TOKEN` makes the job red and
prevents the release job from running. Keep `SONAR_TOKEN` in both the GitHub Actions and
Dependabot secret stores. GitHub does not provide that secret to fork pull requests, so
their build stays red until a separate scan path is configured.

## Browser tests

`./gradlew build` (or `scripts/gradle.sh build` without a local JDK) never resolves Playwright or
needs a browser installed — the Playwright-driven dashboard
tests (play sheet, device switching, rail failure, login gating, touchpad) live in their own
`e2e` source set and Gradle task, outside `check`/`build`.

To run them:

```bash
./gradlew installPlaywrightBrowsers   # once; needs root or passwordless sudo, Ubuntu 22.04-26.04
./gradlew e2eTest                     # Chromium and WebKit; -Pe2eBrowsers=chromium to narrow
```

Without a local JDK, `scripts/e2e.sh` builds a `gradle:jdk25`-based image with both browsers
already installed and runs `e2eTest` inside it (a tracked copy of the same
`.superpowers/e2e.sh` this project's agents use). Playwright traces from any run land in
`build/e2e-artifacts/<test>-<browser>.zip`; open one at https://trace.playwright.dev.

## Releases

Versions are derived from [conventional commit](https://www.conventionalcommits.org)
messages, and a push to `main` releases automatically:

| Commit type | Effect |
|---|---|
| `feat:` | Minor bump |
| `fix:`, `perf:` | Patch bump |
| `feat!:` or `BREAKING CHANGE:` | Minor bump, because this project is still pre-1.0 |
| `docs:`, `ci:`, `chore:`, `test:`, `refactor:` | No release |

A release builds the jar with that version, publishes the multi-arch image, then
creates the tag and the GitHub release from the generated changelog — in that
order, so a failed build never leaves a tag pointing at an image that was never
pushed.

## License

MIT. See [LICENSE](LICENSE).

## Security

Device-only deployments have no authentication: anyone who can reach the port can control the
TV. This is deliberate for a LAN-only tool. Connecting a content source (see "Content sources
and login" above) adds a login password that then guards every page. Either way, do not expose
this app to the internet without putting an authenticating reverse proxy in front of it.
