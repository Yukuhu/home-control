# Bluetooth speakers — host requirements

Home Control can play music on Bluetooth (A2DP) speakers that are paired with the machine it
runs on. The server itself becomes the audio player: it decodes the stream with `mpv` and hands
the sound to the host's audio server, which sends it to the speaker. This only works when the
host provides all of the plumbing below — a Raspberry Pi class machine with a desktop-style
audio stack does; most NAS boxes do not. The module is **off by default** and has no effect on
anything else while off.

## Checklist

Tick every item on the host (not in the container) before switching the module on. The setup
page shows the same checks under **Bluetooth speakers** once the module is on.

1. **A Bluetooth adapter.** `bluetoothctl list` shows a controller. If it is missing or
   "soft blocked": `sudo rfkill unblock bluetooth`. USB dongles need no driver on Raspberry Pi OS,
   Debian or Ubuntu.
2. **BlueZ is installed and running.** `sudo apt install bluez` then
   `sudo systemctl enable --now bluetooth`; `systemctl status bluetooth` says `active (running)`.
3. **The D-Bus system socket exists** at `/run/dbus/system_bus_socket` (it does on every systemd
   host). The container reaches it through the mount `/run/dbus:/run/dbus:ro`. The D-Bus client
   also needs the host's machine id, which the image does not have: mount
   `/etc/machine-id:/etc/machine-id:ro` (both mounts are in `compose.bluetooth.yaml` and the CasaOS
   Bluetooth manifest). A host without `/etc/machine-id` has `/var/lib/dbus/machine-id`; mount
   that to `/etc/machine-id` instead.
4. **The container may talk to BlueZ.** BlueZ's D-Bus policy (`/etc/dbus-1/system.d/bluetooth.conf`)
   allows `root`; the published image runs as root, so nothing is needed. Rootless Docker and
   user-namespace remapping do not work. BlueZ does not use polkit for these calls. On hosts
   whose AppArmor denies D-Bus to containers (the setup page shows "refused this container"), add
   `security_opt: [apparmor:unconfined]` to the service.
5. **An audio server with Bluetooth support runs for one host user** — PipeWire (with WirePlumber
   and `pipewire-pulse`, the Raspberry Pi OS default) or PulseAudio (with
   `pulseaudio-module-bluetooth`). Without it BlueZ refuses to connect the speaker with
   `br-connection-profile-unavailable`.
   - Check: `systemctl --user status pipewire-pulse wireplumber` (or `pulseaudio`) as that user.
   - The audio server must run without anyone logged in: `sudo loginctl enable-linger <user>`.
   - Headless hosts: WirePlumber only enables Bluetooth for the "active seat" by default. Turn
     that off for the audio user and restart WirePlumber:
     - WirePlumber 0.5 (`wireplumber --version`): create
       `~/.config/wireplumber/wireplumber.conf.d/51-headless-bluetooth.conf` with
       ```
       wireplumber.profiles = {
         main = {
           monitor.bluez.seat-monitoring = disabled
         }
       }
       ```
     - WirePlumber 0.4: create `~/.config/wireplumber/bluetooth.lua.d/51-headless.lua` with
       `bluez_monitor.properties["with-logind"] = false`
   - Then `systemctl --user restart wireplumber pipewire pipewire-pulse`.
6. **The container can reach that audio server.** Mount the user's pulse socket directory
   `/run/user/<uid>/pulse` to `/run/pulse` and set `PULSE_SERVER=unix:/run/pulse/native`
   (both done by `compose.bluetooth.yaml`; `HOST_AUDIO_UID` defaults to 1000). With PulseAudio
   (not PipeWire) also mount the user's `~/.config/pulse/cookie` to `/root/.config/pulse/cookie`.
7. **The image contains `mpv`.** Use the image tag `latest-bluetooth` (or `<version>-bluetooth`),
   or build with `--build-arg WITH_MPV=true` (the Compose override does). The default image has
   no player to stay small (mpv adds about 430 MB).
8. **Host networking** as for the rest of Home Control (`network_mode: host`).
9. **Switch the module on:** `HOME_CONTROL_BLUETOOTH_ENABLED=true` (set by both variants).

## Running it

Plain Compose:

```bash
docker compose -f compose.yaml -f compose.bluetooth.yaml up -d --build
```

CasaOS: import `casaos/docker-compose.bluetooth.yml` **instead of** `casaos/docker-compose.yml`
(same app). Adjust `/run/user/1000/pulse` if the audio user is not uid 1000.

## Pairing a speaker

1. Put the speaker into pairing mode.
2. Open **Setup → Bluetooth speakers**, press **Scan for speakers** (about 10 seconds).
3. Press **Pair and add** next to the speaker. Home Control pairs, trusts and connects it and
   opens its chip. The speaker is paired with the **host**; `bluetoothctl devices Paired` lists it.
4. **Forget** on the setup page removes it from Home Control and unpairs it on the host.

## Other audio setups

- **bluealsa** instead of PipeWire/PulseAudio: possible, but the published image lacks the
  bluez-alsa ALSA plugin. Extend the image with it, mount `/run/dbus` and `/etc/machine-id` as above and set
  `HOME_CONTROL_BLUETOOTH_AUDIO_DEVICE_TEMPLATE=alsa/bluealsa:DEV={mac},PROFILE=a2dp`.
