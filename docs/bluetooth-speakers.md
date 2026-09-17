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
   host). The container reaches it through the mount `/run/dbus:/run/dbus:ro`.
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
  bluez-alsa ALSA plugin. Extend the image with it, mount `/run/dbus` as above and set
  `HOME_CONTROL_BLUETOOTH_AUDIO_DEVICE_TEMPLATE=alsa/bluealsa:DEV={mac},PROFILE=a2dp`.
- **A fixed output** (for example a speaker the host always uses): set the speaker's audio device
  on the setup page to one of the ids `mpv --audio-device=help` prints inside the container.
