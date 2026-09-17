package dev.andre.homecontrol.web;

import dev.andre.homecontrol.adapters.androidtv.PairingService;
import dev.andre.homecontrol.content.SourcePreferencesService;
import dev.andre.homecontrol.content.StoredRailPreferences;
import dev.andre.homecontrol.core.content.ContentSource;
import dev.andre.homecontrol.core.content.ContentSources;
import dev.andre.homecontrol.core.content.RailDescriptor;
import dev.andre.homecontrol.core.content.SourcePreferences;
import dev.andre.homecontrol.device.DeviceManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({SourcesSetupController.class, SetupController.class, SourcesSetupAdvice.class})
class SourcesSetupControllerTest {

    static final RailDescriptor RESUME = new RailDescriptor("jellyfin", "resume", "Continue watching");
    static final RailDescriptor NEXT_UP = new RailDescriptor("jellyfin", "next-up", "Next up");
    static final RailDescriptor LATEST = new RailDescriptor("jellyfin", "latest", "Latest in library");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    RequestMappingHandlerMapping handlerMapping;

    @MockitoBean
    PairingService pairing;

    @MockitoBean
    DeviceManager devices;

    @MockitoBean
    SourcePreferencesService prefs;

    @MockitoBean
    ContentSources sources;

    @MockitoBean
    StoredRailPreferences rails;

    final ContentSource jellyfin = mock(ContentSource.class);

    @BeforeEach
    void defaults() {
        given(devices.devices()).willReturn(List.of());
        given(devices.pairable()).willReturn(List.of());
        given(devices.addable()).willReturn(List.of());

        given(jellyfin.id()).willReturn("jellyfin");
        given(jellyfin.displayName()).willReturn("Jellyfin");
        given(jellyfin.available()).willReturn(true);
        given(jellyfin.searchable()).willReturn(true);
        given(sources.all()).willReturn(List.of(jellyfin));
        given(sources.find("jellyfin")).willReturn(Optional.of(jellyfin));
        given(sources.find("nope")).willReturn(Optional.empty());

        given(rails.allRailsInOrder(List.of(jellyfin))).willReturn(List.of(RESUME, NEXT_UP, LATEST));
        given(rails.defaultRefreshInterval(jellyfin)).willReturn(Duration.ofMinutes(5));

        given(prefs.current()).willReturn(SourcePreferences.defaults("de-DE", "DE"));
        // Real validate-by-construction behaviour, so an invalid change really throws.
        given(prefs.update(any())).willAnswer(invocation -> {
            UnaryOperator<SourcePreferences> change = invocation.getArgument(0);
            return change.apply(prefs.current());
        });
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<UnaryOperator<SourcePreferences>> captor() {
        return ArgumentCaptor.forClass(UnaryOperator.class);
    }

    @Test
    void theSetupPageListsSourcesRailsAndLocale() throws Exception {
        mockMvc.perform(get("/setup"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"sources\"")))
                .andExpect(content().string(containsString("Jellyfin")))
                .andExpect(content().string(containsString("Continue watching")))
                .andExpect(content().string(containsString("Next up")))
                .andExpect(content().string(containsString("Latest in library")))
                .andExpect(content().string(containsString("Move up")))
                .andExpect(content().string(containsString("Hide")))
                .andExpect(content().string(containsString("placeholder=\"5\"")))
                .andExpect(content().string(containsString("name=\"locale\"")))
                .andExpect(content().string(containsString("value=\"de-DE\"")))
                .andExpect(content().string(containsString("value=\"netflix\"")));
    }

