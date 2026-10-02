package dev.andre.homecontrol.themes;

import tools.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import com.helger.css.decl.*;

final class ThemeTokens {
    private static final String NUMBER = "[+-]?(?:\\d+(?:\\.\\d+)?|\\.\\d+)";
    private static final Set<String> COLOR_NAMES = Set.of("transparent", "currentColor", "black", "white", "red", "green", "blue", "yellow", "gray", "grey", "orange", "purple", "cyan", "magenta", "pink", "lime", "navy", "teal", "silver", "maroon", "olive", "aqua", "fuchsia", "rebeccapurple");
    private final Map<String, String> schema;
    private final Map<String, String> defaults;

    ThemeTokens() {
        schema = strings(resource("/themes/token-schema.json"));
        defaults = strings(resource("/themes/default/tokens.json"));
        if (!schema.keySet().equals(defaults.keySet())) throw new IllegalStateException("Bundled token schema and Default must agree.");
        defaults.forEach(this::validate);
    }

    Map<String, String> read(byte[] bytes) {
        Map<String, String> supplied = strings(bytes);
        supplied.forEach(this::validate);
        Map<String, String> complete = new LinkedHashMap<>(defaults);
        complete.putAll(supplied);
        return Map.copyOf(complete);
    }

    private void validate(String name, String value) {
        String kind = schema.get(name);
        if (kind == null || !name.matches("[a-z][a-z0-9-]{0,63}")) throw ThemeException.invalid("Unknown theme token: " + name);
        if (value.isBlank() || value.length() > 2048 || value.indexOf(';') >= 0 || value.indexOf('{') >= 0 || value.indexOf('}') >= 0) throw ThemeException.invalid("Invalid token value: " + name);
        ThemeCss.validateTokenValue(value);
        CSSExpression expression = ThemeCss.parseValue(value);
        boolean valid = validType(kind, value, expression);
        if (name.equals("theme-color")) valid &= value.matches("#[0-9a-fA-F]{6}");
        if (!valid) throw ThemeException.invalid("Token " + name + " must be a " + kind + " value.");
    }

    private static boolean validType(String kind, String value, CSSExpression expression) {
        return switch (kind) {
            case "color" -> isColor(expression);
            case "length" -> isLength(expression);
            case "number" -> value.matches(NUMBER) && Double.isFinite(Double.parseDouble(value));
            case "font" -> expression.getAllMembers().stream().allMatch(m -> m instanceof CSSExpressionMemberTermSimple
                    || m == ECSSExpressionOperator.COMMA);
            case "text" -> isText(value);
            case "paint" -> expression.getAllMembers().stream().allMatch(ThemeTokens::isPaintMember);
            case "shadow" -> expression.getAllMembers().stream().allMatch(ThemeTokens::isShadowMember);
            default -> false;
        };
    }

    private static boolean isText(String value) {
        for (String word : value.split(" ", -1)) {
            if (!word.matches("[a-z][a-z-]*")) return false;
        }
        return true;
    }

    private static boolean isPaintMember(ICSSExpressionMember member) {
        return switch (member) {
            case CSSExpressionMemberTermSimple simple -> isPaintColor(simple.getValue());
            case CSSExpressionMemberFunction function -> function.getFunctionName().endsWith("gradient")
                    || function.getFunctionName().equals("var") || colorFunction(function);
            default -> member == ECSSExpressionOperator.COMMA;
        };
    }

    private static boolean isPaintColor(String value) {
        return COLOR_NAMES.contains(value) || value.matches("#[0-9a-fA-F]{3,8}");
    }

    private static boolean isShadowMember(ICSSExpressionMember member) {
        return switch (member) {
            case CSSExpressionMemberTermSimple simple -> isShadowLiteral(simple.getValue());
            case CSSExpressionMemberFunction function -> colorFunction(function) || function.getFunctionName().equals("var");
            default -> member == ECSSExpressionOperator.COMMA;
        };
    }

    private static boolean isShadowLiteral(String value) {
        return value.matches(NUMBER + "(?:px|rem|em|%)?") || isPaintColor(value) || Set.of("none", "inset").contains(value);
    }

    private static boolean isColor(CSSExpression value) {
        if (value.getMemberCount() != 1) return false;
        var member = value.getMemberAtIndex(0);
        return member instanceof CSSExpressionMemberTermSimple simple
                ? simple.getValue().matches("#(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{4}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})") || COLOR_NAMES.contains(simple.getValue())
                : member instanceof CSSExpressionMemberFunction function && colorFunction(function);
    }

    private static boolean colorFunction(CSSExpressionMemberFunction function) {
        return Set.of("rgb", "rgba", "hsl", "hsla").contains(function.getFunctionName())
                && com.helger.css.utils.CSSColorHelper.isColorValue(function.getAsCSSString(com.helger.css.writer.CSSWriterSettings.DEFAULT_SETTINGS, 0));
    }

    private static boolean isLength(CSSExpression value) {
        if (value.getMemberCount() != 1) return false;
        var member = value.getMemberAtIndex(0);
        if (member instanceof CSSExpressionMemberTermSimple simple) return simple.getValue().equals("0") || simple.getValue().matches(NUMBER + "(?:px|em|rem|vh|vw|dvh|dvw|vmin|vmax|ch|ex|%)");
        return numericLength(member);
    }

    private static boolean numericLength(com.helger.css.ICSSWriteable member) {
        if (member instanceof CSSExpressionMemberTermSimple simple) return numericLengthLiteral(simple.getValue());
        if (member instanceof CSSExpressionMemberMathUnitSimple simple) return numericLengthLiteral(simple.getText());
        if (member instanceof CSSExpressionMemberMath math) return math.getAllMembers().stream().allMatch(ThemeTokens::numericLength);
        if (member instanceof CSSExpressionMemberMathProduct product) return product.getAllMembers().stream().allMatch(ThemeTokens::numericLength);
        if (member instanceof CSSExpressionMemberMathUnitProduct product) return numericLength(product.getProduct());
        if (member instanceof CSSExpressionMemberFunction function) {
            if (!Set.of("min", "max", "clamp").contains(function.getFunctionName()) || function.getExpression() == null) return false;
            var parts = function.getExpression().getAllMembers();
            long arguments = 1 + parts.stream().filter(part -> part == ECSSExpressionOperator.COMMA).count();
            if (function.getFunctionName().equals("clamp") && arguments != 3) return false;
            return !parts.isEmpty() && parts.stream().allMatch(ThemeTokens::numericLength);
        }
        return member instanceof ECSSMathOperator || member == ECSSExpressionOperator.COMMA;
    }

    private static boolean numericLengthLiteral(String value) {
        return value.matches(NUMBER + "(?:px|em|rem|vh|vw|dvh|dvw|vmin|vmax|ch|ex|%)?");
    }

    static Map<String, String> strings(byte[] bytes) {
        JsonNode node = ThemeArchive.json(bytes);
        if (!node.isObject()) throw ThemeException.invalid("Theme tokens must be a JSON object of string values.");
        Map<String, String> values = new LinkedHashMap<>();
        node.properties().forEach(field -> {
            if (!field.getValue().isString()) throw ThemeException.invalid("Theme token values must be strings.");
            values.put(field.getKey(), field.getValue().asString());
        });
        return values;
    }

    static byte[] resource(String path) {
        try (InputStream input = ThemeTokens.class.getResourceAsStream(path)) {
            if (input == null) throw new IllegalStateException("Missing bundled theme resource: " + path);
            return input.readAllBytes();
        } catch (IOException e) { throw new IllegalStateException("Cannot read bundled theme resource: " + path, e); }
    }
}
