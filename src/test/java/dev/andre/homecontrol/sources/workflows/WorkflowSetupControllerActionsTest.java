package dev.andre.homecontrol.sources.workflows;

import dev.andre.homecontrol.security.LoginService;
import dev.andre.homecontrol.testsupport.FakeLoginContext;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.validation.DirectFieldBindingResult;

import java.net.URI;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** What the editor shows when saving, testing, switching or removing a workflow cannot be done. */
class WorkflowSetupControllerActionsTest {

    private static final String ID = "w-0123456789ab";

    private final WorkflowStore store = mock(WorkflowStore.class);
    private final WorkflowTestService tests = mock(WorkflowTestService.class);
    private final WorkflowSetupController controller =
            new WorkflowSetupController(store, mock(LoginService.class), tests);
    private final MockHttpServletResponse response = new MockHttpServletResponse();
    private final ExtendedModelMap model = new ExtendedModelMap();

    private static WorkflowDefinition saved() {
        return new WorkflowDefinition(WorkflowDefinition.SCHEMA_VERSION, ID, 3,
                WorkflowFixtures.single(URI.create("https://api.example/catalog")));
    }

    private static MockHttpServletRequest withRevision(String revision) {
        var request = new MockHttpServletRequest();
        if (revision != null) request.addParameter("expectedRevision", revision);
        return request;
    }

    @SuppressWarnings("unchecked")
    private List<WorkflowSetupController.ErrorView> errors() {
        return (List<WorkflowSetupController.ErrorView>) model.getAttribute("errors");
    }

    @Test
    void savingAWorkflowThatWasRemovedMeanwhileIsNotFound() {
        when(store.find(ID)).thenReturn(Optional.empty());
        var form = WorkflowForm.from(saved());

        String view = controller.update(ID, form, new DirectFieldBindingResult(form, "workflowForm"),
                new MockHttpServletRequest(), FakeLoginContext.loggedInBrowser(), response, model);

        assertThat(view).isEqualTo("workflow-editor");
        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(errors()).extracting(WorkflowSetupController.ErrorView::message)
                .containsExactly("This workflow no longer exists.");
        verify(store, never()).update(anyString(), anyLong(), any(), any());
    }

    @Test
    void anUnexpectedSaveFailureShowsOnlyAGenericMessage() {
        when(store.find(ID)).thenReturn(Optional.of(saved()));
        when(store.update(eq(ID), eq(3L), any(), any())).thenThrow(new IllegalStateException("disk detail"));
        var form = WorkflowForm.from(saved());

        controller.update(ID, form, new DirectFieldBindingResult(form, "workflowForm"), new MockHttpServletRequest(),
                FakeLoginContext.loggedInBrowser(), response, model);

        assertThat(errors()).extracting(WorkflowSetupController.ErrorView::message)
                .containsExactly("Could not save the workflow. Check the settings and try again.");
    }

    @Test
    void testingAWorkflowThatIsGoneIsNotFound() {
        when(store.find(ID)).thenReturn(Optional.empty());

        controller.test(ID, withRevision("3"), FakeLoginContext.loggedInBrowser(), response, model);

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(errors()).extracting(WorkflowSetupController.ErrorView::message)
                .containsExactly("This workflow no longer exists. Return to Setup.");
        verify(tests, never()).test(anyString(), anyLong(), any());
    }

    @Test
    void testingNeedsALogin() {
        controller.test(ID, withRevision("3"), FakeLoginContext.loggedOutBrowser(), response, model);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(errors()).extracting(WorkflowSetupController.ErrorView::message)
                .containsExactly("Log in again before testing workflows.");
        verify(tests, never()).test(anyString(), anyLong(), any());
    }

    @Test
    void testingAChangedWorkflowIsAConflict() {
        when(store.find(ID)).thenReturn(Optional.of(saved()));
        when(tests.test(eq(ID), eq(2L), any())).thenThrow(new WorkflowException(WorkflowException.Stage.WORKFLOW,
                "Workflow changed; reopen this item"));

        controller.test(ID, withRevision("2"), FakeLoginContext.loggedInBrowser(), response, model);

        assertThat(response.getStatus()).isEqualTo(409);
        assertThat(errors()).extracting(WorkflowSetupController.ErrorView::message)
                .containsExactly("Workflow changed; reopen this item before testing.");
    }

    @Test
    void aTestWithoutAUsableRevisionOrThatFailsUnexpectedlySaysToReopen() {
        when(store.find(ID)).thenReturn(Optional.of(saved()));
        controller.test(ID, withRevision(null), FakeLoginContext.loggedInBrowser(), response, model);
        assertThat(errors()).extracting(WorkflowSetupController.ErrorView::message)
                .containsExactly("Could not test this workflow. Reopen it and try again.");

        when(tests.test(eq(ID), eq(3L), any())).thenThrow(new IllegalStateException("internal detail"));
        var second = new ExtendedModelMap();
        controller.test(ID, withRevision("3"), FakeLoginContext.loggedInBrowser(), new MockHttpServletResponse(), second);
        assertThat(second.getAttribute("errors")).asString()
                .contains("Could not test this workflow. Reopen it and try again.")
                .doesNotContain("internal detail");
    }

    @Test
    void switchingNeedsTrueOrFalseAndAUsableRevision() {
        when(store.find(ID)).thenReturn(Optional.of(saved()));
        var maybe = withRevision("3");
        maybe.addParameter("enabled", "maybe");

        controller.enabled(ID, maybe, FakeLoginContext.loggedInBrowser(), response, model);
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(errors()).extracting(WorkflowSetupController.ErrorView::message)
                .containsExactly("Could not change this workflow. Reopen Setup and try again.");

        var noRevision = withRevision("0");
        noRevision.addParameter("enabled", "true");
        var second = new MockHttpServletResponse();
        controller.enabled(ID, noRevision, FakeLoginContext.loggedInBrowser(), second, new ExtendedModelMap());
        assertThat(second.getStatus()).isEqualTo(400);
        verify(store, never()).setEnabled(anyString(), anyLong(), anyBoolean(), any());
    }

    @Test
    void aSwitchTheStoreRefusesIsAConflictEvenForAWorkflowThatIsGone() {
        when(store.find(ID)).thenReturn(Optional.empty());
        var request = withRevision("3");
        request.addParameter("enabled", "false");
        doThrow(new IllegalStateException("revision mismatch")).when(store).setEnabled(eq(ID), eq(3L), eq(false), any());

        String view = controller.enabled(ID, request, FakeLoginContext.loggedInBrowser(), response, model);

        assertThat(view).isEqualTo("workflow-editor");
        assertThat(response.getStatus()).isEqualTo(409);
        assertThat(model.getAttribute("workflowId")).isEqualTo(ID);
    }

    @Test
    void removingAnInvalidWorkflowNeedsALogin() {
        String view = controller.removeInvalid("token", FakeLoginContext.loggedOutBrowser(), response, model);

        assertThat(view).isEqualTo("workflow-editor");
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(model.getAttribute("workflowId")).isNull();
        verify(store, never()).removeInvalid(anyString(), any());
    }

    @Test
    void aSwitchThatSucceedsReturnsToSetup() {
        var request = withRevision("3");
        request.addParameter("enabled", "true");

        assertThat(controller.enabled(ID, request, FakeLoginContext.loggedInBrowser(), response, model))
                .isEqualTo("redirect:/setup#workflows");
        verify(store).setEnabled(eq(ID), eq(3L), eq(true), any());
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    }
}
