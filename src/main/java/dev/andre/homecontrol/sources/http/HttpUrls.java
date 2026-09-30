package dev.andre.homecontrol.sources.http;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * The one parser for links a content source connects to. Every link is http or https, has a host without a zone and
 * no user name or password, and a port from 1 to 65535; {@link Rules} holds what differs between callers. A caller
 * words the refusal itself from the {@link Problem}; the exception's own message is a generic default.
 */
public final class HttpUrls {

    /** What is wrong with a link. */
    public enum Problem {
        MISSING("Enter a link"),
        TOO_LONG("That link is too long"),
        SYNTAX("That is not a valid link"),
        SCHEME("Use an http or https link"),
        USER_INFO("Links with a user name or password are not supported"),
        HOST("That link has no valid host"),
        PORT("That link has an invalid port"),
        QUERY("That link may not have a query"),
        FRAGMENT("That link may not have a fragment"),
        DOT_SEGMENT("That link may not contain . or .. path segments");

        private final String message;

        Problem(String message) {
            this.message = message;
        }
    }

    /** A refused link. Its message never repeats the link. */
    public static final class InvalidUrlException extends IllegalArgumentException {
        private final Problem problem;

        InvalidUrlException(Problem problem) {
            super(problem.message);
            this.problem = problem;
        }

        public Problem problem() {
            return problem;
        }
    }

    /**
     * What a caller accepts beyond the shared rules: a query, a fragment, {@code .} or {@code ..} path segments, a
     * {@code webcal(s)} link (read as https), and at most {@code maxLength} characters (0: no limit).
     */
    public record Rules(boolean allowQuery, boolean allowFragment, boolean allowDotSegments, boolean webcal,
                        int maxLength) {
    }

    private HttpUrls() {
    }

    public static URI parse(String raw, Rules rules) {
        if (raw == null || raw.isBlank()) {
            throw new InvalidUrlException(Problem.MISSING);
        }
        if (rules.maxLength() > 0 && raw.length() > rules.maxLength()) {
            throw new InvalidUrlException(Problem.TOO_LONG);
        }
        URI uri = withHttpScheme(raw, rules);
        checkAuthority(uri);
        checkRest(uri, rules);
        return uri;
    }

    /** The link as http or https, a {@code webcal(s)} link read as https when the rules allow it. */
    private static URI withHttpScheme(String raw, Rules rules) {
        URI uri = uri(raw);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (rules.webcal() && (scheme.equals("webcal") || scheme.equals("webcals"))) {
            return uri("https" + raw.substring(uri.getScheme().length()));
        }
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new InvalidUrlException(Problem.SCHEME);
        }
        return uri;
    }

    private static void checkAuthority(URI uri) {
        if (uri.getRawUserInfo() != null) {
            throw new InvalidUrlException(Problem.USER_INFO);
        }
        if (uri.getHost() == null || uri.getHost().isBlank() || uri.getHost().contains("%")) {
            throw new InvalidUrlException(Problem.HOST);
        }
        if (uri.getPort() == 0 || uri.getPort() > 65_535) {
            throw new InvalidUrlException(Problem.PORT);
        }
    }

    private static void checkRest(URI uri, Rules rules) {
        if (!rules.allowQuery() && uri.getRawQuery() != null) {
            throw new InvalidUrlException(Problem.QUERY);
        }
        if (!rules.allowFragment() && uri.getRawFragment() != null) {
            throw new InvalidUrlException(Problem.FRAGMENT);
        }
        if (!rules.allowDotSegments() && hasDotSegment(uri.getPath())) {
            throw new InvalidUrlException(Problem.DOT_SEGMENT);
        }
    }

    private static URI uri(String raw) {
        try {
            return new URI(raw);
        } catch (URISyntaxException _) {
            throw new InvalidUrlException(Problem.SYNTAX);
        }
    }

    private static boolean hasDotSegment(String path) {
        for (String segment : path.split("/", -1)) {
            if (segment.equals(".") || segment.equals("..")) {
                return true;
            }
        }
        return false;
    }
}
