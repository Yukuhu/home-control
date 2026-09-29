package dev.andre.homecontrol.sources.workflows;

import dev.andre.homecontrol.security.LoginService;
import dev.andre.homecontrol.security.PasswordRejectedException;
import dev.andre.homecontrol.testsupport.FullAppTest;
import dev.andre.homecontrol.testsupport.WebSliceTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.validation.BindingResult;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class WorkflowSetupControllerTest extends WebSliceTest {
    @Autowired MockMvc mvc;
    final String id = "w-0123456789ab";
    WorkflowDefinition saved;

    @BeforeEach void setup() {
        saved = new WorkflowDefinition(WorkflowDefinition.SCHEMA_VERSION, id, 3, WorkflowFixtures.single(URI.create("https://api.example/saved-secret")));
        when(workflowStore.find(id)).thenReturn(Optional.of(saved));
        when(workflowStore.all()).thenReturn(List.of(saved));
        when(workflowStore.problems()).thenReturn(Map.of());
        when(login.loginRequired()).thenReturn(true);
        when(login.isAuthenticated(any(jakarta.servlet.http.HttpServletRequest.class))).thenReturn(true);
        when(devices.devices()).thenReturn(List.of());
        when(enrollment.pairable()).thenReturn(List.of());
        when(enrollment.addable()).thenReturn(List.of());
    }

    @Test void newMappingDefaultsSensitiveAndSavedFalseSurvives() {
        assertThat(new WorkflowForm.VariableRow().sensitive).isTrue();
        assertThat(WorkflowForm.from(saved).calls.getFirst().variables.getFirst().sensitive).isFalse();
    }

    @Test void aNewWorkflowStartsWithOneSharedCallNamedMain() {
        var form = WorkflowForm.blank();
        assertThat(form.calls).singleElement().satisfies(call -> {
            assertThat(call.name).isEqualTo("main");
            assertThat(call.scope).isEqualTo(WorkflowDraft.CallScope.SHARED);
            assertThat(call.savedName).isEmpty();
        });
        assertThat(form.entryCall).isEqualTo("main");
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void setupIncludesModeEditAndSavedRevisionTestWithoutFetching(boolean generated) throws Exception {
        if (generated) when(workflowStore.all()).thenReturn(List.of(new WorkflowDefinition(WorkflowDefinition.SCHEMA_VERSION, id, 3, WorkflowFixtures.generated())));
        String html = mvc.perform(get("/setup")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains(generated ? "Generated tiles" : "Single tile", ">Edit</a>", "/setup/workflows/" + id + "/test", "Fetches fresh data", "without playback")
                .containsPattern("(?s)action=\"/setup/workflows/" + id + "/test\".*?name=\"expectedRevision\" value=\"3\"");
        verifyNoInteractions(workflowTests);
    }

    @Test void explicitlyUncheckedSensitivityOverridesNewRowDefault() throws Exception {
        when(workflowStore.update(eq(id), eq(3L), any(), any())).thenReturn(saved);
        mvc.perform(keptPost().param("templateMode", "REPLACE")
                        .param("template", "https://media.example/?token={Token}")
                        .param("calls[0].variables[0].name", "Token")
                        .param("calls[0].variables[0].pointer", "/token").param("_calls[0].variables[0].sensitive", "on"))
                .andExpect(status().is3xxRedirection());
        assertThat(captureUpdatedDraft().calls().getFirst().variables().getFirst().sensitive()).isFalse();
    }

    @Test void savedEditorContainsKeepControlsAndNoStoredSecretsOrFetch() throws Exception {
        var result = mvc.perform(get("/setup/workflows/" + id)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Referrer-Policy", "same-origin")).andReturn();
        assertThat(result.getResponse().getContentAsString()).contains("Keep saved URL", "Keep saved template")
                .doesNotContain("saved-secret", "api.example", "token={C}");
        assertThat(result.getModelAndView().getModel().values()).noneMatch(WorkflowDefinition.class::isInstance);
        verifyNoInteractions(workflowTests);
    }

    @Test void keepAndReplaceAreExplicitAndRowsRetainOrder() throws Exception {
        when(workflowStore.update(eq(id), eq(3L), any(), any())).thenReturn(saved);
        mvc.perform(keptPost().param("templateMode", "KEEP")
                        .param("calls[0].variables[0].name", "C").param("calls[0].variables[0].pointer", "/token")
                        .param("calls[0].variables[0].sensitive", "true").param("_calls[0].variables[0].sensitive", "on")
                        .param("calls[0].variables[1].name", "A").param("calls[0].variables[1].pointer", "/id"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/setup/workflows/" + id));
        var draft = captureUpdatedDraft();
        assertThat(draft.calls().getFirst().url()).isEqualTo(saved.draft().calls().getFirst().url());
        assertThat(draft.calls().getFirst().headers()).isEqualTo(saved.draft().calls().getFirst().headers());
        assertThat(draft.cast().template()).isEqualTo(saved.draft().cast().template());
        assertThat(draft.calls().getFirst().variables()).extracting(WorkflowDraft.Variable::name).containsExactly("C", "A");
        verifyNoInteractions(workflowTests);
    }

    @ParameterizedTest @ValueSource(strings = {"calls[0].variables[999999].name", "calls[0].variables[64].name",
            "calls[0].headers[16].value", "calls[0].variables[1].name", "calls[0].variables[-1].name",
            "calls[0].variables[01].name", "calls[0].variables[x].name", "calls[8].name", "calls[01].name",
            "calls[0].variables[0].scope", "entryVariables[64].name", "variables[0].name", "url", "schemaVersion",
            "id", "revision", "_calls[0].url", "!calls[0].url", "calls[0].url[]", "_subtitleFrom"})
    void hostileFieldsAreRejectedBeforeGrowthAndSecretsNeverReappear(String field) throws Exception {
        var result = mvc.perform(validPost().param(field, "private-marker").param("calls[0].url", "https://private-url/x")
                        .param("template", "https://private-template/x").param("calls[0].headers[0].name", "Authorization")
                        .param("calls[0].headers[0].value", "private-header").param("loginPassword", "private-password")
                        .param("loginPasswordConfirmation", "private-confirmation"))
                .andExpect(status().isOk()).andReturn();
        assertSafeError(result);
        verify(workflowStore, never()).update(anyString(), anyLong(), any(), any());
    }

    @Test void conversionErrorsRebuildBindingResultAndPreserveNonsecretDraft() throws Exception {
        var result = mvc.perform(validPost().param("mode", "private-marker").param("calls[0].url", "https://private-url/x")
                        .param("calls[0].variables[0].name", "KeepMe")
                        .param("calls[0].variables[0].pointer", "/safe").param("calls[0].headers[0].name", "Authorization")
                        .param("calls[0].headers[0].value", "private-header"))
                .andExpect(status().isOk()).andReturn();
        assertSafeError(result);
        assertThat(result.getResponse().getContentAsString()).contains("KeepMe", "/safe", "Authorization");
    }

    @Test void firstSavePasswordFailureScrubsValues() throws Exception {
        when(login.loginRequired()).thenReturn(false);
        mvc.perform(get("/setup/workflows/new")).andExpect(content().string(org.hamcrest.Matchers.containsString("loginPasswordConfirmation")));
        when(workflowStore.create(any(), any(), any(), any())).thenThrow(new PasswordRejectedException("The two passwords do not match"));
        var request = post("/setup/workflows").param("name", "Draft").param("mode", "SINGLE").param("kind", "VIDEO")
                .param("title", "Draft title").param("calls[0].name", "main").param("calls[0].scope", "SHARED")
                .param("calls[0].urlMode", "REPLACE").param("calls[0].url", "https://private-url/x")
                .param("calls[0].variables[0].name", "A").param("calls[0].variables[0].pointer", "/id")
                .param("templateMode", "REPLACE").param("template", "https://private-template/x/{A}")
                .param("calls[0].headersMode", "REPLACE").param("mimeType", "video/mp4")
                .param("loginPassword", "private-password").param("loginPasswordConfirmation", "private-confirmation");
        assertSafeError(mvc.perform(request).andExpect(status().isOk()).andReturn());
    }

    @Test void optionalDisplaySourcesRoundTripAndNoClientDefinitionAppearsInModel() {
        var draft = WorkflowFixtures.generated();
        var definition = new WorkflowDefinition(WorkflowDefinition.SCHEMA_VERSION, id, 1, new WorkflowDraft(draft.name(), true, draft.mode(), draft.kind(),
                draft.calls(), new WorkflowDraft.Listing("main", "/items", "/id", "/title",
                        new WorkflowDraft.Field("", null), null, draft.listing().variables()), null, draft.cast()));
        var form = WorkflowForm.from(definition);
        assertThat(form.subtitleFrom).isEqualTo(WorkflowForm.DisplaySource.POINTER);
        assertThat(form.artworkFrom).isEqualTo(WorkflowForm.DisplaySource.NONE);
        assertThat(form.entryCall).isEqualTo("main");
        assertThat(form.entryVariables).extracting(row -> row.name).containsExactly("A");
        assertThat(form.toDraft(definition).listing().subtitle().pointer()).isEmpty();
        form.artworkFrom = WorkflowForm.DisplaySource.POINTER;
        assertThat(form.toDraft(definition).listing().artwork().pointer()).isEmpty();
        form.artworkFrom = WorkflowForm.DisplaySource.VARIABLE;
        form.artworkVariable = "A";
        assertThat(form.toDraft(definition).listing().artwork()).isEqualTo(new WorkflowDraft.Field(null, "A"));
    }

    @Test void aSavedChainOpensWithEachCallAndItsDisplaySource() {
        var chain = new WorkflowDefinition(WorkflowDefinition.SCHEMA_VERSION, id, 2, WorkflowFixtures.chain(URI.create("https://api.example")));
        var form = WorkflowForm.from(chain);
        assertThat(form.calls).extracting(call -> call.name).containsExactly("list", "images", "stream");
        assertThat(form.calls).extracting(call -> call.savedName).containsExactly("list", "images", "stream");
        assertThat(form.calls).extracting(call -> call.phase)
                .containsExactly("Runs at refresh and Play", "Runs at refresh", "Runs at Play");
        assertThat(form.calls).allSatisfy(call -> {
            assertThat(call.urlMode).isEqualTo(WorkflowForm.Replacement.KEEP);
            assertThat(call.url).isEmpty();
            assertThat(call.headers).isEmpty();
        });
        assertThat(form.artworkFrom).isEqualTo(WorkflowForm.DisplaySource.VARIABLE);
        assertThat(form.artworkVariable).isEqualTo("art");
        assertThat(form.toDraft(chain)).isEqualTo(chain.draft());
    }

    @Test void corruptBlankAndDotIdsAreReachableOnlyThroughCanonicalRecoveryTokens() throws Exception {
        when(workflowStore.problems()).thenReturn(Map.of("", "Stored definition cannot be loaded", ".", "Stored definition cannot be loaded", "..", "Stored definition cannot be loaded"));
        String html = mvc.perform(get("/setup")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("/setup/workflows/invalid-/remove-invalid", "/setup/workflows/invalid-Lg/remove-invalid", "/setup/workflows/invalid-Li4/remove-invalid");
        for (String token : List.of("invalid-", "invalid-Lg", "invalid-Li4")) {
            mvc.perform(post("/setup/workflows/" + token + "/remove-invalid")).andExpect(status().is3xxRedirection());
        }
        verify(workflowStore).removeInvalid(eq(""), any());
        verify(workflowStore).removeInvalid(eq("."), any());
        verify(workflowStore).removeInvalid(eq(".."), any());
        mvc.perform(post("/setup/workflows/invalid-Lg==/remove-invalid")).andExpect(status().isBadRequest());
        mvc.perform(post("/setup/workflows/invalid-Lh/remove-invalid")).andExpect(status().isBadRequest());
    }

    @Test void unknownAndStaleWorkflowsFailWithoutTestingOrLeakingFormValues() throws Exception {
        mvc.perform(get("/setup/workflows/w-ffffffffffff")).andExpect(status().isNotFound());
        var result = mvc.perform(validPost().with(request -> { request.setParameter("expectedRevision", "2"); return request; }).param("calls[0].url", "https://private-url/x"))
                .andExpect(status().isConflict()).andReturn();
        assertSafeError(result);
        verifyNoInteractions(workflowTests);
    }

    @Test void testUsesSavedRevisionAndSafeResultOnly() throws Exception {
        when(workflowTests.test(eq(id), eq(3L), any())).thenReturn(new WorkflowTestService.Result(List.of(), 0, List.of(), List.of()));
        mvc.perform(post("/setup/workflows/" + id + "/test").param("expectedRevision", "3"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
        verify(workflowTests).test(eq(id), eq(3L), any());
    }

    @Test void replacesCredentialsAndClearsHeadersOnlyWhenExplicitlySelected() throws Exception {
        when(workflowStore.update(eq(id), eq(3L), any(), any())).thenReturn(saved);
        mvc.perform(validPost().param("calls[0].urlMode", "REPLACE").param("calls[0].url", "https://new.example/source")
                        .param("templateMode", "REPLACE").param("template", "https://media.example/new/{A}")
                        .param("calls[0].variables[0].name", "A").param("calls[0].variables[0].pointer", "/id")
                        .param("calls[0].headersMode", "REPLACE"))
                .andExpect(status().is3xxRedirection());
        var draft = captureUpdatedDraft();
        assertThat(draft.calls().getFirst().url()).isEqualTo("https://new.example/source");
        assertThat(draft.calls().getFirst().headers()).isEmpty();
        assertThat(draft.cast().template()).isEqualTo("https://media.example/new/{A}");
    }

    @Test void aSingleTileFormWithNoCallRowsSaves() throws Exception {
        when(workflowStore.update(eq(id), eq(3L), any(), any())).thenReturn(saved);
        mvc.perform(post("/setup/workflows/" + id).param("name", "Radio").param("enabled", "true").param("_enabled", "on")
                        .param("mode", "SINGLE").param("kind", "VIDEO").param("title", "Radio")
                        .param("expectedRevision", "3").param("mimeType", "video/mp4")
                        .param("templateMode", "REPLACE").param("template", "https://media.example/radio.mp4"))
                .andExpect(status().is3xxRedirection());
        var draft = captureUpdatedDraft();
        assertThat(draft.calls()).isEmpty();
        assertThat(draft.cast().template()).isEqualTo("https://media.example/radio.mp4");
    }

    @Test void typedHeaderValuesAreStoredAsWrittenTemplates() throws Exception {
        when(workflowStore.update(eq(id), eq(3L), any(), any())).thenReturn(saved);
        mvc.perform(validPost().with(request -> { request.setParameter("calls[0].name", "token"); return request; })
                        .param("calls[0].urlMode", "REPLACE").param("calls[0].url", "https://new.example/token")
                        .param("calls[0].headersMode", "REPLACE")
                        .param("calls[0].variables[0].name", "token").param("calls[0].variables[0].pointer", "/token")
                        .param("calls[1].name", "main").param("calls[1].scope", "SHARED").param("calls[1].savedName", "main")
                        .param("calls[1].urlMode", "KEEP").param("calls[1].headersMode", "REPLACE")
                        .param("calls[1].headers[0].name", "Authorization").param("calls[1].headers[0].value", "Bearer {token}")
                        .param("calls[1].headers[1].name", "X-Filter").param("calls[1].headers[1].value", "{{\"a\":1}}")
                        .param("calls[1].variables[0].name", "A").param("calls[1].variables[0].pointer", "/id")
                        .param("templateMode", "REPLACE").param("template", "https://media.example/new/{A}"))
                .andExpect(status().is3xxRedirection());
        var draft = captureUpdatedDraft();
        assertThat(draft.calls().get(1).url()).isEqualTo("https://api.example/saved-secret");
        var headers = draft.calls().get(1).headers();
        assertThat(headers).extracting(WorkflowDraft.Header::value).containsExactly("Bearer {token}", "{{\"a\":1}}");
        assertThat(new WorkflowHeaderTemplate(headers.get(1).value()).expand(Map.of())).isEqualTo("{\"a\":1}");
    }

    @Test void storageFailureAndUnauthorizedSaveNeverReturnSecrets() throws Exception {
        when(workflowStore.update(eq(id), eq(3L), any(), any())).thenThrow(new IllegalStateException("private-marker"));
        var result = mvc.perform(validPost().param("calls[0].urlMode", "REPLACE").param("calls[0].url", "https://private-url/x")
                        .param("templateMode", "REPLACE").param("template", "https://private-template/x"))
                .andExpect(status().isOk()).andReturn();
        assertSafeError(result);
        when(login.loginRequired()).thenReturn(false); // Exercise the controller's own service-boundary gate.
        when(login.isAuthenticated(any(jakarta.servlet.http.HttpServletRequest.class))).thenReturn(false);
        assertSafeError(mvc.perform(validPost().param("calls[0].url", "https://private-url/x"))
                .andExpect(status().isUnauthorized()).andReturn());
    }

    @Test void legitimateUncheckedMarkersAndDisplaySourcesAreBound() throws Exception {
        when(workflowStore.update(eq(id), eq(3L), any(), any())).thenReturn(saved);
        mvc.perform(keptPost().with(request -> {
                    request.removeParameter("enabled"); request.setParameter("mode", "GENERATED"); return request;
                }).param("entryCall", "main").param("arrayPointer", "/items").param("idPointer", "/id").param("titlePointer", "/title")
                .param("subtitleFrom", "POINTER").param("subtitlePointer", "").param("subtitleVariable", "ignored")
                .param("artworkFrom", "NONE").param("artworkPointer", "/ignored")
                .param("entryVariables[0].name", "A").param("entryVariables[0].pointer", "/id")
                .param("_entryVariables[0].sensitive", "on").param("templateMode", "REPLACE")
                .param("template", "https://media.example/{A}"))
                .andExpect(status().is3xxRedirection());
        var draft = captureUpdatedDraft();
        assertThat(draft.enabled()).isFalse();
        assertThat(draft.listing().call()).isEqualTo("main");
        assertThat(draft.listing().subtitle()).isEqualTo(new WorkflowDraft.Field("", null));
        assertThat(draft.listing().artwork()).isNull();
        assertThat(draft.listing().variables().getFirst().sensitive()).isFalse();
    }

    @Test void requestHeadersCannotSupplyAnOmittedAllowedCheckbox() throws Exception {
        when(workflowStore.update(eq(id), eq(3L), any(), any())).thenReturn(saved);

        mvc.perform(keptPost().with(request -> {
                    request.removeParameter("enabled");
                    return request;
                }).header("Enabled", "true")
                .param("templateMode", "REPLACE").param("template", "https://media.example/item/{A}")
                .param("calls[0].variables[0].name", "A").param("calls[0].variables[0].pointer", "/id"))
                .andExpect(status().is3xxRedirection());

        assertThat(captureUpdatedDraft().enabled()).isFalse();
    }

    @Test void firstSaveRejectsKeepWithoutReachingStore() throws Exception {
        when(login.loginRequired()).thenReturn(false);
        var result = mvc.perform(post("/setup/workflows").param("calls[0].name", "main").param("calls[0].urlMode", "KEEP"))
                .andExpect(status().isOk()).andReturn();
        assertSafeError(result);
        verify(workflowStore, never()).create(any(), any(), any(), any());
    }

    @Test void normalInvalidIdAndUnknownRecoveryTokensCannotRemoveOtherKeys() throws Exception {
        when(workflowStore.problems()).thenReturn(Map.of(id, "Stored definition cannot be loaded"));
        mvc.perform(post("/setup/workflows/" + id + "/remove-invalid")).andExpect(status().is3xxRedirection());
        verify(workflowStore).removeInvalid(eq(id), any());
        for (String token : List.of("invalid-Lg", "invalid-!", "invalid-dy0wMTIzNDU2Nzg5YWI")) {
            mvc.perform(post("/setup/workflows/" + token + "/remove-invalid")).andExpect(status().isBadRequest());
        }
        verify(workflowStore, times(1)).removeInvalid(anyString(), any());
    }

    @Test void enableAndRemoveUseExpectedRevisionAndRequestAuthentication() throws Exception {
        mvc.perform(post("/setup/workflows/" + id + "/enabled").param("expectedRevision", "3").param("enabled", "false"))
                .andExpect(status().is3xxRedirection());
        verify(workflowStore).setEnabled(eq(id), eq(3L), eq(false), any());
        mvc.perform(post("/setup/workflows/" + id + "/remove").param("expectedRevision", "3"))
                .andExpect(status().is3xxRedirection());
        verify(workflowStore).remove(eq(id), eq(3L), any());
        when(login.loginRequired()).thenReturn(false);
        when(login.isAuthenticated(any(jakarta.servlet.http.HttpServletRequest.class))).thenReturn(false);
        mvc.perform(post("/setup/workflows/" + id + "/remove-invalid")).andExpect(status().isUnauthorized());
        verify(workflowStore, never()).removeInvalid(anyString(), any());
    }

    @Test void invalidSourceUrlReturnsLinkedFieldErrorAndConcurrentRevisionFailureIsConflict() throws Exception {
        var result = mvc.perform(validPost().param("calls[0].urlMode", "REPLACE").param("calls[0].url", "private-marker")
                        .param("calls[0].headersMode", "KEEP"))
                .andExpect(status().isOk()).andReturn();
        assertSafeError(result);
        var binding = (BindingResult) result.getModelAndView().getModel().get(BindingResult.MODEL_KEY_PREFIX + "workflowForm");
        assertThat(binding.hasFieldErrors("calls[0].urlMode")).isTrue();
        assertThat(result.getResponse().getContentAsString()).contains("href=\"#workflow-calls-0-urlMode\"");
        when(workflowStore.update(eq(id), eq(3L), any(), any())).thenThrow(
                new WorkflowException(WorkflowException.Stage.WORKFLOW, "Workflow changed; reopen this item"));
        var concurrent = mvc.perform(keptPost().param("templateMode", "REPLACE").param("template", "https://media.example/x/{A}")
                        .param("calls[0].variables[0].name", "A").param("calls[0].variables[0].pointer", "/id"))
                .andExpect(status().isConflict()).andReturn();
        assertSafeError(concurrent);
    }

    @Test void savesTwoCallsWhereTheSecondUsesTheFirstsToken() throws Exception {
        when(workflowStore.create(any(), any(), any(), any())).thenReturn(saved);
        mvc.perform(post("/setup/workflows")
                        .param("name", "Chain").param("mode", "SINGLE").param("kind", "VIDEO").param("title", "Chain")
                        .param("calls[0].name", "token").param("calls[0].scope", "SHARED").param("calls[0].urlMode", "REPLACE")
                        .param("calls[0].url", "https://api.example/token").param("calls[0].headersMode", "REPLACE")
                        .param("calls[0].variables[0].name", "token").param("calls[0].variables[0].pointer", "/token")
                        .param("calls[0].variables[0].sensitive", "true")
                        .param("calls[1].name", "stream").param("calls[1].scope", "SHARED").param("calls[1].urlMode", "REPLACE")
                        .param("calls[1].url", "https://api.example/stream").param("calls[1].headersMode", "REPLACE")
                        .param("calls[1].headers[0].name", "Authorization").param("calls[1].headers[0].value", "Bearer {token}")
                        .param("calls[1].variables[0].name", "path").param("calls[1].variables[0].pointer", "/path")
                        .param("templateMode", "REPLACE").param("template", "https://media.example/{path}")
                        .param("mimeType", "video/mp4").param("expectedRevision", "0"))
                .andExpect(status().is3xxRedirection());
        var draft = captureCreatedDraft();
        assertThat(draft.calls()).extracting(WorkflowDraft.Call::name).containsExactly("token", "stream");
        assertThat(draft.calls().get(1).headers().getFirst().value()).isEqualTo("Bearer {token}");
    }

    @Test void keepFindsTheSavedCallAfterARename() throws Exception {
        var definition = savedDefinition(WorkflowFixtures.single(URI.create("https://api.example/saved-secret")));
        var form = formOf(definition);                       // the saved workflow as the editor posts it
        form.set("calls[0].name", "renamed");                // .param would add a second value, which is refused
        form.set("calls[0].savedName", "main");
        form.set("calls[0].urlMode", "KEEP");
        form.set("calls[0].headersMode", "KEEP");
        mvc.perform(post("/setup/workflows/" + definition.id()).params(form))
                .andExpect(status().is3xxRedirection());
        var call = captureUpdatedDraft().calls().getFirst();
        assertThat(call.name()).isEqualTo("renamed");
        assertThat(call.url()).isEqualTo("https://api.example/saved-secret");
        assertThat(call.headers()).isEqualTo(definition.draft().calls().getFirst().headers());
    }

    @Test void theEditorPostsASavedChainBackUnchanged() throws Exception {
        var definition = savedDefinition(WorkflowFixtures.chain(URI.create("https://api.example/saved-secret")));
        mvc.perform(post("/setup/workflows/" + definition.id()).params(formOf(definition)))
                .andExpect(status().is3xxRedirection());
        assertThat(captureUpdatedDraft()).isEqualTo(definition.draft());
    }

    @Test void aCallUsingAValueFromFurtherDownIsMarkedOnThatCall() throws Exception {
        // As savesTwoCalls…, but call 0 uses {path} from call 1 in its URL.
        var result = mvc.perform(post("/setup/workflows").params(twoCallsWhereTheFirstUsesTheSecond()))
                .andReturn();
        String page = result.getResponse().getContentAsString();
        assertThat(page).contains("href=\"#workflow-calls-0-name\"")
                .contains("This call uses a value no call above it provides.")
                .doesNotContain("further down", "api.example");
        verify(workflowStore, never()).create(any(), any(), any(), any());
    }

    @Test void nestedRowsOfACallThatWasNotSubmittedAreRejected() throws Exception {
        var result = mvc.perform(post("/setup/workflows").params(validSingleCall())
                        .param("calls[3].headers[0].name", "X-Orphan"))
                .andReturn();
        assertThat(result.getResponse().getContentAsString()).contains("Some submitted fields are invalid");
        verify(workflowStore, never()).create(any(), any(), any(), any());
    }

    @Test void aValidSingleCallIsCreated() throws Exception {
        when(workflowStore.create(any(), any(), any(), any())).thenReturn(saved);
        mvc.perform(post("/setup/workflows").params(validSingleCall())).andExpect(status().is3xxRedirection());
        assertThat(captureCreatedDraft().calls()).singleElement().satisfies(call -> {
            assertThat(call.name()).isEqualTo("main");
            assertThat(call.url()).isEqualTo("https://api.example/source");
        });
    }

    @Test void theEditorShowsEachCallsPhaseAndNoSavedAddresses() throws Exception {
        var definition = savedDefinition(WorkflowFixtures.chain(URI.create("https://api.example/saved-secret")));
        String page = mvc.perform(get("/setup/workflows/" + definition.id())).andReturn().getResponse().getContentAsString();
        assertThat(page).contains("Runs at refresh and Play", "Runs at refresh", "Runs at Play")
                .contains("id=\"workflow-calls-2-name\"", "name=\"calls[2].name\"", "value=\"stream\"")
                .contains("id=\"workflow-entryCall\"", "id=\"workflow-entryVariables-0-name\"")
                .doesNotContain("saved-secret", "Bearer {token}");
    }

    @Test void aNewEditorOffersOneCallNamedMain() throws Exception {
        String page = mvc.perform(get("/setup/workflows/new")).andReturn().getResponse().getContentAsString();
        assertThat(page).contains("id=\"workflow-calls-0-name\"", "name=\"calls[0].name\"", "value=\"main\"",
                "Save to see when this call runs", "id=\"workflow-calls-template\"");
    }

    MockHttpServletRequestBuilder validPost() {
        return post("/setup/workflows/" + id).param("name", "Draft").param("enabled", "true").param("_enabled", "on")
                .param("mode", "SINGLE").param("kind", "VIDEO").param("title", "Draft title")
                .param("calls[0].name", "main").param("calls[0].scope", "SHARED").param("calls[0].savedName", "main")
                .param("expectedRevision", "3").param("mimeType", "video/mp4");
    }

    /** An edit that keeps the saved call's URL and headers. */
    MockHttpServletRequestBuilder keptPost() {
        return validPost().param("calls[0].urlMode", "KEEP").param("calls[0].headersMode", "KEEP");
    }

    WorkflowDefinition savedDefinition(WorkflowDraft draft) {
        var definition = new WorkflowDefinition(WorkflowDefinition.SCHEMA_VERSION, id, 3, draft);
        when(workflowStore.find(id)).thenReturn(Optional.of(definition));
        when(workflowStore.update(eq(id), eq(3L), any(), any())).thenReturn(definition);
        return definition;
    }

    WorkflowDraft captureCreatedDraft() {
        var captor = org.mockito.ArgumentCaptor.forClass(WorkflowDraft.class);
        verify(workflowStore).create(captor.capture(), any(), any(), any());
        return captor.getValue();
    }

    WorkflowDraft captureUpdatedDraft() {
        var captor = org.mockito.ArgumentCaptor.forClass(WorkflowDraft.class);
        verify(workflowStore).update(eq(id), eq(3L), captor.capture(), any());
        return captor.getValue();
    }

    /** The fields the editor posts for a saved workflow, as {@link WorkflowForm#from} fills them. */
    static MultiValueMap<String, String> formOf(WorkflowDefinition definition) {
        var form = WorkflowForm.from(definition);
        var fields = new LinkedMultiValueMap<String, String>();
        fields.add("name", form.name);
        fields.add("enabled", Boolean.toString(form.enabled));
        fields.add("_enabled", "on");
        fields.add("mode", form.mode.name());
        fields.add("kind", form.kind.name());
        fields.add("title", form.title);
        fields.add("subtitle", form.subtitle);
        fields.add("artwork", form.artwork);
        for (int i = 0; i < form.calls.size(); i++) {
            var call = form.calls.get(i);
            String prefix = "calls[" + i + "].";
            fields.add(prefix + "name", call.name);
            fields.add(prefix + "scope", call.scope.name());
            fields.add(prefix + "savedName", call.savedName);
            fields.add(prefix + "urlMode", call.urlMode.name());
            fields.add(prefix + "url", call.url);
            fields.add(prefix + "headersMode", call.headersMode.name());
            addVariables(fields, prefix + "variables", call.variables);
        }
        fields.add("entryCall", form.entryCall);
        fields.add("arrayPointer", form.arrayPointer);
        fields.add("idPointer", form.idPointer);
        fields.add("titlePointer", form.titlePointer);
        fields.add("subtitleFrom", form.subtitleFrom.name());
        fields.add("subtitlePointer", form.subtitlePointer);
        fields.add("subtitleVariable", form.subtitleVariable);
        fields.add("artworkFrom", form.artworkFrom.name());
        fields.add("artworkPointer", form.artworkPointer);
        fields.add("artworkVariable", form.artworkVariable);
        addVariables(fields, "entryVariables", form.entryVariables);
        fields.add("templateMode", form.templateMode.name());
        fields.add("template", form.template);
        fields.add("mimeType", form.mimeType);
        fields.add("expectedRevision", Long.toString(form.expectedRevision));
        return fields;
    }

    private static void addVariables(MultiValueMap<String, String> fields, String family, List<WorkflowForm.VariableRow> rows) {
        for (int j = 0; j < rows.size(); j++) {
            var row = rows.get(j);
            String prefix = family + "[" + j + "].";
            fields.add(prefix + "name", row.name);
            fields.add(prefix + "pointer", row.pointer);
            if (row.sensitive) fields.add(prefix + "sensitive", "true");
            fields.add("_" + prefix + "sensitive", "on");
        }
    }

    /** A new one-tile workflow with one call, as the editor posts it. */
    static MultiValueMap<String, String> validSingleCall() {
        var fields = new LinkedMultiValueMap<String, String>();
        fields.add("name", "Single"); fields.add("mode", "SINGLE"); fields.add("kind", "VIDEO"); fields.add("title", "Single");
        fields.add("calls[0].name", "main"); fields.add("calls[0].scope", "SHARED"); fields.add("calls[0].savedName", "");
        fields.add("calls[0].urlMode", "REPLACE"); fields.add("calls[0].url", "https://api.example/source");
        fields.add("calls[0].headersMode", "REPLACE");
        fields.add("calls[0].variables[0].name", "A"); fields.add("calls[0].variables[0].pointer", "/id");
        fields.add("templateMode", "REPLACE"); fields.add("template", "https://media.example/play/{A}");
        fields.add("mimeType", "video/mp4"); fields.add("expectedRevision", "0");
        return fields;
    }

    /** Two calls where the first one's URL uses {path}, which only the second one defines. */
    static MultiValueMap<String, String> twoCallsWhereTheFirstUsesTheSecond() {
        var fields = new LinkedMultiValueMap<String, String>();
        fields.add("name", "Chain"); fields.add("mode", "SINGLE"); fields.add("kind", "VIDEO"); fields.add("title", "Chain");
        fields.add("calls[0].name", "token"); fields.add("calls[0].scope", "SHARED"); fields.add("calls[0].urlMode", "REPLACE");
        fields.add("calls[0].url", "https://api.example/token/{path}"); fields.add("calls[0].headersMode", "REPLACE");
        fields.add("calls[0].variables[0].name", "token"); fields.add("calls[0].variables[0].pointer", "/token");
        fields.add("calls[1].name", "stream"); fields.add("calls[1].scope", "SHARED"); fields.add("calls[1].urlMode", "REPLACE");
        fields.add("calls[1].url", "https://api.example/stream?t={token}"); fields.add("calls[1].headersMode", "REPLACE");
        fields.add("calls[1].variables[0].name", "path"); fields.add("calls[1].variables[0].pointer", "/path");
        fields.add("templateMode", "REPLACE"); fields.add("template", "https://media.example/{path}");
        fields.add("mimeType", "video/mp4"); fields.add("expectedRevision", "0");
        return fields;
    }

    void assertSafeError(org.springframework.test.web.servlet.MvcResult result) throws Exception {
        assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        String html = result.getResponse().getContentAsString();
        assertThat(html).contains("role=\"alert\"").doesNotContain("private-marker", "private-url", "private-template", "private-header", "private-password", "private-confirmation");
        var model = result.getModelAndView().getModel();
        var form = (WorkflowForm) model.get("workflowForm");
        assertThat(form.template).isEmpty();
        assertThat(form.loginPassword).isEmpty(); assertThat(form.loginPasswordConfirmation).isEmpty();
        assertThat(form.calls).allSatisfy(call -> {
            assertThat(call.url).isEmpty();
            assertThat(call.headers).allSatisfy(row -> assertThat(row.value).isEmpty());
        });
        var binding = (BindingResult) model.get(BindingResult.MODEL_KEY_PREFIX + "workflowForm");
        assertThat(binding.getAllErrors()).isNotEmpty();
        assertThat(binding.getFieldErrors()).allSatisfy(error -> assertThat(error.getRejectedValue()).isNull());
        assertThat(binding.toString()).doesNotContain("private-");
    }
}

class WorkflowSetupAuthenticationTest extends FullAppTest {
    @Autowired MockMvc mvc;
    @Autowired WorkflowStore store;
    @Autowired LoginService login;

    @Test void firstSaveEstablishesRealSessionAndExistingLoginGatesAllEditorActions() throws Exception {
        String password = "fixture-only-password";
        mvc.perform(firstSave().param("loginPassword", password).param("loginPasswordConfirmation", "mismatch"))
                .andExpect(status().isOk());
        assertThat(store.all()).isEmpty();
        assertThat(login.loginRequired()).isFalse();
        var result = mvc.perform(firstSave().param("loginPassword", password).param("loginPasswordConfirmation", password))
                .andExpect(status().is3xxRedirection()).andReturn();
        assertThat(store.all()).hasSize(1);
        assertThat(login.loginRequired()).isTrue();
        var session = (org.springframework.mock.web.MockHttpSession) result.getRequest().getSession(false);
        assertThat(session).isNotNull();
        String location = result.getResponse().getRedirectedUrl();
        String html = mvc.perform(get(location).session(session)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(html).doesNotContain("source-secret", "template-secret", password);
        mvc.perform(get(location).accept("text/html")).andExpect(status().is3xxRedirection());
        for (String suffix : List.of("", "/test", "/remove", "/enabled", "/remove-invalid")) {
            mvc.perform(post(location + suffix).param("expectedRevision", "1"))
                    .andExpect(status().isUnauthorized());
        }
        verifyNoInteractions(workflowHttp);
    }

    private MockHttpServletRequestBuilder firstSave() {
        return post("/setup/workflows").param("name", "Saved safely").param("mode", "SINGLE").param("kind", "VIDEO")
                .param("title", "News").param("calls[0].name", "main").param("calls[0].scope", "SHARED")
                .param("calls[0].urlMode", "REPLACE").param("calls[0].url", "https://example.invalid/source-secret")
                .param("templateMode", "REPLACE").param("template", "https://example.invalid/template-secret/{A}")
                .param("calls[0].variables[0].name", "A").param("calls[0].variables[0].pointer", "/id")
                .param("calls[0].headersMode", "REPLACE").param("mimeType", "video/mp4");
    }
}
