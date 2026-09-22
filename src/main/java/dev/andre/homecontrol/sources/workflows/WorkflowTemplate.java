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
    private static final Pattern VARIABLE = Pattern.compile("\\{([A-Za-z][A-Za-z0-9_]{0,31})\\}");
    private static final int MAX_URL = 8_192;
    private final String template;
    private final List<Token> tokens;
    private final int pathStart;
    private final int queryStart;

    private record Token(String text, boolean variable, int start) {}

    public WorkflowTemplate(String template, Set<String> variableNames) {
        if (template == null || template.isBlank() || template.length() > MAX_URL) fail("invalid media URL template length");
        this.template = template;
        List<Token> parts = new ArrayList<>();
        Matcher matcher = VARIABLE.matcher(template);
        int previous = 0;
        while (matcher.find()) {
            String literal = template.substring(previous, matcher.start());
            if (literal.indexOf('{') >= 0 || literal.indexOf('}') >= 0) fail("invalid media URL placeholder");
            parts.add(new Token(literal, false, previous));
            if (!variableNames.contains(matcher.group(1))) fail("unknown media URL placeholder: " + matcher.group(1));
            parts.add(new Token(matcher.group(1), true, matcher.start()));
            previous = matcher.end();
        }
        String tail = template.substring(previous);
        if (tail.indexOf('{') >= 0 || tail.indexOf('}') >= 0) fail("invalid media URL placeholder");
        parts.add(new Token(tail, false, previous));
        this.tokens = List.copyOf(parts);
        int authority = template.indexOf("://");
        int slash = authority < 0 ? -1 : template.indexOf('/', authority + 3);
        int query = template.indexOf('?');
        this.queryStart = query;
        this.pathStart = slash >= 0 && (query < 0 || slash < query) ? slash : -1;
        for (Token token : tokens) {
            if (!token.variable()) continue;
            int start = token.start();
            if (query < 0 || start < query) {
                if (pathStart < 0 || start < pathStart) fail("media URL placeholder must be in a path or query value");
            } else {
                int fieldStart = Math.max(template.lastIndexOf('&', start), query);
                int equals = template.indexOf('=', fieldStart + 1);
                if (equals < 0 || equals >= start) fail("media URL placeholder must be in a query value");
            }
        }
        StringBuilder checked = new StringBuilder();
        for (Token token : tokens) checked.append(token.variable() ? "x" : token.text());
        validUri(checked.toString());
    }

    public URI expand(Map<String, WorkflowJson.Value> values) {
        StringBuilder built = new StringBuilder();
        for (Token token : tokens) {
            if (token.variable()) {
                WorkflowJson.Value value = values.get(token.text());
                if (value == null || value.text() == null) fail("unresolved media URL placeholder");
                built.append(encodeComponent(value.text()));
            } else built.append(token.text());
            if (built.length() > MAX_URL) fail("expanded media URL exceeds limit");
        }
        return validUri(built.toString());
    }

    public String preview(Map<String, WorkflowJson.Value> values) {
        expand(values);
        StringBuilder display = new StringBuilder();
        for (Token token : tokens) {
            if (token.variable()) {
                WorkflowJson.Value value = values.get(token.text());
                if (value == null || value.text() == null) fail("unresolved media URL placeholder");
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
            int position = token.start() + i;
            char c = literal.charAt(i);
            if (position < pathStart || (pathStart < 0 && (queryStart < 0 || position < queryStart))) {
                out.append(c);
                masked = false;
            } else if (queryStart < 0 || position < queryStart) {
                if (c == '/') { out.append(c); masked = false; }
                else if (!masked) { out.append("•••"); masked = true; }
            } else {
                int fieldStart = Math.max(template.lastIndexOf('&', position), queryStart);
                int equals = template.indexOf('=', fieldStart + 1);
                boolean delimiter = c == '?' || c == '&' || (c == '=' && position == equals);
                if (delimiter) { out.append(c); masked = false; continue; }
                boolean queryName = equals < 0 || position < equals;
                if (queryName) { out.append(c); masked = false; }
                else if (!masked) { out.append("•••"); masked = true; }
            }
        }
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

    private static URI validUri(String raw) {
        try {
            URI uri = new URI(raw);
            if (uri.getScheme() == null || !(uri.getScheme().equalsIgnoreCase("http") || uri.getScheme().equalsIgnoreCase("https"))
                    || uri.getHost() == null || uri.getHost().isBlank() || uri.getRawUserInfo() != null
                    || uri.getRawFragment() != null) fail("invalid media URL template");
            for (String segment : uri.getPath().split("/", -1)) {
                if (segment.equals(".") || segment.equals("..")) fail("dot path segment in media URL");
            }
            return uri;
        } catch (URISyntaxException e) {
            throw new WorkflowException(WorkflowException.Stage.BUILD, "invalid media URL template");
        }
    }

    private static void fail(String detail) {
        throw new WorkflowException(WorkflowException.Stage.BUILD, detail);
    }
}
