# Google Cast adapter (sub-project B) — manual acceptance

**Release:** 0.7 · **Epic:** Google Cast adapter · **Plan:** `docs/superpowers/plans/2026-09-16-google-cast-adapter.md`

Automated coverage: `CastEndToEndTest` (real application against the in-process fake receiver,
`src/test/java/dev/andre/homecontrol/web/CastEndToEndTest.java`), `CastSessionTest`,
`CastConnectionTest`, `CastAdapterTest`, `CastDiscoveryTest`, `DeviceManagerMergeTest`,
`DeviceManagerExecuteTest`, `PlaybackPlannerTest`, `AppLinksTest`, `DeviceControllerTest`,
`DashboardPageTest`. All of the above ran green on 2026-09-16 (`.superpowers/gradle.sh build`).

No agent running this task has access to a real NVIDIA Shield, a Chromecast, or any other
physical Cast receiver, so the items below cannot be executed here. Every row is recorded as
**Pending — requires real hardware**; none is claimed as passed.

Setup for every item: the container runs with `network_mode: host`; test stream
`https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4`.

## NVIDIA Shield (built-in Cast), already paired over Android TV

| # | Check | Result |
|---|---|---|
| 1 | Start the new image against the existing `data/`: the Shield keeps its pairing; within a minute the setup page does not list the Shield's receiver under "Ready to add" and `devices.json` shows a `cast` entry after `androidtv` for the Shield | Pending — requires real hardware |
| 2 | The Shield appears once in the device strip; D-pad keys still work | Pending — requires real hardware |
| 3 | Cast YouTube from a phone to the Shield: the chip shows the video title from Cast media status and the position advances | Pending — requires real hardware |
| 4 | The drawer's volume slider changes the Shield's volume; Mute and Unmute work | Pending — requires real hardware |
| 5 | "Stop casting" ends the phone's cast; the chip returns to the Android TV app name | Pending — requires real hardware |
| 6 | Pasting the test stream on the Shield answers "Open commondatastorage.googleapis.com on the device" (app link wins over Cast by spec order) and the Shield reacts or shows nothing, as documented | Pending — requires real hardware |
| 7 | Setup → "Split cast into its own device" creates a "Shield (cast)" chip; the Shield chip keeps its keys; restarting the container does not merge them back; "Merge devices" (cast into Shield) joins them again | Pending — requires real hardware |
| 8 | Reboot the Shield: the chip goes DISCONNECTED, then CONNECTED again without user action | Pending — requires real hardware |

## One Chromecast (or Chromecast with Google TV / Cast speaker)

| # | Check | Result |
|---|---|---|
| 9 | Setup lists the Chromecast under "Ready to add" with its friendly name; Add creates a chip | Pending — requires real hardware |
| 10 | Pasting the test stream shows "Cast with the Default Media Receiver" and the video plays on the TV | Pending — requires real hardware |
| 11 | The chip shows "BigBuckBunny.mp4 · m:ss / 9:56" and the position advances roughly every 5 s | Pending — requires real hardware |
| 12 | Pause from the TV's own remote or a phone: the chip shows "(paused)" | Pending — requires real hardware |
| 13 | A Cast group containing the Chromecast is not offered on the setup page | Pending — requires real hardware |
| 14 | Unplug the Chromecast for a minute and plug it back in: the chip recovers on its own | Pending — requires real hardware |
| 15 | With `HOME_CONTROL_CAST_ENABLED=false` the Chromecast chip shows no Cast controls, nothing is offered under "Ready to add", and the Shield still works over Android TV | Pending — requires real hardware |

## Findings

None recorded yet. No item above has been run against real hardware; this document records
only what the automated suite (listed above) already proves against the fake in-process receiver.
