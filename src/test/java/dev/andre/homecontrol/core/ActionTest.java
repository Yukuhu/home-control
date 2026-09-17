package dev.andre.homecontrol.core;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ActionTest {

    @Test
    void aKeyPressRequiresRemoteKeys() {
        assertThat(new Action.PressKey(RemoteKey.HOME).requires()).isEqualTo(Capability.REMOTE_KEYS);
    }

    @Test
    void openingAnAppLinkRequiresAppLink() {
        Action action = new Action.OpenAppLink(URI.create("https://www.youtube.com/watch?v=abc"));
        assertThat(action.requires()).isEqualTo(Capability.APP_LINK);
    }

    @Test
    void volumeActionsRequireVolumeAndStopRequiresACastReceiver() {
        assertThat(new Action.SetVolume(40).requires()).isEqualTo(Capability.VOLUME);
        assertThat(new Action.Mute(true).requires()).isEqualTo(Capability.VOLUME);
        assertThat(new Action.Stop().requires()).isEqualTo(Capability.CAST_RECEIVER);
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

        assertThat(load.requires()).isEqualTo(Capability.CAST_RECEIVER);
        assertThat(load.load()).containsEntry("autoplay", true);
        assertThat(load.toString()).isEqualTo("CastLoad[receiverAppId=CC1AD845]")
                .doesNotContain("secret-key");
    }

    @Test
    void aCastMessageRequiresACastReceiverCopiesItsBodyAndNeverPrintsIt() {
        Map<String, Object> body = new HashMap<>(Map.of("accessToken", "secret-token"));
        Action.CastMessage message = new Action.CastMessage("F007D354", "urn:x-cast:com.connectsdk", body);
        body.put("accessToken", "changed");

        assertThat(message.requires()).isEqualTo(Capability.CAST_RECEIVER);
        assertThat(message.message()).containsEntry("accessToken", "secret-token");
        assertThat(message.toString()).doesNotContain("secret-token").contains("F007D354");
        assertThatThrownBy(() -> new Action.CastMessage("F007D354", "com.connectsdk", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