- **A fixed output** (for example a speaker the host always uses): set the speaker's audio device
  on the setup page to one of the ids `mpv --audio-device=help` prints inside the container.

## Failure modes

Every problem below shows up in words on the setup page (**Setup → Bluetooth speakers**, the
checks at the top) or as the message of a failed command. Nothing here affects the rest of Home
Control; switching the module off (`HOME_CONTROL_BLUETOOTH_ENABLED=false`) removes it entirely.

| What you see | Cause | Fix |
|---|---|---|
| ✗ D-Bus system socket — "No D-Bus system socket (nothing at /run/dbus/system_bus_socket)" | `/run/dbus` is not mounted into the container | Add `/run/dbus:/run/dbus:ro` (use `compose.bluetooth.yaml` or the CasaOS Bluetooth manifest). |
| ✗ BlueZ — "The container has no D-Bus machine id" | `/etc/machine-id` is not mounted into the container | Add `/etc/machine-id:/etc/machine-id:ro` (use `compose.bluetooth.yaml` or the CasaOS Bluetooth manifest). |
| ✗ BlueZ — "BlueZ is not running on the host" | `bluez` missing or `bluetooth.service` stopped | `sudo apt install bluez && sudo systemctl enable --now bluetooth` |
| ✗ BlueZ — "The host's D-Bus refused this container" | Rootless Docker, user-namespace remapping, a non-root container user, or AppArmor denying D-Bus | Run the container as root (default); with AppArmor add `security_opt: [apparmor:unconfined]`. |
| ✗ Bluetooth adapter — "No Bluetooth adapter found on the host" | No controller (many NAS and virtual machines), USB dongle unplugged, hard-blocked radio | Plug in a dongle; `bluetoothctl list`; `sudo rfkill unblock bluetooth`. |
| ✗ Bluetooth adapter — "hci0 (…) is powered off" | Soft-blocked or switched off | Scanning switches it on; otherwise `sudo rfkill unblock bluetooth`. |
| ✗ mpv player — "mpv is not installed in this container" | The default image has no player | Use the tag `latest-bluetooth`, or build with `WITH_MPV=true`. |
| ✗ Audio output — "No PipeWire or PulseAudio server is reachable from the container" | Socket directory not mounted, wrong uid, audio server not running (no one logged in and no linger), PulseAudio cookie missing | Mount `/run/user/<uid>/pulse` to `/run/pulse`, set `PULSE_SERVER=unix:/run/pulse/native`, `sudo loginctl enable-linger <user>`, mount the cookie for PulseAudio. |
| Pairing: "The speaker refused pairing" | Speaker not in pairing mode, or a legacy speaker that wants a PIN | Enter pairing mode and retry. PIN speakers: pair once on the host with `bluetoothctl` (`pair`, `trust`), then **Pair and add**. |
| Pairing: "… is not a speaker or headphones (no A2DP audio sink)" | The device offers no A2DP audio sink (phone, keyboard, hands-free-only headset) | Pick an audio device. |
| Connect: "The host has no Bluetooth audio service for this speaker (br-connection-profile-unavailable)" | PipeWire/WirePlumber or PulseAudio's Bluetooth module is not running for the audio user — typical on headless hosts | Enable linger and turn off WirePlumber seat monitoring (see the checklist), then connect again. |
| Connect or play: "The speaker did not answer" | Speaker off, out of range, or connected to a phone | Switch it on, disconnect it from the phone, move it closer. |
| Play: "JBL Flip 5 is not paired with this server any more" | The pairing was removed on the host (`bluetoothctl remove`) or on the speaker | Forget it on the setup page and pair again. |
| Play: "No audio output for AA:BB:… was found" | BlueZ connected the speaker but the audio server shows no sink for it (another user's session owns Bluetooth audio, or WirePlumber ignores it) | Fix the audio server as above, or set the speaker's audio output on the setup page to an id from `mpv --audio-device=help`. |
| Play: "… could not play the stream: mpv exited before opening its control socket (mpv: … Failed to initialize audio output …)" | The chosen audio output does not exist or the audio server refused the stream | Leave the audio output blank (automatic) or correct it. |
| Play: "… could not play the stream: the stream could not be loaded (…)" | The **server** cannot fetch the URL (wrong Jellyfin address inside the container, link needs a login, unsupported format) | Open the link from the host; check Jellyfin's server address on the setup page. |
| Play: "… plays audio only" / "a Bluetooth speaker plays audio streams only" | A video or a non-HTTP link | Play music; videos belong on a TV. |
| Pause/Play: "Nothing is playing on …" | The track ended or the speaker disconnected | Play again. |
| Music stops when the speaker switches off | Intended: Home Control stops the player so audio never continues on the host's HDMI or headphone output | — |
| Stuttering audio | 2.4 GHz interference (Wi-Fi on 2.4 GHz, USB 3 ports), weak onboard Bluetooth | Use 5 GHz Wi-Fi or Ethernet, a USB Bluetooth dongle on an extension cable. |
