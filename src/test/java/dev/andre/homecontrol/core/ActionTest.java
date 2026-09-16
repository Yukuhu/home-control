package dev.andre.homecontrol.core;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

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
}
