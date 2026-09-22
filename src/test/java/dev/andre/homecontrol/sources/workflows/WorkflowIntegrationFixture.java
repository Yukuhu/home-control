package dev.andre.homecontrol.sources.workflows;

import dev.andre.homecontrol.security.LoginService;
import dev.andre.homecontrol.storage.SecretStore;
import org.springframework.mock.web.MockHttpServletRequest;
import java.net.URI;
import java.security.SecureRandom;
import java.util.Optional;
import static org.mockito.Mockito.*;

/** Real revision/store semantics with only persistence and login at the boundary replaced. */
final class WorkflowIntegrationFixture {
    static final String ID = "w-0123456789ab";
    final MockHttpServletRequest request = new MockHttpServletRequest();
    final WorkflowStore store;
    final WorkflowDefinition definition;

    WorkflowIntegrationFixture(boolean generated) {
        this(generated ? WorkflowFixtures.generated() : WorkflowFixtures.single(URI.create("https://api.example/feed")));
    }

    WorkflowIntegrationFixture(WorkflowDraft draft) {
        definition = new WorkflowDefinition(1, ID, 1, draft);
        var secrets = mock(SecretStore.class);
        var login = mock(LoginService.class);
        when(secrets.names()).thenReturn(java.util.Set.of("workflow." + ID));
        when(secrets.secret("workflow." + ID)).thenReturn(Optional.of(new WorkflowCodec().encode(definition)));
        when(login.isAuthenticated(request)).thenReturn(true);
        store = new WorkflowStore(secrets, login, new WorkflowCodec(), event -> {}, new SecureRandom());
    }
}
