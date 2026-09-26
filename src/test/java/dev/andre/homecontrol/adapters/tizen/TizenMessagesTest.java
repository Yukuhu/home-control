package dev.andre.homecontrol.adapters.tizen;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class TizenMessagesTest {

    @Test
    void theRemoteUrlCarriesTheBase64ClientName() {
        assertThat(TizenMessages.remoteUri("192.168.1.61", 8002, "Home Control", null)).hasToString("wss://192.168.1.61:8002/api/v2/channels/samsung.remote.control?name=SG9tZSBDb250cm9s");
    }

    @Test
    void aStoredTokenIsAppended() {
        assertThat(TizenMessages.remoteUri("192.168.1.61", 8002, "Home Control", "73184052")).hasToString("wss://192.168.1.61:8002/api/v2/channels/samsung.remote.control?name=SG9tZSBDb250cm9s&token=73184052");
    }

    @Test
    void base64PaddingIsUrlEncoded() {
        assertThat(TizenMessages.remoteUri("192.168.1.61", 8002, "TV", null).toString()).endsWith("name=VFY%3D");
    }

    @Test
    void ipv6HostsAreBracketed() {
        assertThat(TizenMessages.remoteUri("fe80::1", 8002, "Home Control", null).toString()).startsWith("wss://[fe80::1]:8002/");
    }

    @Test
    void aKeyIsAClick() {
        assertThat(TizenMessages.key("KEY_HOME")).isEqualTo("{\"method\":\"ms.remote.control\",\"params\":{\"Cmd\":\"Click\","
                + "\"DataOfCmd\":\"KEY_HOME\",\"Option\":\"false\",\"TypeOfRemote\":\"SendRemoteKey\"}}");
    }

    @Test
    void aHeldKeyIsAPressAndARelease() {
        assertThat(TizenMessages.key("KEY_UP", "Press")).contains("\"Cmd\":\"Press\"", "\"DataOfCmd\":\"KEY_UP\"");
        assertThat(TizenMessages.key("KEY_UP", "Release")).contains("\"Cmd\":\"Release\"");
    }

    @Test
    void anAppLaunchIsAChannelEmitToTheHost() {
        assertThat(TizenMessages.launchApp("3201907018807", "DEEP_LINK")).isEqualTo("{\"method\":\"ms.channel.emit\","
                + "\"params\":{\"event\":\"ed.apps.launch\",\"to\":\"host\",\"data\":{\"action_type\":\"DEEP_LINK\","
                + "\"appId\":\"3201907018807\",\"metaTag\":\"\"}}}");
    }

    @Test
    void theInstalledAppsRequest() {
        assertThat(TizenMessages.installedAppsRequest())
                .isEqualTo("{\"method\":\"ms.channel.emit\",\"params\":{\"event\":\"ed.installedApp.get\",\"to\":\"host\"}}");
    }

    @Test
    void parsesTheInstalledAppsEvent() throws IOException {
        String event = Files.readString(Path.of("src/test/resources/fixtures/tizen/installed-apps.json"));

        assertThat(TizenMessages.installedApps(TizenMessages.JSON.readTree(event))).containsExactly(
                new TizenApp("111299001912", "YouTube", 2),
                new TizenApp("3201907018807", "Netflix", 2),
                new TizenApp("org.tizen.browser", "Internet", 4));
    }
}
