# Multi-device core — acceptance record (2026-09-16)

Plan A "Multi-device core", Task 11. No agent running this task has access to a
real NVIDIA Shield or any other physical Android TV device, so the five manual
checks below cannot be executed here. They are recorded as pending so a human
with hardware can run them; none is claimed as passed.

## Manual acceptance on real hardware — pending

1. Start the new image against a copy of a v0.3 `data/` directory: no re-pairing
   needed; the Shield connects.
   **Pending — requires real hardware.**
2. Pair a second Android TV device (or the same Shield at a second address if
   only one exists): both chips show live state; keys go to the selected chip
   only.
   **Pending — requires real hardware.**
3. Kill the network to one device: only its badge goes DISCONNECTED; the other
   keeps working.
   **Pending — requires real hardware.**
4. Open a YouTube watch URL, a Netflix title URL, and a `https://example.org`
   URL: the first two open in their apps, the third produces "Open example.org
   on the device" and the TV either shows a browser or nothing (documented
   behaviour).
   **Pending — requires real hardware.**
5. Forget one device: its chip disappears, the other is unaffected,
   `keystore.p12` still holds the other alias.
   **Pending — requires real hardware.**

## Automated smoke test (run 2026-09-16)

Ran on the build host in place of the hardware checks, to get real evidence
that the container starts, serves the dashboard, and migrates an old
`devices.json` in place. This exercises the UNPAIRED path only (no keystore in
the temp data dir), so it does not substitute for checks 1–5 above — it only
confirms the container and migration machinery work.

### Build

```bash
cd /home/docker1/home-control
docker build -t home-control:smoke .
```

Result: image built successfully (`gradle --no-daemon bootJar` inside the
`build` stage reported `BUILD SUCCESSFUL in 50s`; final image
`home-control:smoke` tagged).

### Prepare a v1 `devices.json`, no keystore

```bash
TMPDATA=/tmp/.../scratchpad/smoke-data
mkdir -p "$TMPDATA"
cp src/test/resources/devices-v1.json "$TMPDATA/devices.json"
```

Contents of the seeded v1 file:

```json
[{"id":"192-168-1-50","name":"Living Room Shield","host":"192.168.1.50","port":6466,"certificateFingerprint":"AB:CD","lastSeen":"2026-08-29T18:00:00Z"}]
```

No `keystore.p12` was placed in the directory, so this device is expected to
come up UNPAIRED (no client certificate to authenticate the Remote v2 session).

### Run

```bash
docker run -d --name hc-smoke -p 18080:8080 -v "$TMPDATA:/data" home-control:smoke
```

Startup logs (full output):

```
2026-09-16T22:05:10.252Z  INFO 1 --- [main] d.a.homecontrol.HomeControlApplication   : Starting HomeControlApplication v0.0.0-SNAPSHOT using Java 25.0.4 with PID 1 (/app/app.jar started by root in /app)
2026-09-16T22:05:10.258Z  INFO 1 --- [main] d.a.homecontrol.HomeControlApplication   : No active profile set, falling back to 1 default profile: "default"
2026-09-16T22:05:11.740Z  INFO 1 --- [main] o.s.boot.tomcat.TomcatWebServer          : Tomcat initialized with port 8080 (http)
2026-09-16T22:05:11.777Z  INFO 1 --- [main] o.apache.catalina.core.StandardService   : Starting service [Tomcat]
2026-09-16T22:05:11.777Z  INFO 1 --- [main] o.apache.catalina.core.StandardEngine    : Starting Servlet engine: [Apache Tomcat/11.0.24]
2026-09-16T22:05:11.808Z  INFO 1 --- [main] b.w.c.s.WebApplicationContextInitializer : Root WebApplicationContext: initialization completed in 1435 ms
2026-09-16T22:05:11.938Z  INFO 1 --- [main] d.a.h.adapters.androidtv.MdnsDiscovery   : Listening for _androidtvremote2._tcp.local.
2026-09-16T22:05:12.458Z  INFO 1 --- [main] o.s.boot.tomcat.TomcatWebServer          : Tomcat started on port 8080 (http) with context path '/'
2026-09-16T22:05:12.472Z  INFO 1 --- [main] d.a.homecontrol.HomeControlApplication   : Started HomeControlApplication in 2.902 seconds (process running for 3.622)
```

No migration-specific log line is emitted (the migration runs silently on
read); the rewritten file on disk is the evidence, checked below.

### `curl -s -i localhost:18080/`

Result: `HTTP/1.1 200`, dashboard HTML (not a `/setup` redirect). The migrated
device appears in the strip, selected by default, with an UNPAIRED badge —
consistent with no keystore being present:

```html
<title>Home Control</title>
...
<body data-device="192-168-1-50">
<header class="strip">
    <nav class="devices" aria-label="Devices">
        <a class="chip selected"
           href="/?device=192-168-1-50" data-device="192-168-1-50">
            <span class="name">Living Room Shield</span>
            <span class="badge off" id="status-192-168-1-50">UNPAIRED</span>
            <span class="app" id="app-192-168-1-50">Nothing playing</span>
        </a>
    </nav>
    <a href="/setup">Setup</a>
</header>
...
```

The remote controls for the device (D-pad, transport row, etc.) rendered
underneath, addressed at `/devices/192-168-1-50/...`.

### `devices.json` rewritten in v2 shape

The file was written back by the container as root (`-rw------- root root`),
so it was read through a throwaway container rather than directly:

```bash
docker run --rm -v "$TMPDATA:/d" alpine cat /d/devices.json
```

```json
[{"id":"192-168-1-50","name":"Living Room Shield","kind":"ANDROID_TV","host":"192.168.1.50","adapters":{"androidtv":{"port":"6466","certificateFingerprint":"AB:CD"}},"lastSeen":"2026-08-29T18:00:00Z"}]
```

Confirms the v1→v2 migration ran: the flat `host`/`port`/`certificateFingerprint`
fields became `kind: "ANDROID_TV"` plus a nested `adapters.androidtv` object,
and `id`, `name`, `lastSeen` were preserved unchanged.

### Cleanup

```bash
docker rm -f hc-smoke
docker rmi home-control:smoke
docker run --rm -v "$TMPDATA:/d" alpine sh -c "rm -rf /d/*"
```

All three commands completed: container removed, image untagged and deleted,
and the root-owned `devices.json` removed from the temp directory (plain
`rm -rf` on the host could not remove it directly since it was owned by root
inside the container's user namespace).

### Conclusion

The automated smoke test is a real, reproducible substitute for none of the
five hardware checks — it never talks to a Shield — but it does give positive
evidence, actually observed, that: the Docker image builds and runs from a
clean `Dockerfile`; the app starts and serves the dashboard at `/` without
requiring `/setup` first when a device is already registered; an old v1
`devices.json` is upgraded to v2 shape in place on first start; and an
UNPAIRED device still renders correctly in the strip and remote view. The
hardware-dependent behaviour (live pairing, per-device SSE state, multi-device
isolation, open-link routing on a real TV, forgetting a device) remains
untested and is listed above as pending.
