# Shield Web Remote

A small Spring Boot web app that controls an NVIDIA Shield (or any Android TV device)
on the same network: a browser remote with live device state.

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

The app stores its Remote v2 client certificate in `data/keystore.p12` and its
paired-device registry in `data/devices.json`. **The certificate is the pairing
credential** — losing it means the Shield must be paired again. Keep the whole
data directory mounted persistently and keep any custom keystore password stable.

The current foreground package remains visible in the remote header as connection
context. Remote v2 does not expose a reliable way to derive a launchable deep link
from that package, so the remote intentionally does not offer app shortcuts.

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
