package dev.andre.homecontrol.sources.youtube;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** A small LRU cache of videos the setup page or a rail has already resolved, so item() rarely needs a request. */
public class KnownVideos {

    private final Map<String, YouTubeVideo> videos;

    public KnownVideos(int capacity) {
        this.videos = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, YouTubeVideo> eldest) {
                return size() > capacity;
            }
        };
    }

    public synchronized void remember(Collection<YouTubeVideo> found) {
        found.forEach(video -> videos.put(video.id(), video));
    }

    public synchronized Optional<YouTubeVideo> find(String id) {
        return Optional.ofNullable(videos.get(id));
    }
}
