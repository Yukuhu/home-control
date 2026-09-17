package dev.andre.homecontrol.adapters.sonos.protocol;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ZoneGroupStateTest {

    static String fixture(String name) throws IOException {
        return Files.readString(Path.of("src/test/resources/fixtures/sonos/" + name)).strip();
    }

    @Test
    void readsGroupsMembersAndCoordinators() throws IOException {
        ZoneGroupState state = ZoneGroupState.parse(fixture("zone-group-state.xml"));

        assertThat(state.groups()).hasSize(3);
        ZoneGroupState.Group living = state.groups().getFirst();
        assertThat(living.coordinator()).isEqualTo("RINCON_000E58A0B1C201400");
        assertThat(living.id()).isEqualTo("RINCON_000E58A0B1C201400:3471562718");
        assertThat(living.members()).extracting(ZoneGroupState.Member::uuid, ZoneGroupState.Member::invisible)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("RINCON_000E58A0B1C201400", false),
                        org.assertj.core.groups.Tuple.tuple("RINCON_000E58A0B1C201401", true));
        ZoneGroupState.Member member = state.member("RINCON_000E58A0B1C201400").orElseThrow();
        assertThat(member.host()).isEqualTo("192.168.1.70");
        assertThat(member.port()).isEqualTo(1400);
        assertThat(member.zoneName()).isEqualTo("Living Room");
        assertThat(state.member("RINCON_000E58F6A7B801400").orElseThrow().zoneName()).isEqualTo("Office & Studio");
    }

    @Test
    void visibleMembersSkipBondedSpeakersAndSatellites() throws IOException {
        ZoneGroupState state = ZoneGroupState.parse(fixture("zone-group-state.xml"));

        assertThat(state.visibleMembers()).extracting(ZoneGroupState.Member::uuid)
                .containsExactly("RINCON_000E58A0B1C201400", "RINCON_000E58C3D4E501400", "RINCON_000E58F6A7B801400");
        assertThat(state.member("RINCON_000E58F6A7B901400")).isEmpty();
    }

    @Test
    void groupOfFindsTheGroupOfAnyMember() throws IOException {
        ZoneGroupState state = ZoneGroupState.parse(fixture("zone-group-state.xml"));

        ZoneGroupState.Group living = state.groupOf("RINCON_000E58A0B1C201401").orElseThrow();
        assertThat(living).isEqualTo(state.groups().getFirst());
        assertThat(living.coordinatorMember()).map(ZoneGroupState.Member::uuid).contains("RINCON_000E58A0B1C201400");
        assertThat(state.groupOf("RINCON_NOPE")).isEmpty();
    }

    @Test
    void readsTheLegacyShape() throws IOException {
        ZoneGroupState state = ZoneGroupState.parse(fixture("zone-group-state-legacy.xml"));

        assertThat(state.groups()).hasSize(1);
        assertThat(state.groups().getFirst().visibleMembers()).hasSize(2);
    }

    @Test
    void refusesDoctypesAndGarbage() {
        assertThatThrownBy(() -> ZoneGroupState.parse(
                "<!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><ZoneGroups>&e;</ZoneGroups>"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ZoneGroupState.parse("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ZoneGroupState.parse("nope")).isInstanceOf(IllegalArgumentException.class);

        ZoneGroupState state = ZoneGroupState.parse("<ZoneGroups><ZoneGroup Coordinator=\"A\" ID=\"A:1\">"
                + "<ZoneGroupMember UUID=\"B\" Location=\"::bad\" ZoneName=\"Bad\"/>"
                + "<ZoneGroupMember UUID=\"A\" Location=\"http://192.168.1.80:1400/xml/device_description.xml\" ZoneName=\"Good\"/>"
                + "</ZoneGroup></ZoneGroups>");
        assertThat(state.visibleMembers()).extracting(ZoneGroupState.Member::uuid).containsExactly("A");
    }

    @Test
    void membersOutsideTheLanOrNamedByHostNameAreSkipped() {
        // The topology is device-supplied: only http locations at private IP literals become members we may call.
        ZoneGroupState state = ZoneGroupState.parse("<ZoneGroups><ZoneGroup Coordinator=\"A\" ID=\"A:1\">"
                + "<ZoneGroupMember UUID=\"A\" Location=\"http://192.168.1.80:1400/xml/device_description.xml\" ZoneName=\"Lan\"/>"
                + "<ZoneGroupMember UUID=\"B\" Location=\"http://8.8.8.8:1400/xml/device_description.xml\" ZoneName=\"Public\"/>"
                + "<ZoneGroupMember UUID=\"C\" Location=\"http://speaker.lan:1400/xml/device_description.xml\" ZoneName=\"Name\"/>"
                + "<ZoneGroupMember UUID=\"D\" Location=\"https://192.168.1.81:1400/xml/device_description.xml\" ZoneName=\"Tls\"/>"
                + "</ZoneGroup></ZoneGroups>");

        assertThat(state.visibleMembers()).extracting(ZoneGroupState.Member::uuid).containsExactly("A");
    }
}
