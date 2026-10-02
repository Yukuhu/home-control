package dev.andre.homecontrol.themes;

import com.helger.css.ICSSWriteable;
import com.helger.css.decl.*;
import com.helger.css.reader.CSSReader;
import com.helger.css.reader.CSSReaderSettings;
import com.helger.css.reader.errorhandler.ICSSInterpretErrorHandler;
import com.helger.css.reader.errorhandler.ThrowingCSSParseErrorHandler;
import com.helger.css.writer.CSSWriterSettings;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Version 1 appearance vocabulary. Every accepted AST node is inspected before serialization. */
final class ThemeCss {
    private static final CSSWriterSettings WRITER = new CSSWriterSettings(false);
    private static final Set<String> PROPERTIES = words("color color-scheme background background-color background-image background-origin background-position background-size background-repeat background-clip background-blend-mode border border-color border-width border-style border-top border-right border-bottom border-left border-top-color border-right-color border-bottom-color border-left-color border-top-width border-right-width border-bottom-width border-left-width border-radius border-top-left-radius border-top-right-radius border-bottom-left-radius border-bottom-right-radius box-shadow text-shadow text-transform text-decoration text-decoration-color text-decoration-thickness text-underline-offset font-family font-size font-weight font-style font-variant font-variant-numeric line-height letter-spacing word-spacing outline outline-color outline-offset accent-color caret-color opacity filter backdrop-filter clip-path scrollbar-color animation animation-name animation-duration animation-delay animation-timing-function animation-iteration-count animation-direction animation-fill-mode animation-play-state transition transition-property transition-duration transition-delay transition-timing-function transform transform-origin padding-left");
    private static final Set<String> DECORATION_PROPERTIES = words("content position inset top right bottom left width height z-index pointer-events display margin vertical-align");
    private static final Set<String> FONT_PROPERTIES = words("font-family src font-weight font-style font-stretch font-display size-adjust ascent-override descent-override line-gap-override unicode-range");
    private static final Set<String> FUNCTIONS = words("var calc min max clamp rgb rgba hsl hsla hwb lab lch oklab oklch color color-mix linear-gradient repeating-linear-gradient radial-gradient repeating-radial-gradient conic-gradient repeating-conic-gradient polygon inset circle ellipse round rect xywh steps cubic-bezier linear translate translateX translateY translateZ translate3d rotate rotateX rotateY rotateZ scale scaleX scaleY skew skewX skewY matrix matrix3d perspective blur brightness contrast drop-shadow grayscale hue-rotate invert opacity saturate sepia format");
    private static final Set<String> ELEMENTS = words("* body button a input select textarea option label legend fieldset section header footer nav main aside article dialog form h1 h2 h3 h4 p div span strong small em b i kbd code pre table thead tbody tr th td ul ol li img svg path summary details progress meter");
    private static final Set<String> PSEUDOS = words(":root :hover :focus :focus-visible :focus-within :active :disabled :enabled :checked :indeterminate :target :open :modal :first-child :last-child :only-child :empty :required :optional :valid :invalid :placeholder-shown ::before ::after ::placeholder ::backdrop ::marker ::selection");
    private static final Set<String> ATTRIBUTES = words("type open hidden disabled checked aria-current aria-selected aria-checked aria-expanded aria-disabled role");
    private static final Set<String> MEDIA_FEATURES = words("min-width max-width width min-height max-height height orientation prefers-reduced-motion forced-colors prefers-color-scheme hover any-hover pointer any-pointer");
    // The public hook vocabulary is deliberately stable and contains no credential or device attribute selectors.
    private static final Set<String> HOOKS = words("active app app-header art auth-card auth-footer auth-page badge brand brand-light brand-mark button chip chip-group compact connected connection-status control-icon count danger dashboard-heading device-icon device-section devices down dpad drawer drawer-close drawer-header drawer-toggle empty-art empty-state error essential eyebrow header-nav hint host-checks inputs keyboard-help left library-heading link-help meta mode-switch name ok onboarding open-link page-heading pairing-code placeholder primary problem progress rail rail-empty rail-error rail-preferences rail-stale rails-empty refreshing remote-body remote-connection remote-controls retry right row search search-icon search-on-demand search-pending secondary section-heading selected setup setup-category setup-content setup-group-heading setup-layout setup-nav setup-steps setup-welcome sheet sheet-close sheet-close-form sheet-device sheet-devices sheet-item sheet-label sheet-pin sheet-route skeleton skip-link source source-links source-preferences speaker-group strip subtitle table-scroll theme-actions theme-boot theme-card theme-grid theme-library theme-meta theme-picker theme-preview theme-review theme-select theme-swatch theme-toggle theme-toggle-label tile tile-play tiles title toast-action touchpad unroutable up user-code visually-hidden volume welcome-card workflow-call workflow-call-header workflow-checkbox workflow-editor workflow-field workflow-phase workflow-row workflow-sample");
    private static final Set<String> IDS = words("sheet-play search-q toast");
    private final String scope;
    private final String namespace;
    private final String prefix;
    private final Map<String, String> contentTypes;
    private final Set<String> referenced = new HashSet<>();

