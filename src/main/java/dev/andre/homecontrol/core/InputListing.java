package dev.andre.homecontrol.core;

import java.util.List;

/** Implemented by handles whose device can list its inputs; empty while disconnected. */
public interface InputListing {
    List<TvInput> inputs();
}
