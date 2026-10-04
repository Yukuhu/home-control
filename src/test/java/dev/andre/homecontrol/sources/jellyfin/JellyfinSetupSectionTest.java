package dev.andre.homecontrol.sources.jellyfin;

import dev.andre.homecontrol.core.Capability;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.core.DeviceQueries;
import dev.andre.homecontrol.core.content.ContentSourceException;
import dev.andre.homecontrol.security.LoginService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/** What the setup page shows about Jellyfin, and the open apps it offers to link. */
class JellyfinSetupSectionTest {

    private static final URI HOME = URI.create("http://home-control.lan:8080");

    private final JellyfinSetupService setup = mock(JellyfinSetupService.class);
    private final LoginService login = mock(LoginService.class);
    private final JellyfinSessions sessions = mock(JellyfinSessions.class);
    private final DeviceQueries devices = mock(DeviceQueries.class);
    private final JellyfinSettings connected = new JellyfinSettings(URI.create("http://jellyfin:8096"),
            URI.create("http://jellyfin:8096"), "server-1", "nas", "10.11.2", "user-1", "andre",
            JellyfinSettings.AuthMode.API_KEY, "hc", "F007D354", Map.of("shield", "jf-shield"),
            Map.of("shield", JellyfinSettings.Player.VLC));

    @BeforeEach
    void devices() {
        Device shield = new Device("shield", "Shield", DeviceKind.ANDROID_TV, "10.0.0.5", Map.of(), Instant.EPOCH);
        Device kitchen = new Device("kitchen", "Kitchen", DeviceKind.CAST, "10.0.0.7", Map.of(), Instant.EPOCH);
        given(devices.devices()).willReturn(List.of(shield, kitchen));
        given(devices.capabilities("shield")).willReturn(Set.of(Capability.ANDROID_APPS));
        given(devices.capabilities("kitchen")).willReturn(Set.of(Capability.CAST_RECEIVER));
    }

    private static <T> ObjectProvider<T> provider(T bean) {
        @SuppressWarnings("unchecked")
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        given(provider.getIfAvailable()).willReturn(bean);
        return provider;
    }

    private JellyfinSetupSection section() {
        return new JellyfinSetupSection(provider(setup), provider(login), provider(sessions), provider(devices));
    }

    @Test
    void withoutTheJellyfinBeansOrSettingsTheConnectFormIsShown() {
        var bare = new JellyfinSetupSection(provider(null), provider(null), provider(null), provider(null));
        assertThat(bare.view(HOME).configured()).isFalse();
        assertThat(bare.view(HOME).needsLoginPassword()).isTrue();
        assertThat(bare.sessions()).isEqualTo(new JellyfinSetupSection.Sessions(List.of(), null, List.of()));

        given(setup.settings()).willReturn(Optional.empty());
        JellyfinSetupSection.View view = section().view(HOME);
        assertThat(view.configured()).isFalse();
        assertThat(view.mode()).isEqualTo("password");
        assertThat(section().sessions().sessions()).isEmpty();
    }

    @Test
    void aConnectedServerShowsItsAddressesModeAndEachDevicesPlayer() {
        given(setup.settings()).willReturn(Optional.of(connected));
        given(login.loginRequired()).willReturn(true);

        JellyfinSetupSection.View view = section().view(HOME);

        assertThat(view.configured()).isTrue();
        assertThat(view.serverName()).isEqualTo("nas");
        assertThat(view.serverUrl()).isEqualTo("http://jellyfin:8096");
        assertThat(view.mode()).isEqualTo("api-key");
        assertThat(view.deviceAddressLooksLocal()).isTrue();
        assertThat(view.needsLoginPassword()).isFalse();
        assertThat(view.devices()).containsExactly(
                new JellyfinSetupSection.DeviceOption("shield", "Shield", true, "vlc"),
                new JellyfinSetupSection.DeviceOption("kitchen", "Kitchen", false, "jellyfin"));
    }

    @Test
    void openAppsAreOfferedWithTheDeviceTheyAreLinkedTo() {
        given(setup.settings()).willReturn(Optional.of(connected));
        given(sessions.controllable()).willReturn(List.of(
                new JellyfinSession("s1", "jf-shield", "SHIELD", "Android TV", "10.0.0.5", Instant.EPOCH, true),
                new JellyfinSession("s2", "jf-web", "Firefox", "Jellyfin Web", "10.0.0.9", Instant.EPOCH, true)));

        JellyfinSetupSection.Sessions open = section().sessions();

        assertThat(open.error()).isNull();
        assertThat(open.sessions()).containsExactly(
                new JellyfinSetupSection.SessionOption("jf-shield", "SHIELD · Android TV · 10.0.0.5", "shield"),
                new JellyfinSetupSection.SessionOption("jf-web", "Firefox · Jellyfin Web · 10.0.0.9", ""));
        assertThat(open.devices()).hasSize(2);
    }

    @Test
    void aServerThatCannotListItsAppsSaysWhy() {
        given(setup.settings()).willReturn(Optional.of(connected));
        given(sessions.controllable()).willThrow(new JellyfinException(ContentSourceException.Kind.UNREACHABLE,
                "Could not reach Jellyfin at http://jellyfin:8096"));

        JellyfinSetupSection.Sessions open = section().sessions();

        assertThat(open.sessions()).isEmpty();
        assertThat(open.error()).isEqualTo("Could not reach Jellyfin at http://jellyfin:8096");
        assertThat(open.devices()).extracting(JellyfinSetupSection.DeviceOption::id).containsExactly("shield", "kitchen");
    }

    @Test
    void withoutTheDeviceListNoDeviceIsOffered() {
        given(setup.settings()).willReturn(Optional.of(connected));
        var noDevices = new JellyfinSetupSection(provider(setup), provider(login), provider(sessions), provider(null));

        assertThat(noDevices.view(HOME).devices()).isEmpty();
    }
}
