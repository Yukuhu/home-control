package dev.andre.homecontrol.adapters.sonos;

import dev.andre.homecontrol.adapters.upnp.protocol.UpnpXml;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Several fake players sharing one topology. Bind players to distinct loopback addresses (127.0.0.2, …). */
public class FakeSonosHousehold implements AutoCloseable {

    public static final String HOUSEHOLD = "Sonos_7yHxP2wqKbL1aZ3mN5cD9eF0gH";

    private final List<FakeSonosPlayer> players = new CopyOnWriteArrayList<>();
    /** member uuid → coordinator uuid */
    private final Map<String, String> coordinatorOf = new ConcurrentHashMap<>();

    public FakeSonosPlayer addPlayer(String bindAddress, String uuid, String zoneName) throws IOException {
        FakeSonosPlayer player = new FakeSonosPlayer(this, bindAddress, uuid, zoneName);
        players.add(player);
        coordinatorOf.put(uuid, uuid);
        return player;
    }

    public synchronized void removePlayer(FakeSonosPlayer player) {
        leave(player.uuid());
        players.remove(player);
        coordinatorOf.remove(player.uuid());
        player.close();
    }

    public synchronized void join(String member, String coordinator) {
        coordinatorOf.put(member, coordinatorOf(coordinator));
    }

    /** The leaving player stands alone; if it coordinated others, the first of them takes over. */
    public synchronized void leave(String member) {
        List<String> followers = coordinatorOf.entrySet().stream()
                .filter(entry -> entry.getValue().equals(member) && !entry.getKey().equals(member))
                .map(Map.Entry::getKey).sorted().toList();
        coordinatorOf.put(member, member);
        followers.forEach(follower -> coordinatorOf.put(follower, followers.getFirst()));
    }

    public String coordinatorOf(String uuid) {
        return coordinatorOf.getOrDefault(uuid, uuid);
    }

    public boolean isCoordinator(String uuid) {
        return coordinatorOf(uuid).equals(uuid);
    }

    public synchronized String zoneGroupState() {
        StringBuilder xml = new StringBuilder("<ZoneGroupState><ZoneGroups>");
        for (FakeSonosPlayer coordinator : players) {
            if (!isCoordinator(coordinator.uuid())) {
                continue;
            }
            xml.append("<ZoneGroup Coordinator=\"").append(coordinator.uuid()).append("\" ID=\"")
                    .append(coordinator.uuid()).append(":1\">");
            for (FakeSonosPlayer member : players) {
                if (coordinatorOf(member.uuid()).equals(coordinator.uuid())) {
                    xml.append("<ZoneGroupMember UUID=\"").append(member.uuid()).append("\" Location=\"")
                            .append(member.location()).append("\" ZoneName=\"").append(UpnpXml.escape(member.zoneName()))
                            .append("\" SoftwareVersion=\"85.0-65020\" BootSeq=\"98\"/>");
                }
            }
            xml.append("</ZoneGroup>");
        }
        return xml.append("</ZoneGroups><VanishedDevices></VanishedDevices></ZoneGroupState>").toString();
    }

    @Override
    public void close() {
        players.forEach(FakeSonosPlayer::close);
    }
}
