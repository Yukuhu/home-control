package dev.andre.homecontrol.adapters.upnp.protocol;

import dev.andre.homecontrol.discovery.ssdp.DeviceDescription;

import java.net.URI;

public record ServiceEndpoint(String serviceType, URI controlUrl, URI scpdUrl) {

    public static ServiceEndpoint of(DeviceDescription.Service service) {
        return new ServiceEndpoint(service.serviceType(), service.controlUrl(), service.scpdUrl());
    }
}
