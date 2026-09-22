package dev.andre.homecontrol.sources.workflows;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/** Transport only: blank/dot malformed store suffixes must survive browser path normalization. */
final class WorkflowRecoveryToken {
    private WorkflowRecoveryToken() {}
    static String encode(String id) {
        return normal(id) ? id : "invalid-" + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(id.getBytes(StandardCharsets.US_ASCII));
    }
    static String decodeRecorded(String token, Map<String, String> problems) {
        String id = token;
        if (!normal(token)) {
            if (token == null || !token.matches("invalid-[A-Za-z0-9_-]{0,74}")) throw new IllegalArgumentException();
            id = new String(Base64.getUrlDecoder().decode(token.substring(8)), StandardCharsets.US_ASCII);
            if (!id.matches("[a-z0-9.-]{0,55}") || !encode(id).equals(token)) throw new IllegalArgumentException();
        }
        if (!problems.containsKey(id)) throw new IllegalArgumentException();
        return id;
    }
    private static boolean normal(String id) { return id != null && id.matches("w-[0-9a-f]{12}"); }
}
