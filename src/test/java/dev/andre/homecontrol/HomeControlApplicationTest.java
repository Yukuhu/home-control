package dev.andre.homecontrol;

import dev.andre.homecontrol.adapters.tizen.TizenAdapter;
import dev.andre.homecontrol.adapters.webos.WebOsAdapter;
import dev.andre.homecontrol.core.PromptPairing;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class HomeControlApplicationTest {

    /** The modules a user can switch off; the modules-off tests switch all of them off at once. */
    private static final Set<String> SWITCHABLE = Set.of("jellyfin", "youtube", "tmdb", "pinned", "sports", "workflows",
            "cast", "webos", "tizen", "upnp", "sonos", "bluetooth");

    private static final Pattern MODULE_PACKAGE =
            Pattern.compile("^dev\\.andre\\.homecontrol\\.(?:sources|adapters)\\.([a-z]+)\\.");

    @Autowired
    ApplicationContext context;

    @Test
    void contextLoads() {
        assertThat(context.getBean(HomeControlApplication.class)).isNotNull();
    }

    @Test
    void bothTvModulesAreOnByDefault() {
        assertThat(context.getBeansOfType(PromptPairing.class)).hasSize(2);
        assertThat(context.getBeansOfType(WebOsAdapter.class)).hasSize(1);
        assertThat(context.getBeansOfType(TizenAdapter.class)).hasSize(1);
    }

    /**
     * No bean of one switchable module needs a bean of another, not even through a shared type such as {@code Clock}:
     * the application would then fail to start for a user who switches only the other module off, which neither this
     * context (everything on) nor the modules-off context (everything off) would show. An optional dependency
     * ({@code ObjectProvider}) is not registered as a dependency and stays allowed.
     */
    @Test
    void noSwitchableModuleNeedsAnotherModulesBean() {
        ConfigurableListableBeanFactory beans = ((ConfigurableApplicationContext) context).getBeanFactory();
        Set<String> modulesSeen = new TreeSet<>();
        List<String> crossings = new ArrayList<>();
        for (String name : beans.getBeanDefinitionNames()) {
            Optional<String> module = moduleOf(beans, name);
            if (module.isEmpty()) {
                continue;
            }
            modulesSeen.add(module.get());
            for (String dependency : beans.getDependenciesForBean(name)) {
                moduleOf(beans, dependency).filter(other -> !other.equals(module.get())).ifPresent(other ->
                        crossings.add(name + " (" + module.get() + ") needs " + dependency + " (" + other + ")"));
            }
        }

        // Every switchable module but Bluetooth, which is off by default, is on here: the check saw their beans.
        assertThat(modulesSeen).containsAll(SWITCHABLE.stream().filter(module -> !module.equals("bluetooth")).toList());
        assertThat(crossings).isEmpty();
    }

    /** The switchable module a bean belongs to: its factory's package for a {@code @Bean} method, else its own. */
    private static Optional<String> moduleOf(ConfigurableListableBeanFactory beans, String name) {
        if (!beans.containsBeanDefinition(name)) {
            return Optional.empty();
        }
        BeanDefinition definition = beans.getMergedBeanDefinition(name);
        String owner = definition.getFactoryBeanName() != null ? definition.getFactoryBeanName() : name;
        Class<?> type = beans.getType(owner);
        if (type == null) {
            return Optional.empty();
        }
        Matcher module = MODULE_PACKAGE.matcher(type.getPackageName() + ".");
        return module.find() && SWITCHABLE.contains(module.group(1)) ? Optional.of(module.group(1)) : Optional.empty();
    }
}