    ThemeCss(String id, String revision, Map<String, String> contentTypes) {
        scope = ":root[data-theme=\"" + id + "\"]";
        namespace = "theme-" + id + "-" + revision.substring(0, 12) + "-";
        prefix = "/themes/packages/" + id + "/" + revision + "/";
        this.contentTypes = contentTypes;
    }

    String compile(Map<String, String> tokens, String source) {
        StringBuilder output = new StringBuilder("@layer theme {\n").append(scope).append(" {\n");
        new java.util.TreeMap<>(tokens).forEach((name, value) -> output.append("--").append(name).append(": ")
                .append(expression(parseValue(value), false, 0)).append(";\n"));
        output.append("}\n");
        var sheet = parse(source);
        if (sheet.hasImportRules() || sheet.hasNamespaceRules()) throw ThemeException.invalid("CSS imports and namespaces are not supported.");
        for (var rule : sheet.getAllRules()) output.append(rule(rule, false, 0));
        output.append("}\n");
        for (String path : contentTypes.keySet()) {
            if (!path.equals("preview.png") && !referenced.contains(path)) throw ThemeException.invalid("Binary asset is not referenced by theme.css: " + path);
        }
        return output.toString();
    }

    private String rule(ICSSWriteable rule, boolean nested, int depth) {
        if (depth > 32) throw ThemeException.invalid("CSS nesting is too deep.");
        if (rule instanceof CSSStyleRule style) {
            String selectors = style.getAllSelectors().stream().map(s -> selector(s, nested, false)).collect(Collectors.joining(", "));
            boolean decoration = style.getAllSelectors().stream().allMatch(s -> {
                String text = s.getAsCSSString(WRITER, 0);
                return text.endsWith("::before") || text.endsWith("::after") || text.endsWith("::backdrop");
            });
            StringBuilder result = new StringBuilder(selectors).append(" {\n");
            result.append(declarations(style.getAllDeclarations(), false, decoration));
            for (var child : style.getAllRules()) result.append(rule(child, true, depth + 1));
            return result.append("}\n").toString();
        }
        if (rule instanceof CSSNestedDeclarations declarations && nested) return declarations(declarations.getAllDeclarations(), false, false);
        if (rule instanceof CSSMediaRule media) {
            for (var query : media.getAllMediaQueries()) {
                if (query.getMedium() != null && !words("all screen print").contains(query.getMedium())) throw ThemeException.invalid("Unsupported CSS media type.");
                for (var feature : query.getAllMediaExpressions()) {
                    if (!MEDIA_FEATURES.contains(feature.getFeature()) || feature.isRangeContext()) throw ThemeException.invalid("Unsupported CSS media feature.");
                    if (feature.getValue() != null) expression(feature.getValue(), false, 0);
                }
            }
            String queries = media.getAllMediaQueries().stream().map(q -> q.getAsCSSString(WRITER, 0)).collect(Collectors.joining(", "));
            StringBuilder result = new StringBuilder("@media ").append(queries).append(" {\n");
            for (var child : media.getAllRules()) result.append(rule(child, nested, depth + 1));
            return result.append("}\n").toString();
        }
        if (rule instanceof CSSFontFaceRule font && !nested) {
            var family = font.getDeclarationOfPropertyName("font-family");
            var src = font.getDeclarationOfPropertyName("src");
            if (family == null || src == null || !unquote(family.getExpressionAsCSSString()).matches("theme-[A-Za-z0-9-]+")) {
                throw ThemeException.invalid("Font faces require a theme-* family and a local src.");
            }
            return "@font-face {\n" + declarations(font.getAllDeclarations(), true, false) + "}\n";
        }
        if (rule instanceof CSSKeyframesRule frames && !nested) {
            if (!frames.getDeclaration().equals("@keyframes") || !frames.getAnimationName().matches("theme-[a-zA-Z0-9-]+")) {
                throw ThemeException.invalid("Animations must use a theme-* name.");
            }
            StringBuilder result = new StringBuilder("@keyframes ").append(namespaced(frames.getAnimationName())).append(" {\n");
            for (var block : frames.getAllBlocks()) {
                for (String key : block.getAllKeyframesSelectors()) {
                    if (!key.matches("from|to|(?:[0-9]{1,2}(?:\\.[0-9]+)?|100)%")) throw ThemeException.invalid("Unsupported animation keyframe.");
                }
                result.append(String.join(",", block.getAllKeyframesSelectors())).append(" {")
                        .append(declarations(block.getAllDeclarations(), false, false)).append("}\n");
            }
            return result.append("}\n").toString();
        }
        throw ThemeException.invalid("Unsupported CSS rule: " + rule.getClass().getSimpleName());
    }

