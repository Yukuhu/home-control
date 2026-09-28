package dev.andre.homecontrol.config;

import org.apache.commons.logging.Log;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.lang.reflect.RecordComponent;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Every old key in the table reaches a component of a properties record under its new name, with its value. */
class LegacyPropertyNamesBindingTest {

    private static final Map<String, Object> OLD_VALUES = Map.of(
            "shield.data-dir", "/srv/legacy", "shield.discovery-enabled", "false",
            "shield.keystore-password", "legacy-password");

    @Test
    void everyOldKeyBindsUnderItsNewName() throws Exception {
        List<LegacyPropertyNames.Rename> renames = LegacyPropertyNames.RENAMES;
        StandardEnvironment environment = new StandardEnvironment();
        Map<String, Object> old = new HashMap<>();
        for (int i = 0; i < renames.size(); i++) {
            LegacyPropertyNames.Rename rename = renames.get(i);
            // Distinct and increasing, so records that compare two waits (stale above heartbeat) stay valid.
            old.put(rename.oldName(), rename.seconds() ? String.valueOf(100 + i) : OLD_VALUES.get(rename.oldName()));
        }
        environment.getPropertySources().addFirst(new MapPropertySource("old", old));
        LegacyPropertyNames.apply(environment, renames, mock(Log.class));
        Binder binder = Binder.get(environment);

        List<Class<?>> records = propertiesRecords();
        for (int i = 0; i < renames.size(); i++) {
            LegacyPropertyNames.Rename rename = renames.get(i);
            Object expected = rename.seconds() ? Duration.ofSeconds(100 + i) : expected(rename.oldName());
            assertThat(bound(binder, records, rename.newName()))
                    .as(rename.oldName() + " -> " + rename.newName()).isEqualTo(expected);
        }
    }

    private static Object expected(String oldName) {
        return switch (oldName) {
            case "shield.data-dir" -> Path.of("/srv/legacy");
            case "shield.discovery-enabled" -> false;
            default -> OLD_VALUES.get(oldName);
        };
    }

    private static List<Class<?>> propertiesRecords() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(ConfigurationProperties.class));
        List<Class<?>> records = new ArrayList<>();
        for (BeanDefinition candidate : scanner.findCandidateComponents("dev.andre.homecontrol")) {
            records.add(Class.forName(candidate.getBeanClassName()));
        }
        return records;
    }

    /**
     * Binds the record with the longest prefix of {@code name}, then walks the rest of the name through components,
     * matching them as Spring's relaxed binding does: case and dashes do not count ({@code thesportsdb} is
     * {@code theSportsDb}).
     */
    private static Object bound(Binder binder, List<Class<?>> records, String name) throws Exception {
        Class<?> owner = records.stream()
                .filter(type -> name.startsWith(prefix(type) + "."))
                .max(Comparator.comparingInt(type -> prefix(type).length()))
                .orElseThrow(() -> new AssertionError("No properties record binds " + name));
        Object value = binder.bindOrCreate(prefix(owner), owner);
        for (String segment : name.substring(prefix(owner).length() + 1).split("\\.")) {
            Optional<RecordComponent> component = Arrays.stream(value.getClass().getRecordComponents())
                    .filter(each -> each.getName().equalsIgnoreCase(segment.replace("-", ""))).findFirst();
            assertThat(component).as(name + " has no component " + segment).isPresent();
            value = component.get().getAccessor().invoke(value);
        }
        return value;
    }

    private static String prefix(Class<?> type) {
        ConfigurationProperties annotation = type.getAnnotation(ConfigurationProperties.class);
        return annotation.value().isEmpty() ? annotation.prefix() : annotation.value();
    }
}
