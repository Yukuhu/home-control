# Bluetooth speakers (sub-project J) — manual acceptance

Automated coverage: `BluetoothSpeakerEndToEndTest`, `BluetoothJellyfinEndToEndTest`, `BluetoothClassLoadingTest`,
`BluetoothDeploymentTest` and the unit tests of Tasks 1–4, all against in-process fakes (`FakeBluezClient`,
`FakeMpv` in-process and as a child process, `FakeJellyfinServer`). No test talks to a real D-Bus, BlueZ, adapter,
audio server, mpv or speaker. Agents cannot operate real hardware: every item below is
**Pending — requires real hardware** until a person runs it on the target host and replaces the status with
Passed/Failed plus notes. Household facts are unknown (spec §13 question 4); record them first.

- Host (model, RAM, OS and version, kernel): _unknown — target is a Raspberry Pi class machine (e.g. Pi 4/5, Raspberry Pi OS Bookworm or later)_
- Bluetooth adapter (onboard / USB dongle model, `bluetoothctl show`): _unknown_
- Audio server (PipeWire + WirePlumber versions, or PulseAudio), audio user uid: _unknown_
- Docker / CasaOS version, image tag used: _unknown_
- Speakers (brand, model, firmware, PIN or Just Works): _unknown_

## Host and packaging

| # | Check | Status |
|---|---|---|
| H1 | With the default image and the module off, Home Control behaves exactly as before (no Bluetooth section, no extra log lines, no `mpv` process) | Pending — requires real hardware |
| H2 | `docker compose -f compose.yaml -f compose.bluetooth.yaml up -d --build` builds on arm64 and starts; `docker exec … mpv --version` prints mpv 0.41 | Pending — requires real hardware |
| H3 | The published `latest-bluetooth` image pulls and runs on arm64 | Pending — requires real hardware |
| H4 | The CasaOS Bluetooth manifest imports, shows the three mounts and starts the app | Pending — requires real hardware |
| H5 | Following docs/bluetooth-speakers.md on a fresh Raspberry Pi OS install turns every setup-page check green | Pending — requires real hardware |
| H6 | After a host reboot with nobody logged in (linger enabled), all checks are still green | Pending — requires real hardware |
| H7 | Image size difference between the default and the -bluetooth image is recorded (expected ≈ +430 MB uncompressed) | Pending — requires real hardware |

## Pairing

| # | Check | Status |
|---|---|---|
| P1 | "Scan for speakers" lists a speaker in pairing mode within the scan time, with its name, marked "speaker" | Pending — requires real hardware |
| P2 | Phones, keyboards and other non-audio devices are hidden and counted | Pending — requires real hardware |
| P3 | "Pair and add" pairs, trusts and connects; the dashboard opens on the new chip, which shows connected | Pending — requires real hardware |
| P4 | `bluetoothctl info <MAC>` on the host shows Paired: yes, Trusted: yes, Connected: yes | Pending — requires real hardware |
| P5 | A speaker already paired on the host via bluetoothctl is added without re-pairing | Pending — requires real hardware |
| P6 | A PIN/legacy speaker: record whether "Pair and add" works or the bluetoothctl workaround is needed | Pending — requires real hardware |
| P7 | Disconnect and Connect on the setup page work; the chip follows within 5 s | Pending — requires real hardware |
| P8 | Switching the speaker off shows disconnected within 5 s; switching it on reconnects (trusted) or connects on the next play | Pending — requires real hardware |
| P9 | Forget removes the chip and unpairs the speaker on the host (`bluetoothctl devices Paired` no longer lists it) | Pending — requires real hardware |
| P10 | After a container restart, registered speakers come back connected without pairing again | Pending — requires real hardware |

## Playback

| # | Check | Status |
|---|---|---|
| A1 | A pasted direct .mp3 link plays on the speaker within 3 s; the chip shows the file name as now playing | Pending — requires real hardware |
| A2 | .flac, .m4a and .ogg links play | Pending — requires real hardware |
| A3 | A Jellyfin track from "Recently played music" plays; the play sheet names "Play through the server on this Bluetooth speaker" first | Pending — requires real hardware |
| A4 | Pause, Play and Stop in the drawer act within a second; now playing follows | Pending — requires real hardware |
| A5 | The volume slider and mute change loudness; the next track keeps the volume | Pending — requires real hardware |
| A6 | The position advances in the chip while playing and stops while paused | Pending — requires real hardware |
| A7 | A track that ends clears now playing and leaves no mpv process (`docker exec … ps`) | Pending — requires real hardware |
| A8 | Playing a second track replaces the first without two streams overlapping | Pending — requires real hardware |
| A9 | Two speakers paired at once: record whether both play simultaneously (depends on the adapter) | Pending — requires real hardware |
| A10 | `ps aux` on the host during playback shows no stream URL or Jellyfin key in mpv's command line | Pending — requires real hardware |
| A11 | CPU load of mpv while playing FLAC is recorded (expected well below one core on a Pi 4) | Pending — requires real hardware |
| A12 | Audio stays in sync and does not stutter for a 30-minute session | Pending — requires real hardware |
| A13 | An internet radio (Icecast MP3) link plays and shows its stream title | Pending — requires real hardware |

## Failure modes

| # | Check | Status |
|---|---|---|
| F1 | Without the `/run/dbus` mount the setup page shows "No D-Bus system socket"; the app otherwise works | Pending — requires real hardware |
| F2 | With `bluetooth.service` stopped the page shows "BlueZ is not running on the host" | Pending — requires real hardware |
| F3 | With the adapter blocked (`rfkill block bluetooth`) the page shows the adapter problem; scanning or unblocking recovers | Pending — requires real hardware |
| F4 | With the default image and the module on, the page shows "mpv is not installed" and playing answers the same | Pending — requires real hardware |
| F5 | Without the pulse socket mount the page shows "No PipeWire or PulseAudio server is reachable" | Pending — requires real hardware |
| F6 | With the audio server stopped for the audio user, connecting shows the br-connection-profile-unavailable explanation | Pending — requires real hardware |
| F7 | Switching the speaker off during playback stops mpv within 2 s; no sound comes out of HDMI or the headphone jack | Pending — requires real hardware |
| F8 | A Jellyfin address the container cannot reach gives "could not play the stream" instead of silence | Pending — requires real hardware |
| F9 | Removing the pairing with bluetoothctl shows the speaker as unpaired and playing asks to pair again | Pending — requires real hardware |
| F10 | Stopping the container during playback leaves no mpv process on the host and releases the speaker | Pending — requires real hardware |
| F11 | On a NAS without Bluetooth (CasaOS), the module on shows clear problems and nothing else breaks | Pending — requires real hardware |

## Findings

_None yet._