    private String declarations(List<CSSDeclaration> declarations, boolean font, boolean decoration) {
        StringBuilder out = new StringBuilder();
        Set<String> descriptors = new HashSet<>();
        for (var declaration : declarations) {
            String property = declaration.getProperty();
            if (font && !descriptors.add(property)) throw ThemeException.invalid("Duplicate font-face descriptor: " + property);
            boolean custom = property.matches("--[a-z][a-z0-9-]{0,63}");
            if (declaration.isImportant() || !(font ? FONT_PROPERTIES.contains(property)
                    : custom || PROPERTIES.contains(property) || DECORATION_PROPERTIES.contains(property))) {
                throw ThemeException.invalid("Unsupported CSS property or !important: " + property);
            }
            if (property.equals("animation") || property.equals("animation-name")) validateAnimation(declaration.getExpression());
            if (property.equals("src")) validateFontSource(declaration.getExpression());
            String value = expression(declaration.getExpression(), true, 0);
            if (!font && DECORATION_PROPERTIES.contains(property) && !decoration
                    && !(property.equals("position") && value.equals("relative"))) throw ThemeException.invalid("Geometry is only allowed on decorative pseudo-elements.");
            if (property.equals("pointer-events") && !value.equals("none")) throw ThemeException.invalid("Decorations must not intercept input.");
            if (property.equals("content") && !value.equals("\"\"") && !value.equals("''") && !value.equals("none")) throw ThemeException.invalid("Decorative content must be empty.");
            if (property.equals("src") && !containsUri(declaration.getExpression())) throw ThemeException.invalid("Font src requires a packaged WOFF2 URL.");
            out.append(property).append(':').append(value).append(";\n");
        }
        return out.toString();
    }

    private static void validateAnimation(CSSExpression expression) {
        Set<String> keywords = words("none infinite normal reverse alternate alternate-reverse forwards backwards both running paused ease ease-in ease-out ease-in-out linear step-start step-end initial inherit unset");
        for (var member : expression.getAllMembers()) {
            if (member instanceof CSSExpressionMemberTermSimple simple) {
                String value = simple.getValue();
                if (!(value.matches("theme-[A-Za-z0-9-]+") || keywords.contains(value)
                        || value.matches("[+-]?(?:[0-9]*\\.)?[0-9]+(?:ms|s)?"))) throw ThemeException.invalid("Animation names must use the theme-* namespace.");
            } else if (member instanceof CSSExpressionMemberFunction function) {
                if (!Set.of("steps", "cubic-bezier", "linear").contains(function.getFunctionName())) throw ThemeException.invalid("Animation values support only literal theme-* names and timing functions.");
            } else if (member != ECSSExpressionOperator.COMMA) throw ThemeException.invalid("Unsupported animation value.");
        }
    }

    private void validateFontSource(CSSExpression expression) {
        for (var member : expression.getAllMembers()) {
            if (member instanceof CSSExpressionMemberTermURI uri) {
                if (!"font/woff2".equals(contentTypes.get(uri.getURIString()))) throw ThemeException.invalid("Font src must reference a packaged WOFF2 font.");
            } else if (member instanceof CSSExpressionMemberFunction function) {
                if (!function.getFunctionName().equals("format") || function.getExpression() == null
                        || !unquote(function.getExpression().getAsCSSString(WRITER, 0)).equals("woff2")) throw ThemeException.invalid("Font src supports only WOFF2 format.");
            } else if (member != ECSSExpressionOperator.COMMA) throw ThemeException.invalid("Font src requires literal packaged WOFF2 URLs.");
        }
    }

    private boolean containsUri(CSSExpression expression) {
        return expression.getAllMembers().stream().anyMatch(m -> m instanceof CSSExpressionMemberTermURI);
    }

