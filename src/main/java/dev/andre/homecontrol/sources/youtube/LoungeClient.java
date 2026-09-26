package dev.andre.homecontrol.sources.youtube;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * YouTube's unofficial "Lounge" remote-control API, reduced to "start this video on that screen".
 * Best effort: Google does not document it and changes it without notice. Wire format as observed in
 * casttube (MIT) and pyytlounge; no code taken from either. Goes through {@link YouTubeHttp}: no
 * redirects, timeouts, bounded bodies. Lounge tokens and session ids never appear in a message.
 */
public class LoungeClient {

    public static final String NAME = "Home Control";
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** A bound remote-control session. Its ids are credentials for that screen: never printed. */
    public record LoungeSession(String sid, String gsessionId, long lastEventId) {
        @Override
        public String toString() {
            return "LoungeSession[lastEventId=" + lastEventId + "]";
        }
    }

    private final YouTubeHttp http;
    private final URI base;

    public LoungeClient(YouTubeHttp http, URI loungeBaseUrl) {
        this.http = http;
        this.base = loungeBaseUrl;
    }

    public String loungeToken(String screenId) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("screen_ids", screenId);
        YouTubeHttp.Response response = post("lounge token", YouTubeHttp.uri(base, "/pairing/get_lounge_token_batch", Map.of()), form);
        if (!response.ok()) {
            throw new LoungeException("lounge token", "YouTube answered HTTP " + response.status());
        }
        String token;
        try {
            token = response.json().path("screens").path(0).path("loungeToken").asString("");
        } catch (YouTubeException _) {
            token = "";
        }
        if (token.isBlank()) {
            throw new LoungeException("lounge token", "YouTube did not issue a lounge token for this screen");
        }
        return token;
    }

    public LoungeSession bind(String loungeToken, String remoteId) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("RID", "1");
        query.put("VER", "8");
        query.put("CVER", "1");
        query.put("auth_failure_option", "send_error");
        Map<String, String> form = new LinkedHashMap<>();
        form.put("app", "web");
        form.put("mdx-version", "3");
        form.put("name", NAME);
        form.put("id", remoteId);
        form.put("device", "REMOTE_CONTROL");
        form.put("capabilities", "que,dsdtr,atp");
        form.put("magnaKey", "cloudPairedDevice");
        form.put("ui", "false");
        form.put("theme", "cl");
        form.put("loungeIdToken", loungeToken);
        YouTubeHttp.Response response = post("bind", YouTubeHttp.uri(base, "/bc/bind", query), form);
        if (response.status() == 401) {
            throw new LoungeException("bind", "YouTube rejected the lounge token");
        }
        if (!response.ok()) {
            throw new LoungeException("bind", "YouTube answered HTTP " + response.status());
        }
        return parseBind(response.text());
    }

    public void setPlaylist(String loungeToken, LoungeSession session, String videoId) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("name", NAME);
        query.put("loungeIdToken", loungeToken);
        query.put("SID", session.sid());
        query.put("AID", String.valueOf(session.lastEventId()));
        query.put("gsessionid", session.gsessionId());
        query.put("device", "REMOTE_CONTROL");
        query.put("app", "youtube-desktop");
        query.put("VER", "8");
        query.put("v", "2");
        query.put("RID", "2");
        Map<String, String> form = new LinkedHashMap<>();
        form.put("count", "1");
        form.put("ofs", "1");
        form.put("req0__sc", "setPlaylist");
        form.put("req0_videoId", videoId);
        YouTubeHttp.Response response = post("setPlaylist", YouTubeHttp.uri(base, "/bc/bind", query), form);
        if (Set.of(400, 404, 410).contains(response.status())) {
            throw new LoungeException("setPlaylist", "YouTube dropped the lounge session (HTTP " + response.status() + ")");
        }
        if (!response.ok()) {
            throw new LoungeException("setPlaylist", "YouTube answered HTTP " + response.status());
        }
    }

    /**
     * Length-prefixed chunks of {@code [[eventId,[type,…]],…]} whose JSON may span lines. The length
     * lines are not trusted: every top-level array is taken as one chunk, whatever sits between them.
     * {@code ["c",sid,…]} carries the session id, {@code ["S",gsessionid]} the other one.
     */
    public static LoungeSession parseBind(String body) {
        String sid = "";
        String gsessionId = "";
        long lastEventId = -1;
        int depth = 0;
        int start = -1;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"' && depth > 0) {
                inString = true;
            } else if (c == '[') {
                if (depth++ == 0) {
                    start = i;
                }
            } else if (c == ']' && depth > 0 && --depth == 0) {
                JsonNode events;
                try {
                    events = MAPPER.readTree(body.substring(start, i + 1));
                } catch (JacksonException _) {
                    continue;
                }
                for (JsonNode event : events) {
                    if (!event.isArray()) {
                        continue;
                    }
                    lastEventId = Math.max(lastEventId, event.path(0).asLong(-1));
                    JsonNode payload = event.path(1);
                    switch (payload.path(0).asString("")) {
                        case "c" -> sid = payload.path(1).asString("");
                        case "S" -> gsessionId = payload.path(1).asString("");
                        default -> {
                        }
                    }
                }
            }
        }
        if (sid.isBlank() || gsessionId.isBlank()) {
            throw new LoungeException("bind", "YouTube's answer had no session");
        }
        return new LoungeSession(sid, gsessionId, Math.max(lastEventId, 0));
    }

    private YouTubeHttp.Response post(String step, URI uri, Map<String, String> form) {
        try {
            return http.postForm(uri, form, Map.of());
        } catch (YouTubeException e) {
            throw new LoungeException(step, e.kind() == YouTubeException.Kind.UNREACHABLE
                    ? "could not reach YouTube" : "YouTube sent an answer that could not be used");
        }
    }
}
