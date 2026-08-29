package dev.andre.shield.discovery;

import dev.andre.shield.ShieldProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceEvent;
import javax.jmdns.ServiceInfo;
import javax.jmdns.ServiceListener;

import java.io.IOException;
import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Finds Android TV devices advertising the remote service.
 *
 * <p>Multicast does not cross a Docker bridge network, so the UI always offers manual
 * host entry alongside whatever this finds (spec §7).
 */
@Service
public class MdnsDiscovery implements AutoCloseable {

    public static final String SERVICE_TYPE = "_androidtvremote2._tcp.local.";

    private static final Logger log = LoggerFactory.getLogger(MdnsDiscovery.class);

    private final Map<String, DiscoveredDevice> found = new ConcurrentHashMap<>();
    private final boolean enabled;

    private JmDNS jmdns;

    /** Two constructors, so Spring needs to be told which one to use. */
    @Autowired
    public MdnsDiscovery(ShieldProperties properties) {
        this(properties.discoveryEnabled());
    }

    public MdnsDiscovery(boolean enabled) {
        this.enabled = enabled;
    }

    @PostConstruct
    public void start() {
        if (!enabled) {
            log.info("mDNS discovery is disabled; add devices by host name or address");
            return;
        }
        try {
            jmdns = JmDNS.create(InetAddress.getLocalHost());
            jmdns.addServiceListener(SERVICE_TYPE, new Listener());
            log.info("Listening for {}", SERVICE_TYPE);
        } catch (IOException e) {
            log.warn("Could not start mDNS discovery ({}); use manual host entry", e.getMessage());
        }
    }

    public List<DiscoveredDevice> devices() {
        return List.copyOf(found.values());
    }

    /** Pure mapping so it can be tested without multicast. */
    static Optional<DiscoveredDevice> toDevice(String name, InetAddress[] addresses, int port) {
        if (addresses == null || addresses.length == 0 || port <= 0) {
            return Optional.empty();
        }
        return Optional.of(new DiscoveredDevice(name, addresses[0].getHostAddress(), port));
    }

    private class Listener implements ServiceListener {

        @Override
        public void serviceAdded(ServiceEvent event) {
            // Resolution arrives via serviceResolved; ask for it explicitly.
            event.getDNS().requestServiceInfo(event.getType(), event.getName(), 1000);
        }

        @Override
        public void serviceRemoved(ServiceEvent event) {
            found.remove(event.getName());
        }

        @Override
        public void serviceResolved(ServiceEvent event) {
            ServiceInfo info = event.getInfo();
            toDevice(info.getName(), info.getInetAddresses(), info.getPort())
                    .ifPresent(device -> {
                        found.put(event.getName(), device);
                        log.info("Discovered {} at {}:{}", device.name(), device.host(), device.port());
                    });
        }
    }

    @Override
    @PreDestroy
    public void close() {
        if (jmdns != null) {
            try {
                jmdns.close();
            } catch (IOException ignored) {
                // Shutting down anyway.
            }
        }
    }
}
