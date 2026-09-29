package dev.andre.homecontrol.sources.jellyfin;

import dev.andre.homecontrol.core.Capability;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static dev.andre.homecontrol.testsupport.Planners.unroutableReason;
import static org.assertj.core.api.Assertions.assertThat;

/** What a person reads when nothing routes a Jellyfin reference, even with every capability. */
class JellyfinPlayableTest {

    @Test
    void everyJellyfinReferenceNamesWhatIsMissing() {
        Set<Capability> all = EnumSet.allOf(Capability.class);

        assertThat(unroutableReason(new JellyfinPlayable.Item("srv", "item-1", 0), all))
                .isEqualTo("Jellyfin is switched off on this server");
        assertThat(unroutableReason(new JellyfinPlayable.Session("s", "item-1", 0, "Android TV"), all))
                .isEqualTo("the open Jellyfin app cannot be controlled");
        assertThat(unroutableReason(new JellyfinPlayable.Vlc("item-1"), all)).isEqualTo("VLC cannot be opened on this device");
        assertThat(unroutableReason(new JellyfinPlayable.App("item-1", 0), all))
                .isEqualTo("the Jellyfin app cannot be started on this device");
    }
}
