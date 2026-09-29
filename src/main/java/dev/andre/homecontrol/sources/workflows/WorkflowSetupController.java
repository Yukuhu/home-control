package dev.andre.homecontrol.sources.workflows;

import dev.andre.homecontrol.config.ConditionalOnModule;
import dev.andre.homecontrol.config.Module;
import dev.andre.homecontrol.security.LoginRequiredException;
import dev.andre.homecontrol.security.LoginService;
import dev.andre.homecontrol.security.PasswordRejectedException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.validation.DirectFieldBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ExtendedServletRequestDataBinder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Controller
@ConditionalOnModule(Module.WORKFLOWS)
public final class WorkflowSetupController {

    private static final String INVALID = "invalid";
    private static final String CHANGED = "changed";
    private static final String WORKFLOW_FORM = "workflowForm";
    private static final String VIEW = "workflow-editor";
    private static final String BASE = "/setup/workflows";
    private static final String ENABLED = "enabled";
    private static final Set<String> SCALARS = Set.of("name", ENABLED, "mode", "kind", "title", "subtitle", "artwork",
            "entryCall", "arrayPointer", "idPointer", "titlePointer", "subtitleFrom", "subtitlePointer",
            "subtitleVariable", "artworkFrom", "artworkPointer", "artworkVariable", "templateMode", "template",
            "mimeType", "expectedRevision", "loginPassword", "loginPasswordConfirmation");
    private static final Set<String> CHECKBOXES = Set.of(ENABLED);
    private static final String CALLS = "calls";
    private static final int MAX_HEADER_ROWS = 16;
    private static final int MAX_VARIABLE_ROWS = 64;
    private static final Pattern CALL = Pattern.compile("calls\\[([0-7])\\]\\.(name|scope|savedName|urlMode|url|headersMode)");
    private static final Pattern HEADER = Pattern.compile("(calls\\[[0-7]\\]\\.headers)\\[(0|[1-9]\\d?)\\]\\.(name|value)");
    private static final Pattern VARIABLE = Pattern.compile(
            "(calls\\[[0-7]\\]\\.variables|entryVariables)\\[(0|[1-9]\\d?)\\]\\.(name|pointer|sensitive)");
    private static final Pattern CALL_NAME = Pattern.compile("[a-z][a-z0-9_]{0,23}");
    private static final Pattern VARIABLE_NAME = Pattern.compile("[A-Za-z]\\w{0,31}");
    public record ErrorView(String target, String message) {}
    private final WorkflowStore store;
    private final LoginService login;
    private final WorkflowTestService tests;

    public WorkflowSetupController(WorkflowStore store, LoginService login, WorkflowTestService tests) {
        this.store = store; this.login = login; this.tests = tests;
    }

    @ModelAttribute(WORKFLOW_FORM)
    WorkflowForm emptyForm(HttpServletRequest request) {
        WorkflowForm form = new WorkflowForm();
        if (!request.getRequestURI().equals(request.getContextPath() + BASE)
                && !request.getRequestURI().endsWith("/new")) {
            form.templateMode = WorkflowForm.Replacement.KEEP; // each call row brings its own modes
        }
        return form;
    }

    @InitBinder(WORKFLOW_FORM)
    void bindWorkflow(WebDataBinder binder, HttpServletRequest request) {
        if (binder instanceof ExtendedServletRequestDataBinder servletBinder) {
            servletBinder.addHeaderPredicate(ignored -> false);
        }
        binder.initDirectFieldAccess();
        binder.setAutoGrowCollectionLimit(MAX_VARIABLE_ROWS);
        binder.setFieldDefaultPrefix(null); // Never accept Spring's !field client-selected defaults.
        BindingFields fields = allowedFields(request);
        binder.setAllowedFields(fields.allowed().toArray(String[]::new));
        if (fields.invalid()) request.setAttribute("workflowInvalidFields", Boolean.TRUE);
    }

