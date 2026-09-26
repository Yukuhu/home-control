package dev.andre.homecontrol.sources.youtube;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.MissingNode;

import java.net.URI;
import java.util.Map;

/** YouTube Data API v3 reads: quota first, bearer token, one retry after a 401, errors mapped to user-facing text. */
public class YouTubeApiClient {

    private final YouTubeHttp http;
    private final URI base;
    private final GoogleTokens tokens;
    private final QuotaLedger ledger;

    public YouTubeApiClient(YouTubeHttp http, URI apiBaseUrl, GoogleTokens tokens, QuotaLedger ledger) {
        this.http = http;
        this.base = apiBaseUrl;
        this.tokens = tokens;
        this.ledger = ledger;
    }

    public JsonNode get(QuotaLedger.Call call, String resource, Map<String, String> query) {
        URI uri = YouTubeHttp.uri(base, "/" + resource, query);
        YouTubeHttp.Response response = chargedSend(call, uri);
        if (response.status() == 401) {
            tokens.invalidate();
            response = chargedSend(call, uri);
            if (response.status() == 401) {
                throw new YouTubeException(YouTubeException.Kind.UNAUTHORIZED,
                        "Google rejected the YouTube authorization; reconnect YouTube on the setup page", "authError");
            }
        }
        if (response.ok()) {
            return response.json();
        }
        throw failure(response);
    }

    /**
     * Obtains the access token before charging: a revoked, unconfigured or unreachable-refresh
     * failure throws here and never reaches the ledger, since no Data API call was made. Only once
     * a token is in hand is the call charged, then sent — so a retried 401 is charged again too,
     * one call, one charge.
     */
    private YouTubeHttp.Response chargedSend(QuotaLedger.Call call, URI uri) {
        String accessToken = tokens.accessToken();
        ledger.charge(call);
        return http.get(uri, Map.of("Authorization", "Bearer " + accessToken, "Accept", "application/json"));
    }

    private YouTubeException failure(YouTubeHttp.Response response) {
        int status = response.status();
        JsonNode error = errorBody(response);
        String reason = error.path("errors").path(0).path("reason").asString("");
        if (status == 403) {
            if (reason.equals("quotaExceeded") || reason.equals("dailyLimitExceeded")) {
                ledger.markExhausted();
                return ledger.exhaustedException();
            }
            String message = error.path("message").asString("");
            if (reason.equals("accessNotConfigured") || ("PERMISSION_DENIED".equals(error.path("status").asString(""))
                    && (message.contains("has not been used") || message.contains("is disabled")))) {
                return new YouTubeException(YouTubeException.Kind.FORBIDDEN, "The YouTube Data API v3 is not enabled"
                        + " in your Google Cloud project. Enable it, wait a few minutes and try again.", reason);
            }
            if (reason.equals("insufficientPermissions")) {
                return new YouTubeException(YouTubeException.Kind.FORBIDDEN,
                        "The saved authorization does not include read access to YouTube; reconnect YouTube.", reason);
            }
            return new YouTubeException(YouTubeException.Kind.FORBIDDEN,
                    "YouTube refused the request (" + (reason.isBlank() ? "HTTP 403" : reason) + ")", reason);
        }
        if (status == 404) {
            return new YouTubeException(YouTubeException.Kind.NOT_FOUND,
                    "YouTube could not find it (" + (reason.isBlank() ? "HTTP 404" : reason) + ")", reason);
        }
        if (status >= 500) {
            return new YouTubeException(YouTubeException.Kind.SERVER_ERROR, "YouTube is having problems (HTTP " + status + ")");
        }
        return new YouTubeException(YouTubeException.Kind.BAD_RESPONSE, "YouTube answered HTTP " + status, reason);
    }

    private static JsonNode errorBody(YouTubeHttp.Response response) {
        try {
            return response.json().path("error");
        } catch (YouTubeException _) {
            return MissingNode.getInstance();
        }
    }
}
