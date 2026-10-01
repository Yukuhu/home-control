package dev.andre.homecontrol.core.content;

import dev.andre.homecontrol.core.playback.PlayableRef;

import java.util.Optional;

/**
 * A title link the user pasted for an item a source can only launch at the app level (spec §11:
 * "let the user pin a pasted URL to upgrade the item"). Sources put it in place of the app-home link.
 */
public interface PinnedLinks {
    Optional<PlayableRef.AppLink> linkFor(String sourceId, String itemId);
}
