package dev.andre.homecontrol.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class OnModuleConditionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Guarded.class);

    @Configuration(proxyBeanMethods = false)
    static class Guarded {

        @Bean
        @ConditionalOnModule(Module.JELLYFIN)
        String jellyfinBean() {
            return "jellyfin";
        }

        @Bean
        @ConditionalOnModule(Module.BLUETOOTH)
        String bluetoothBean() {
            return "bluetooth";
        }

        @Bean
        @ConditionalOnModule(Module.THESPORTSDB)
        String theSportsDbBean() {
            return "thesportsdb";
        }
    }

    @Test
    void aModuleIsOnByDefault() {
        runner.run(context -> assertThat(context).hasBean("jellyfinBean").hasBean("theSportsDbBean"));
    }

    @Test
    void aModuleCanBeSwitchedOff() {
        runner.withPropertyValues("home-control.jellyfin.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean("jellyfinBean"));
    }

    @Test
    void bluetoothIsOffUnlessSwitchedOn() {
        runner.run(context -> assertThat(context).doesNotHaveBean("bluetoothBean"));
        runner.withPropertyValues("home-control.bluetooth.enabled=true")
                .run(context -> assertThat(context).hasBean("bluetoothBean"));
    }

    @Test
    void aModuleIsOffWhenItsParentIs() {
        runner.withPropertyValues("home-control.sports.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean("theSportsDbBean"));
    }

    @Test
    void onlyTrueSwitchesAModuleOnAsBefore() {
        runner.withPropertyValues("home-control.jellyfin.enabled=TRUE")
                .run(context -> assertThat(context).hasBean("jellyfinBean"));
        runner.withPropertyValues("home-control.jellyfin.enabled=yes")
                .run(context -> assertThat(context).doesNotHaveBean("jellyfinBean"));
    }

    @Test
    void everyModuleNamesItsProperty() {
        assertThat(Module.THESPORTSDB.property()).isEqualTo("home-control.sports.thesportsdb.enabled");
        assertThat(Module.ANDROIDTV.property()).isEqualTo("home-control.androidtv.enabled");
        assertThat(Module.THESPORTSDB.parent()).contains(Module.SPORTS);
    }
}