    @Test
    void togglingASourceUpdatesPreferences() throws Exception {
        ArgumentCaptor<UnaryOperator<SourcePreferences>> captor = captor();

        mockMvc.perform(post("/setup/sources/preferences/jellyfin/enabled").param("enabled", "false"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/setup#sources"))
                .andExpect(flash().attribute("sourcesMessage", "Jellyfin is hidden from the dashboard and search"));

        verify(prefs).update(captor.capture());
        assertThat(captor.getValue().apply(SourcePreferences.defaults("de-DE", "DE")).disabledSources())
                .containsExactly("jellyfin");
    }

    @Test
    void anUnknownSourceIsRejected() throws Exception {
        mockMvc.perform(post("/setup/sources/preferences/nope/enabled").param("enabled", "true"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/setup#sources"))
                .andExpect(flash().attribute("sourcesError", "No content source nope"));

        verify(prefs, never()).update(any());
    }

    @Test
    void setsAndClearsTheInterval() throws Exception {
        ArgumentCaptor<UnaryOperator<SourcePreferences>> captor = captor();

        mockMvc.perform(post("/setup/sources/preferences/jellyfin/interval").param("minutes", "10"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/setup#sources"))
                .andExpect(flash().attribute("sourcesMessage", "Jellyfin refreshes every 10 minutes"));
        verify(prefs).update(captor.capture());
        assertThat(captor.getValue().apply(SourcePreferences.defaults("de-DE", "DE")).refreshMinutes())
                .containsEntry("jellyfin", 10);

        mockMvc.perform(post("/setup/sources/preferences/jellyfin/interval").param("minutes", ""))
                .andExpect(flash().attribute("sourcesMessage", "Jellyfin uses its default refresh interval"));
        verify(prefs, org.mockito.Mockito.times(2)).update(captor.capture());
        SourcePreferences withStoredInterval = SourcePreferences.defaults("de-DE", "DE").withRefreshMinutes("jellyfin", 10);
        assertThat(captor.getValue().apply(withStoredInterval).refreshMinutes()).isEmpty();

        mockMvc.perform(post("/setup/sources/preferences/jellyfin/interval").param("minutes", "abc"))
                .andExpect(flash().attribute("sourcesError", "Refresh every 1 to 1440 minutes"));
        verify(prefs, org.mockito.Mockito.times(2)).update(any());
    }

    @Test
    void movesARail() throws Exception {
        ArgumentCaptor<UnaryOperator<SourcePreferences>> captor = captor();

        mockMvc.perform(post("/setup/sources/preferences/rails/move")
                        .param("rail", "jellyfin/next-up").param("direction", "up"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/setup#sources"))
                .andExpect(flash().attribute("sourcesMessage", "Rail order saved"));
        verify(prefs).update(captor.capture());
        assertThat(captor.getValue().apply(SourcePreferences.defaults("de-DE", "DE")).railOrder())
                .containsExactly("jellyfin/next-up", "jellyfin/resume", "jellyfin/latest");

        mockMvc.perform(post("/setup/sources/preferences/rails/move")
                        .param("rail", "jellyfin/next-up").param("direction", "sideways"))
                .andExpect(flash().attribute("sourcesError", "Choose up or down"));
    }

    @Test
    void hidesAndShowsARail() throws Exception {
        ArgumentCaptor<UnaryOperator<SourcePreferences>> captor = captor();

        mockMvc.perform(post("/setup/sources/preferences/rails/visibility")
                        .param("rail", "jellyfin/resume").param("visible", "false"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/setup#sources"))
                .andExpect(flash().attribute("sourcesMessage", "Continue watching is hidden"));
        verify(prefs).update(captor.capture());
        assertThat(captor.getValue().apply(SourcePreferences.defaults("de-DE", "DE")).hiddenRails())
                .containsExactly("jellyfin/resume");

        mockMvc.perform(post("/setup/sources/preferences/rails/visibility")
                        .param("rail", "unknown/rail").param("visible", "true"))
                .andExpect(flash().attribute("sourcesError", "No rail unknown/rail"));
    }

    @Test
    void savesLocaleRegionAndProviders() throws Exception {
        ArgumentCaptor<UnaryOperator<SourcePreferences>> captor = captor();

        mockMvc.perform(post("/setup/sources/preferences/locale")
                        .param("locale", "en-GB").param("region", "GB")
                        .param("providers", "netflix").param("providers", "dazn"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/setup#sources"))
                .andExpect(flash().attribute("sourcesMessage", "Language and services saved"));
        verify(prefs).update(captor.capture());
        SourcePreferences result = captor.getValue().apply(SourcePreferences.defaults("de-DE", "DE"));
        assertThat(result.locale()).isEqualTo("en-GB");
        assertThat(result.region()).isEqualTo("GB");
        assertThat(result.providers()).containsExactly("netflix", "dazn");

        mockMvc.perform(post("/setup/sources/preferences/locale").param("locale", "english").param("region", "DE"))
                .andExpect(flash().attribute("sourcesError", "Use a language tag such as de-DE"));
    }

    @Test
    void theseFormsAreUnderTheGuardedPrefix() {
        handlerMapping.getHandlerMethods().forEach((info, method) -> {
            if (method.getBeanType().equals(SourcesSetupController.class)) {
                assertThat(info.getPatternValues())
                        .allSatisfy(pattern -> assertThat(pattern).startsWith("/setup/sources/"));
            }
        });
    }
}
