package dev.andre.homecontrol.sources.workflows;

import dev.andre.homecontrol.security.LoginService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.validation.DirectFieldBindingResult;

import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Which form field a save failure points at, and what the editor shows for it. */
class WorkflowSetupControllerErrorsTest {

    private final WorkflowStore store = mock(WorkflowStore.class);
    private final LoginService login = mock(LoginService.class);
    private final WorkflowSetupController controller =
            new WorkflowSetupController(store, login, mock(WorkflowTestService.class));

    @BeforeEach
    void loggedIn() {
        when(login.isAuthenticated(any(HttpServletRequest.class))).thenReturn(true);
    }

    /** A new single-tile workflow that passes validation, with mappings A and C. */
    private static WorkflowForm validNewForm() {
        WorkflowForm form = WorkflowForm.from(new WorkflowDefinition(1, "w-0123456789ab", 1,
                WorkflowFixtures.single(URI.create("https://api.example/catalog"))));
        form.urlMode = form.templateMode = form.headersMode = WorkflowForm.Replacement.REPLACE;
        form.url = "https://api.example/catalog";
        form.template = "https://media.example/play?id={A}&token={C}";
        return form;
    }

    @SuppressWarnings("unchecked")
    private List<WorkflowSetupController.ErrorView> save(WorkflowForm form, DirectFieldBindingResult binding) {
        var model = new ExtendedModelMap();
        String view = controller.create(form, binding, new MockHttpServletRequest(), new MockHttpServletResponse(), model);
        assertThat(view).isEqualTo("workflow-editor");
        return (List<WorkflowSetupController.ErrorView>) model.getAttribute("errors");
    }

    private List<WorkflowSetupController.ErrorView> storeRejects(WorkflowException failure) {
        when(store.create(any(), any(), any(), any())).thenThrow(failure);
        WorkflowForm form = validNewForm();
        return save(form, new DirectFieldBindingResult(form, "workflowForm"));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            invalid fetch URL                   | workflow-url
            invalid media type                  | workflow-mimeType
            invalid artwork URL                 | workflow-artwork
            invalid name                        | workflow-name
            kind must be video or audio         | workflow-kind
            invalid tile title                  | workflow-title
            invalid tile subtitle               | workflow-subtitle
            invalid array pointer               | workflow-arrayPointer
            invalid entry ID pointer            | workflow-idPointer
            invalid entry title pointer         | workflow-titlePointer
            invalid entry subtitle pointer      | workflow-subtitlePointer
            invalid entry artwork pointer       | workflow-artworkPointer
            invalid mapping C pointer           | workflow-variables-1-pointer
            invalid mapping scope: C            | workflow-variables-1-scope
            duplicate mapping name: C           | workflow-variables-1-name
            too many headers                    | workflow-headersMode
            could not allocate workflow ID      | workflow-form
            """)
    void aValidationFailurePointsAtItsField(String detail, String target) {
        assertThat(storeRejects(new WorkflowException(WorkflowException.Stage.WORKFLOW, detail)))
                .extracting(WorkflowSetupController.ErrorView::target)
                .containsExactly(target);
    }

    @Test
    void aTemplateFailurePointsAtTheTemplateChoice() {
        assertThat(storeRejects(new WorkflowException(WorkflowException.Stage.BUILD, "invalid media URL template")))
                .extracting(WorkflowSetupController.ErrorView::target)
                .containsExactly("workflow-templateMode");
    }

    @Test
    void aMappingWithAnInvalidNameIsBlamedBeforeTheDetailIsRead() {
        WorkflowForm form = validNewForm();
        form.variables.getFirst().name = "1st";

        assertThat(save(form, new DirectFieldBindingResult(form, "workflowForm")))
                .extracting(WorkflowSetupController.ErrorView::target)
                .containsExactly("workflow-variables-0-name");
    }

    @Test
    void anErrorWithoutTextStillShowsAMessage() {
        WorkflowForm form = validNewForm();
        var binding = new DirectFieldBindingResult(form, "workflowForm");
        binding.reject("anything");

        assertThat(save(form, binding))
                .containsExactly(new WorkflowSetupController.ErrorView("workflow-form", "Check the form and try again."));
    }
}
