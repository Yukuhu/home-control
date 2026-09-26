package dev.andre.homecontrol.adapters.upnp;

import dev.andre.homecontrol.adapters.upnp.protocol.UpnpActions;
import dev.andre.homecontrol.core.DeviceDiscoveredEvent;
import dev.andre.homecontrol.core.DiscoveredDevice;
import dev.andre.homecontrol.discovery.ssdp.DeviceDescription;
import dev.andre.homecontrol.discovery.ssdp.DeviceFetch;
import dev.andre.homecontrol.discovery.ssdp.SsdpDiscovery;
import dev.andre.homecontrol.discovery.ssdp.SsdpService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;

/** UPnP/DLNA media renderers seen through the shared SSDP listener (spec §7). */
public class UpnpDiscovery implements AutoCloseable {

    public static final String SEARCH_TARGET = "urn:schemas-upnp-org:device:MediaRenderer:1";
    private static final int DESCRIPTION_RETRIES = 5;
    private static final Logger log = LoggerFactory.getLogger(UpnpDiscovery.class);

    private final SsdpDiscovery ssdp;
    private final ApplicationEventPublisher events;
    private final boolean ignoreSonos;
    private final Map<String, DiscoveredDevice> announced = new ConcurrentHashMap<>();
    private final List<Consumer<String>> aliveListeners = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService worker =
            Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("upnp-discovery").factory());

    /** {@code ignoreSonos}: leave Sonos players to the Sonos module (when it is on). */
    public UpnpDiscovery(SsdpDiscovery ssdp, ApplicationEventPublisher events, boolean ignoreSonos) {
        this.ssdp = ssdp;
        this.events = events;
        this.ignoreSonos = ignoreSonos;
        // SSDP listeners must return at once: hand off to our own thread.
        ssdp.addListener(SEARCH_TARGET, service -> submit(() -> seen(service.usn(), 0)));
    }

    public List<DiscoveredDevice> devices() {
        return ssdp.services(SEARCH_TARGET).stream().map(this::map).flatMap(Optional::stream).toList();
    }

    /**
     * The description address most recently announced for {@code udn} by {@code host} — the registered
     * device's own address. Both the announcement's sender and its LOCATION must be that host: another
     * machine claiming the UDN can never move a session (and the stream URLs it sends) elsewhere. A
     * renderer whose address changed is added again from Setup.
     */
    public Optional<URI> location(String udn, String host) {
        if (udn == null || host == null) {
            return Optional.empty();
        }
        return ssdp.services(SEARCH_TARGET).stream()
                .filter(service -> host.equals(service.address()))
                .filter(service -> DeviceFetch.isSafeToFetch(service.location(), service.address()))
                .filter(service -> udn.equalsIgnoreCase(udnOf(service.usn()))
                        || (service.description() != null && udn.equalsIgnoreCase(service.description().udn())))
                .map(SsdpService::location)
                .findFirst();
    }

    public void onAlive(Consumer<String> udnListener) {
        aliveListeners.add(udnListener);
    }

    void seen(String usn, int attempt) {
        Optional<SsdpService> service = ssdp.services(SEARCH_TARGET).stream().filter(s -> s.usn().equals(usn)).findFirst();
        if (service.isEmpty()) {
            return;
        }
        if (service.get().description() == null) {
            // SsdpDiscovery fetches descriptions after telling listeners; look again shortly.
            if (attempt < DESCRIPTION_RETRIES) {
                try {
                    worker.schedule(() -> seen(usn, attempt + 1), 1, TimeUnit.SECONDS);
                } catch (RejectedExecutionException _) {
                    // closing
                }
            }
            return;
        }
        map(service.get()).ifPresent(found -> {
            aliveListeners.forEach(listener -> listener.accept(found.attributes().get(UpnpSettings.UDN_KEY)));
            DiscoveredDevice previous = announced.put(usn, found);
            if (!found.equals(previous)) {
                log.info("Discovered media renderer {} at {}", found.name(), found.host());
                events.publishEvent(new DeviceDiscoveredEvent(found));
            }
        });
    }

    /** The renderer this service describes, unless it is a Sonos player the Sonos module handles. */
    Optional<DiscoveredDevice> map(SsdpService service) {
        return toDevice(service, ignoreSonos);
    }

    static Optional<DiscoveredDevice> toDevice(SsdpService service, boolean ignoreSonos) {
        if (ignoreSonos && isSonos(service)) {
            return Optional.empty();
        }
        DeviceDescription description = service.description();
        URI location = service.location();
        if (description == null || location == null || description.service(UpnpActions.AV_TRANSPORT).isEmpty()) {
            return Optional.empty();
        }
        String udn = description.udn() != null ? description.udn() : udnOf(service.usn());
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put(UpnpSettings.UDN_KEY, udn);
        attributes.put(UpnpSettings.LOCATION_KEY, location.toString());
        String model = String.join(" ", Stream.of(description.manufacturer(), description.modelName())
                .filter(Objects::nonNull).filter(part -> !part.isBlank()).toList());
        if (!model.isBlank()) {
            attributes.put(UpnpSettings.MODEL_KEY, model);
        }
        String name = description.friendlyName() == null || description.friendlyName().isBlank()
                ? "Media renderer at " + service.address() : description.friendlyName();
        int port = location.getPort() > 0 ? location.getPort() : 80;
        return Optional.of(new DiscoveredDevice(UpnpSettings.ADAPTER_ID, name, service.address(), port, attributes));
    }

    static boolean isSonos(SsdpService service) {
        return service.usn().startsWith("uuid:RINCON_")
                || (service.description() != null && service.description().manufacturer() != null
                && service.description().manufacturer().startsWith("Sonos"));
    }

    static String udnOf(String usn) {
        int separator = usn.indexOf("::");
        return separator < 0 ? usn : usn.substring(0, separator);
    }

    private void submit(Runnable task) {
        try {
            worker.execute(task);
        } catch (RejectedExecutionException _) {
            // closing
        }
    }

    @Override
    public void close() {
        worker.shutdownNow();
    }
}
