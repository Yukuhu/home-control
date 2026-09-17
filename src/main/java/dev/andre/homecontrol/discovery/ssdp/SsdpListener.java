package dev.andre.homecontrol.discovery.ssdp;

/**
 * Hears services of one watched search target. Called on the receive thread, possibly many times
 * for the same service (every announcement and search response): return quickly, never block.
 */
public interface SsdpListener {

    void alive(SsdpService service);

    default void byebye(SsdpService service) {
    }
}
