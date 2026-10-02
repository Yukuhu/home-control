package dev.andre.homecontrol.web;

import dev.andre.homecontrol.testsupport.WebSliceTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** The setup page is built from the modules' setup sections, by group and order. */
class SetupSectionsTest extends WebSliceTest {

    private static final List<String> SOURCES = List.of("jellyfin", "youtube", "tmdb", "pinned", "workflows", "sports");

    @Autowired
    MockMvc mockMvc;

    private String page() throws Exception {
        return mockMvc.perform(get("/setup")).andReturn().getResponse().getContentAsString();
    }

    @Test
    void theContentSourcesAreListedInOrderWithWorkflows() throws Exception {
        String connections = section(page(), "connections");
        String links = connections.substring(0, connections.indexOf("</nav>"));
        Matcher href = Pattern.compile("href=\"#([a-z]+)\"").matcher(links);
        List<String> anchors = href.results().map(match -> match.group(1)).toList();

        assertThat(anchors).containsExactlyElementsOf(SOURCES);
    }

    @Test
    void eachSectionIsRenderedUnderItsAnchorInOrder() throws Exception {
        String page = page();
        int previous = page.indexOf("id=\"bluetooth\"");

        assertThat(previous).as("bluetooth").isPositive();
        for (String id : SOURCES) {
            int at = page.indexOf("id=\"" + id + "\"");
            assertThat(at).as(id).isGreaterThan(previous);
            previous = at;
        }
    }

    /** Several of the setup page's forms ask for the first password at once, so their fields carry no id to repeat. */
    @Test
    void theFirstPasswordFieldsCarryNoId() throws Exception {
        String page = page();

        assertThat(Pattern.compile("name=\"loginPassword\"").matcher(page).results().count()).isGreaterThan(1);
        assertThat(page).doesNotContain("-loginPassword\"", "-loginPasswordConfirmation\"");
    }

    @Test
    void theAccountSectionStillKnowsWhetherALoginIsRequired() throws Exception {
        given(login.loginRequired()).willReturn(true);

        assertThat(section(page(), "account")).contains("Change password");
    }
}
