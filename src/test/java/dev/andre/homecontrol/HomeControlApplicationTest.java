package dev.andre.homecontrol;

import dev.andre.homecontrol.adapters.tizen.TizenAdapter;
import dev.andre.homecontrol.adapters.webos.WebOsAdapter;
import dev.andre.homecontrol.core.PromptPairing;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class HomeControlApplicationTest {

    @Autowired
    ApplicationContext context;

    @Test
    void contextLoads() {
    }

    @Test
    void bothTvModulesAreOnByDefault() {
        assertThat(context.getBeansOfType(PromptPairing.class)).hasSize(2);
        assertThat(context.getBeansOfType(WebOsAdapter.class)).hasSize(1);
        assertThat(context.getBeansOfType(TizenAdapter.class)).hasSize(1);
    }
}
