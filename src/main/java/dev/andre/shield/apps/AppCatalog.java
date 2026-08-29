package dev.andre.shield.apps;

import dev.andre.shield.ShieldProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The launcher's list of apps, read from a user-editable YAML file. */
@Component
public class AppCatalog {

    private static final Logger log = LoggerFactory.getLogger(AppCatalog.class);
    private static final String BUNDLED = "/default-apps.yaml";

    private final List<AppEntry> entries;

    /** Two constructors, so Spring needs to be told which one to use. */
    @Autowired
    public AppCatalog(ShieldProperties properties) {
        this(properties.appsFile());
    }

    public AppCatalog(Path file) {
        this.entries = load(file);
    }

    public List<AppEntry> entries() {
        return entries;
    }

    public Optional<AppEntry> byId(String id) {
        return entries.stream().filter(entry -> entry.id().equals(id)).findFirst();
    }

    public Optional<AppEntry> byPackage(String appPackage) {
        return entries.stream().filter(entry -> entry.appPackage().equals(appPackage)).findFirst();
    }

    private static List<AppEntry> load(Path file) {
        try {
            if (!Files.exists(file)) {
                Files.createDirectories(file.toAbsolutePath().getParent());
                try (InputStream bundled = AppCatalog.class.getResourceAsStream(BUNDLED)) {
                    if (bundled == null) {
                        throw new IllegalStateException("Bundled catalog resource not found: " + BUNDLED);
                    }
                    Files.write(file, bundled.readAllBytes());
                }
            }
            try (InputStream in = Files.newInputStream(file)) {
                return parse(in);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not read the app catalog " + file, e);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<AppEntry> parse(InputStream in) {
        Map<String, Object> document = new Yaml().load(in);
        if (document == null) {
            return List.of();
        }

        List<Map<String, String>> apps =
                (List<Map<String, String>>) document.getOrDefault("apps", List.of());

        List<AppEntry> entries = new ArrayList<>();
        for (Map<String, String> app : apps) {
            String id = app.get("id");
            String name = app.get("name");
            String appPackage = app.get("package");

            if (isBlankString(id) || isBlankString(name) || isBlankString(appPackage)) {
                log.warn("Skipping malformed app entry (missing or blank id, name, or package): {}", app);
                continue;
            }

            entries.add(new AppEntry(id, name, appPackage, app.get("deepLink")));
        }
        return List.copyOf(entries);
    }

    private static boolean isBlankString(Object value) {
        if (!(value instanceof String)) {
            return true;
        }
        return ((String) value).isBlank();
    }
}
