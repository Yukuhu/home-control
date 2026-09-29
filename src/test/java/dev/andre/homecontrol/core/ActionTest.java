package dev.andre.homecontrol.core;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;

import static dev.andre.homecontrol.core.Capability.CAST_RECEIVER;
import static dev.andre.homecontrol.core.Capability.LOCAL_AUDIO_SINK;
import static dev.andre.homecontrol.core.Capability.MEDIA_RENDERER;
import static dev.andre.homecontrol.core.Capability.REMOTE_KEYS;
import static dev.andre.homecontrol.core.Capability.VOLUME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ActionTest {

    @Test
    void authenticatedAppLinksKeepCredentialsOutOfCommandErrors() {
        var action = new Action.OpenAppLink(URI.create("vlc://https://nas.lan/video?api_key=secret-token"));
        assertThat(action.uri().getRawQuery()).contains("secret-token");
        assertThat("Device cannot perform " + action).doesNotContain("secret-token");
    }

    @Test
    void aKeyPressRequiresRemoteKeys() {
        assertThat(new Action.PressKey(RemoteKey.HOME).requires()).containsExactly(Capability.REMOTE_KEYS);
    }

    @Test
    void aKeyPressIsShortByDefault() {
        assertThat(new Action.PressKey(RemoteKey.HOME).press()).isEqualTo(KeyPress.SHORT);
        assertThat(new Action.PressKey(RemoteKey.HOME, null).press()).isEqualTo(KeyPress.SHORT);
    }

    @Test
    void longPressSupportIsLimitedToNavigationKeys() {
        assertThat(RemoteKey.DPAD_CENTER.supportsLongPress()).isTrue();
        assertThat(RemoteKey.BACK.supportsLongPress()).isTrue();
        assertThat(RemoteKey.VOLUME_UP.supportsLongPress()).isFalse();
        assertThat(RemoteKey.POWER.supportsLongPress()).isFalse();
    }

    @Test
    void openingAnAppLinkRequiresAppLink() {
        Action action = new Action.OpenAppLink(URI.create("https://www.youtube.com/watch?v=abc"));
        assertThat(action.requires()).containsExactly(Capability.APP_LINK);
    }

    @Test
    void volumeActionsRequireVolumeAndStopRequiresACastReceiver() {
        assertThat(new Action.SetVolume(40).requires()).containsExactly(Capability.VOLUME);
        assertThat(new Action.Mute(true).requires()).containsExactly(Capability.VOLUME);
        assertThat(new Action.Stop().requires()).containsExactlyInAnyOrder(CAST_RECEIVER, MEDIA_RENDERER, LOCAL_AUDIO_SINK);
    }

    @Test
    void aVolumeLevelIsAPercentage() {
        assertThat(new Action.SetVolume(0).level()).isZero();
        assertThat(new Action.SetVolume(100).level()).isEqualTo(100);
        assertThatThrownBy(() -> new Action.SetVolume(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Action.SetVolume(101))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Volume must be between 0 and 100");
    }

    @Test
    void aCastLoadRequiresACastReceiverCopiesItsBodyAndNeverPrintsIt() {
        Map<String, Object> body = new HashMap<>(Map.of("autoplay", true, "media", Map.of("contentId", "http://x?ApiKey=secret-key")));
        Action.CastLoad load = new Action.CastLoad("CC1AD845", body);
        body.put("autoplay", false);

        assertThat(load.requires()).containsExactly(Capability.CAST_RECEIVER);
        assertThat(load.load()).containsEntry("autoplay", true);
        assertThat(load.toString()).isEqualTo("CastLoad[receiverAppId=CC1AD845]")
                .doesNotContain("secret-key");
    }

    @Test
    void aCastMessageRequiresACastReceiverCopiesItsBodyAndNeverPrintsIt() {
        Map<String, Object> body = new HashMap<>(Map.of("accessToken", "secret-token"));
        Action.CastMessage message = new Action.CastMessage("F007D354", "urn:x-cast:com.connectsdk", body);
        body.put("accessToken", "changed");

        assertThat(message.requires()).containsExactly(Capability.CAST_RECEIVER);
        assertThat(message.message()).containsEntry("accessToken", "secret-token");
        assertThat(message.toString()).doesNotContain("secret-token").contains("F007D354");
        assertThatThrownBy(() -> new Action.CastMessage("F007D354", "com.connectsdk", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void selectingAnInputRequiresInputs() {
        assertThat(new Action.SelectInput("HDMI_1").requires()).containsExactly(Capability.INPUTS);
    }

    @Test
    void streamActionsRequireARendererOrALocalSink() {
        assertThat(new Action.PlayMedia(URI.create("http://nas/a.flac"), "audio/flac", "A", null).requires())
                .containsExactlyInAnyOrder(MEDIA_RENDERER, LOCAL_AUDIO_SINK);
        assertThat(new Action.Pause().requires()).containsExactlyInAnyOrder(MEDIA_RENDERER, LOCAL_AUDIO_SINK);
        assertThat(new Action.Resume().requires()).containsExactlyInAnyOrder(MEDIA_RENDERER, LOCAL_AUDIO_SINK);
    }

    @Test
    void stopReachesCastReceiversMediaRenderersAndLocalSinks() {
        assertThat(new Action.Stop().requires()).containsExactlyInAnyOrder(CAST_RECEIVER, MEDIA_RENDERER, LOCAL_AUDIO_SINK);
        assertThat(new Action.Stop().acceptedBy(EnumSet.of(MEDIA_RENDERER))).isTrue();
        assertThat(new Action.Stop().acceptedBy(EnumSet.of(CAST_RECEIVER))).isTrue();
        assertThat(new Action.Stop().acceptedBy(EnumSet.of(VOLUME, REMOTE_KEYS))).isFalse();
        assertThat(new Action.PressKey(RemoteKey.HOME).acceptedBy(EnumSet.of(REMOTE_KEYS))).isTrue();
        assertThat(new Action.PressKey(RemoteKey.HOME).acceptedBy(EnumSet.of(VOLUME))).isFalse();
    }

    @Test
    void playMediaNeedsAUrlAndDefaultsTheType() {
        assertThatThrownBy(() -> new Action.PlayMedia(null, "audio/flac", "A", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new Action.PlayMedia(URI.create("http://nas/a"), " ", "A", null).mimeType())
                .isEqualTo("application/octet-stream");
    }

    @Test
    void playMediaNeverPrintsTheStreamCredential() {
        String printed = new Action.PlayMedia(URI.create("http://nas:8096/Audio/x/stream.flac?static=true&ApiKey=secret-key"),
                "audio/flac", "Song", null).toString();

        assertThat(printed).contains("http://nas:8096/Audio/x/stream.flac?…").doesNotContain("secret-key");
    }

    @Test
    void localAudioSinksAcceptPlaybackActions() {
        var sink = EnumSet.of(Capability.LOCAL_AUDIO_SINK);
        assertThat(new Action.PlayMedia(URI.create("http://nas/a.mp3"), "audio/mpeg", "A", null).acceptedBy(sink)).isTrue();
        assertThat(new Action.Pause().acceptedBy(sink)).isTrue();
        assertThat(new Action.Resume().acceptedBy(sink)).isTrue();
        assertThat(new Action.Stop().acceptedBy(sink)).isTrue();
        assertThat(new Action.SetVolume(10).acceptedBy(sink)).isFalse();
        assertThat(new Action.SetVolume(10).acceptedBy(EnumSet.of(Capability.LOCAL_AUDIO_SINK, VOLUME))).isTrue();
        assertThat(new Action.PressKey(RemoteKey.HOME).acceptedBy(sink)).isFalse();

        assertThat(new Action.PlayMedia(URI.create("http://nas/a.mp3"), "audio/mpeg", "A", null).requires())
                .containsExactlyInAnyOrder(MEDIA_RENDERER, LOCAL_AUDIO_SINK);
        assertThat(new Action.Stop().requires()).containsExactlyInAnyOrder(CAST_RECEIVER, MEDIA_RENDERER, LOCAL_AUDIO_SINK);

        // I's renderer and Cast assertions still hold.
        assertThat(new Action.Stop().acceptedBy(EnumSet.of(MEDIA_RENDERER))).isTrue();
        assertThat(new Action.Stop().acceptedBy(EnumSet.of(CAST_RECEIVER))).isTrue();
    }

    @Test
    void groupingRequiresGrouping() {
        assertThat(new Action.JoinGroup("RINCON_1").requires()).containsExactly(Capability.GROUPING);
        assertThat(new Action.LeaveGroup().requires()).containsExactly(Capability.GROUPING);
        assertThatThrownBy(() -> new Action.JoinGroup(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Pick a speaker to join");
    }

    @Test
    void everyActionNamesItsPurpose() {
        assertThat(new Action.PressKey(RemoteKey.HOME).purpose()).isEqualTo("take remote keys");
        assertThat(new Action.OpenAppLink(URI.create("https://example.org")).purpose()).isEqualTo("open app links");
        assertThat(new Action.SelectInput("HDMI_1").purpose()).isEqualTo("switch inputs");
        assertThat(new Action.SetVolume(40).purpose()).isEqualTo("change the volume");
        assertThat(new Action.Mute(true).purpose()).isEqualTo("mute");
        assertThat(new Action.Stop().purpose()).isEqualTo("stop playback");
        assertThat(new Action.PlayMedia(URI.create("http://nas/a.flac"), "audio/flac", "A", null).purpose())
                .isEqualTo("play a stream");
        assertThat(new Action.Pause().purpose()).isEqualTo("pause");
        assertThat(new Action.Resume().purpose()).isEqualTo("resume");
        assertThat(new Action.JoinGroup("RINCON_1").purpose()).isEqualTo("be grouped");
        assertThat(new Action.LeaveGroup().purpose()).isEqualTo("be grouped");
        assertThat(new Action.CastLoad("CC1AD845", Map.of()).purpose()).isEqualTo("receive Cast media");
        assertThat(new Action.CastMessage("APP", "urn:x-cast:app", Map.of()).purpose()).isEqualTo("receive Cast media");
    }
}