    private static BindingFields allowedFields(HttpServletRequest request) {
        Set<String> allowed = new HashSet<>(SCALARS);
        Map<String, Set<Integer>> rows = new HashMap<>();
        boolean invalid = false;
        for (var parameter : request.getParameterMap().entrySet()) {
            if (!allowParameter(parameter.getKey(), parameter.getValue(), allowed, rows)) invalid = true;
        }
        Set<Integer> calls = rows.getOrDefault(CALLS, Set.of());
        for (var family : rows.entrySet()) {
            String prefix = family.getKey() + "[";
            // A call's rows without the call itself, or rows with gaps, are not bound at all.
            boolean orphan = family.getKey().startsWith("calls[") && !calls.contains(family.getKey().charAt(6) - '0');
            if (orphan || !contiguous(family.getValue())) {
                allowed.removeIf(field -> field.startsWith(orphan ? family.getKey() : prefix));
                invalid = true;
            }
        }
        return new BindingFields(allowed, invalid);
    }

    private static boolean allowParameter(String raw, String[] values, Set<String> allowed, Map<String, Set<Integer>> rows) {
        String field = raw.startsWith("_") ? raw.substring(1) : raw;
        boolean marker = raw.startsWith("_");
        if (SCALARS.contains(field) && (!marker || CHECKBOXES.contains(field))) return singleValue(values);
        if (!singleValue(values)) return false;
        var call = CALL.matcher(field);
        if (call.matches() && !marker) {
            rows.computeIfAbsent(CALLS, k -> new HashSet<>()).add(Integer.parseInt(call.group(1)));
            allowed.add(field);
            return true;
        }
        var header = HEADER.matcher(field);
        if (header.matches() && !marker) {
            return row(header.group(1), Integer.parseInt(header.group(2)), MAX_HEADER_ROWS, field, allowed, rows);
        }
        var variable = VARIABLE.matcher(field);
        if (variable.matches() && (!marker || variable.group(3).equals("sensitive"))) {
            return row(variable.group(1), Integer.parseInt(variable.group(2)), MAX_VARIABLE_ROWS, field, allowed, rows);
        }
        return false;
    }

    private static boolean row(String family, int index, int limit, String field, Set<String> allowed,
                               Map<String, Set<Integer>> rows) {
        if (index >= limit) return false;
        rows.computeIfAbsent(family, k -> new HashSet<>()).add(index);
        allowed.add(field);
        return true;
    }

    private static boolean singleValue(String[] values) {
        return values.length == 1;
    }

    private record BindingFields(Set<String> allowed, boolean invalid) {}

    @GetMapping(BASE + "/new")
    public String createEditor(Model model, HttpServletResponse response) {
        privateResponse(response);
        return editor(null, WorkflowForm.blank(), null, model);
    }

    @GetMapping(BASE + "/{id}")
    public String savedEditor(@PathVariable String id, Model model, HttpServletResponse response) {
        privateResponse(response);
        var saved = store.find(id).orElse(null);
        if (saved == null) return missing(model, response);
        return editor(id, WorkflowForm.from(saved), null, model);
    }

    @PostMapping(BASE)
    public String create(@ModelAttribute(WORKFLOW_FORM) WorkflowForm form, BindingResult binding,
                         HttpServletRequest request, HttpServletResponse response, Model model) {
        return save(null, form, binding, request, response, model);
    }

    @PostMapping(BASE + "/{id}")
    public String update(@PathVariable String id, @ModelAttribute(WORKFLOW_FORM) WorkflowForm form, BindingResult binding,
                         HttpServletRequest request, HttpServletResponse response, Model model) {
        return save(id, form, binding, request, response, model);
    }

