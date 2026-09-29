package dev.andre.homecontrol.sources.workflows;

import dev.andre.homecontrol.core.playback.ContentKind;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import static dev.andre.homecontrol.sources.workflows.WorkflowDraft.*;

/** Save-time syntax, reference and size checks. Address resolution belongs to the outbound fetch policy. */
public final class WorkflowValidator {
    static final int MAX_CALLS = 8;
    static final int MAX_HEADERS = 16;
    static final int MAX_VARIABLES = 64;
    private static final String INVALID_PREFIX = "invalid ";
    private static final String INVALID_HEADER_VALUE = "invalid header value: ";
    private static final Pattern CALL_NAME = Pattern.compile("[a-z][a-z0-9_]{0,23}");
    private static final Pattern NAME = Pattern.compile("[A-Za-z]\\w{0,31}");
    /** In a JSON pointer, {@code ~} only ever starts {@code ~0} or {@code ~1}. */
    private static final Pattern BAD_POINTER_ESCAPE = Pattern.compile("~(?![01])");
    private static final Pattern MIME = Pattern.compile("[A-Za-z0-9!#$&^_.+*-]+/[A-Za-z0-9!#$&^_.+*-]+");
    private static final Pattern HEADER_NAME = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");
    private static final Set<String> DENIED_HEADERS = Set.of("host", "cookie", "connection", "content-length",
            "transfer-encoding", "te", "trailer", "upgrade", "keep-alive", "expect", "accept-encoding", "proxy");

    private WorkflowValidator() {}

    /** Everything a stored definition satisfies. The codec checks this when reading and writing. */
    public static WorkflowPlan validateStored(WorkflowDraft draft) {
        if (draft == null) fail("definition has no draft");
        text(draft.name(), 120, "name");
        if (draft.mode() == null) fail("mode is required");
        if (draft.kind() != ContentKind.VIDEO && draft.kind() != ContentKind.TRACK) fail("kind must be video or audio");
        Set<String> names = variableNames(draft);
        calls(draft, names);
        presentation(draft, names);
        cast(draft.cast(), names);
        return WorkflowPlan.of(draft);
    }

    /** What a Save additionally requires. */
    public static void validate(WorkflowDraft draft) {
        WorkflowPlan plan = validateStored(draft);
        for (Call call : draft.calls()) {
            if (plan.phase(call.name()) == WorkflowPlan.Phase.UNUSED) fail("call " + call.name() + ": nothing uses this call");
        }
    }

    private static Set<String> variableNames(WorkflowDraft draft) {
        if (draft.calls() == null) fail("at least one call is required");
        // A single tile may play a fixed media URL without any call; a generated workflow needs its entry source.
        if (draft.calls().isEmpty() && draft.mode() != Mode.SINGLE) fail("at least one call is required");
        if (draft.calls().size() > MAX_CALLS) fail("too many calls");
        Set<String> names = new HashSet<>();
        for (Call call : draft.calls()) {
            if (call == null || call.variables() == null) fail("mappings are required");
            addVariables(call.variables(), names);
        }
        if (draft.listing() != null) {
            if (draft.listing().variables() == null) fail("mappings are required");
            addVariables(draft.listing().variables(), names);
        }
        if (names.size() > MAX_VARIABLES) fail("too many mappings");
        return names;
    }

    private static void addVariables(List<Variable> variables, Set<String> names) {
        for (Variable variable : variables) variable(variable, names);
    }

    private static void variable(Variable variable, Set<String> names) {
        if (variable == null || variable.name() == null || !NAME.matcher(variable.name()).matches()) fail("invalid mapping name");
        if (!names.add(variable.name())) fail("duplicate mapping name: " + variable.name());
        pointer(variable.pointer(), "mapping " + variable.name(), true);
    }

