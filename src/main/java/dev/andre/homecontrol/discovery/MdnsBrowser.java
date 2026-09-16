package dev.andre.homecontrol.discovery;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceEvent;
import javax.jmdns.ServiceInfo;
import javax.jmdns.ServiceListener;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The application's one jmDNS instance (spec §7: discovery is one service). Adapters register
 * the service types they care about; nobody else opens a multicast socket.
 *
 * <p>Multicast does not cross a Docker bridge network, so the UI always offers manual entry too.
 */
@Component
public class MdnsBrowser implements AutoCloseable {

    public interface Listener {
        void resolved(MdnsService service);

        void removed(String serviceType, String name);
    }

    public record MdnsService(String type, String name, List<InetAddress> addresses, int port, Map<String, String> txt) {

        public MdnsService {
            addresses = List.copyOf(addresses);
            txt = Map.copyOf(txt);
        }

        /** IPv4 first: home devices are reached over IPv4; link-local IPv6 needs a scope id. */
        public Optional<String> host() {
            return addresses.stream()
                    .min(Comparator.comparingInt(address -> address instanceof Inet4Address ? 0 : 1))
                    .map(InetAddress::getHostAddress);
        }
    }

    private static final Logger log = LoggerFactory.getLogger(MdnsBrowser.class);

    private final boolean enabled;
    private final Map<String, List<Listener>> listeners = new ConcurrentHashMap<>();

    private JmDNS jmdns;

    public MdnsBrowser(@Value("${shield.discovery-enabled:true}") boolean enabled) {
        this.enabled = enabled;
    }

    /** Safe before or after {@link #start()}. */
    public synchronized void browse(String serviceType, Listener listener) {
        List<Listener> forType = listeners.computeIfAbsent(serviceType, type -> new CopyOnWriteArrayList<>());
        boolean firstForType = forType.isEmpty();
        forType.add(listener);
        if (jmdns != null && firstForType) {
            jmdns.addServiceListener(serviceType, new JmdnsListener(serviceType));
        }
    }

    @PostConstruct
    public synchronized void start() {
        if (jmdns != null) {
            return;
        }
        if (!enabled) {
            log.info("mDNS discovery is disabled; add devices by host name or address");
            return;
        }
        try {
            jmdns = JmDNS.create(InetAddress.getLocalHost());
            listeners.keySet().forEach(type -> jmdns.addServiceListener(type, new JmdnsListener(type)));
            log.info("Listening for {}", listeners.keySet());
        } catch (IOException e) {
            log.warn("Could not start mDNS discovery ({}); use manual entry", e.getMessage());
        }
    }

    /** Pure mapping so it can be tested without multicast. */
    static Optional<MdnsService> toService(String type, String name, InetAddress[] addresses, int port,
                                           Map<String, String> txt) {
        if (addresses == null || addresses.length == 0 || port <= 0) {
            return Optional.empty();
        }
        return Optional.of(new MdnsService(type, name, List.of(addresses), port, txt));
    }

    void dispatchResolved(MdnsService service) {
        for (Listener listener : listeners.getOrDefault(service.type(), List.of())) {
            try {
                listener.resolved(service);
            } catch (RuntimeException e) {
                log.warn("An mDNS listener failed for {}", service.name(), e);
            }
        }
    }

    void dispatchRemoved(String type, String name) {
        for (Listener listener : listeners.getOrDefault(type, List.of())) {
            try {
                listener.removed(type, name);
            } catch (RuntimeException e) {
                log.warn("An mDNS listener failed for {}", name, e);
            }
        }
    }

    private final class JmdnsListener implements ServiceListener {

        private final String type;

        JmdnsListener(String type) {
            this.type = type;
        }

        @Override
        public void serviceAdded(ServiceEvent event) {
            // Resolution arrives via serviceResolved; ask for it explicitly.
            event.getDNS().requestServiceInfo(event.getType(), event.getName(), 1000);
        }

        @Override
        public void serviceRemoved(ServiceEvent event) {
            dispatchRemoved(type, event.getName());
        }

        @Override
        public void serviceResolved(ServiceEvent event) {
            ServiceInfo info = event.getInfo();
            Map<String, String> txt = new HashMap<>();
            Enumeration<String> names = info.getPropertyNames();
            while (names.hasMoreElements()) {
                String key = names.nextElement();
                String value = info.getPropertyString(key);
                if (value != null) {
                    txt.put(key, value);
                }
            }
            toService(type, info.getName(), info.getInetAddresses(), info.getPort(), txt)
                    .ifPresent(MdnsBrowser.this::dispatchResolved);
        }
    }

    @Override
    @PreDestroy
    public synchronized void close() {
        if (jmdns != null) {
            try {
                jmdns.close();
            } catch (IOException ignored) {
                // Shutting down anyway.
            }
            jmdns = null;
        }
    }
}