    private String save(String id, WorkflowForm form, BindingResult binding, HttpServletRequest request,
                        HttpServletResponse response, Model model) {
        privateResponse(response);
        // Spring also adds the route's id to property values; it is not a client-editable form field.
        boolean suppressed = java.util.Arrays.stream(binding.getSuppressedFields())
                .anyMatch(field -> !field.equals("id") || request.getParameterMap().containsKey("id"));
        if (suppressed || Boolean.TRUE.equals(request.getAttribute("workflowInvalidFields"))) {
            binding.reject(INVALID, "Some submitted fields are invalid. Check the form and try again.");
        }
        try {
            authenticate(request);
            WorkflowDefinition saved = id == null ? null : store.find(id).orElse(null);
            if (id != null && saved == null) {
                response.setStatus(404); binding.reject("missing", "This workflow no longer exists.");
            } else if (saved != null && saved.revision() != form.expectedRevision) {
                response.setStatus(409); binding.reject(CHANGED, "Workflow changed; reopen this item before saving.");
            }
            if (binding.hasErrors()) return editor(id, form, binding, model);
            var draft = form.toDraft(saved);
            WorkflowValidator.validate(draft);
            var changed = id == null ? store.create(draft, form.loginPassword, form.loginPasswordConfirmation, request)
                    : store.update(id, form.expectedRevision, draft, request);
            form.clearSecrets();
            return "redirect:" + BASE + "/" + changed.id();
        } catch (LoginRequiredException _) {
            response.setStatus(401); binding.reject("login", "Log in again before changing workflows.");
        } catch (PasswordRejectedException _) {
            binding.rejectValue("loginPassword", "password", "Passwords must match and contain 10–1024 characters.");
        } catch (WorkflowException failure) {
            rejectSaveFailure(failure, form, binding, response);
        } catch (RuntimeException _) {
            binding.reject("save", "Could not save the workflow. Check the settings and try again.");
        }
        return editor(id, form, binding, model);
    }

    private static void rejectSaveFailure(WorkflowException failure, WorkflowForm form, BindingResult binding,
                                          HttpServletResponse response) {
        if ("Workflow changed; reopen this item".equals(failure.detail())) {
            response.setStatus(409);
            binding.reject(CHANGED, "Workflow changed; reopen this item before saving.");
            return;
        }
        String field = validationField(failure, form);
        String message = guidance(failure.detail());
        if (field == null) {
            binding.reject("settings", message == null ? "Check the calls, media template, fields and headers." : message);
        } else {
            binding.rejectValue(field, "settings", message == null ? "Check this field's format and limits." : message);
        }
    }

    /** Text written here, chosen by the kind of problem; the domain message itself is never shown. */
    private static String guidance(String detail) {
        if (detail.contains(" uses {") && detail.contains("further down")) return "This call uses a value no call above it provides.";
        if (detail.contains(" uses {")) return "This call uses a value that no call defines.";
        if (detail.contains("make it a per-entry call")) return "This call uses an entry value; set it to run once per entry.";
        if (detail.endsWith("nothing uses this call")) return "Nothing uses this call. Use one of its values or remove it.";
        if (detail.endsWith("is marked sensitive")) return "A tile cannot show a value marked sensitive.";
        if (detail.equals("the entry source must be a shared call")) return "Choose a call that runs once as the source of entries.";
        return null;
    }

    @PostMapping(BASE + "/{id}/test")
    public String test(@PathVariable String id, HttpServletRequest request, HttpServletResponse response, Model model) {
        privateResponse(response);
        WorkflowForm form = new WorkflowForm();
        var binding = new DirectFieldBindingResult(form, WORKFLOW_FORM);
        try {
            authenticate(request);
            var saved = store.find(id).orElse(null);
            if (saved == null) return missing(model, response);
            form = WorkflowForm.from(saved);
            binding = new DirectFieldBindingResult(form, WORKFLOW_FORM);
            var result = tests.test(id, revision(request), request);
            model.addAttribute("testResult", result);
        } catch (LoginRequiredException _) {
            response.setStatus(401); binding.reject("login", "Log in again before testing workflows.");
        } catch (WorkflowException _) {
            response.setStatus(409); binding.reject(CHANGED, "Workflow changed; reopen this item before testing.");
        } catch (RuntimeException _) {
            binding.reject("test", "Could not test this workflow. Reopen it and try again.");
        }
        return editor(id, form, binding, model);
    }

