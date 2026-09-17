package dev.andre.homecontrol.sources.youtube;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Reads the connected Google account's own channel and records it in the non-secret settings. */
public class YouTubeAccount {

    private final YouTubeApiClient api;
    private final YouTubeSetupService setup;

    public YouTubeAccount(YouTubeApiClient api, YouTubeSetupService setup) {
        this.api = api;
        this.setup = setup;
    }

    /** Reads the connected account's channel and saves it; returns the channel title. */
    public String refreshChannel() {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("part", "snippet");
        query.put("mine", "true");
        JsonNode response = api.get(QuotaLedger.Call.CHANNELS_LIST, "channels", query);
        JsonNode item = response.path("items").path(0);
        String id = item.path("id").asString("");
        String title = item.path("snippet").path("title").asString("");
        if (id.isBlank() || title.isBlank()) {
            throw new YouTubeException(YouTubeException.Kind.BAD_RESPONSE, "YouTube did not return your channel");
        }
        YouTubeSettings current = setup.settings();
        Instant connectedAt = current.connectedAt() != null ? current.connectedAt() : Instant.now();
        setup.save(current.withConnection(connectedAt, id, title));
        return title;
    }
}
