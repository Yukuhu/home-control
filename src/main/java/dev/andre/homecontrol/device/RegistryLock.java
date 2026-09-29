package dev.andre.homecontrol.device;

/**
 * The monitor every read-modify-write of the device registry runs under, shared by enrollment and the adapter settings
 * store: an adopt racing a forget or a discovery merge sees one registry. Held only for in-memory work and local disk
 * writes, never for DNS, {@code adapter.connect}, closing a handle or publishing an event.
 */
final class RegistryLock {
}
