# Shield Web Remote

A small Spring Boot web app that controls an NVIDIA Shield (or any Android TV device)
on the same network: a browser remote, an app launcher, and live device state.

## Running

```bash
docker compose up --build
```

Then open `http://<host>:8080`.

## Running a prebuilt image

CI publishes a multi-arch image (`linux/amd64` and `linux/arm64`) to
`ghcr.io/yukuhu/home-control:latest` on every push to `main`, so you can skip
building from source:

```bash
docker pull ghcr.io/yukuhu/home-control:latest
```

Swap `build: .` for `image: ghcr.io/yukuhu/home-control:latest` in
`compose.yaml` to use it. Images are also tagged with the full commit SHA if you
want to pin one.

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
| `shield.data-dir` | `/data` in Docker | Where the keystore, registry and app catalog live |
| `SHIELD_KEYSTORE_PASSWORD` | `shield` | Keystore password |
| `shield.discovery-enabled` | `true` | Turn mDNS off entirely |
| `shield.stale-timeout-seconds` | `10` | No inbound message for this long means the connection is dead |
| `shield.reconnect-max-delay-seconds` | `60` | Upper bound on reconnect backoff |

## License

MIT. See [LICENSE](LICENSE).

## Security

There is no authentication: anyone who can reach the port can control the TV. This is
deliberate for a LAN-only tool. Do not expose it to the internet without putting an
authenticating reverse proxy in front of it.
