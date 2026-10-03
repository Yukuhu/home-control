# Home Control

A web app for your home network that brings your devices and your media together on one page. It finds your TVs,
streaming boxes and speakers, shows what they are playing, gives each one a remote, and plays what you pick on the
device you choose.

- **Devices:** NVIDIA Shield and other Android TV / Google TV devices, Google Cast receivers, LG webOS and Samsung
  Tizen TVs, DLNA/UPnP and Sonos speakers, and Bluetooth speakers attached to the host.
- **Content:** Jellyfin, YouTube, trending titles from TMDB, sport fixtures from calendars and TheSportsDB, pinned
  links, and dynamic workflows that turn a JSON API into tiles you can play. Netflix, Prime Video and DAZN titles open
  in their own apps.
- **One container on your LAN:** Docker Compose or CasaOS, a phone-friendly web app, no cloud service. A login password,
  set in Setup or with the first content source, guards it.

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

The image checks its own health: `docker ps` (and CasaOS) shows the container as
`healthy` once the app answers, usually within a minute of starting.

## Running a prebuilt image

CI publishes a multi-arch image (`linux/amd64` and `linux/arm64`) to
`ghcr.io/yukuhu/home-control:latest` with every release, so you can skip
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

### The user the image runs as

The image runs as user 1000, not as root. That user must own the directory mounted at
`/data`. A volume that Docker creates belongs to it from the start.

- **Upgrading from 0.10 or older**, which ran as root: the data directory belongs to root, and
  the app refuses to start and says so. Hand the directory over once, on the host:
  `sudo chown -R 1000:1000 data`.
- **A directory of the host** that does not exist yet is created by Docker as root. Create it
  yourself first. The `data` directory of this repository exists for that reason.
- **Another user**: `user: "1001:1001"` in the Compose file, or `--user 1001:1001`, runs the
  app as that user, who must then own the directory. A start that cannot use the directory names
  the uid and gid it runs as, and the `chown` that hands the directory over.
- **Root after all**: `user: "0:0"`, or `--user 0:0`.

Two variants still run as root. The `-bluetooth` image does, because the host's D-Bus lets
only root talk to BlueZ. The CasaOS manifest does, because CasaOS creates the data directory
as root.

## CasaOS

Import the CasaOS manifest directly from:

```text
https://raw.githubusercontent.com/Yukuhu/home-control/main/casaos/docker-compose.yml
```

The manifest uses host networking so mDNS discovery works and persists `/data` at
`/DATA/AppData/$AppID/data` on the CasaOS host. Back up and move that directory as a whole: its files
only work together. The important ones are:

- `/DATA/AppData/$AppID/data/keystore.p12` — the Remote v2 client credential;
- `/DATA/AppData/$AppID/data/devices.json` — the paired-device registry;
- `/DATA/AppData/$AppID/data/secrets.json` — the keystore password, the LG and Samsung pairing keys and the
  content sources' credentials, encrypted;
- `/DATA/AppData/$AppID/data/secret.key` — the key that decrypts `secrets.json`, unless `HOME_CONTROL_SECRET` is
  set (see [Security](docs/user/security.md)).

An older CasaOS deployment that had no volume mapping cannot recover data from an
already discarded anonymous container. Pair once after installing this manifest;
future container replacements and image updates will reuse the bind-mounted pairing.

The keystore password is generated with the keystore and kept encrypted in
`secrets.json`, so the CasaOS manifest sets none. It protects the local PKCS12 file; it is not a
web login or network authentication. If you set `HOME_CONTROL_ANDROIDTV_KEYSTORE_PASSWORD`
yourself (older setups use `SHIELD_KEYSTORE_PASSWORD`, which still works), keep the same value
for every redeployment.

## First steps

1. Open `http://<host>:8080` and go to **Setup**. Devices on your network show up there; pair each one as
   [Pairing](docs/user/devices.md#pairing) describes.
2. On your phone, add the page to the home screen: see [On your phone](docs/user/devices.md#on-your-phone).
3. Connect content sources on the setup page: see
   [Content sources and login](docs/user/sources.md#content-sources-and-login). The first one asks you to set a login
   password.

## Documentation

Every section this README used to hold now lives in one of these pages, under the same heading.

**Using Home Control** (`docs/user/`):
- [Devices](docs/user/devices.md): pairing, the phone app, opening links, Cast devices, smart TVs, Wi-Fi and Bluetooth
  speakers, discovery problems.
- [Bluetooth speakers](docs/user/bluetooth-speakers.md): host requirements and every failure mode's fix.
- [Content sources](docs/user/sources.md): login, dynamic workflows, Jellyfin, play routes, Netflix, Prime Video and
  DAZN, YouTube, sport.
- [Configuration](docs/user/configuration.md): every property and environment variable.
- [Themes](docs/user/themes.md): choosing, installing, sharing and recovering themes.
- [Security](docs/user/security.md): what is protected, secrets, reverse proxies, allowed hosts and origins.

**Working on Home Control:**
- [AGENTS.md](AGENTS.md): how to build and test, and the rules every change follows.
- [Architecture](docs/dev/architecture.md), [Testing](docs/dev/testing.md) and
  [CI and releases](docs/dev/ci-and-releases.md).
- [Architecture decisions](docs/adr/README.md).
- [Theme authoring](docs/dev/themes.md): package format, tokens, styling hooks and local assets.

## Security

Until a login password is set, anyone who can reach the port can control the devices. The first content source asks
for one, and **Setup → Account** sets one at any time. Do not expose Home Control to the internet without an
authenticating reverse proxy in front of it. [Security](docs/user/security.md) has
the details.

## License

MIT. See [LICENSE](LICENSE). The Cyberpunk theme bundles the Rajdhani font by the Indian Type
Foundry under the SIL Open Font License 1.1 (see
[`OFL.txt`](src/main/resources/themes/cyberpunk/assets/OFL.txt)).
