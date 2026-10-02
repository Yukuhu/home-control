package dev.andre.homecontrol.adapters.upnp.protocol;

import dev.andre.homecontrol.discovery.ssdp.protocol.DeviceDescription;
import dev.andre.homecontrol.discovery.ssdp.protocol.DeviceDescriptions;
import dev.andre.homecontrol.discovery.ssdp.protocol.DeviceFetch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Optional;

/**
 * Finds a media renderer's services in its device description, under F1's rules ({@link DeviceFetch}): the
 * description only from the device's own address (plain HTTP to an IP literal, 64 KiB at most, no redirects), only a
 * description that names the expected UDN, and only services whose control URL is on the description's host and whose
 * type is well formed. The volume maximum comes from the RenderingControl SCPD on that host, else the default.
 * Locations are never logged.
 */
public final class RendererResolver {

    /** A renderer's services; {@code renderingControl} and {@code connectionManager} may be null. */
    public record Renderer(ServiceEndpoint avTransport, ServiceEndpoint renderingControl,
                           ServiceEndpoint connectionManager, int volumeMax) {
    }

    private static final Logger log = LoggerFactory.getLogger(RendererResolver.class);

    private final HttpClient http;
    private final Duration timeout;

    public RendererResolver(HttpClient http, Duration timeout) {
        this.http = http;
        this.timeout = timeout;
    }

    /** {@code expectedUdn} is the UDN the description must name, or null to accept any. */
    public Renderer resolve(URI location, String deviceHost, String expectedUdn) throws IOException {
        if (location == null || !DeviceFetch.isSafeToFetch(location, deviceHost)) {
            throw new IOException("No description address on the device's own host");
        }
        DeviceDescription description;
        try {
            description = DeviceDescriptions.parse(
                    DeviceFetch.get(http, location, timeout, DeviceFetch.MAX_DESCRIPTION_BYTES), location);
        } catch (IllegalArgumentException _) {
            throw new IOException("Unreadable device description");
        } catch (InterruptedException _) {
            // Only close() interrupts the poll loop; the attempt fails like any unreachable device.
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted while reading the device description");
        }
        if (expectedUdn != null && (description.udn() == null || !expectedUdn.equalsIgnoreCase(description.udn()))) {
            throw new IOException("The description at the device's address belongs to another device");
        }
        ServiceEndpoint avTransport = service(description, UpnpActions.AV_TRANSPORT, location, deviceHost)
                .orElseThrow(() -> new IOException("No usable AVTransport service"));
        ServiceEndpoint renderingControl = service(description, UpnpActions.RENDERING_CONTROL, location, deviceHost)
                .orElse(null);
        ServiceEndpoint connectionManager = service(description, UpnpActions.CONNECTION_MANAGER, location, deviceHost)
                .orElse(null);
        int volumeMax = renderingControl == null ? 0 : volumeMaximum(renderingControl, location);
        return new Renderer(avTransport, renderingControl, connectionManager, volumeMax);
    }

    /** Services on another host than the (already verified) description location are refused (epic constraint). */
    private static Optional<ServiceEndpoint> service(DeviceDescription description, String typePrefix, URI location,
                                                     String deviceHost) {
        return description.service(typePrefix).map(ServiceEndpoint::of).filter(endpoint -> {
            boolean sameHost = onHost(endpoint.controlUrl(), location);
            if (!sameHost) {
                log.warn("Ignoring {} of the renderer at {}: its control URL is not on the host it announced itself from",
                        typePrefix, deviceHost);
            }
            boolean validType = SoapClient.isValidServiceType(endpoint.serviceType());
            if (!validType) {
                log.warn("Ignoring {} of the renderer at {}: malformed service type", typePrefix, deviceHost);
            }
            return sameHost && validType;
        });
    }

    private static boolean onHost(URI url, URI location) {
        return url != null && "http".equalsIgnoreCase(url.getScheme()) && url.getHost() != null
                && url.getHost().equalsIgnoreCase(location.getHost());
    }

    private int volumeMaximum(ServiceEndpoint renderingControl, URI location) {
        URI scpd = renderingControl.scpdUrl();
        if (!onHost(scpd, location)) {
            return VolumeRange.DEFAULT_MAXIMUM;
        }
        try {
            return VolumeRange.maximum(DeviceFetch.get(http, scpd, timeout, DeviceFetch.MAX_DESCRIPTION_BYTES));
        } catch (IOException _) {
            return VolumeRange.DEFAULT_MAXIMUM;
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return VolumeRange.DEFAULT_MAXIMUM;
        }
    }
}
