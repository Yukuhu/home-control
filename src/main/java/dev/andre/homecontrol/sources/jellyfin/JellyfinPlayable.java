package dev.andre.homecontrol.sources.jellyfin;

import dev.andre.homecontrol.core.playback.SourceRef;

/**
 * Jellyfin's references: the {@link Item} a rail offers, and what {@link JellyfinPlayableResolver} turns it into at
 * play time.
 */
public sealed interface JellyfinPlayable extends SourceRef {

    record Item(String serverId, String itemId, long resumeTicks) implements JellyfinPlayable {
        @Override
        public String kindLabel() {
            return "Jellyfin";
        }

        /** Only the resolver turns an item into something playable; without it, the module is off. */
        @Override
        public String unroutableReason() {
            return "Jellyfin is switched off on this server";
        }
    }

    /** An open, controllable Jellyfin app on the device (spec §5.3 rung 1). Created at play time by a resolver. */
    record Session(String sessionId, String itemId, long startPositionTicks, String client) implements JellyfinPlayable {
        @Override
        public String kindLabel() {
            return "Jellyfin app";
        }

        @Override
        public String unroutableReason() {
            return "the open Jellyfin app cannot be controlled";
        }
    }

    /** Deferred VLC launch; credentials are resolved only when Play is pressed. */
    record Vlc(String itemId) implements JellyfinPlayable {
        @Override public String kindLabel() { return "Open in VLC (from beginning; no Jellyfin progress tracking)"; }

        @Override
        public String unroutableReason() {
            return "VLC cannot be opened on this device";
        }
    }

    /** A paired Android TV can open Jellyfin before a controllable session exists. */
    record App(String itemId, long startPositionTicks) implements JellyfinPlayable {
        @Override
        public String kindLabel() {
            return "Jellyfin app";
        }

        @Override
        public String unroutableReason() {
            return "the Jellyfin app cannot be started on this device";
        }
    }
}
