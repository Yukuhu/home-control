# Wi-Fi speakers (sub-project I) — manual acceptance

Automated coverage: `SpeakersEndToEndTest`, `SpeakerJellyfinEndToEndTest` and the unit tests of Tasks 1–4,
all against in-process fakes (`FakeUpnpRenderer`, `FakeSonosHousehold`, `FakeSsdpResponder`, `FakeJellyfinServer`).
Agents cannot operate real speakers. Every item below is **Pending — requires real hardware** until a person
with the household's speakers runs it and replaces the status with Passed/Failed plus notes.
Household speakers are unknown (spec §13); record them first.

- DLNA/UPnP renderers (brand, model, firmware): _unknown_
- Sonos players (models, S1/S2, firmware version, stereo pairs / subs): _unknown_
- Host: Docker with `network_mode: host`: _unknown_

## UPnP / DLNA renderers

| # | Check | Status |
|---|---|---|
| U1 | The renderer appears under discovered devices on /setup within a minute, with its friendly name | Pending — requires real hardware |
| U2 | "Add" registers it; the chip turns connected and shows the speaker's volume | Pending — requires real hardware |
| U3 | A renderer built into a TV that is already registered (webOS/Tizen/Cast) merges into that chip instead of a new one | Pending — requires real hardware |
| U4 | Pasting a direct .mp3 link plays it; the chip shows the file name as now playing within 2 s | Pending — requires real hardware |
| U5 | A .flac link plays (or is refused with "cannot play audio/flac" if the device lacks FLAC — record which) | Pending — requires real hardware |
| U6 | Pause, Play and Stop in the drawer act within a second; now playing follows | Pending — requires real hardware |
| U7 | The volume slider sets the speaker volume; the speaker's own buttons are reflected within 10 s | Pending — requires real hardware |
| U8 | Mute and unmute work | Pending — requires real hardware |
| U9 | A video link on an audio-only speaker is refused with a clear reason | Pending — requires real hardware |
| U10 | Playback started from another app (e.g. BubbleUPnP) shows its title as now playing | Pending — requires real hardware |
| U11 | After power-cycling the speaker it reconnects on its own (it may pick a new port) | Pending — requires real hardware |
| U12 | A strict DLNA TV accepts the stream with the DLNA.ORG flags (record the model) | Pending — requires real hardware |

## Sonos

| # | Check | Status |
|---|---|---|
| S1 | Every room appears once by room name; stereo pair partners, subs and surrounds do not appear | Pending — requires real hardware |
| S2 | Sonos players do not additionally appear as plain UPnP renderers | Pending — requires real hardware |
| S3 | Adding a room connects it and shows its volume | Pending — requires real hardware |
| S4 | A direct .mp3/.flac link plays with the title shown in the Sonos app | Pending — requires real hardware |
| S5 | An Icecast MP3 radio URL without a file extension plays (x-rincon-mp3radio) | Pending — requires real hardware |
| S6 | "Join <room>" groups the rooms; the Sonos app shows the same group | Pending — requires real hardware |
| S7 | Playing on a grouped room plays on the whole group | Pending — requires real hardware |
| S8 | Volume on a grouped room changes only that room | Pending — requires real hardware |
| S9 | "Leave group" separates the room; the rest of the group keeps playing | Pending — requires real hardware |
| S10 | Grouping changed in the Sonos app shows in the drawer within 30 s | Pending — requires real hardware |
| S11 | Now playing shows titles of Spotify/radio started from the Sonos app | Pending — requires real hardware |
| S12 | Pause/Play/Stop on a grouped room act on the group | Pending — requires real hardware |

## Jellyfin music

| # | Check | Status |
|---|---|---|
| J1 | "Recently played music" and "Latest music" rails list tracks with artist and album art | Pending — requires real hardware |
| J2 | Playing a track on a UPnP speaker plays it from the start with title and artist on devices with a display | Pending — requires real hardware |
| J3 | Playing a track on a Sonos room plays it; FLAC and MP3 libraries both work | Pending — requires real hardware |
| J4 | A track in a format the speaker cannot play gives a reason instead of silence | Pending — requires real hardware |
| J5 | The play sheet names the route "Stream directly to this device (DLNA/UPnP)" before playing | Pending — requires real hardware |

## General

| # | Check | Status |
|---|---|---|
| G1 | `HOME_CONTROL_UPNP_ENABLED=false` removes renderers; Sonos still works | Pending — requires real hardware |
| G2 | `HOME_CONTROL_SONOS_ENABLED=false` removes the Sonos module; players appear as plain renderers | Pending — requires real hardware |
| G3 | With bridge networking nothing is discovered and nothing breaks | Pending — requires real hardware |
| G4 | CPU and network use stay negligible with all speakers idle for an hour | Pending — requires real hardware |

## Findings

_None yet._
