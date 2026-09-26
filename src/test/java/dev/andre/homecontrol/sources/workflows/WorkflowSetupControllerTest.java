package dev.andre.homecontrol.sources.workflows;

import dev.andre.homecontrol.adapters.androidtv.PairingService;
import dev.andre.homecontrol.device.DeviceManager;
import dev.andre.homecontrol.security.LoginService;
import dev.andre.homecontrol.security.PasswordRejectedException;
import dev.andre.homecontrol.web.SetupController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
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

@WebMvcTest({WorkflowSetupController.class, WorkflowSetupAdvice.class, SetupController.class})
class WorkflowSetupControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean WorkflowStore store;
    @MockitoBean WorkflowTestService testService;
    @MockitoBean LoginService login;
    @MockitoBean PairingService pairing;
    @MockitoBean DeviceManager devices;
    final String id = "w-0123456789ab";
    WorkflowDefinition saved;

    @BeforeEach void setup() {
        saved = new WorkflowDefinition(1, id, 3, WorkflowFixtures.single(URI.create("https://api.example/saved-secret")));
        when(store.find(id)).thenReturn(Optional.of(saved));
        when(store.all()).thenReturn(List.of(saved));
        when(store.problems()).thenReturn(Map.of());
        when(login.loginRequired()).thenReturn(true);
        when(login.isAuthenticated(any(jakarta.servlet.http.HttpServletRequest.class))).thenReturn(true);
        when(devices.devices()).thenReturn(List.of());
        when(devices.pairable()).thenReturn(List.of());
        when(devices.addable()).thenReturn(List.of());
    }

    @Test void newMappingDefaultsSensitiveAndSavedFalseSurvives() {
        assertThat(new WorkflowForm.VariableRow().sensitive).isTrue();
        assertThat(WorkflowForm.from(saved).variables.getFirst().sensitive).isFalse();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void setupIncludesModeEditAndSavedRevisionTestWithoutFetching(boolean generated) throws Exception {
        if (generated) when(store.all()).thenReturn(List.of(new WorkflowDefinition(1, id, 3, WorkflowFixtures.generated())));
        String html = mvc.perform(get("/setup")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains(generated ? "Generated tiles" : "Single tile", ">Edit</a>", "/setup/workflows/" + id + "/test", "Fetches fresh data", "without playback")
                .containsPattern("(?s)action=\"/setup/workflows/" + id + "/test\".*?name=\"expectedRevision\" value=\"3\"");
        verifyNoInteractions(testService);
    }

    @Test void explicitlyUncheckedSensitivityOverridesNewRowDefault() throws Exception {
        when(store.update(eq(id), eq(3L), any(), any())).thenReturn(saved);
        mvc.perform(validPost().param("urlMode", "KEEP").param("headersMode", "KEEP").param("templateMode", "REPLACE")
                        .param("template", "https://media.example/?token={Token}")
                        .param("variables[0].name", "Token").param("variables[0].scope", "ROOT")
                        .param("variables[0].pointer", "/token").param("_variables[0].sensitive", "on"))
                .andExpect(status().is3xxRedirection());
        var captor = org.mockito.ArgumentCaptor.forClass(WorkflowDraft.class);
        verify(store).update(eq(id), eq(3L), captor.capture(), any());
        assertThat(captor.getValue().variables().getFirst().sensitive()).isFalse();
    }

    @Test void savedEditorContainsKeepControlsAndNoStoredSecretsOrFetch() throws Exception {
        var result = mvc.perform(get("/setup/workflows/" + id)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Referrer-Policy", "same-origin")).andReturn();
        assertThat(result.getResponse().getContentAsString()).contains("Keep saved URL", "Keep saved template")
                .doesNotContain("saved-secret", "api.example", "token={C}");
        assertThat(result.getModelAndView().getModel().values()).noneMatch(WorkflowDefinition.class::isInstance);
        verifyNoInteractions(testService);
    }

    @Test void keepAndReplaceAreExplicitAndRowsRetainOrder() throws Exception {
        when(store.update(eq(id), eq(3L), any(), any())).thenReturn(saved);
        mvc.perform(validPost().param("urlMode", "KEEP").param("templateMode", "KEEP").param("headersMode", "KEEP")
                        .param("variables[0].name", "C").param("variables[0].scope", "ROOT").param("variables[0].pointer", "/token")
                        .param("variables[0].sensitive", "true").param("_variables[0].sensitive", "on")
                        .param("variables[1].name", "A").param("variables[1].scope", "ROOT").param("variables[1].pointer", "/id"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/setup/workflows/" + id));
        var captor = org.mockito.ArgumentCaptor.forClass(WorkflowDraft.class);
        verify(store).update(eq(id), eq(3L), captor.capture(), any());
        assertThat(captor.getValue().fetch()).isEqualTo(saved.draft().fetch());
        assertThat(captor.getValue().cast().template()).isEqualTo(saved.draft().cast().template());
        assertThat(captor.getValue().variables()).extracting(WorkflowDraft.Variable::name).containsExactly("C", "A");
        verifyNoInteractions(testService);
    }

    @ParameterizedTest @ValueSource(strings = {"variables[999999].name", "variables[32].name", "headers[16].value",
            "variables[1].name", "variables[-1].name", "variables[01].name", "variables[x].name", "schemaVersion", "id", "revision", "_url", "!url", "url[]"})
    void hostileFieldsAreRejectedBeforeGrowthAndSecretsNeverReappear(String field) throws Exception {
        var result = mvc.perform(validPost().param(field, "private-marker").param("url", "https://private-url/x")
                        .param("template", "https://private-template/x").param("headers[0].name", "Authorization")
                        .param("headers[0].value", "private-header").param("loginPassword", "private-password")
                        .param("loginPasswordConfirmation", "private-confirmation"))
                .andExpect(status().isOk()).andReturn();
        assertSafeError(result);
        verify(store, never()).update(anyString(), anyLong(), any(), any());
    }

    @Test void conversionErrorsRebuildBindingResultAndPreserveNonsecretDraft() throws Exception {
        var result = mvc.perform(validPost().param("mode", "private-marker").param("url", "https://private-url/x")
                        .param("variables[0].name", "KeepMe").param("variables[0].scope", "ROOT")
                        .param("variables[0].pointer", "/safe").param("headers[0].name", "Authorization")
                        .param("headers[0].value", "private-header"))
                .andExpect(status().isOk()).andReturn();
        assertSafeError(result);
        assertThat(result.getResponse().getContentAsString()).contains("KeepMe", "/safe", "Authorization");
    }

    @Test void firstSavePasswordFailureScrubsValues() throws Exception {
        when(login.loginRequired()).thenReturn(false);
        mvc.perform(get("/setup/workflows/new")).andExpect(content().string(org.hamcrest.Matchers.containsString("loginPasswordConfirmation")));
        when(store.create(any(), any(), any(), any())).thenThrow(new PasswordRejectedException("The two passwords do not match"));
        var request = post("/setup/workflows").param("name", "Draft").param("mode", "SINGLE").param("kind", "VIDEO")
                .param("title", "Draft title").param("urlMode", "REPLACE").param("url", "https://private-url/x")
                .param("templateMode", "REPLACE").param("template", "https://private-template/x")
                .param("headersMode", "REPLACE").param("mimeType", "video/mp4")
                .param("loginPassword", "private-password").param("loginPasswordConfirmation", "private-confirmation");
        assertSafeError(mvc.perform(request).andExpect(status().isOk()).andReturn());
    }

    @Test void optionalRootPointersRoundTripAndNoClientDefinitionAppearsInModel() {
        var draft = WorkflowFixtures.generated();
        var definition = new WorkflowDefinition(1, id, 1, new WorkflowDraft(draft.name(), true, draft.mode(), draft.kind(),
                draft.fetch(), new WorkflowDraft.Listing("/items", "/id", "/title", "", null), null, draft.variables(), draft.cast()));
        var form = WorkflowForm.from(definition);
        assertThat(form.includeSubtitlePointer).isTrue();
        assertThat(form.includeArtworkPointer).isFalse();
        assertThat(form.toDraft(definition).listing().subtitlePointer()).isEmpty();
        form.includeArtworkPointer = true;
        assertThat(form.toDraft(definition).listing().artworkPointer()).isEmpty();
    }

    @Test void corruptBlankAndDotIdsAreReachableOnlyThroughCanonicalRecoveryTokens() throws Exception {
        when(store.problems()).thenReturn(Map.of("", "Stored definition cannot be loaded", ".", "Stored definition cannot be loaded", "..", "Stored definition cannot be loaded"));
        String html = mvc.perform(get("/setup")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("/setup/workflows/invalid-/remove-invalid", "/setup/workflows/invalid-Lg/remove-invalid", "/setup/workflows/invalid-Li4/remove-invalid");
        for (String token : List.of("invalid-", "invalid-Lg", "invalid-Li4")) {
            mvc.perform(post("/setup/workflows/" + token + "/remove-invalid")).andExpect(status().is3xxRedirection());
        }
        verify(store).removeInvalid(eq(""), any());
        verify(store).removeInvalid(eq("."), any());
        verify(store).removeInvalid(eq(".."), any());
        mvc.perform(post("/setup/workflows/invalid-Lg==/remove-invalid")).andExpect(status().isBadRequest());
        mvc.perform(post("/setup/workflows/invalid-Lh/remove-invalid")).andExpect(status().isBadRequest());
    }

    @Test void unknownAndStaleWorkflowsFailWithoutTestingOrLeakingFormValues() throws Exception {
        mvc.perform(get("/setup/workflows/w-ffffffffffff")).andExpect(status().isNotFound());
        var result = mvc.perform(validPost().with(request -> { request.setParameter("expectedRevision", "2"); return request; }).param("url", "https://private-url/x"))
                .andExpect(status().isConflict()).andReturn();
        assertSafeError(result);
        verifyNoInteractions(testService);
    }

    @Test void testUsesSavedRevisionAndSafeResultOnly() throws Exception {
        when(testService.test(eq(id), eq(3L), any())).thenReturn(new WorkflowTestService.Result(List.of(), 0, List.of(), List.of()));
        mvc.perform(post("/setup/workflows/" + id + "/test").param("expectedRevision", "3"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
        verify(testService).test(eq(id), eq(3L), any());
    }

    @Test void replacesCredentialsAndClearsHeadersOnlyWhenExplicitlySelected() throws Exception {
        when(store.update(eq(id), eq(3L), any(), any())).thenReturn(saved);
        mvc.perform(validPost().param("urlMode", "REPLACE").param("url", "https://new.example/source")
                        .param("templateMode", "REPLACE").param("template", "https://media.example/new")
                        .param("headersMode", "REPLACE"))
                .andExpect(status().is3xxRedirection());
        var captor = org.mockito.ArgumentCaptor.forClass(WorkflowDraft.class);
        verify(store).update(eq(id), eq(3L), captor.capture(), any());
        assertThat(captor.getValue().fetch().url()).isEqualTo("https://new.example/source");
        assertThat(captor.getValue().fetch().headers()).isEmpty();
        assertThat(captor.getValue().cast().template()).isEqualTo("https://media.example/new");
    }

    @Test void storageFailureAndUnauthorizedSaveNeverReturnSecrets() throws Exception {
        when(store.update(eq(id), eq(3L), any(), any())).thenThrow(new IllegalStateException("private-marker"));
        var result = mvc.perform(validPost().param("urlMode", "REPLACE").param("url", "https://private-url/x")
                        .param("templateMode", "REPLACE").param("template", "https://private-template/x"))
                .andExpect(status().isOk()).andReturn();
        assertSafeError(result);
        when(login.loginRequired()).thenReturn(false); // Exercise the controller's own service-boundary gate.
        when(login.isAuthenticated(any(jakarta.servlet.http.HttpServletRequest.class))).thenReturn(false);
        assertSafeError(mvc.perform(validPost().param("url", "https://private-url/x"))
                .andExpect(status().isUnauthorized()).andReturn());
    }

    @Test void legitimateUncheckedMarkersAndOptionalRootSelectionAreBound() throws Exception {
        when(store.update(eq(id), eq(3L), any(), any())).thenReturn(saved);
        mvc.perform(validPost().with(request -> {
                    request.removeParameter("enabled"); request.setParameter("mode", "GENERATED"); return request;
                }).param("arrayPointer", "/items").param("idPointer", "/id").param("titlePointer", "/title")
                .param("_includeSubtitlePointer", "on").param("includeSubtitlePointer", "true")
                .param("subtitlePointer", "").param("_includeArtworkPointer", "on")
                .param("variables[0].name", "A").param("variables[0].scope", "ENTRY").param("variables[0].pointer", "/id")
                .param("_variables[0].sensitive", "on").param("templateMode", "REPLACE")
                .param("template", "https://media.example/{A}"))
                .andExpect(status().is3xxRedirection());
        var captor = org.mockito.ArgumentCaptor.forClass(WorkflowDraft.class);
        verify(store).update(eq(id), eq(3L), captor.capture(), any());
        assertThat(captor.getValue().enabled()).isFalse();
        assertThat(captor.getValue().listing().subtitlePointer()).isEmpty();
        assertThat(captor.getValue().listing().artworkPointer()).isNull();
        assertThat(captor.getValue().variables().getFirst().sensitive()).isFalse();
    }

    @Test void requestHeadersCannotSupplyAnOmittedAllowedCheckbox() throws Exception {
        when(store.update(eq(id), eq(3L), any(), any())).thenReturn(saved);

        mvc.perform(validPost().with(request -> {
                    request.removeParameter("enabled");
                    return request;
                }).header("Enabled", "true")
                .param("templateMode", "REPLACE").param("template", "https://media.example/item.mp4"))
                .andExpect(status().is3xxRedirection());

        var captor = org.mockito.ArgumentCaptor.forClass(WorkflowDraft.class);
        verify(store).update(eq(id), eq(3L), captor.capture(), any());
        assertThat(captor.getValue().enabled()).isFalse();
    }

    @Test void firstSaveRejectsKeepWithoutReachingStore() throws Exception {
        when(login.loginRequired()).thenReturn(false);
        var result = mvc.perform(post("/setup/workflows").param("urlMode", "KEEP"))
                .andExpect(status().isOk()).andReturn();
        assertSafeError(result);
        verify(store, never()).create(any(), any(), any(), any());
    }

    @Test void normalInvalidIdAndUnknownRecoveryTokensCannotRemoveOtherKeys() throws Exception {
        when(store.problems()).thenReturn(Map.of(id, "Stored definition cannot be loaded"));
        mvc.perform(post("/setup/workflows/" + id + "/remove-invalid")).andExpect(status().is3xxRedirection());
        verify(store).removeInvalid(eq(id), any());
        for (String token : List.of("invalid-Lg", "invalid-!", "invalid-dy0wMTIzNDU2Nzg5YWI")) {
            mvc.perform(post("/setup/workflows/" + token + "/remove-invalid")).andExpect(status().isBadRequest());
        }
        verify(store, times(1)).removeInvalid(anyString(), any());
    }

    @Test void enableAndRemoveUseExpectedRevisionAndRequestAuthentication() throws Exception {
        mvc.perform(post("/setup/workflows/" + id + "/enabled").param("expectedRevision", "3").param("enabled", "false"))
                .andExpect(status().is3xxRedirection());
        verify(store).setEnabled(eq(id), eq(3L), eq(false), any());
        mvc.perform(post("/setup/workflows/" + id + "/remove").param("expectedRevision", "3"))
                .andExpect(status().is3xxRedirection());
        verify(store).remove(eq(id), eq(3L), any());
        when(login.loginRequired()).thenReturn(false);
        when(login.isAuthenticated(any(jakarta.servlet.http.HttpServletRequest.class))).thenReturn(false);
        mvc.perform(post("/setup/workflows/" + id + "/remove-invalid")).andExpect(status().isUnauthorized());
        verify(store, never()).removeInvalid(anyString(), any());
    }

    @Test void invalidSourceUrlReturnsLinkedFieldErrorAndConcurrentRevisionFailureIsConflict() throws Exception {
        var result = mvc.perform(validPost().param("urlMode", "REPLACE").param("url", "private-marker"))
                .andExpect(status().isOk()).andReturn();
        assertSafeError(result);
        var binding = (BindingResult) result.getModelAndView().getModel().get(BindingResult.MODEL_KEY_PREFIX + "workflowForm");
        assertThat(binding.hasFieldErrors("url")).isTrue();
        assertThat(result.getResponse().getContentAsString()).contains("href=\"#workflow-url\"");
        when(store.update(eq(id), eq(3L), any(), any())).thenThrow(
                new WorkflowException(WorkflowException.Stage.WORKFLOW, "Workflow changed; reopen this item"));
        var concurrent = mvc.perform(validPost().param("templateMode", "REPLACE").param("template", "https://media.example/x"))
                .andExpect(status().isConflict()).andReturn();
        assertSafeError(concurrent);
    }

    MockHttpServletRequestBuilder validPost() {
        return post("/setup/workflows/" + id).param("name", "Draft").param("enabled", "true").param("_enabled", "on")
                .param("mode", "SINGLE").param("kind", "VIDEO").param("title", "Draft title")
                .param("expectedRevision", "3").param("mimeType", "video/mp4");
    }

    void assertSafeError(org.springframework.test.web.servlet.MvcResult result) throws Exception {
        assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        String html = result.getResponse().getContentAsString();
        assertThat(html).contains("role=\"alert\"").doesNotContain("private-marker", "private-url", "private-template", "private-header", "private-password", "private-confirmation");
        var model = result.getModelAndView().getModel();
        var form = (WorkflowForm) model.get("workflowForm");
        assertThat(form.url).isEmpty(); assertThat(form.template).isEmpty();
        assertThat(form.loginPassword).isEmpty(); assertThat(form.loginPasswordConfirmation).isEmpty();
        assertThat(form.headers).allSatisfy(row -> assertThat(row.value).isEmpty());
        var binding = (BindingResult) model.get(BindingResult.MODEL_KEY_PREFIX + "workflowForm");
        assertThat(binding.getAllErrors()).isNotEmpty();
        assertThat(binding.getFieldErrors()).allSatisfy(error -> assertThat(error.getRejectedValue()).isNull());
        assertThat(binding.toString()).doesNotContain("private-");
    }
}

@org.springframework.boot.test.context.SpringBootTest(properties = "home-control.content.rails.scheduler-enabled=false")
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
class WorkflowSetupAuthenticationTest {
    @Autowired MockMvc mvc;
    @Autowired WorkflowStore store;
    @Autowired LoginService login;
    @MockitoBean WorkflowHttpClient http;
    @org.springframework.test.context.DynamicPropertySource
    static void data(org.springframework.test.context.DynamicPropertyRegistry registry) throws java.io.IOException {
        String directory = java.nio.file.Files.createTempDirectory("workflow-editor-login").toString();
        registry.add("shield.data-dir", () -> directory);
    }

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
        verifyNoInteractions(http);
    }

    private MockHttpServletRequestBuilder firstSave() {
        return post("/setup/workflows").param("name", "Saved safely").param("mode", "SINGLE").param("kind", "VIDEO")
                .param("title", "News").param("urlMode", "REPLACE").param("url", "https://example.invalid/source-secret")
                .param("templateMode", "REPLACE").param("template", "https://example.invalid/template-secret")
                .param("headersMode", "REPLACE").param("mimeType", "video/mp4");
    }
}
