package dev.andre.homecontrol.testsupport;

import dev.andre.homecontrol.adapters.androidtv.AndroidTvAdapter;
import dev.andre.homecontrol.adapters.androidtv.PairingService;
import dev.andre.homecontrol.adapters.androidtv.CertificateStore;
import dev.andre.homecontrol.adapters.bluetooth.BluetoothSpeakerAdapter;
import dev.andre.homecontrol.adapters.cast.CastAdapter;
import dev.andre.homecontrol.adapters.sonos.SonosAdapter;
import dev.andre.homecontrol.adapters.tizen.TizenAdapter;
import dev.andre.homecontrol.adapters.upnp.UpnpAdapter;
import dev.andre.homecontrol.adapters.webos.WebOsAdapter;
import dev.andre.homecontrol.sources.jellyfin.JellyfinClient;
import dev.andre.homecontrol.sources.pinned.PinnedShortcuts;
import dev.andre.homecontrol.sources.sports.SportsContentSource;
import dev.andre.homecontrol.sources.tmdb.TmdbContentSource;
import dev.andre.homecontrol.sources.workflows.WorkflowStore;
import dev.andre.homecontrol.sources.youtube.YouTubeSetupService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** With every module that can be switched off switched off, the application still starts, and none of them is left. */
class ModulesOffSmokeTest extends ModulesOffTest {

    @Test
    void theApplicationStartsWithEveryModuleOff() {
        assertThat(List.of(AndroidTvAdapter.class, PairingService.class, CertificateStore.class, JellyfinClient.class,
                YouTubeSetupService.class, TmdbContentSource.class, PinnedShortcuts.class, SportsContentSource.class,
                WorkflowStore.class, CastAdapter.class,
                WebOsAdapter.class, TizenAdapter.class, UpnpAdapter.class, SonosAdapter.class,
                BluetoothSpeakerAdapter.class))
                .isNotEmpty()
                .allSatisfy(type -> assertThat(context.getBeanNamesForType(type)).as(type.getSimpleName()).isEmpty());
    }
}