    @PostMapping(BASE + "/{id}/enabled")
    public String enabled(@PathVariable String id, HttpServletRequest request, HttpServletResponse response, Model model) {
        return mutate(id, request, response, model, () -> {
            String value = request.getParameter(ENABLED);
            if (!"true".equals(value) && !"false".equals(value)) throw new IllegalArgumentException();
            store.setEnabled(id, revision(request), Boolean.parseBoolean(value), request);
        });
    }

    @PostMapping(BASE + "/{id}/remove")
    public String remove(@PathVariable String id, HttpServletRequest request, HttpServletResponse response, Model model) {
        return mutate(id, request, response, model, () -> store.remove(id, revision(request), request));
    }

    @PostMapping(BASE + "/{id}/remove-invalid")
    public String removeInvalid(@PathVariable String id, HttpServletRequest request, HttpServletResponse response, Model model) {
        return mutate(null, request, response, model,
                () -> store.removeInvalid(WorkflowRecoveryToken.decodeRecorded(id, store.problems()), request));
    }

    private String mutate(String id, HttpServletRequest request, HttpServletResponse response, Model model, Runnable operation) {
        privateResponse(response);
        try {
            authenticate(request); operation.run(); return "redirect:/setup#workflows";
        } catch (LoginRequiredException _) {
            response.setStatus(401);
        } catch (IllegalArgumentException _) {
            response.setStatus(400);
        } catch (RuntimeException _) {
            response.setStatus(409);
        }
        WorkflowForm form = id == null ? new WorkflowForm() : store.find(id).map(WorkflowForm::from).orElseGet(WorkflowForm::new);
        var binding = new DirectFieldBindingResult(form, WORKFLOW_FORM);
        binding.reject("action", "Could not change this workflow. Reopen Setup and try again.");
        return editor(id, form, binding, model);
    }

    private String missing(Model model, HttpServletResponse response) {
        response.setStatus(404);
        WorkflowForm form = new WorkflowForm();
        var binding = new DirectFieldBindingResult(form, WORKFLOW_FORM);
        binding.reject("missing", "This workflow no longer exists. Return to Setup.");
        return editor(null, form, binding, model);
    }

    private String editor(String id, WorkflowForm form, BindingResult original, Model model) {
        form.clearSecrets();
        var safe = new DirectFieldBindingResult(form, WORKFLOW_FORM);
        List<ErrorView> errors = original == null ? List.of() : copySafely(original, safe);
        model.addAttribute(WORKFLOW_FORM, form);
        model.addAttribute(BindingResult.MODEL_KEY_PREFIX + WORKFLOW_FORM, safe);
        model.addAttribute("workflowId", id);
        model.addAttribute("needsLoginPassword", !login.loginRequired());
        model.addAttribute("errors", List.copyOf(errors));
        return VIEW;
    }

    /** Never retains rejected values, formatter arguments, causes, or user-selected message codes. */
    private static List<ErrorView> copySafely(BindingResult original, BindingResult safe) {
        List<ErrorView> errors = new ArrayList<>();
        for (var error : original.getAllErrors()) {
            String field = error instanceof FieldError f && safeField(f.getField()) ? f.getField() : null;
            String message = safeMessage(error);
            if (field == null) safe.reject(INVALID, message);
            else safe.addError(new FieldError(WORKFLOW_FORM, field, null, false, new String[]{INVALID}, null, message));
            errors.add(new ErrorView(field == null ? "workflow-form" : fieldId(field), message));
        }
        return errors;
    }

    private static String safeMessage(ObjectError error) {
        if (error instanceof FieldError f && f.isBindingFailure()) return "Choose a valid value for this field.";
        String message = error.getDefaultMessage();
        return message == null ? "Check the form and try again." : message;
    }

