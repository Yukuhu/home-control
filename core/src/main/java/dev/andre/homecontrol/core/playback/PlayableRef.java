package dev.andre.homecontrol.core.playback;

import dev.andre.homecontrol.core.Action;
import dev.andre.homecontrol.core.RedactedUris;

import java.net.URI;
import java.util.Map;

/**
 * One way an item could be played. A source attaches every reference it can build; the
 * planner picks (spec §5.2). The references every source shares are defined here; a source's
 * own references are {@link SourceRef}s in its module.
 */
public sealed interface PlayableRef permits PlayableRef.AppLink, PlayableRef.StreamUrl, PlayableRef.CastLoad,
        PlayableRef.CastMessage, SourceRef {

    /** Human word for the reference kind, used in "no route" explanations. */
    String kindLabel();

    /** A URI the device should hand to whichever app claims it. {@code service} is a lower-case key such as {@code youtube}. */
    record AppLink(URI uri, String service) implements PlayableRef {
        @Override
        public String kindLabel() {
            return "app link";
        }
    }

    record CastLoad(String receiverAppId, Map<String, Object> payload) implements PlayableRef {
        @Override
        public String kindLabel() {
            return "cast";
        }
    }

    record StreamUrl(URI url, String mimeType) implements PlayableRef {
        @Override
        public String kindLabel() {
            return "direct stream";
        }

        /** Stream URLs may carry an ApiKey or, pasted by hand, user:password@; print them without either. */
        @Override
        public String toString() {
            return "StreamUrl[url=" + RedactedUris.withoutQuery(url) + ", mimeType=" + mimeType + "]";
        }
    }

    /** A custom-namespace message for a Cast receiver app. Built at play time only: it may carry a token. */
    record CastMessage(Action.CastMessage message, String receiverLabel) implements PlayableRef {
        @Override
        public String kindLabel() {
            return "cast";
        }

        @Override
        public String toString() {
            return message.toString();
        }
    }
}
