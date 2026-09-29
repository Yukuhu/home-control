package dev.andre.homecontrol.sources.workflows;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static dev.andre.homecontrol.sources.workflows.WorkflowException.Stage;

/** A request header value with {name} placeholders. {{ and }} stand for literal braces. Values are checked, not encoded. */
public final class WorkflowHeaderTemplate {
    private static final Pattern NAME = Pattern.compile("[A-Za-z]\\w{0,31}");
    private static final int MAX_VALUE = 1_024;
    private final List<Token> tokens;

    private record Token(String text, boolean variable) {}

    public WorkflowHeaderTemplate(String template) {
        if (template == null) throw new WorkflowException(Stage.WORKFLOW, "invalid header placeholder");
        tokens = tokenize(template);
    }

    private static List<Token> tokenize(String template) {
        List<Token> parts = new ArrayList<>();
        StringBuilder literal = new StringBuilder();
        int i = 0;
        while (i < template.length()) {
            char c = template.charAt(i);
            if (template.startsWith("{{", i)) {
                literal.append('{');
                i += 2;
            } else if (template.startsWith("}}", i)) {
                literal.append('}');
                i += 2;
            } else if (c == '{') {
                int end = template.indexOf('}', i);
                String name = end < 0 ? "" : template.substring(i + 1, end);
                if (!NAME.matcher(name).matches()) throw new WorkflowException(Stage.WORKFLOW, "invalid header placeholder");
                if (!literal.isEmpty()) {
                    parts.add(new Token(literal.toString(), false));
                    literal.setLength(0);
                }
                parts.add(new Token(name, true));
                i = end + 1;
            } else if (c == '}') {
                throw new WorkflowException(Stage.WORKFLOW, "invalid header placeholder");
            } else {
                literal.append(c);
                i++;
            }
        }
        if (!literal.isEmpty()) parts.add(new Token(literal.toString(), false));
        return List.copyOf(parts);
    }

    public Set<String> references() {
        return tokens.stream().filter(Token::variable).map(Token::text).collect(Collectors.toUnmodifiableSet());
    }

    public String expand(Map<String, WorkflowJson.Value> values) {
        StringBuilder out = new StringBuilder();
        for (Token token : tokens) {
            if (!token.variable()) {
                out.append(token.text());
                continue;
            }
            WorkflowJson.Value value = values.get(token.text());
            if (value == null || value.text() == null) throw new WorkflowException(Stage.FETCH, "unresolved header placeholder");
            String text = value.text();
            if (text.length() > MAX_VALUE || text.chars().anyMatch(WorkflowHeaderTemplate::control)) {
                throw new WorkflowException(Stage.FETCH, "header value from " + token.text() + " is not allowed");
            }
            out.append(text);
        }
        return out.toString();
    }

    /** The template whose expansion is {@code literal} itself. */
    public static String literal(String literal) {
        return literal.replace("{", "{{").replace("}", "}}");
    }

    private static boolean control(int c) {
        return (c < 0x20 && c != '\t') || c == 0x7f;
    }
}
