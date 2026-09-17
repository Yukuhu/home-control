package dev.andre.homecontrol.adapters.upnp.protocol;

/** What this session last asked the renderer to play; the URI may carry a credential. */
public record PlayedItem(String uri, String title) {

    @Override
    public String toString() {
        return "PlayedItem[title=" + title + "]";
    }
}
