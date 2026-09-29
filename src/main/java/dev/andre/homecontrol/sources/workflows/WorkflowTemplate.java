package dev.andre.homecontrol.sources.workflows;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** One validated media URL template, tokenized once for expansion and safe preview. */
public final class WorkflowTemplate {
    private static final Pattern VARIABLE = Pattern.compile("\\{([A-Za-z]\\w{0,31})\\}");
    private static final String INVALID = "invalid ";
    private static final String PLACEHOLDER = " placeholder";
    private static final int MAX_URL = 8_192;
    private final String template;
    private final List<Token> tokens;
    private final int pathStart;
    private final int queryStart;
    private final String label;
    private final WorkflowException.Stage stage;

    private record Token(String text, boolean variable, int start) {}

    public WorkflowTemplate(String template, Set<String> variableNames) {
        this(template, variableNames, "media URL", WorkflowException.Stage.BUILD);
    }

    public WorkflowTemplate(String template, Set<String> variableNames, String label, WorkflowException.Stage stage) {
        this.label = label;
        this.stage = stage;
        if (template == null || template.isBlank() || template.length() > MAX_URL) fail(INVALID + label + " template length");
        this.template = template;
        this.tokens = tokenize(template, variableNames);
        int authority = template.indexOf("://");
        int slash = authority < 0 ? -1 : template.indexOf('/', authority + 3);
        int query = template.indexOf('?');
        this.queryStart = query;
        this.pathStart = slash >= 0 && (query < 0 || slash < query) ? slash : -1;
        validatePlaceholderPositions();
        StringBuilder checked = new StringBuilder();
        for (Token token : tokens) checked.append(token.variable() ? "x" : token.text());
        validUri(checked.toString());
    }

    /** The variable names this template uses. */
    public Set<String> references() {
        return tokens.stream().filter(Token::variable).map(Token::text).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private List<Token> tokenize(String template, Set<String> variableNames) {
        List<Token> parts = new ArrayList<>();
        Matcher matcher = VARIABLE.matcher(template);
        int previous = 0;
        while (matcher.find()) {
            String literal = template.substring(previous, matcher.start());
            if (literal.indexOf('{') >= 0 || literal.indexOf('}') >= 0) fail(INVALID + label + PLACEHOLDER);
            parts.add(new Token(literal, false, previous));
            if (!variableNames.contains(matcher.group(1))) fail("unknown " + label + PLACEHOLDER + ": " + matcher.group(1));
            parts.add(new Token(matcher.group(1), true, matcher.start()));
            previous = matcher.end();
        }
        String tail = template.substring(previous);
        if (tail.indexOf('{') >= 0 || tail.indexOf('}') >= 0) fail(INVALID + label + PLACEHOLDER);
        parts.add(new Token(tail, false, previous));
        return List.copyOf(parts);
    }

    private void validatePlaceholderPositions() {
        for (Token token : tokens) {
            if (!token.variable()) continue;
            int start = token.start();
            if (queryStart < 0 || start < queryStart) {
                if (pathStart < 0 || start < pathStart) fail(label + PLACEHOLDER + " must be in a path or query value");
            } else {
                int fieldStart = Math.max(template.lastIndexOf('&', start), queryStart);
                int equals = template.indexOf('=', fieldStart + 1);
                if (equals < 0 || equals >= start) fail(label + PLACEHOLDER + " must be in a query value");
            }
        }
    }

    public URI expand(Map<String, WorkflowJson.Value> values) {
        StringBuilder built = new StringBuilder();
        for (Token token : tokens) {
            if (token.variable()) {
                WorkflowJson.Value value = values.get(token.text());
                if (value == null || value.text() == null) fail("unresolved " + label + PLACEHOLDER);
                built.append(encodeComponent(value.text()));
            } else built.append(token.text());
            if (built.length() > MAX_URL) fail("expanded " + label + " exceeds limit");
        }
        return validUri(built.toString());
    }

    public String preview(Map<String, WorkflowJson.Value> values) {
        expand(values);
        StringBuilder display = new StringBuilder();
        for (Token token : tokens) {
            if (token.variable()) {
                WorkflowJson.Value value = values.get(token.text());
                if (value == null || value.text() == null) fail("unresolved " + label + PLACEHOLDER);
                display.append(value.sensitive() ? "•••" : encodeComponent(value.text()));
            } else {
                previewLiteral(display, token);
            }
        }
        return display.toString();
    }

    private void previewLiteral(StringBuilder out, Token token) {
        String literal = token.text();
        boolean masked = false;
        for (int i = 0; i < literal.length(); i++) {
            char c = literal.charAt(i);
            if (shownInPreview(c, token.start() + i)) {
                out.append(c);
                masked = false;
            } else if (!masked) {
                out.append("•••");
                masked = true;
            }
        }
    }

    /** Scheme, authority, path separators, query delimiters and query names show; path and query values do not. */
    private boolean shownInPreview(char c, int position) {
        if (position < pathStart || (pathStart < 0 && (queryStart < 0 || position < queryStart))) return true;
        if (queryStart < 0 || position < queryStart) return c == '/';
        int fieldStart = Math.max(template.lastIndexOf('&', position), queryStart);
        int equals = template.indexOf('=', fieldStart + 1);
        boolean delimiter = c == '?' || c == '&' || (c == '=' && position == equals);
        return delimiter || equals < 0 || position < equals;
    }

    static String encodeComponent(String value) {
        StringBuilder encoded = new StringBuilder();
        final String hex = "0123456789ABCDEF";
        for (byte octet : value.getBytes(StandardCharsets.UTF_8)) {
            int b = octet & 255;
            boolean safe = b >= 'a' && b <= 'z' || b >= 'A' && b <= 'Z'
                    || b >= '0' && b <= '9' || b == '-' || b == '.' || b == '_' || b == '~';
            if (safe) encoded.append((char) b);
            else encoded.append('%').append(hex.charAt(b >>> 4)).append(hex.charAt(b & 15));
        }
        return encoded.toString();
    }

    private URI validUri(String raw) {
        try {
            URI uri = new URI(raw);
            if (uri.getScheme() == null || !(uri.getScheme().equalsIgnoreCase("http") || uri.getScheme().equalsIgnoreCase("https"))
                    || uri.getHost() == null || uri.getHost().isBlank() || uri.getRawUserInfo() != null
                    || uri.getRawFragment() != null) fail(INVALID + label + " template");
            for (String segment : uri.getPath().split("/", -1)) {
                if (segment.equals(".") || segment.equals("..")) fail("dot path segment in " + label);
            }
            return uri;
        } catch (URISyntaxException _) {
            throw new WorkflowException(stage, INVALID + label + " template");
        }
    }

    private void fail(String detail) {
        throw new WorkflowException(stage, detail);
    }
}
