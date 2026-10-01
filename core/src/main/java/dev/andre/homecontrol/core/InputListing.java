package dev.andre.homecontrol.core;

import java.util.List;

/**
 * A connection that lists its device's inputs; empty while disconnected. Found with {@link DeviceHandle#feature};
 * an adapter whose connections offer it declares {@link Capability#INPUTS}.
 */
public interface InputListing {
    List<TvInput> inputs();
}
