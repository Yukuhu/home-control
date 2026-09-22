package dev.andre.homecontrol.sources.workflows;

import dev.andre.homecontrol.security.LoginService;
import dev.andre.homecontrol.web.SetupController;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import java.util.List;

@ControllerAdvice(assignableTypes = SetupController.class)
@ConditionalOnProperty(name = "home-control.workflows.enabled", havingValue = "true", matchIfMissing = true)
public final class WorkflowSetupAdvice {
    public record Summary(String id, String name, WorkflowDraft.Mode mode, boolean enabled, long revision) {}
    public record Problem(String token, String message) {}
    public record View(List<Summary> definitions, List<Problem> problems, boolean needsLoginPassword) {}
    private final ObjectProvider<WorkflowStore> store;
    private final ObjectProvider<LoginService> login;
    public WorkflowSetupAdvice(ObjectProvider<WorkflowStore> store, ObjectProvider<LoginService> login) {
        this.store = store; this.login = login;
    }
    @ModelAttribute("workflows")
    public View workflows() {
        var storage = store.getIfAvailable();
        var authentication = login.getIfAvailable();
        return new View(storage == null ? List.of() : storage.all().stream()
                .map(d -> new Summary(d.id(), d.draft().name(), d.draft().mode(), d.draft().enabled(), d.revision())).toList(),
                storage == null ? List.of() : storage.problems().keySet().stream().sorted()
                        .map(id -> new Problem(WorkflowRecoveryToken.encode(id), "Stored definition cannot be loaded")).toList(),
                authentication == null || !authentication.loginRequired());
    }
}
