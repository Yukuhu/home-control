# Configuration

Every setting, as a Spring property or an environment variable.

| Property | Default | Meaning |
|---|---|---|
| `SERVER_PORT` | `8080` | Port the web UI listens on |
| `home-control.data-dir` | `/data` in Docker | Where the keystore, device registry, secrets and settings live |
| `home-control.discovery.enabled` | `true` | Turn mDNS off entirely |
| `home-control.androidtv.enabled` | `true` | Turn Android TV off entirely; the setup page drops its pairing form (`HOME_CONTROL_ANDROIDTV_ENABLED`) |
| `HOME_CONTROL_ANDROIDTV_KEYSTORE_PASSWORD` | empty | Keystore password. Empty means generated with the keystore and kept encrypted in `secrets.json`; set it only to the password an existing keystore was made with. `SHIELD_KEYSTORE_PASSWORD`, the old name, still works |
| `home-control.androidtv.stale-timeout` | `10s` | No incoming message or successfully sent command for this long triggers a reconnect; commands can postpone device pings |
| `home-control.androidtv.reconnect-max-delay` | `60s` | Upper bound on reconnect backoff |
| `HOME_CONTROL_CAST_ENABLED` | `true` | Turn the Cast module off entirely; Android TV devices keep working |
| `home-control.cast.*` | see `CastProperties` | Cast receiver heartbeat interval, stale timeout, reconnect backoff, and command/load timeouts |
| `home-control.ssdp.enabled` | `true` | SSDP discovery for smart TVs |
| `home-control.webos.enabled` | `true` | LG webOS module (`HOME_CONTROL_WEBOS_ENABLED`) |
| `home-control.webos.pairing-timeout` | `60s` | How long pairing waits for the prompt |
| `home-control.webos.liveness-interval` | `30s` | How often a connected LG TV is checked; no answer means it is gone |
| `home-control.tizen.enabled` | `true` | Samsung Tizen module (`HOME_CONTROL_TIZEN_ENABLED`) |
| `home-control.tizen.client-name` | `Home Control` | Name shown in the Samsung Allow prompt |
| `home-control.tizen.poll-interval` | `5s` | How often Samsung state is polled |
| `home-control.wake-on-lan.broadcast-address` | `255.255.255.255` | Use the subnet broadcast on multi-homed hosts |
| `home-control.events.heartbeat-interval` | `25s` | How often the dashboard's live updates send a keep-alive, so a reverse proxy does not close them; at least `1s`, and it cannot be switched off |
| `home-control.deep-link-test.youtube-url` | Big Buck Bunny on YouTube | Video the test button opens |
| `home-control.deep-link-test.timeout` | `10s` | How long the test button watches for the app to change (the setup page says so) |
| `HOME_CONTROL_SECRET` | unset | Passphrase that encrypts `secrets.json`; without it a random `secret.key` is created next to it on first use |
| `HOME_CONTROL_RESET_LOGIN` | `false` | `true` removes a forgotten login password, and the content sources' credentials, at the first start with it; the TV pairings stay. It runs once; unset it again so a later reset can run |
| `HOME_CONTROL_TRUSTED_ORIGINS` | empty | Comma-separated origins allowed to send changes, e.g. `https://home.example.org` behind a reverse proxy; their host names are also allowed |
| `HOME_CONTROL_ALLOWED_HOSTS` | empty | Comma-separated extra host names the app answers to: exact names, or `*.example.org` for its subdomains |
| `HOME_CONTROL_SECURE_COOKIE` | `false` | Mark the login cookie `Secure` when the app is only reached over HTTPS |
| `HOME_CONTROL_TRUSTED_PROXIES` | empty | Comma-separated IP addresses of reverse proxies whose `X-Forwarded-For`, `-Proto`, `-Host` and `-Port` are believed; see [Security](security.md) |
| `HOME_CONTROL_JELLYFIN_ENABLED` | `true` | Turn the Jellyfin module off entirely |
| `HOME_CONTROL_YOUTUBE_ENABLED` | `true` | Turn the YouTube module off entirely |
| `HOME_CONTROL_WORKFLOWS_ENABLED` | `true` | Turn the workflow UI, source, Test and Cast execution off while retaining encrypted definitions |
| `HOME_CONTROL_WORKFLOWS_ALLOW_LOOPBACK` | `false` | Allow workflow source and media URLs to use this host's loopback address |
| `home-control.workflows.request-timeout` | `10s` | The limit for one workflow call |
| `home-control.workflows.max-concurrent-fetches` | `8` | How many workflow calls may run at once, across all workflows |
| `home-control.workflows.play-timeout` | `20s` | The limit for one Play, or for the Play part of a Test |
| `home-control.workflows.refresh-timeout` | `60s` | The limit for one refresh of a workflow's tiles |
| `home-control.youtube.daily-quota-units` | `10000` | Your Cloud project's daily YouTube Data API budget |
| `home-control.youtube.searches-per-day` | `20` | On-demand searches allowed per day (100 quota units each) |
| `home-control.youtube.channels-per-refresh` | `30` | Subscribed channels read per subscriptions refresh |
| `home-control.youtube.refresh-interval` | `60m` | How often every YouTube rail (subscriptions, Watch Later, chosen playlists) refreshes in the background |
| `home-control.youtube.allow-loopback` | `false` | Allow YouTube and Google sign-in to reach this machine's own address, for a mirror served here (`HOME_CONTROL_YOUTUBE_ALLOW_LOOPBACK`) |
| `HOME_CONTROL_TMDB_ENABLED` | `true` | Turn the TMDB module off entirely |
| `HOME_CONTROL_TMDB_API_BASE_URL` | `https://api.themoviedb.org/3` | TMDB API base URL |
| `HOME_CONTROL_TMDB_ALLOW_LOOPBACK` | `false` | Allow TMDB to reach this machine's own address, for an API mirror served here |
| `HOME_CONTROL_TMDB_IMAGE_BASE_URL` | discovered from TMDB's `/configuration` | Override the poster CDN, e.g. with a mirror, for privacy |
| `HOME_CONTROL_TMDB_PROVIDER_IDS_NETFLIX` | `8,1796` | TMDB watch-provider ids counted as Netflix |
| `HOME_CONTROL_TMDB_PROVIDER_IDS_PRIMEVIDEO` | `9,119,2100` | TMDB watch-provider ids counted as Prime Video |
| `HOME_CONTROL_PINNED_ENABLED` | `true` | Turn pinned shortcuts off entirely |
| `HOME_CONTROL_PINNED_MAX_PINS` | `200` | How many links a household can pin |
| `home-control.upnp.enabled` | `true` | DLNA/UPnP media renderer module (`HOME_CONTROL_UPNP_ENABLED`) |
| `home-control.upnp.poll-interval` | `2s` | State polling while something plays |
| `home-control.upnp.idle-poll-interval` | `10s` | State polling while idle |
| `home-control.sonos.enabled` | `true` | Sonos module (`HOME_CONTROL_SONOS_ENABLED`; off: players appear as plain renderers) |
| `home-control.sonos.topology-interval` | `30s` | How often group topology is re-read |
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
| `HOME_CONTROL_SPORTS_THESPORTSDB_ALLOW_LOOPBACK` | `false` | Allow TheSportsDB to reach this machine's own address, for an API mirror served here |
| `HOME_CONTROL_SPORTS_THESPORTSDB_FIXTURES_TTL` | `24h` | How long a competition's daily fixtures are cached |
| `home-control.bluetooth.enabled` | `false` | Bluetooth speaker module |
| `home-control.bluetooth.dbus-address` | `unix:path=/run/dbus/system_bus_socket` | Host D-Bus system bus |
| `home-control.bluetooth.adapter` | *(first powered)* | Adapter MAC or id such as `hci0` |
| `home-control.bluetooth.scan-duration` | `10s` | Length of a scan |
| `home-control.bluetooth.mpv-path` | `mpv` | Player executable |
| `home-control.bluetooth.audio-device-template` | *(blank: find the speaker's sink)* | e.g. `alsa/bluealsa:DEV={mac},PROFILE=a2dp` |
| `home-control.bluetooth.default-volume` | `50` | Player volume until changed |

Durations take a unit (`10s`, `2m`, `6h`); a bare number of a key that was once `…-seconds` still means seconds.

[Security](security.md#allowed-hosts-and-origins) explains which host names the app answers to, and when
`HOME_CONTROL_ALLOWED_HOSTS` and `HOME_CONTROL_TRUSTED_ORIGINS` are needed.

Files under `/data` from an older version are upgraded in place on first start, keeping every
pairing and setting:

- `devices.json` gains a format version, and LG and Samsung TVs' pairing keys move from it into
  the encrypted `secrets.json`;
- `sources.json` keeps each source's settings in its own shape;
- a keystore protected by the old default password `shield`, or by `change-me` from an older
  `compose.yaml`, is re-protected under a generated one.

The upgrade is one-way: an older image cannot read the new files, and it cannot open the
re-protected `keystore.p12`. **Copy the whole data directory before you upgrade.** To roll back,
stop the app, put that copy back in place, and start the older image. Without a copy there is
no way back that keeps the Android TV pairings: delete `keystore.p12` and `secrets.json`, pair
the TVs again and reconnect the content sources. Each original is also kept once beside it as
`<name>.v<n>.json`, for example `devices.v2.json`, for reference; that copy leaves out the LG
and Samsung pairing keys, which are kept only encrypted now.

Edit the files under `/data` only while the app is stopped: it reads each one once and would
overwrite a change made while it runs.

## Renamed settings

Older versions used different names. They keep working, and the log says which new name to use:

| Old name | New name |
| --- | --- |
| `shield.data-dir` | `home-control.data-dir` |
| `shield.discovery-enabled` | `home-control.discovery.enabled` |
| `shield.keystore-password`, `SHIELD_KEYSTORE_PASSWORD` | `home-control.androidtv.keystore-password`, `HOME_CONTROL_ANDROIDTV_KEYSTORE_PASSWORD` |
| `shield.stale-timeout-seconds`, `shield.reconnect-*-seconds` | `home-control.androidtv.stale-timeout`, `home-control.androidtv.reconnect-*` |
| any `home-control.…-seconds` key | the same key without `-seconds`, as a duration (`3s`, `2m`); a bare number still means seconds |
| `home-control.bluetooth.scan-seconds` | `home-control.bluetooth.scan-duration` |
| `home-control.bluetooth.host-check-cache-seconds` | `home-control.bluetooth.host-check-cache-ttl` |
