package dev.andre.homecontrol.adapters.sonos.protocol;

import dev.andre.homecontrol.adapters.upnp.protocol.UpnpXml;
import org.w3c.dom.Element;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A Sonos household's groups, from ZoneGroupTopology#GetZoneGroupState (both firmware shapes). The
 * document is device-supplied, so only members at a plain-http, private IP literal location are kept.
 */
public record ZoneGroupState(List<Group> groups) {

    public record Member(String uuid, URI location, String zoneName, boolean invisible) {
        public String host() {
            return location.getHost();
        }

        public int port() {
            return location.getPort() > 0 ? location.getPort() : SonosEndpoints.DEFAULT_PORT;
        }
    }

    public record Group(String coordinator, String id, List<Member> members) {
        public Group {
            members = List.copyOf(members);
        }

        public boolean contains(String uuid) {
            return members.stream().anyMatch(member -> member.uuid().equals(uuid));
        }

        public Optional<Member> coordinatorMember() {
            return members.stream().filter(member -> member.uuid().equals(coordinator)).findFirst();
        }

        public List<Member> visibleMembers() {
            return members.stream().filter(member -> !member.invisible()).toList();
        }
    }

    public ZoneGroupState {
        groups = List.copyOf(groups);
    }

    public static ZoneGroupState parse(String xml) {
        Element root = UpnpXml.parse(xml);
        List<Group> groups = new ArrayList<>();
        List<Element> groupElements = "ZoneGroup".equals(UpnpXml.localName(root)) ? List.of(root) : UpnpXml.descendants(root, "ZoneGroup");
        for (Element group : groupElements) {
            List<Member> members = new ArrayList<>();
            for (Element member : UpnpXml.childElements(group)) {
                if (!"ZoneGroupMember".equals(UpnpXml.localName(member))) {
                    continue; // Satellite elements are children of members, never of groups
                }
                String uuid = member.getAttribute("UUID");
                URI location;
                try {
                    location = URI.create(member.getAttribute("Location"));
                } catch (IllegalArgumentException e) {
                    continue;
                }
                if (uuid.isBlank() || !SonosEndpoints.isLanLocation(location)) {
                    continue; // device-supplied: never a member we would call outside the LAN or by host name
                }
                members.add(new Member(uuid, location, member.getAttribute("ZoneName"), "1".equals(member.getAttribute("Invisible"))));
            }
            groups.add(new Group(group.getAttribute("Coordinator"), group.getAttribute("ID"), members));
        }
        return new ZoneGroupState(groups);
    }

    public Optional<Group> groupOf(String uuid) {
        return groups.stream().filter(group -> group.contains(uuid)).findFirst();
    }

    public Optional<Member> member(String uuid) {
        return groups.stream().flatMap(group -> group.members().stream()).filter(member -> member.uuid().equals(uuid)).findFirst();
    }

    public List<Member> visibleMembers() {
        return groups.stream().flatMap(group -> group.visibleMembers().stream()).toList();
    }
}
