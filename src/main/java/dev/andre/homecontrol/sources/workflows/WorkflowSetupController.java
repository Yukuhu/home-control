package dev.andre.homecontrol.sources.workflows;

import dev.andre.homecontrol.security.LoginRequiredException;
import dev.andre.homecontrol.security.LoginService;
import dev.andre.homecontrol.security.PasswordRejectedException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.validation.DirectFieldBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

@Controller
@ConditionalOnProperty(name = "home-control.workflows.enabled", havingValue = "true", matchIfMissing = true)
public final class WorkflowSetupController {
    private static final String VIEW = "workflow-editor";
    private static final String BASE = "/setup/workflows";
    private static final Set<String> SCALARS = Set.of("name", "enabled", "mode", "kind", "title", "subtitle", "artwork",
            "arrayPointer", "idPointer", "titlePointer", "subtitlePointer", "artworkPointer",
            "includeSubtitlePointer", "includeArtworkPointer", "urlMode", "url", "templateMode", "template", "mimeType",
            "headersMode", "expectedRevision", "loginPassword", "loginPasswordConfirmation");
    private static final Set<String> CHECKBOXES = Set.of("enabled", "includeSubtitlePointer", "includeArtworkPointer");
    private static final Pattern VARIABLE = Pattern.compile("variables\\[(0|[1-9][0-9]?)\\]\\.(name|scope|pointer|sensitive)");
    private static final Pattern HEADER = Pattern.compile("headers\\[(0|[1-9][0-9]?)\\]\\.(name|value)");
    public record ErrorView(String target, String message) {}
    private final WorkflowStore store;
    private final LoginService login;
    private final WorkflowTestService tests;

    public WorkflowSetupController(WorkflowStore store, LoginService login, WorkflowTestService tests) {
        this.store = store; this.login = login; this.tests = tests;
    }

    @ModelAttribute("workflowForm")
    WorkflowForm emptyForm(HttpServletRequest request) {
        WorkflowForm form = new WorkflowForm();
        if (!request.getRequestURI().equals(request.getContextPath() + BASE)
                && !request.getRequestURI().endsWith("/new")) {
            form.urlMode = form.templateMode = form.headersMode = WorkflowForm.Replacement.KEEP;
        }
        return form;
    }

    @InitBinder("workflowForm")
    void bindWorkflow(WebDataBinder binder, HttpServletRequest request) {
        binder.initDirectFieldAccess();
        binder.setAutoGrowCollectionLimit(32);
        binder.setFieldDefaultPrefix(null); // Never accept Spring's !field client-selected defaults.
        Set<String> allowed = new HashSet<>(SCALARS);
        Set<Integer> variables = new HashSet<>(), headers = new HashSet<>();
        boolean invalid = false;
        for (var parameter : request.getParameterMap().entrySet()) {
            String raw = parameter.getKey();
            String field = raw.startsWith("_") ? raw.substring(1) : raw;
            boolean marker = raw.startsWith("_");
            var variable = VARIABLE.matcher(field);
            var header = HEADER.matcher(field);
            boolean recognized = false;
            if (SCALARS.contains(field) && (!marker || CHECKBOXES.contains(field))) {
                recognized = true;
            } else if (variable.matches() && (!marker || variable.group(2).equals("sensitive"))) {
                int index = Integer.parseInt(variable.group(1));
                if (index < 32) { variables.add(index); allowed.add(field); recognized = true; }
            } else if (!marker && header.matches()) {
                int index = Integer.parseInt(header.group(1));
                if (index < 16) { headers.add(index); allowed.add(field); recognized = true; }
            }
            if (!recognized || parameter.getValue().length != 1) invalid = true;
        }
        if (!contiguous(variables)) { allowed.removeIf(field -> field.startsWith("variables[")); invalid = true; }
        if (!contiguous(headers)) { allowed.removeIf(field -> field.startsWith("headers[")); invalid = true; }
        binder.setAllowedFields(allowed.toArray(String[]::new));
        if (invalid) request.setAttribute("workflowInvalidFields", Boolean.TRUE);
    }

    @GetMapping(BASE + "/new")
    public String createEditor(Model model, HttpServletResponse response) {
        privateResponse(response);
        return editor(null, new WorkflowForm(), null, model);
    }

