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

## Discovery does not work

mDNS is multicast and does not cross a Docker bridge network. Either run with
`network_mode: host` as the bundled compose file does, or add the device by address.

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
| `HOME_CONTROL_SECRET` | unset | Passphrase that encrypts `secrets.json`; without it a random `secret.key` is created next to it on first use |
| `HOME_CONTROL_TRUSTED_ORIGINS` | empty | Comma-separated origins allowed to send changes, e.g. `https://home.example.org` behind a reverse proxy; their host names are also allowed |
| `HOME_CONTROL_ALLOWED_HOSTS` | empty | Comma-separated extra host names the app answers to: exact names, or `*.example.org` for its subdomains |
| `HOME_CONTROL_SECURE_COOKIE` | `false` | Mark the login cookie `Secure` when the app is only reached over HTTPS |

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

There is no authentication: anyone who can reach the port can control the TV. This is
deliberate for a LAN-only tool. Do not expose it to the internet without putting an
authenticating reverse proxy in front of it.