    private String selector(CSSSelector selector, boolean nested, boolean inner) {
        StringBuilder result = new StringBuilder();
        boolean root = false;
        for (var member : selector.getAllMembers()) {
            if (member instanceof CSSSelectorSimpleMember simple) {
                String value = simple.getValue();
                boolean valid = simple.isClass() ? HOOKS.contains(value.substring(1))
                        : simple.isHash() ? IDS.contains(value.substring(1))
                        : simple.isNesting() ? nested
                        : simple.isPseudo() ? PSEUDOS.contains(value) || value.matches(":nth-(?:last-)?(?:child|of-type)\\((?:[0-9n+\\-\\s]+|odd|even)\\)") : ELEMENTS.contains(value);
                if (!valid) throw ThemeException.invalid("Unsupported CSS selector hook: " + value);
                if (value.equals(":root")) {
                    if (nested || inner || !result.isEmpty()) throw ThemeException.invalid(":root must begin a top-level selector.");
                    result.append(scope); root = true;
                } else result.append(value);
            } else if (member instanceof CSSSelector child) {
                result.append(selector(child, nested, true));
            } else if (member instanceof ECSSSelectorCombinator combinator) {
                result.append(combinator.getAsCSSString(WRITER, 0));
            } else if (member instanceof CSSSelectorAttribute attribute) {
                if (attribute.getNamespacePrefix() != null || !ATTRIBUTES.contains(attribute.getAttrName())
                        || (attribute.getOperator() != null && !attribute.getOperator().getAsCSSString(WRITER, 0).equals("="))) {
                    throw ThemeException.invalid("Unsupported CSS attribute selector.");
                }
                result.append(attribute.getAsCSSString(WRITER, 0));
            } else if (member instanceof CSSSelectorMemberPseudoIs is) {
                result.append(selectorFunction(":is", is.getAllSelectors(), nested));
            } else if (member instanceof CSSSelectorMemberPseudoWhere where) {
                result.append(selectorFunction(":where", where.getAllSelectors(), nested));
            } else if (member instanceof CSSSelectorMemberNot not) {
                result.append(selectorFunction(":not", not.getAllSelectors(), nested));
            } else if (member instanceof CSSSelectorMemberPseudoHas has) {
                result.append(selectorFunction(":has", has.getAllSelectors(), nested));
            } else if (member instanceof CSSSelectorMemberFunctionLike function) {
                if (!words(":nth-child :nth-last-child :nth-of-type :nth-last-of-type").contains(function.getFunctionName())) throw ThemeException.invalid("Unsupported CSS selector function.");
                String parameter = function.getParameterExpression().getAsCSSString(WRITER, 0);
                if (!parameter.matches("[0-9n+\\-\\s]+|odd|even")) throw ThemeException.invalid("Unsupported nth selector expression.");
                result.append(function.getAsCSSString(WRITER, 0));
            } else throw ThemeException.invalid("Unsupported CSS selector.");
        }
        return !nested && !inner && !root ? scope + " " + result : result.toString();
    }

    private String selectorFunction(String name, List<CSSSelector> selectors, boolean nested) {
        return name + "(" + selectors.stream().map(s -> selector(s, nested, true)).collect(Collectors.joining(",")) + ")";
    }

    private String expression(CSSExpression expression, boolean allowAssets, int depth) {
        if (depth > 32) throw ThemeException.invalid("CSS values are nested too deeply.");
        for (var member : expression.getAllMembers()) validateMember(member, allowAssets, depth);
        return expression.getAsCSSString(WRITER, 0);
    }

    private void validateMember(ICSSWriteable member, boolean allowAssets, int depth) {
        if (depth > 32) throw ThemeException.invalid("CSS values are nested too deeply.");
        if (member instanceof CSSExpressionMemberTermURI uri) {
            String path = uri.getURIString();
            if (!allowAssets || !path.startsWith("assets/") || !contentTypes.containsKey(path)) throw ThemeException.invalid("CSS URLs must reference a packaged image or WOFF2 font under assets/.");
            referenced.add(path); uri.setURIString(prefix + path);
        } else if (member instanceof CSSExpressionMemberFunction function) {
            if (!FUNCTIONS.contains(function.getFunctionName())) throw ThemeException.invalid("Unsupported CSS function: " + function.getFunctionName());
            if (function.getExpression() != null) expression(function.getExpression(), allowAssets, depth + 1);
        } else if (member instanceof CSSExpressionMemberTermSimple simple) {
            String value = simple.getValue();
            if (value.contains("\\") || value.indexOf('\0') >= 0) throw ThemeException.invalid("CSS escapes are not supported in version 1.");
            String plain = unquote(value);
            if (plain.startsWith("theme-") && plain.matches("theme-[A-Za-z0-9-]+")) {
                simple.setValue(simple.isStringLiteral() ? "\"" + namespaced(plain) + "\"" : namespaced(plain));
            }
        } else if (member instanceof CSSExpressionMemberMath math) {
            for (var part : math.getAllMembers()) validateMember(part, allowAssets, depth + 1);
        } else if (member instanceof CSSExpressionMemberMathProduct product) {
            for (var part : product.getAllMembers()) validateMember(part, allowAssets, depth + 1);
        } else if (member instanceof CSSExpressionMemberMathUnitProduct product) {
            validateMember(product.getProduct(), allowAssets, depth + 1);
        } else if (member instanceof CSSExpressionMemberMathUnitSimple unit) {
            // Math leaves may include var()/calc() as raw text in ph-css. Parse them again as expressions.
            String text = unit.getText();
            if (text.indexOf('(') >= 0) expression(parseValue(text), false, depth + 1);
            else if (!text.matches("[+\\-]?(?:[0-9]*\\.)?[0-9]+(?:[a-zA-Z%]+)?")) throw ThemeException.invalid("Unsupported CSS math value.");
        } else if (!(member instanceof ECSSExpressionOperator) && !(member instanceof ECSSMathOperator)) {
            throw ThemeException.invalid("Unsupported CSS value construct.");
        }
    }

