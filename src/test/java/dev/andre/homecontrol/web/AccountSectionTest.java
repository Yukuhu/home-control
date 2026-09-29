package dev.andre.homecontrol.web;

import dev.andre.homecontrol.testsupport.WebSliceTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The setup page's Account section: set a password without a login, change or remove one with it. */
class AccountSectionTest extends WebSliceTest {

    private static final Pattern REMOVE_FORM =
            Pattern.compile("<form method=\"post\" action=\"/setup/password/remove\">(.*?)</form>", Pattern.DOTALL);

    @Autowired
    MockMvc mockMvc;

    @BeforeEach
    void noDevices() {
        given(devices.devices()).willReturn(List.of());
        given(enrollment.pairable()).willReturn(List.of());
        given(enrollment.addable()).willReturn(List.of());
    }

    private String setupPage() throws Exception {
        return mockMvc.perform(get("/setup")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private static String removeForm(String page) {
        Matcher form = REMOVE_FORM.matcher(page);
        assertThat(form.find()).as("the remove-password form").isTrue();
        return form.group(1);
    }

    @Test
    void withoutALoginTheAccountSectionOffersToSetAPassword() throws Exception {
        given(login.loginRequired()).willReturn(false);

        mockMvc.perform(get("/setup"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"#account\"")))
                .andExpect(content().string(containsString("action=\"/setup/password/set\"")))
                .andExpect(content().string(not(containsString("action=\"/setup/password/remove\""))))
                .andExpect(content().string(not(containsString("Current password"))));
    }

    @Test
    void withALoginTheAccountSectionOffersChangeAndRemove() throws Exception {
        given(login.loginRequired()).willReturn(true);
        given(login.connectedAccounts()).willReturn(List.of());

        String page = setupPage();

        assertThat(page).contains("action=\"/setup/password\"").doesNotContain("action=\"/setup/password/set\"");
        assertThat(removeForm(page)).doesNotContain("disabled");
    }

    @Test
    void removingIsDisabledAndExplainedWhileAccountsAreConnected() throws Exception {
        given(login.loginRequired()).willReturn(true);
        given(login.connectedAccounts()).willReturn(List.of("Jellyfin", "YouTube"));

        String page = setupPage();

        assertThat(page).contains("Disconnect Jellyfin, YouTube first: their credentials need the password.");
        assertThat(removeForm(page)).contains("disabled");
    }
}