    /** Domain diagnostics choose a field only; their text/arguments are never copied to the view. */
    private static String validationField(WorkflowException failure, WorkflowForm form) {
        if (failure.stage() == WorkflowException.Stage.BUILD) return "templateMode";
        String detail = failure.detail();
        String field = callField(detail, form);
        if (field == null) field = variableField(detail, form);
        if (field == null) field = scalarField(detail);
        return field;
    }

    private static String callField(String detail, WorkflowForm form) {
        for (int i = 0; i < form.calls.size(); i++) {
            String name = form.calls.get(i).name;
            String prefix = "calls[" + i + "].";
            if (name == null || !CALL_NAME.matcher(name).matches()) {
                if (detail.equals("invalid call name")) return prefix + "name";
                continue;
            }
            if (detail.equals("duplicate call name: " + name)) return prefix + "name";
            if (!detail.startsWith("call " + name + ": ")) continue;
            if (detail.contains("fetch URL")) return prefix + "urlMode";
            if (detail.contains("header")) return prefix + "headersMode";
            if (detail.endsWith("invalid scope")) return prefix + "scope";
            return prefix + "name";
        }
        return null;
    }

    private static String variableField(String detail, WorkflowForm form) {
        for (int i = 0; i < form.calls.size(); i++) {
            String found = variableRows(detail, form.calls.get(i).variables, "calls[" + i + "].variables[");
            if (found != null) return found;
        }
        return variableRows(detail, form.entryVariables, "entryVariables[");
    }

    private static String variableRows(String detail, List<WorkflowForm.VariableRow> rows, String prefix) {
        for (int i = 0; i < rows.size(); i++) {
            var row = rows.get(i);
            if (row.name == null || !VARIABLE_NAME.matcher(row.name).matches()) return prefix + i + "].name";
            if (detail.contains("mapping " + row.name + " pointer")) return prefix + i + "].pointer";
            if (detail.equals("duplicate mapping name: " + row.name)) return prefix + i + "].name";
        }
        return null;
    }

    /** Order matters: a pointer-shaped display message must match before its broader "entry subtitle" check. */
    private static String scalarField(String detail) {
        if (detail.contains("media type")) return "mimeType";
        if (detail.contains("artwork URL")) return "artwork";
        if (detail.equals("invalid name")) return "name";
        if (detail.contains("kind must")) return "kind";
        if (detail.contains("tile title")) return "title";
        if (detail.contains("tile subtitle")) return "subtitle";
        if (detail.contains("entry source")) return "entryCall";
        if (detail.contains("array pointer")) return "arrayPointer";
        if (detail.contains("entry ID pointer")) return "idPointer";
        if (detail.contains("entry title pointer")) return "titlePointer";
        if (detail.contains("entry subtitle pointer")) return "subtitlePointer";
        if (detail.contains("entry subtitle")) return "subtitleVariable";
        if (detail.contains("entry artwork pointer")) return "artworkPointer";
        if (detail.contains("entry artwork")) return "artworkVariable";
        return null;
    }

    private static boolean safeField(String field) {
        return SCALARS.contains(field) || CALL.matcher(field).matches() || HEADER.matcher(field).matches()
                || VARIABLE.matcher(field).matches();
    }
    private static String fieldId(String field) { return "workflow-" + field.replace("[", "-").replace("].", "-"); }
    private static boolean contiguous(Set<Integer> rows) {
        for (int i = 0; i < rows.size(); i++) if (!rows.contains(i)) return false;
        return true;
    }
    private static long revision(HttpServletRequest request) {
        var values = request.getParameterValues("expectedRevision");
        if (values == null || values.length != 1) throw new IllegalArgumentException();
        long revision = Long.parseLong(values[0]);
        if (revision < 1) throw new IllegalArgumentException();
        return revision;
    }
    private void authenticate(HttpServletRequest request) { if (!login.isAuthenticated(request)) throw new LoginRequiredException(); }
    private static void privateResponse(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store"); response.setHeader("Referrer-Policy", "same-origin");
    }
}