    @GetMapping(BASE + "/{id}")
    public String savedEditor(@PathVariable String id, Model model, HttpServletResponse response) {
        privateResponse(response);
        var saved = store.find(id).orElse(null);
        if (saved == null) return missing(model, response);
        return editor(id, WorkflowForm.from(saved), null, model);
    }

    @PostMapping(BASE)
    public String create(@ModelAttribute("workflowForm") WorkflowForm form, BindingResult binding,
                         HttpServletRequest request, HttpServletResponse response, Model model) {
        return save(null, form, binding, request, response, model);
    }

    @PostMapping(BASE + "/{id}")
    public String update(@PathVariable String id, @ModelAttribute("workflowForm") WorkflowForm form, BindingResult binding,
                         HttpServletRequest request, HttpServletResponse response, Model model) {
        return save(id, form, binding, request, response, model);
    }

    private String save(String id, WorkflowForm form, BindingResult binding, HttpServletRequest request,
                        HttpServletResponse response, Model model) {
        privateResponse(response);
        // Spring also adds the route's id to property values; it is not a client-editable form field.
        boolean suppressed = java.util.Arrays.stream(binding.getSuppressedFields())
                .anyMatch(field -> request.getParameterMap().containsKey(field));
        if (suppressed || Boolean.TRUE.equals(request.getAttribute("workflowInvalidFields"))) {
            binding.reject("invalid", "Some submitted fields are invalid. Check the form and try again.");
        }
        try {
            authenticate(request);
            WorkflowDefinition saved = id == null ? null : store.find(id).orElse(null);
            if (id != null && saved == null) {
                response.setStatus(404); binding.reject("missing", "This workflow no longer exists.");
            } else if (saved != null && saved.revision() != form.expectedRevision) {
                response.setStatus(409); binding.reject("changed", "Workflow changed; reopen this item before saving.");
            }
            if (binding.hasErrors()) return editor(id, form, binding, model);
            var draft = form.toDraft(saved);
            WorkflowValidator.validate(draft);
            var changed = id == null ? store.create(draft, form.loginPassword, form.loginPasswordConfirmation, request)
                    : store.update(id, form.expectedRevision, draft, request);
            form.clearSecrets();
            return "redirect:" + BASE + "/" + changed.id();
        } catch (LoginRequiredException failure) {
            response.setStatus(401); binding.reject("login", "Log in again before changing workflows.");
        } catch (PasswordRejectedException failure) {
            binding.rejectValue("loginPassword", "password", "Passwords must match and contain 10–1024 characters.");
        } catch (WorkflowException failure) {
            if ("Workflow: Workflow changed; reopen this item".equals(failure.getMessage())) {
                response.setStatus(409);
                binding.reject("changed", "Workflow changed; reopen this item before saving.");
            } else {
                String field = validationField(failure, form);
                if (field == null) binding.reject("settings", "Check the source URL, media template, fields and headers.");
                else binding.rejectValue(field, "settings", "Check this field's format and limits.");
            }
        } catch (RuntimeException failure) {
            binding.reject("save", "Could not save the workflow. Check the settings and try again.");
        }
        return editor(id, form, binding, model);
    }

    @PostMapping(BASE + "/{id}/test")
    public String test(@PathVariable String id, HttpServletRequest request, HttpServletResponse response, Model model) {
        privateResponse(response);
        WorkflowForm form = new WorkflowForm();
        var binding = new DirectFieldBindingResult(form, "workflowForm");
        try {
            authenticate(request);
            var saved = store.find(id).orElse(null);
            if (saved == null) return missing(model, response);
            form = WorkflowForm.from(saved);
            binding = new DirectFieldBindingResult(form, "workflowForm");
            var result = tests.test(id, revision(request), request);
            model.addAttribute("testResult", result);
        } catch (LoginRequiredException failure) {
            response.setStatus(401); binding.reject("login", "Log in again before testing workflows.");
        } catch (WorkflowException failure) {
            response.setStatus(409); binding.reject("changed", "Workflow changed; reopen this item before testing.");
        } catch (RuntimeException failure) {
            binding.reject("test", "Could not test this workflow. Reopen it and try again.");
        }
        return editor(id, form, binding, model);
    }

