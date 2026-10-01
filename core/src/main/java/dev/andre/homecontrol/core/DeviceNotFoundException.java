package dev.andre.homecontrol.core;

/** No device is registered under this id. Distinct from {@link DeviceOfflineException} (a known
 * device that cannot currently be reached) so the web layer can map it to HTTP 404. */
public class DeviceNotFoundException extends RuntimeException {
    public DeviceNotFoundException(String message) {
        super(message);
    }
}
