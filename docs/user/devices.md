# Devices

Pairing and using the devices Home Control controls. Bluetooth speakers have their own guide: [Bluetooth speakers](bluetooth-speakers.md).

## Pairing

1. Open `/setup`.
2. Pick your Shield from the discovered list, or type its IP address.
3. The TV displays a six character code. Type it in and submit.

Pairing can be repeated for several devices — each one appears as its own chip in the
device strip on the dashboard.

The app stores its Remote v2 client certificate in `data/keystore.p12` and its
paired-device registry in `data/devices.json`. **The certificate is the pairing
credential** — losing it means the Shield must be paired again. The keystore's password is
generated with it and kept encrypted in `data/secrets.json`, unless you set
`HOME_CONTROL_ANDROIDTV_KEYSTORE_PASSWORD`; then keep that value stable. Keep the whole data
directory mounted persistently. LG and Samsung TVs' pairing keys are kept in `data/secrets.json`
too.

Each device's current foreground package is shown on its chip in the device strip as
connection context. Remote v2 does not expose a reliable way to derive a launchable deep link
from that package — see [Opening links on a device](#opening-links-on-a-device) for how to start an app
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
**Install app** link, which leads to **A home on your home screen** — the exact wording of the
browser's own prompt depends on the browser. Over
plain HTTP the installed icon opens the dashboard like a bookmark; offline support and the
installed app's own icon need HTTPS, typically through a reverse proxy.

The **Setup** page groups devices, content connections, dashboard preferences, and app
installation with section navigation. Forms stack on smaller screens and have visible labels.

The **Theme** selector in the header switches every page between Default, Cyberpunk and
your installed themes. Your choice is remembered per browser, and other open tabs follow
along. Default and Cyberpunk are always available. **Setup → Appearance** lets you export,
install and remove custom themes; see [Themes](themes.md) for sharing and recovery.

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
  A receiver matched only by name is left alone when another device also has that name or
  that address: it stays under **Ready to add**, and **Add** makes it a device of its own.
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

- Open **Setup**. TVs found on the network appear under **Find a device**; press **Pair**, or
  enter the TV's address under **Add another smart TV by address**.
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

- Open **Setup**. Renderers and Sonos rooms appear under **Ready to add (no pairing needed)**;
  press **Add**. A renderer inside a TV that is already registered (same address)
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

See [Bluetooth speakers](bluetooth-speakers.md) for the full host checklist and every failure mode's fix.

## Discovery does not work

mDNS is multicast and does not cross a Docker bridge network. Either run with
`network_mode: host` as the bundled compose file does, or add the device by address.
