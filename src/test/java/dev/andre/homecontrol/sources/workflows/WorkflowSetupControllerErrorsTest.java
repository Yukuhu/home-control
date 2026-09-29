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

    /** A new single-tile workflow that passes validation: one call, main, with values A and C. */
    private static WorkflowForm validNewForm() {
        return replaced(WorkflowForm.from(new WorkflowDefinition(WorkflowDefinition.SCHEMA_VERSION, "w-0123456789ab", 1,
                WorkflowFixtures.single(URI.create("https://api.example/catalog")))));
    }

    /** A new generated workflow that passes validation: call main with C, and the entry field A. */
    private static WorkflowForm validGeneratedForm() {
        return replaced(WorkflowForm.from(new WorkflowDefinition(WorkflowDefinition.SCHEMA_VERSION, "w-0123456789ab", 1,
                WorkflowFixtures.generated())));
    }

    private static WorkflowForm replaced(WorkflowForm form) {
        form.templateMode = WorkflowForm.Replacement.REPLACE;
        form.template = "https://media.example/play?id={A}&token={C}";
        for (var call : form.calls) {
            call.urlMode = call.headersMode = WorkflowForm.Replacement.REPLACE;
            call.savedName = "";
            call.url = "https://api.example/catalog";
        }
        return form;
    }

    @SuppressWarnings("unchecked")
    private List<WorkflowSetupController.ErrorView> save(WorkflowForm form, DirectFieldBindingResult binding) {
        var model = new ExtendedModelMap();
        String view = controller.create(form, binding, new MockHttpServletRequest(), new MockHttpServletResponse(), model);
        assertThat(view).isEqualTo("workflow-editor");
        return (List<WorkflowSetupController.ErrorView>) model.getAttribute("errors");
    }

    private List<WorkflowSetupController.ErrorView> storeRejects(WorkflowForm form, WorkflowException failure) {
        when(store.create(any(), any(), any(), any())).thenThrow(failure);
        return save(form, new DirectFieldBindingResult(form, "workflowForm"));
    }

    private List<WorkflowSetupController.ErrorView> storeRejects(WorkflowException failure) {
        return storeRejects(validNewForm(), failure);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            call main: invalid fetch URL                                   | workflow-calls-0-urlMode
            call main: invalid header name                                 | workflow-calls-0-headersMode
            call main: invalid header value: Authorization                 | workflow-calls-0-headersMode
            call main: too many headers                                    | workflow-calls-0-headersMode
            call main: invalid scope                                       | workflow-calls-0-scope
            call main: nothing uses this call                              | workflow-calls-0-name
            call main: uses {x}, which is defined by a call further down  | workflow-calls-0-name
            call main: uses {x}, which no call defines                     | workflow-calls-0-name
            duplicate call name: main                                      | workflow-calls-0-name
            invalid media type                                             | workflow-mimeType
            invalid artwork URL                                            | workflow-artwork
            invalid name                                                   | workflow-name
            kind must be video or audio                                    | workflow-kind
            invalid tile title                                             | workflow-title
            invalid tile subtitle                                          | workflow-subtitle
            the entry source must be a shared call                         | workflow-entryCall
            invalid array pointer                                          | workflow-arrayPointer
            invalid entry ID pointer                                       | workflow-idPointer
            invalid entry title pointer                                    | workflow-titlePointer
            invalid entry subtitle pointer                                 | workflow-subtitlePointer
            unknown entry subtitle variable                                | workflow-subtitleVariable
            entry subtitle variable {C} is marked sensitive                | workflow-subtitleVariable
            invalid entry artwork pointer                                  | workflow-artworkPointer
            unknown entry artwork variable                                 | workflow-artworkVariable
            invalid mapping C pointer                                      | workflow-calls-0-variables-1-pointer
            duplicate mapping name: C                                      | workflow-calls-0-variables-1-name
            call main: uses {headerToken}, which no call defines           | workflow-calls-0-name
            call main: uses the entry value {A}; make it a per-entry call  | workflow-calls-0-scope
            call main: invalid header name                                 | workflow-calls-0-headersMode
            call main: headers are required                                | workflow-calls-0-headersMode
            call main: header is not allowed: Host                         | workflow-calls-0-headersMode
            call main: duplicate header: X-A                               | workflow-calls-0-headersMode
            definition exceeds storage limit                               | workflow-form
            too many calls                                                 | workflow-form
            could not allocate workflow ID                                 | workflow-form
            """)
    void aValidationFailurePointsAtItsField(String detail, String target) {
        assertThat(storeRejects(new WorkflowException(WorkflowException.Stage.WORKFLOW, detail)))
                .extracting(WorkflowSetupController.ErrorView::target)
                .containsExactly(target);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            call main: uses {x}, which is defined by a call further down  | This call uses a value no call above it provides.
            call main: uses {x}, which no call defines                     | This call uses a value that no call defines.
            call main: uses the entry value {A}; make it a per-entry call  | This call uses an entry value; set it to run once per entry.
            call main: nothing uses this call                              | Nothing uses this call. Use one of its values or remove it.
            entry subtitle variable {C} is marked sensitive                | A tile cannot show a value marked sensitive.
            the entry source must be a shared call                         | Choose a call that runs once as the source of entries.
            definition exceeds storage limit                               | This workflow is too large to save; remove calls, headers or variables.
            call main: invalid fetch URL                                   | Check this field's format and limits.
            could not allocate workflow ID                                 | Check the calls, media template, fields and headers.
            """)
    void aValidationFailureShowsLocallyWrittenGuidance(String detail, String message) {
        assertThat(storeRejects(new WorkflowException(WorkflowException.Stage.WORKFLOW, detail)))
                .extracting(WorkflowSetupController.ErrorView::message)
                .containsExactly(message);
    }

    @Test
    void anEntryFieldFailurePointsAtTheEntryFieldRow() {
        assertThat(storeRejects(validGeneratedForm(),
                new WorkflowException(WorkflowException.Stage.WORKFLOW, "invalid mapping A pointer")))
                .extracting(WorkflowSetupController.ErrorView::target)
                .containsExactly("workflow-entryVariables-0-pointer");
    }

    @Test
    void aFailureOfTheSecondCallPointsAtThatCall() {
        WorkflowForm form = validNewForm();
        var second = new WorkflowForm.CallRow();
        second.name = "stream";
        form.calls.add(second);
        assertThat(storeRejects(form, new WorkflowException(WorkflowException.Stage.WORKFLOW, "call stream: invalid fetch URL")))
                .extracting(WorkflowSetupController.ErrorView::target)
                .containsExactly("workflow-calls-1-urlMode");
    }

    @Test
    void aTemplateFailurePointsAtTheTemplateChoice() {
        assertThat(storeRejects(new WorkflowException(WorkflowException.Stage.BUILD, "invalid media URL template")))
                .extracting(WorkflowSetupController.ErrorView::target)
                .containsExactly("workflow-templateMode");
    }

    @Test
    void aCallWithAnInvalidNameIsBlamed() {
        WorkflowForm form = validNewForm();
        form.calls.getFirst().name = "Main";

        assertThat(save(form, new DirectFieldBindingResult(form, "workflowForm")))
                .extracting(WorkflowSetupController.ErrorView::target)
                .containsExactly("workflow-calls-0-name");
    }

    @Test
    void aMappingWithAnInvalidNameIsBlamedBeforeTheDetailIsRead() {
        WorkflowForm form = validNewForm();
        form.calls.getFirst().variables.getFirst().name = "1st";

        assertThat(save(form, new DirectFieldBindingResult(form, "workflowForm")))
                .extracting(WorkflowSetupController.ErrorView::target)
                .containsExactly("workflow-calls-0-variables-0-name");
    }

    @Test
    void aBlankLeftoverValueRowDoesNotCaptureAnUnrelatedError() {
        WorkflowForm form = validNewForm();
        form.entryVariables.add(new WorkflowForm.VariableRow());

        assertThat(storeRejects(form, new WorkflowException(WorkflowException.Stage.WORKFLOW, "invalid name")))
                .extracting(WorkflowSetupController.ErrorView::target)
                .containsExactly("workflow-name");
    }

    @Test
    void aBlankEntryValueRowInSingleModeIsNeverBlamed() {
        WorkflowForm form = validNewForm();
        form.name = "";
        form.entryVariables.add(new WorkflowForm.VariableRow());

        assertThat(save(form, new DirectFieldBindingResult(form, "workflowForm")))
                .extracting(WorkflowSetupController.ErrorView::target)
                .containsExactly("workflow-name");
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
