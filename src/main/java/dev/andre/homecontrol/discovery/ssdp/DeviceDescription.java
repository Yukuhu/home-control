package dev.andre.homecontrol.discovery.ssdp;

import java.net.URI;
import java.util.List;
import java.util.Optional;

/** The parts of a UPnP device description adapters need. Services include those of embedded devices. */
public record DeviceDescription(String friendlyName, String manufacturer, String modelName, String udn,
                                List<Service> services) {

    public DeviceDescription {
        services = services == null ? List.of() : List.copyOf(services);
    }

    public record Service(String serviceType, String serviceId, URI controlUrl, URI eventSubUrl, URI scpdUrl) {
    }

    /** The first service whose type starts with {@code serviceTypePrefix} (ignoring the version suffix is the usual use). */
    public Optional<Service> service(String serviceTypePrefix) {
        return services.stream()
                .filter(service -> service.serviceType() != null && service.serviceType().startsWith(serviceTypePrefix))
                .findFirst();
    }
}
