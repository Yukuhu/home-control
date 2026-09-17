# Smart TV adapters (sub-project F) — manual acceptance

Agents cannot operate real TVs. Every item below is **Pending — requires real hardware** until a
person with the household's TVs runs it and replaces the status with Passed/Failed plus notes.
Household TV models are unknown (spec §13, question 1); record them here first.

- LG model / webOS version: _unknown_
- Samsung model / model year / firmware: _unknown_
- Host: Docker with `network_mode: host`: _unknown_

Automated coverage (fake TVs over real sockets, no hardware): `WebOsEndToEndTest`,
`TizenEndToEndTest`, and the adapter tests under `adapters/webos`, `adapters/tizen`,
`adapters/net`, `discovery/ssdp`. They prove the protocol as the fakes implement it, not what a
given firmware accepts.

## LG webOS

| # | Check | Status |
|---|---|---|
| L1 | The TV appears under "Devices on this network" within a minute of opening /setup | Pending — requires real hardware |
| L2 | Pair shows the "allow Home Control" prompt on the TV; accepting redirects to the dashboard | Pending — requires real hardware |
| L3 | Declining the prompt shows "declined" on the setup page and stores nothing | Pending — requires real hardware |
| L4 | D-pad, OK, Back, Home, Menu, Info, Guide, Settings, Stop, Rewind, Fast forward act on the TV | Pending — requires real hardware |
| L5 | Play/Pause alternates pause and play in the YouTube app | Pending — requires real hardware |
| L6 | Volume up/down/mute change the TV volume and the chip shows the new level | Pending — requires real hardware |
| L7 | The drawer lists the TV's inputs with their custom names; tapping one switches input | Pending — requires real hardware |
| L8 | Power turns the TV off; the chip shows it off within a few seconds | Pending — requires real hardware |
| L9 | With "Turn on via Wi-Fi" / "Mobile TV On" enabled, Power switches the TV on and it reconnects | Pending — requires real hardware |
| L10 | The MAC address was learned automatically (setup page shows it) and matches the TV's network settings | Pending — requires real hardware |
| L11 | Opening a YouTube video link starts that video (not just the app) — record the webOS version | Pending — requires real hardware |
| L12 | Opening a Netflix title link opens that title (not just the app) — record the result | Pending — requires real hardware |
| L13 | A Prime Video link opens the Prime Video app | Pending — requires real hardware |
| L14 | An ordinary https link opens in the TV browser | Pending — requires real hardware |
| L15 | "Test deep link" reports "switched from … to youtube.leanback.v4" and the test video plays | Pending — requires real hardware |
| L16 | Firmware that closed port 3000 still connects over wss://…:3001 | Pending — requires real hardware |
| L17 | The unsigned registration manifest is accepted (no permission error on pairing) | Pending — requires real hardware |
| L18 | After a factory reset of the TV the device shows UNPAIRED and pairing again restores control | Pending — requires real hardware |
| L19 | Switching the TV on with its own remote reconnects within seconds (SSDP announcement) | Pending — requires real hardware |
| L20 | Pulling the TV's power plug (no clean disconnect) shows it DISCONNECTED within about 40 seconds (liveness check: 30 s interval + 10 s request timeout) | Pending — requires real hardware |
| L21 | The liveness request (`ssap://system/getSystemInfo` every 30 s) causes no toast, prompt or log noise on the TV | Pending — requires real hardware |
| L22 | Holding a touchpad direction on an LG TV moves once (webOS has no key hold) without an error toast | Pending — requires real hardware |

## Samsung Tizen

| # | Check | Status |
|---|---|---|
| S1 | The TV appears under "Devices on this network" | Pending — requires real hardware |
| S2 | Pair shows the Allow prompt naming "Home Control"; Allow redirects to the dashboard and a token is stored | Pending — requires real hardware |
| S3 | Deny shows "declined" | Pending — requires real hardware |
| S4 | D-pad, OK, Back, Home, Menu, volume and mute keys act on the TV | Pending — requires real hardware |
| S5 | Absolute volume is refused with "only takes volume up, down and mute keys" | Pending — requires real hardware |
| S6 | Power turns the TV off; with "Power On with Mobile" enabled, Power switches it on again | Pending — requires real hardware |
| S7 | The MAC address was learned from the REST API | Pending — requires real hardware |
| S8 | Standby is shown as off (REST PowerState) within two poll intervals | Pending — requires real hardware |
| S9 | A YouTube video link starts that video through DIAL | Pending — requires real hardware |
| S10 | A Netflix link opens the Netflix app (no title — expected); record the app id found | Pending — requires real hardware |
| S11 | A Prime Video link opens Prime Video, or says it is not installed | Pending — requires real hardware |
| S12 | An ordinary web link is refused with the "cannot open web links" message | Pending — requires real hardware |
| S13 | "Test deep link" reports YouTube in front (polled) and the test video plays | Pending — requires real hardware |
| S14 | The current app label shows YouTube/Netflix while those apps are open (if the model has the applications endpoint) | Pending — requires real hardware |
| S15 | After removing "Home Control" from the TV's device list the device shows DISCONNECTED (not UNPAIRED); the Allow prompt reappears at most at growing intervals up to 5 minutes, never every few seconds; choosing Deny makes it UNPAIRED; pairing again from Setup restores control | Pending — requires real hardware |
| S16 | A TV that is still booting when the server connects keeps its pairing (DISCONNECTED, retried with a growing delay) and connects once it is up | Pending — requires real hardware |
| S17 | Holding a touchpad direction repeats the key on the TV until released (Press/Release) | Pending — requires real hardware |

## Both

| # | Check | Status |
|---|---|---|
| B1 | A TV that is also registered under another adapter at the same IP (e.g. a Cast receiver) is merged into one chip when paired | Pending — requires real hardware |
| B2 | Nothing breaks with `HOME_CONTROL_WEBOS_ENABLED=false` / `HOME_CONTROL_TIZEN_ENABLED=false` | Pending — requires real hardware |
| B3 | With bridge networking, discovery finds nothing but pairing by address works (Wake-on-LAN may not) | Pending — requires real hardware |
| B4 | The client key / token never appears on the setup page, in the dashboard HTML or in the logs | Pending — requires real hardware |

## Findings

_None yet._