    private static void calls(WorkflowDraft draft, Set<String> variables) {
        Set<String> names = new HashSet<>();
        for (Call call : draft.calls()) {
            if (call.name() == null || !CALL_NAME.matcher(call.name()).matches()) fail("invalid call name");
            if (!names.add(call.name())) fail("duplicate call name: " + call.name());
            String context = "call " + call.name() + ": ";
            if (call.scope() == null || (draft.mode() == Mode.SINGLE && call.scope() != CallScope.SHARED)) {
                fail(context + "invalid scope");
            }
            try {
                new WorkflowTemplate(call.url(), variables, "call URL", WorkflowException.Stage.WORKFLOW);
            } catch (WorkflowException _) {
                fail(context + INVALID_PREFIX + "fetch URL");
            }
            headers(call, context, variables);
        }
    }

    private static void headers(Call call, String context, Set<String> variables) {
        if (call.headers() == null) fail(context + "headers are required");
        if (call.headers().size() > MAX_HEADERS) fail(context + "too many headers");
        Set<String> names = new HashSet<>();
        for (Header header : call.headers()) {
            if (header == null || header.name() == null || !HEADER_NAME.matcher(header.name()).matches()) {
                fail(context + "invalid header name");
            }
            String lower = header.name().toLowerCase(Locale.ROOT);
            if (DENIED_HEADERS.contains(lower) || lower.startsWith("proxy-")) fail(context + "header is not allowed: " + header.name());
            if (!names.add(lower)) fail(context + "duplicate header: " + header.name());
            headerValue(header, context, variables);
        }
    }

    private static void headerValue(Header header, String context, Set<String> variables) {
        String invalid = context + INVALID_HEADER_VALUE + header.name();
        if (header.value() == null || header.value().chars().anyMatch(c -> c == '\r' || c == '\n' || c == 0)) fail(invalid);
        try {
            if (!variables.containsAll(new WorkflowHeaderTemplate(header.value()).references())) fail(invalid);
        } catch (WorkflowException _) {
            fail(invalid);
        }
    }

    private static void presentation(WorkflowDraft draft, Set<String> variables) {
        if (draft.mode() == Mode.SINGLE) {
            if (draft.listing() != null) fail("single mode cannot have entry selection");
            if (draft.tile() == null) fail("single mode requires a tile");
            text(draft.tile().title(), 120, "tile title");
            optionalText(draft.tile().subtitle(), 240, "tile subtitle");
            if (draft.tile().artwork() != null && WorkflowJson.artwork(draft.tile().artwork()) == null) fail("invalid artwork URL");
            return;
        }
        if (draft.tile() != null) fail("generated mode cannot have a saved tile");
        Listing listing = draft.listing();
        if (listing == null) fail("generated mode requires entry selection");
        boolean shared = draft.calls().stream()
                .anyMatch(call -> call.name().equals(listing.call()) && call.scope() == CallScope.SHARED);
        if (!shared) fail("the entry source must be a shared call");
        pointer(listing.arrayPointer(), "array", true);
        pointer(listing.idPointer(), "entry ID", true);
        pointer(listing.titlePointer(), "entry title", true);
        display(listing.subtitle(), "entry subtitle", variables);
        display(listing.artwork(), "entry artwork", variables);
    }

    private static void display(Field field, String label, Set<String> variables) {
        if (field == null) return;
        if ((field.pointer() == null) == (field.variable() == null)) fail(INVALID_PREFIX + label);
        if (field.pointer() != null) pointer(field.pointer(), label, true);
        else if (!variables.contains(field.variable())) fail("unknown " + label + " variable");
    }

    private static void cast(Cast cast, Set<String> names) {
        if (cast == null) fail("Cast action is required");
        new WorkflowTemplate(cast.template(), names);
        String mime = cast.mimeType();
        if (mime == null || mime.length() > 100 || !MIME.matcher(mime).matches()) fail("invalid media type");
    }

    private static void pointer(String value, String field, boolean required) {
        if (value == null) {
            if (required) fail(field + " pointer is required");
            return;
        }
        if (value.length() > 512 || (!value.isEmpty() && !value.startsWith("/"))) fail(INVALID_PREFIX + field + " pointer");
        if (BAD_POINTER_ESCAPE.matcher(value).find()) fail(INVALID_PREFIX + field + " pointer escape");
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
