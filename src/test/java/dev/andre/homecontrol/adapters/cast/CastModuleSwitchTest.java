package dev.andre.homecontrol.adapters.cast;

import dev.andre.homecontrol.discovery.MdnsBrowser;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class CastModuleSwitchTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(MdnsBrowser.class, () -> new MdnsBrowser(false))
            .withUserConfiguration(CastConfiguration.class);

    @Test
    void isOnByDefault() {
        runner.run(context -> assertThat(context).hasSingleBean(CastAdapter.class).hasSingleBean(CastDiscovery.class));
    }

    @Test
    void canBeSwitchedOff() {
        runner.withPropertyValues("home-control.cast.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(CastAdapter.class).doesNotHaveBean(CastDiscovery.class));
    }
}
