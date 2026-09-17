package dev.andre.homecontrol.adapters.bluetooth;

import dev.andre.homecontrol.adapters.bluetooth.bluez.BluetoothDeviceInfo;
import dev.andre.homecontrol.adapters.bluetooth.bluez.BluezClient;
import dev.andre.homecontrol.adapters.bluetooth.bluez.BluezException;
import dev.andre.homecontrol.adapters.bluetooth.player.AudioDeviceNotFoundException;
import dev.andre.homecontrol.adapters.bluetooth.player.AudioDeviceResolver;
import dev.andre.homecontrol.adapters.bluetooth.player.MpvException;
import dev.andre.homecontrol.adapters.bluetooth.player.MpvNotInstalledException;
import dev.andre.homecontrol.adapters.bluetooth.player.MpvPlayer;
import dev.andre.homecontrol.adapters.bluetooth.player.PlayerStatus;
import dev.andre.homecontrol.adapters.bluetooth.player.StreamRedaction;
import dev.andre.homecontrol.core.Action;
import dev.andre.homecontrol.core.ActionFailedException;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceHandle;
import dev.andre.homecontrol.core.DeviceOfflineException;
import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.DeviceStatus;
import dev.andre.homecontrol.core.NowPlaying;
import dev.andre.homecontrol.core.PlaybackState;
import dev.andre.homecontrol.core.UnsupportedActionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** One Bluetooth speaker: polls BlueZ on one virtual thread and publishes state changes. */
public class BluetoothSpeakerSession implements DeviceHandle {

    private static final Logger log = LoggerFactory.getLogger(BluetoothSpeakerSession.class);

    public static final String MPV_MISSING = "mpv is not installed in this container. Use the image tag latest-bluetooth, "
            + "or build the image with WITH_MPV=true (see docs/bluetooth-speakers.md).";

    private final Device device;
    private final BluetoothSettings settings;
    private final BluetoothProperties properties;
    private final BluezClient bluez;
    private final MpvPlayer player;
    private final AudioDeviceResolver audioDevices;
    private final Consumer<DeviceState> onChange;
    private final ScheduledExecutorService loop;
    private final Object commands = new Object();

    private volatile DeviceState state = DeviceState.initial();
    private volatile int volume;
    private volatile boolean muted;
    private volatile boolean closed;
    private volatile String title;
    private ScheduledFuture<?> nextPoll;      // loop thread only
    private boolean lookedOnce;                // loop thread only