    private String namespaced(String name) { return namespace + name.substring("theme-".length()); }
    private static String unquote(String value) {
        String text = value.trim();
        return text.length() >= 2 && ((text.charAt(0) == '"' && text.charAt(text.length()-1) == '"')
                || (text.charAt(0) == '\'' && text.charAt(text.length()-1) == '\'')) ? text.substring(1, text.length()-1) : text;
    }

    static CSSExpression parseValue(String value) {
        var sheet = parse(":root { color: " + value + "; }");
        if (sheet.getRuleCount() != 1 || !(sheet.getRuleAtIndex(0) instanceof CSSStyleRule rule)
                || rule.getDeclarationCount() != 1 || rule.hasRules() || rule.getDeclarationAtIndex(0).isImportant()) throw ThemeException.invalid("Theme value must be one CSS value.");
        return rule.getDeclarationAtIndex(0).getExpression();
    }

    static void validateTokenValue(String value) {
        new ThemeCss("token", "0".repeat(64), Map.of()).expression(parseValue(value), false, 0);
    }

    private static CascadingStyleSheet parse(String source) {
        if (source.indexOf('\\') >= 0 || source.indexOf('\0') >= 0) throw ThemeException.invalid("CSS escapes and null characters are not supported in version 1.");
        checkNesting(source);
        try {
            CSSReaderSettings settings = new CSSReaderSettings().setBrowserCompliantMode(false)
                    .setCustomErrorHandler(new ThrowingCSSParseErrorHandler())
                    .setCustomExceptionHandler(error -> { throw new ThemeException(400, "Theme CSS is malformed.", error); })
                    .setInterpretErrorHandler(new ICSSInterpretErrorHandler() {
                        @Override public void onCSSInterpretationWarning(String message) { throw ThemeException.invalid("Theme CSS has unsupported syntax."); }
                        @Override public void onCSSInterpretationError(String message) { throw ThemeException.invalid("Theme CSS is malformed."); }
                    });
            CascadingStyleSheet sheet = CSSReader.readFromStringReader(source, settings);
            if (sheet == null) throw ThemeException.invalid("Theme CSS is malformed.");
            return sheet;
        } catch (ThemeException e) { throw e; }
        catch (RuntimeException e) { throw ThemeException.invalid("Theme CSS is malformed or unsupported."); }
    }

    private static void checkNesting(String source) {
        java.util.ArrayDeque<Character> brackets = new java.util.ArrayDeque<>();
        char quote = 0;
        boolean comment = false;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            char next = i + 1 < source.length() ? source.charAt(i + 1) : 0;
            if (comment) { if (c == '*' && next == '/') { comment = false; i++; } continue; }
            if (quote != 0) { if (c == quote) quote = 0; continue; }
            if (c == '/' && next == '*') { comment = true; i++; continue; }
            if (c == '\'' || c == '"') { quote = c; continue; }
            if (c == '(' || c == '{' || c == '[') {
                brackets.push(c);
                if (brackets.size() > 64) throw ThemeException.invalid("CSS nesting is too deep.");
            } else if (c == ')' || c == '}' || c == ']') {
                char expected = c == ')' ? '(' : c == '}' ? '{' : '[';
                if (brackets.isEmpty() || brackets.pop() != expected) throw ThemeException.invalid("CSS nesting is malformed.");
            }
        }
        if (!brackets.isEmpty() || comment || quote != 0) throw ThemeException.invalid("CSS nesting or a string/comment is incomplete.");
    }

    private static Set<String> words(String words) { return Set.of(words.split(" +")); }
}
