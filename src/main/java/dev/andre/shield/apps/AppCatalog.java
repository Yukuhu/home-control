package dev.andre.shield.apps;

import dev.andre.shield.ShieldProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The launcher's list of apps, read from a user-editable YAML file. */
@Component
public class AppCatalog {

    private static final Logger log = LoggerFactory.getLogger(AppCatalog.class);
    private static final String BUNDLED = "/default-apps.yaml";

    private final Path file;
    private volatile List<AppEntry> entries;

    /** Two constructors, so Spring needs to be told which one to use. */
    @Autowired
    public AppCatalog(ShieldProperties properties) {
        this(properties.appsFile());
    }

    public AppCatalog(Path file) {
        this.file = file;
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

    /** Adds a package reported by the device and persists it for future starts. */
    public synchronized AppEntry addPackage(String appPackage) {
        if (appPackage == null || appPackage.isBlank()) {
            throw new IllegalArgumentException("The app package must not be blank");
        }

        Optional<AppEntry> existing = byPackage(appPackage);
        if (existing.isPresent()) {
            return existing.get();
        }

        AppEntry added = new AppEntry(uniqueId(appPackage), appPackage, appPackage, null);
        List<AppEntry> updated = new ArrayList<>(entries);
        updated.add(added);
        writeAll(file, updated);
        entries = List.copyOf(updated);
        return added;
    }

    private String uniqueId(String appPackage) {
        String candidate = appPackage;
        int suffix = 2;
        while (byId(candidate).isPresent()) {
            candidate = appPackage + "-" + suffix++;
        }
        return candidate;
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

        Object appsValue = document.get("apps");
        if (appsValue == null) {
            return List.of();
        }

        if (!(appsValue instanceof List)) {
            log.warn("The 'apps' key must contain a list, not {}", appsValue.getClass().getSimpleName());
            return List.of();
        }

        List<Map<String, String>> apps = (List<Map<String, String>>) appsValue;

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

    private static void writeAll(Path file, List<AppEntry> entries) {
        List<Map<String, String>> apps = new ArrayList<>();
        for (AppEntry entry : entries) {
            Map<String, String> app = new LinkedHashMap<>();
            app.put("id", entry.id());
            app.put("name", entry.name());
            app.put("package", entry.appPackage());
            if (entry.deepLink() != null && !entry.deepLink().isBlank()) {
                app.put("deepLink", entry.deepLink());
            }
            apps.add(app);
        }

        Path parent = file.toAbsolutePath().getParent();
        Path temp = null;
        try {
            Files.createDirectories(parent);
            temp = Files.createTempFile(parent, "apps", ".yaml");
            String yaml = new Yaml().dump(Map.of("apps", apps));
            Files.writeString(temp, yaml, StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // Cleanup error; let the original exception propagate.
                }
            }
            throw new IllegalStateException("Could not write the app catalog " + file, e);
        }
    }

    private static boolean isBlankString(Object value) {
        if (!(value instanceof String)) {
            return true;
        }
        return ((String) value).isBlank();
    }
}