    public BluetoothSpeakerSession(Device device, BluetoothProperties properties, BluezClient bluez, MpvPlayer player,
                                   AudioDeviceResolver audioDevices, Consumer<DeviceState> onChange) {
        this.device = device;
        this.settings = BluetoothSettings.of(device);
        this.properties = properties;
        this.bluez = bluez;
        this.player = player;
        this.audioDevices = audioDevices;
        this.onChange = onChange;
        this.volume = properties.defaultVolume();
        this.loop = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("bluetooth-" + device.id()).factory());
    }

    public void start() {
        onChange.accept(state);
        loop.execute(this::poll);
    }

    @Override
    public DeviceState state() {
        return state;
    }

    @Override
    public void execute(Action action) {
        if (closed) {
            throw new DeviceOfflineException(device.name() + " is not connected");
        }
        synchronized (commands) {
            switch (action) {
                case Action.PlayMedia play -> play(play);
                case Action.Pause ignored -> pause(true);
                case Action.Resume ignored -> pause(false);
                case Action.Stop ignored -> {
                    player.stop();
                    title = null;
                }
                case Action.SetVolume set -> {
                    volume = set.level();
                    whilePlaying("change the volume", () -> player.volume(set.level()));
                }
                case Action.Mute mute -> {
                    muted = mute.muted();
                    whilePlaying("mute", () -> player.mute(mute.muted()));
                }
                default -> throw new UnsupportedActionException(device.name()
                        + " is a Bluetooth speaker and cannot handle " + action.getClass().getSimpleName());
            }
        }
        pollNow();
    }

    @Override
    public void close() {
        closed = true;
        loop.shutdownNow();
        player.stop();
    }

    /** Forces an immediate re-check instead of waiting for the next scheduled poll (setup page actions). */
    void pollNow() {
        if (!closed) {
            loop.execute(this::poll);
        }
    }

    private void play(Action.PlayMedia play) {
        String scheme = play.url().getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new UnsupportedActionException(device.name() + " plays http and https streams only");
        }
        if (!play.mimeType().toLowerCase(Locale.ROOT).startsWith("audio/")) {
            throw new UnsupportedActionException(device.name() + " plays audio only");
        }
        ensureConnected();
        try {
            String audioDevice = audioDevices.resolve(settings.address(), settings.audioDevice());
            player.play(play.url(), audioDevice, volume, muted);
            title = play.title();
        } catch (MpvNotInstalledException e) {
            throw new ActionFailedException(MPV_MISSING);
        } catch (AudioDeviceNotFoundException e) {
            throw new ActionFailedException(device.name() + ": " + e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ActionFailedException(device.name() + ": the audio output id is not usable; fix it on the setup page");
        } catch (IOException | MpvException e) {
            throw new ActionFailedException(device.name() + " could not play the stream: " + StreamRedaction.redact(e.getMessage()));
        }
    }

    private void pause(boolean paused) {
        if (!player.active()) {
            throw new ActionFailedException("Nothing is playing on " + device.name());
        }
        whilePlaying(paused ? "pause" : "resume", () -> player.pause(paused));
    }

    private interface PlayerCall {
        void run() throws IOException, MpvException;
    }

    private void whilePlaying(String what, PlayerCall call) {
        if (!player.active()) {
            return;
        }
        try {
            call.run();
        } catch (IOException | MpvException e) {
            throw new ActionFailedException(device.name() + " did not " + what + ": " + StreamRedaction.redact(e.getMessage()));
        }
    }

    private void ensureConnected() {
        try {
            Optional<BluetoothDeviceInfo> info = bluez.device(settings.adapter(), settings.address());
            if (info.isEmpty() || !info.get().paired()) {
                throw new DeviceOfflineException(device.name()
                        + " is not paired with this server any more. Pair it again on the setup page.");
            }
            if (!info.get().connected()) {
                bluez.connect(settings.adapter(), settings.address());
            }
        } catch (BluezException e) {
            throw new DeviceOfflineException(device.name() + " is not connected: " + e.getMessage());
        }
    }

    private void poll() {
        if (closed) {
            return;
        }
        try {
            readState();
        } catch (RuntimeException e) {
            log.warn("Reading the state of {} failed", device.name(), e);
        } finally {
            if (!closed) {
                if (nextPoll != null) {
                    nextPoll.cancel(false);
                }
                nextPoll = loop.schedule(this::poll, nextPollSeconds(), TimeUnit.SECONDS);
            }
        }
    }

    private long nextPollSeconds() {
        return player.active() ? properties.playingPollIntervalSeconds() : properties.pollIntervalSeconds();
    }

    private void readState() {
        DeviceStatus status = bluetoothStatus();
        if (status != DeviceStatus.CONNECTED && player.active()) {
            log.info("{} is no longer connected; stopping playback so it does not move to another output", device.name());
            player.stop();
        }
        NowPlaying nowPlaying = null;
        Optional<PlayerStatus> playing = player.status();
        if (playing.isPresent()) {
            PlayerStatus now = playing.get();
            volume = now.volume();
            muted = now.muted();
            String shown = title != null && !title.isBlank() ? title
                    : now.metadataTitle() != null ? now.metadataTitle() : "Unknown title";
            PlaybackState playbackState = now.paused() ? PlaybackState.PAUSED
                    : now.buffering() ? PlaybackState.BUFFERING : PlaybackState.PLAYING;
            nowPlaying = new NowPlaying(shown, playbackState, now.positionSeconds(), now.durationSeconds());
        }
        publish(state.withStatus(status).withPower(status == DeviceStatus.CONNECTED)
                .withVolume(volume, 100, muted).withNowPlaying(nowPlaying));
    }

    private DeviceStatus bluetoothStatus() {
        // Auto-connect only on the very first look after start: never page a switched-off speaker
        // repeatedly, and never reconnect behind the back of a poll that just saw it go away.
        boolean firstLook = !lookedOnce;
        lookedOnce = true;
        try {
            Optional<BluetoothDeviceInfo> info = bluez.device(settings.adapter(), settings.address());
            if (info.isEmpty() || !info.get().paired()) {
                return DeviceStatus.UNPAIRED;
            }
            if (info.get().connected()) {
                return DeviceStatus.CONNECTED;
            }
            if (properties.autoConnect() && firstLook) {
                bluez.connect(settings.adapter(), settings.address());
                return DeviceStatus.CONNECTED;
            }
            return DeviceStatus.DISCONNECTED;
        } catch (BluezException e) {
            log.debug("{}: {}", device.name(), e.getMessage());
            return DeviceStatus.DISCONNECTED;
        }
    }

    private void publish(DeviceState next) {
        if (!next.sameIgnoringTime(state)) {
            state = next;
            onChange.accept(next);
        }
    }
}
