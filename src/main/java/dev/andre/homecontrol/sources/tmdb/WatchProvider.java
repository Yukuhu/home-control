package dev.andre.homecontrol.sources.tmdb;

import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** One entry of TMDB's JustWatch-backed watch-provider lists for a region. */
public record WatchProvider(int id, String name, Category category, int displayPriority) {

    public enum Category {
        FLATRATE("flatrate"), FREE("free"), ADS("ads"), RENT("rent"), BUY("buy");

        private final String jsonKey;

        Category(String jsonKey) {
            this.jsonKey = jsonKey;
        }

        public String jsonKey() {
            return jsonKey;
        }

        /** Watchable with a subscription or for free — what "on your services" means. */
        public boolean subscription() {
            return this == FLATRATE || this == FREE || this == ADS;
        }
    }

    /** {@code providers} is the object holding {@code results} (the endpoint body or {@code "watch/providers"}). */
    public static List<WatchProvider> parse(JsonNode providers, String region) {
        JsonNode forRegion = providers.path("results").path(region.toUpperCase(Locale.ROOT));
        List<WatchProvider> found = new ArrayList<>();
        for (Category category : Category.values()) {
            List<WatchProvider> inCategory = new ArrayList<>();
            for (JsonNode entry : forRegion.path(category.jsonKey())) {
                int id = entry.path("provider_id").asInt(0);
                String name = entry.path("provider_name").asString("").strip();
                if (id > 0 && !name.isEmpty()) {
                    inCategory.add(new WatchProvider(id, name, category, entry.path("display_priority").asInt(Integer.MAX_VALUE)));
                }
            }
            inCategory.sort(Comparator.comparingInt(WatchProvider::displayPriority));
            found.addAll(inCategory);
        }
        return List.copyOf(found);
    }
}