    @PostMapping(BASE + "/{id}/enabled")
    public String enabled(@PathVariable String id, HttpServletRequest request, HttpServletResponse response, Model model) {
        return mutate(id, request, response, model, () -> {
            String value = request.getParameter("enabled");
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
        } catch (LoginRequiredException failure) {
            response.setStatus(401);
        } catch (IllegalArgumentException failure) {
            response.setStatus(400);
        } catch (RuntimeException failure) {
            response.setStatus(409);
        }
        WorkflowForm form = id == null ? new WorkflowForm() : store.find(id).map(WorkflowForm::from).orElseGet(WorkflowForm::new);
        var binding = new DirectFieldBindingResult(form, "workflowForm");
        binding.reject("action", "Could not change this workflow. Reopen Setup and try again.");
        return editor(id, form, binding, model);
    }

    private String missing(Model model, HttpServletResponse response) {
        response.setStatus(404);
        WorkflowForm form = new WorkflowForm();
        var binding = new DirectFieldBindingResult(form, "workflowForm");
        binding.reject("missing", "This workflow no longer exists. Return to Setup.");
        return editor(null, form, binding, model);
    }

    private String editor(String id, WorkflowForm form, BindingResult original, Model model) {
        form.clearSecrets();
        var safe = new DirectFieldBindingResult(form, "workflowForm");
        List<ErrorView> errors = new ArrayList<>();
        if (original != null) for (var error : original.getAllErrors()) {
            String field = error instanceof FieldError f && safeField(f.getField()) ? ((FieldError) error).getField() : null;
            String message = (error instanceof FieldError f && f.isBindingFailure()) ? "Choose a valid value for this field." : error.getDefaultMessage();
            // Never retain rejected values, formatter arguments, causes, or user-selected message codes.
            if (field == null) safe.reject("invalid", message);
            else safe.addError(new FieldError("workflowForm", field, null, false, new String[]{"invalid"}, null, message));
            errors.add(new ErrorView(field == null ? "workflow-form" : fieldId(field), message));
        }
        model.addAttribute("workflowForm", form);
        model.addAttribute(BindingResult.MODEL_KEY_PREFIX + "workflowForm", safe);
        model.addAttribute("workflowId", id);
        model.addAttribute("needsLoginPassword", !login.loginRequired());
        model.addAttribute("errors", List.copyOf(errors));
        return VIEW;
    }
    /** Domain diagnostics choose a field only; their text/arguments are never copied to the view. */
    private static String validationField(WorkflowException failure, WorkflowForm form) {
        if (failure.stage() == WorkflowException.Stage.BUILD) return "templateMode";
        String detail = failure.getMessage();
        if (detail.contains("fetch URL")) return "url";
        if (detail.contains("media type")) return "mimeType";
        if (detail.contains("artwork URL")) return "artwork";
        if (detail.equals("Workflow: invalid name")) return "name";
        if (detail.contains("kind must")) return "kind";
        if (detail.contains("tile title")) return "title";
        if (detail.contains("tile subtitle")) return "subtitle";
        if (detail.contains("array pointer")) return "arrayPointer";
        if (detail.contains("entry ID pointer")) return "idPointer";
        if (detail.contains("entry title pointer")) return "titlePointer";
        if (detail.contains("entry subtitle pointer")) return "subtitlePointer";
        if (detail.contains("entry artwork pointer")) return "artworkPointer";
        for (int i = 0; i < form.variables.size(); i++) {
            var row = form.variables.get(i);
            if (row.name == null || !row.name.matches("[A-Za-z][A-Za-z0-9_]{0,31}")) return "variables[" + i + "].name";
            if (detail.contains("mapping " + row.name + " pointer")) return "variables[" + i + "].pointer";
            if (detail.equals("Workflow: invalid mapping scope: " + row.name)) return "variables[" + i + "].scope";
            if (detail.equals("Workflow: duplicate mapping name: " + row.name)) return "variables[" + i + "].name";
        }
        if (detail.contains("header")) return "headersMode";
        return null;
    }

    private static boolean safeField(String field) { return SCALARS.contains(field) || VARIABLE.matcher(field).matches() || HEADER.matcher(field).matches(); }
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
