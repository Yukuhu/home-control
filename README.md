# Shield Web Remote

A small Spring Boot web app that controls an NVIDIA Shield (or any Android TV device)
on the same network: a browser remote, an app launcher, and live device state.

## Running

```bash
docker compose up --build
```

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

## Pairing

1. Open `/setup`.
2. Pick your Shield from the discovered list, or type its IP address.
3. The TV displays a six character code. Type it in and submit.

The app stores a client certificate in `data/keystore.p12`. **That certificate is the
pairing credential** — delete it and you must pair again. `data/devices.json` holds the
device list and `data/apps.yaml` the launcher entries, which you can edit freely.

## Discovery does not work

mDNS is multicast and does not cross a Docker bridge network. Either run with
`network_mode: host` as the bundled compose file does, or add the device by address.

## Configuration

| Property | Default | Meaning |
|---|---|---|
| `SERVER_PORT` | `8080` | Port the web UI listens on |
| `shield.data-dir` | `/data` in Docker | Where the keystore, registry and app catalog live |
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
