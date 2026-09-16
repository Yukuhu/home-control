package dev.andre.homecontrol.adapters.androidtv;

import dev.andre.homecontrol.core.DiscoveredDevice;
import dev.andre.homecontrol.discovery.MdnsBrowser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Finds Android TV devices advertising the remote service, through the shared
 * {@link MdnsBrowser}.
 *
 * <p>Multicast does not cross a Docker bridge network, so the UI always offers manual
 * host entry alongside whatever this finds (spec §7).
 */
@Service
public class MdnsDiscovery implements AutoCloseable {

    public static final String SERVICE_TYPE = "_androidtvremote2._tcp.local.";

    private static final Logger log = LoggerFactory.getLogger(MdnsDiscovery.class);

    private final Map<String, DiscoveredDevice> found = new ConcurrentHashMap<>();
    private final MdnsBrowser browser;
    /** True only when this instance built its own browser (outside Spring), so it must close it. */
    private final boolean ownsBrowser;

    /** Spring starts and closes the shared browser; this only registers the service type. */
    @Autowired
    public MdnsDiscovery(MdnsBrowser browser) {
        this(browser, false);
    }

    /** Outside Spring: a private browser that nothing starts until {@link #start()} is called. */
    public MdnsDiscovery(boolean enabled) {
        this(new MdnsBrowser(enabled), true);
    }

    private MdnsDiscovery(MdnsBrowser browser, boolean ownsBrowser) {
        this.browser = browser;
        this.ownsBrowser = ownsBrowser;
        browser.browse(SERVICE_TYPE, new MdnsBrowser.Listener() {
            @Override
            public void resolved(MdnsBrowser.MdnsService service) {
                toDevice(service.name(), service.addresses().toArray(InetAddress[]::new), service.port())
                        .ifPresent(device -> {
                            found.put(service.name(), device);
                            log.info("Discovered {} at {}:{}", device.name(), device.host(), device.port());
                        });
            }

            @Override
            public void removed(String serviceType, String name) {
                found.remove(name);
            }
        });
    }

    /**
     * Starts the browser. Not a {@code @PostConstruct}: in the application the shared browser
     * starts itself; this is for a standalone instance such as the opt-in multicast test.
     */
    public void start() {
        browser.start();
    }

    public List<DiscoveredDevice> devices() {
        return List.copyOf(found.values());
    }

    /** Pure mapping so it can be tested without multicast. */
    static Optional<DiscoveredDevice> toDevice(String name, InetAddress[] addresses, int port) {
        if (addresses == null || addresses.length == 0 || port <= 0) {
            return Optional.empty();
        }
        return Optional.of(new DiscoveredDevice(AndroidTvSettings.ADAPTER_ID, name, addresses[0].getHostAddress(), port));
    }

    @Override
    public void close() {
        if (ownsBrowser) {
            browser.close();
        }
    }
}
