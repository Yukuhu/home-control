package dev.andre.homecontrol.sources.workflows;

import dev.andre.homecontrol.core.playback.ContentKind;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import static dev.andre.homecontrol.sources.workflows.WorkflowDraft.*;

/** Save-time syntax and size checks. Address resolution belongs to the outbound fetch policy. */
public final class WorkflowValidator {

    private static final String INVALID_PREFIX = "invalid ";
    private static final int MAX_URL = 8_192;
    private static final Pattern NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,31}");
    private static final Pattern MIME = Pattern.compile("[A-Za-z0-9!#$&^_.+*-]+/[A-Za-z0-9!#$&^_.+*-]+");
    private static final Pattern HEADER_NAME = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");
    private static final Set<String> DENIED_HEADERS = Set.of("host", "cookie", "connection", "content-length",
            "transfer-encoding", "te", "trailer", "upgrade", "keep-alive", "expect", "accept-encoding", "proxy");

    private WorkflowValidator() {}

    public static void validate(WorkflowDraft draft) {
        if (draft == null) fail("definition has no draft");
        text(draft.name(), 120, "name");
        if (draft.mode() == null) fail("mode is required");
        if (draft.kind() != ContentKind.VIDEO && draft.kind() != ContentKind.TRACK) fail("kind must be video or audio");
        fetch(draft.fetch());
        presentation(draft);
        Set<String> names = mappingNames(draft);
        cast(draft.cast(), names);
    }

    private static void presentation(WorkflowDraft draft) {
        if (draft.mode() == Mode.SINGLE) {
            if (draft.listing() != null) fail("single mode cannot have entry selection");
            if (draft.tile() == null) fail("single mode requires a tile");
            text(draft.tile().title(), 120, "tile title");
            optionalText(draft.tile().subtitle(), 240, "tile subtitle");
            if (draft.tile().artwork() != null) artwork(draft.tile().artwork());
        } else {
            if (draft.tile() != null) fail("generated mode cannot have a saved tile");
            if (draft.listing() == null) fail("generated mode requires entry selection");
            pointer(draft.listing().arrayPointer(), "array", true);
            pointer(draft.listing().idPointer(), "entry ID", true);
            pointer(draft.listing().titlePointer(), "entry title", true);
            pointer(draft.listing().subtitlePointer(), "entry subtitle", false);
            pointer(draft.listing().artworkPointer(), "entry artwork", false);
        }
    }

    private static Set<String> mappingNames(WorkflowDraft draft) {
        if (draft.variables() == null) fail("mappings are required");
        if (draft.variables().size() > 32) fail("too many mappings");
        Set<String> names = new HashSet<>();
        for (Variable variable : draft.variables()) {
            if (variable == null || variable.name() == null || !NAME.matcher(variable.name()).matches()) {
                fail("invalid mapping name");
            }
            if (!names.add(variable.name())) fail("duplicate mapping name: " + variable.name());
            if (variable.scope() == null || (draft.mode() == Mode.SINGLE && variable.scope() != Scope.ROOT)) {
                fail("invalid mapping scope: " + variable.name());
            }
            pointer(variable.pointer(), "mapping " + variable.name(), true);
        }
        return names;
    }

    private static void fetch(Fetch fetch) {
        if (fetch == null) fail("fetch settings are required");
        url(fetch.url(), "fetch URL");
        if (fetch.headers() == null) fail("headers are required");
        if (fetch.headers().size() > 16) fail("too many headers");
        Set<String> names = new HashSet<>();
        for (Header header : fetch.headers()) {
            validateHeader(header, names);
        }
    }

    private static void validateHeader(Header header, Set<String> names) {
        if (header == null || header.name() == null || !HEADER_NAME.matcher(header.name()).matches()) {
            fail("invalid header name");
        }
        String lower = header.name().toLowerCase(Locale.ROOT);
        if (DENIED_HEADERS.contains(lower) || lower.startsWith("proxy-")) fail("header is not allowed: " + header.name());
        if (!names.add(lower)) fail("duplicate header: " + header.name());
        if (header.value() == null || header.value().chars().anyMatch(c -> c == '\r' || c == '\n' || c == 0)) {
            fail("invalid header value: " + header.name());
        }
    }

    private static void cast(Cast cast, Set<String> names) {
        if (cast == null) fail("Cast action is required");
        new WorkflowTemplate(cast.template(), names);
        String mime = cast.mimeType();
        if (mime == null || mime.length() > 100 || !MIME.matcher(mime).matches()) fail("invalid media type");
    }

    private static URI url(String value, String field) {
        if (value == null || value.isBlank() || value.length() > MAX_URL) fail(INVALID_PREFIX + field);
        try {
            URI uri = new URI(value);
            if (uri.getScheme() == null || !(uri.getScheme().equalsIgnoreCase("http") || uri.getScheme().equalsIgnoreCase("https"))
                    || uri.getHost() == null || uri.getHost().isBlank() || uri.getRawUserInfo() != null
                    || uri.getRawFragment() != null) fail(INVALID_PREFIX + field);
            for (String segment : uri.getRawPath().split("/", -1)) {
                String decodedDots = segment.replaceAll("(?i)%2e", ".");
                if (!decodedDots.isEmpty() && decodedDots.chars().allMatch(c -> c == '.')) fail("dot path segment in " + field);
            }
            return uri;
        } catch (URISyntaxException _) {
            throw new WorkflowException(WorkflowException.Stage.WORKFLOW, INVALID_PREFIX + field);
        }
    }

    private static void artwork(String value) {
        if (WorkflowJson.artwork(value) == null) fail("invalid artwork URL");
    }

    private static void pointer(String value, String field, boolean required) {
        if (value == null) {
            if (required) fail(field + " pointer is required");
            return;
        }
        if (value.length() > 512 || (!value.isEmpty() && !value.startsWith("/"))) fail(INVALID_PREFIX + field + " pointer");
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) == '~' && (i + 1 >= value.length()
                    || (value.charAt(i + 1) != '0' && value.charAt(i + 1) != '1'))) {
                fail(INVALID_PREFIX + field + " pointer escape");
            }
            if (value.charAt(i) == '~') i++;
        }
    }

    private static void text(String value, int max, String field) {
        if (value == null || value.isBlank() || value.length() > max) fail(INVALID_PREFIX + field);
    }

    private static void optionalText(String value, int max, String field) {
        if (value != null && value.length() > max) fail(INVALID_PREFIX + field);
    }

    private static void fail(String detail) {
        throw new WorkflowException(WorkflowException.Stage.WORKFLOW, detail);
    }
}
