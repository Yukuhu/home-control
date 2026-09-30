package dev.andre.homecontrol.sources.workflows;

import dev.andre.homecontrol.config.ConditionalOnModule;
import dev.andre.homecontrol.config.Module;
import dev.andre.homecontrol.config.SetupSection;
import dev.andre.homecontrol.security.LoginService;
import java.net.URI;
import org.springframework.beans.factory.ObjectProvider;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnModule(Module.WORKFLOWS)
public final class WorkflowSetupSection implements SetupSection {
    public record Summary(String id, String name, WorkflowDraft.Mode mode, boolean enabled, long revision) {}
    public record Problem(String token, String message) {}
    public record View(List<Summary> definitions, List<Problem> problems, boolean needsLoginPassword) {}
    private final ObjectProvider<WorkflowStore> store;
    private final ObjectProvider<LoginService> login;
    public WorkflowSetupSection(ObjectProvider<WorkflowStore> store, ObjectProvider<LoginService> login) {
        this.store = store; this.login = login;
    }
    @Override
    public String id() {
        return "workflows";
    }

    @Override
    public String title() {
        return "Workflows";
    }

    @Override
    public String fragment() {
        return "fragments/workflows-setup";
    }

    @Override
    public Group group() {
        return Group.CONTENT_SOURCES;
    }

    @Override
    public int order() {
        return 50;
    }

    @Override
    public View view(URI baseUrl) {
        var storage = store.getIfAvailable();
        var authentication = login.getIfAvailable();
        return new View(storage == null ? List.of() : storage.all().stream()
                .map(d -> new Summary(d.id(), d.draft().name(), d.draft().mode(), d.draft().enabled(), d.revision())).toList(),
                storage == null ? List.of() : storage.problems().keySet().stream().sorted()
                        .map(id -> new Problem(WorkflowRecoveryToken.encode(id), "Stored definition cannot be loaded")).toList(),
                authentication == null || !authentication.loginRequired());
    }
}
