package dev.andre.homecontrol.adapters.cast;

import dev.andre.homecontrol.core.DeviceDiscoveredEvent;
import dev.andre.homecontrol.core.DiscoveredDevice;
import dev.andre.homecontrol.discovery.MdnsBrowser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Finds Cast receivers ({@code _googlecast._tcp}) through the shared mDNS browser. */
public class CastDiscovery {

    public static final String SERVICE_TYPE = "_googlecast._tcp.local.";

    /** TXT {@code ca} capability bit for a multizone group (not a physical receiver). */
    private static final int CAPABILITY_MULTIZONE_GROUP = 32;
    private static final List<String> KEPT_ATTRIBUTES = List.of("id", "md", "fn", "ca");

    private static final Logger log = LoggerFactory.getLogger(CastDiscovery.class);

    private final Map<String, DiscoveredDevice> found = new ConcurrentHashMap<>();
    private final ApplicationEventPublisher events;

    public CastDiscovery(MdnsBrowser browser, ApplicationEventPublisher events) {
        this.events = events;
        browser.browse(SERVICE_TYPE, new MdnsBrowser.Listener() {
            @Override
            public void resolved(MdnsBrowser.MdnsService service) {
                CastDiscovery.this.resolved(service);
            }

            @Override
            public void removed(String serviceType, String name) {
                CastDiscovery.this.removed(name);
            }
        });
    }

    public List<DiscoveredDevice> devices() {
        return List.copyOf(found.values());
    }

    void resolved(MdnsBrowser.MdnsService service) {
        toDevice(service).ifPresent(device -> {
            DiscoveredDevice previous = found.put(service.name(), device);
            if (!device.equals(previous)) {
                log.info("Discovered Cast receiver {} at {}:{}", device.name(), device.host(), device.port());
                events.publishEvent(new DeviceDiscoveredEvent(device));
            }
        });
    }

    void removed(String name) {
        found.remove(name);
    }

    static Optional<DiscoveredDevice> toDevice(MdnsBrowser.MdnsService service) {
        Optional<String> host = service.host();
        if (host.isEmpty() || isGroup(service.txt())) {
            return Optional.empty();
        }
        String friendlyName = service.txt().getOrDefault("fn", "");
        Map<String, String> attributes = new LinkedHashMap<>();
        KEPT_ATTRIBUTES.forEach(key -> {
            String value = service.txt().get(key);
            if (value != null) {
                attributes.put(key, value);
            }
        });
        return Optional.of(new DiscoveredDevice(CastSettings.ADAPTER_ID,
                friendlyName.isBlank() ? service.name() : friendlyName, host.get(), service.port(), attributes));
    }

    private static boolean isGroup(Map<String, String> txt) {
        if ("Google Cast Group".equals(txt.get("md"))) {
            return true;
        }
        try {
            return (Integer.parseInt(txt.getOrDefault("ca", "0")) & CAPABILITY_MULTIZONE_GROUP) != 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
