package dev.andre.homecontrol.themes;

import dev.andre.homecontrol.config.Json;
import dev.andre.homecontrol.storage.AtomicFiles;
import dev.andre.homecontrol.storage.DataDirectory;
import dev.andre.homecontrol.storage.StorageException;
import dev.andre.homecontrol.storage.VersionedJsonFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Validates immutable revisions before atomically publishing a versioned catalog snapshot. */
public final class ThemeCatalog {
    private static final int MAX_IMPORTS = 32;
    private static final long MAX_STORAGE = 256L * 1024 * 1024;
    private static final String THEMES = "themes";
    private static final String TOKENS = "tokens.json";
    private static final String STYLESHEET = "theme.css";
    private static final String REVISION_PATTERN = "[a-f0-9]{64}";
    private final Path directory;
    private final ThemeTokens tokenSchema = new ThemeTokens();
    private final VersionedJsonFile<Map<String, Revision>> registry;
    private final Map<String, ThemePackage> builtins = new LinkedHashMap<>();
    private final Map<String, ThemePackage> installed = new LinkedHashMap<>();
    private final Map<String, ThemePackage> retained = new LinkedHashMap<>();
    private final Map<String, ThemeAsset> publicAssets = new HashMap<>();
    private final List<String> problems = new ArrayList<>();
    private Map<String, Revision> entries = Map.of();
    private boolean damagedRegistry;

    public ThemeCatalog(DataDirectory data) {
        directory = data.resolve(THEMES).toAbsolutePath().normalize();
        registry = new VersionedJsonFile<>(directory.resolve("catalog.json"), "the theme catalog", 1,
                Map::of, ThemeCatalog::readRegistry, ThemeCatalog::writeRegistry);
        for (String id : List.of("default", "cyberpunk")) {
            ThemePackage theme = bundled(id);
            builtins.put(id, theme);
            publicAssets.putAll(theme.presentation());
        }
        load();
    }

    public synchronized List<ThemeDescriptor> themes() {
        List<ThemeDescriptor> result = new ArrayList<>();
        builtins.values().forEach(theme -> result.add(theme.descriptor(true)));
        installed.values().stream().sorted(Comparator.comparing(t -> t.manifest().id())).forEach(theme -> result.add(theme.descriptor(false)));
        return List.copyOf(result);
    }

    public synchronized ThemeDescriptor require(String id) { return requirePackage(id).descriptor(builtins.containsKey(id)); }
    public ThemePackage inspect(byte[] bytes) { return validate(ThemeArchive.read(bytes), false); }
    public synchronized List<String> problems() { return List.copyOf(problems); }
    public String defaultColor() { return builtins.get("default").tokens().get("theme-color"); }
    public synchronized Optional<ThemeAsset> asset(String rawPath) { return Optional.ofNullable(publicAssets.get(rawPath)); }
    public synchronized byte[] export(String id) {
        ThemePackage theme = requirePackage(id);
        Map<String, byte[]> source = new LinkedHashMap<>(theme.files());
        // Export all additive token defaults while retaining original author CSS and relative asset paths.
        source.put(TOKENS, Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(new java.util.TreeMap<>(theme.tokens())));
        return ThemeArchive.write(source);
    }

    public synchronized ThemeDescriptor install(byte[] bytes, String expectedRevision) {
        writable();
        ThemePackage candidate = inspect(bytes);
        String id = candidate.manifest().id();
        Revision previous = entries.get(id);
        ThemePackage priorPackage = installed.get(id);
        String current = priorPackage == null ? null : priorPackage.revision();
        if (!Objects.equals(current, expectedRevision)) throw new ThemeException(409, "This theme changed after review. Review the current theme and try again.");
        if (candidate.revision().equals(current)) throw new ThemeException(409, "This theme package is already installed.");
        if (previous == null && entries.size() >= MAX_IMPORTS) throw new ThemeException(507, "The limit of 32 imported themes has been reached.");
        Path revisionPath = revisionPath(id, candidate.sourceRevision());
        boolean published = false;
        try {
            if (storedBytes() + (Files.exists(revisionPath, LinkOption.NOFOLLOW_LINKS) ? 0 : candidate.storedSize()) > MAX_STORAGE) {
                throw new ThemeException(507, "Imported theme storage exceeds the 256 MiB limit, including retained revisions.");
            }
            published = publish(candidate);
            Map<String, Revision> next = new LinkedHashMap<>(entries);
            next.put(id, new Revision(candidate.sourceRevision(), previous == null ? null : previous.current()));
            registry.write(Map.copyOf(next)); // Durable commit point: mutate the in-memory view only after this succeeds.
            entries = Map.copyOf(next);
            installed.put(id, candidate);
            revoke(id);
            retained.remove(id);
            if (priorPackage != null) { retained.put(id, priorPackage); publicAssets.putAll(priorPackage.presentation()); }
            publicAssets.putAll(candidate.presentation());
            if (previous != null && previous.previous() != null && !previous.previous().equals(candidate.sourceRevision())) reclaim(id, previous.previous());
            return candidate.descriptor(false);
        } catch (IOException | StorageException e) {
            if (published) reclaim(id, candidate.sourceRevision());
            throw new ThemeException(507, "Could not save the theme. The previous catalog is unchanged; check available storage and permissions.", e);
        }
    }

    public synchronized void remove(String id) {
        protect(id);
        writable();
        Revision old = entries.get(id);
        if (old == null) throw new ThemeException(404, "Theme was not found.");
        Map<String, Revision> next = new LinkedHashMap<>(entries);
        next.remove(id);
        try { registry.write(Map.copyOf(next)); }
        catch (StorageException e) { throw new ThemeException(507, "Could not remove the theme; the catalog is unchanged.", e); }
        entries = Map.copyOf(next);
        installed.remove(id);
        retained.remove(id);
        revoke(id);
        reclaim(id, old.current());
        if (old.previous() != null) reclaim(id, old.previous());
    }

    private ThemePackage requirePackage(String id) {
        ThemePackage theme = builtins.get(id);
        if (theme == null) theme = installed.get(id);
        if (theme == null) throw new ThemeException(404, "Theme was not found.");
        return theme;
    }

    private ThemePackage validate(Map<String, byte[]> files, boolean builtin) {
        ThemeManifest manifest = ThemeArchive.manifest(files.get("theme.json"), builtin);
        Map<String, String> tokens = tokenSchema.read(files.get(TOKENS));
        Map<String, String> types = ThemeAssets.validate(files);
        String revision = ThemeArchive.revision(files);
        String css = ThemeArchive.utf8(files.getOrDefault(STYLESHEET, new byte[0]));
        if (css.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > ThemeArchive.MAX_CSS) throw ThemeException.tooLarge("Theme CSS exceeds 256 KiB.");
        String compiled = new ThemeCss(manifest.id(), revision, types).compile(tokens, css);
        return new ThemePackage(manifest, revision, tokens, files, compiled, types);
    }

    private ThemePackage bundled(String id) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        String marker = "/themes/" + id + "/";
        var resolver = new org.springframework.core.io.support.PathMatchingResourcePatternResolver();
        try {
            for (var resource : resolver.getResources("classpath*:themes/" + id + "/**")) {
                String url = resource.getURL().toExternalForm();
                String name = url.substring(url.lastIndexOf(marker) + marker.length());
                if (name.isEmpty() || name.endsWith("/")) continue;
                ThemeArchive.path(name, false);
                try (var input = resource.getInputStream()) { files.put(name, input.readAllBytes()); }
            }
        } catch (IOException e) { throw new IllegalStateException("Cannot load bundled theme " + id, e); }
        return validate(files, true);
    }

    private void load() {
        try {
            if (Files.isSymbolicLink(directory) || Files.isSymbolicLink(registry.file())) throw new StorageException("Theme catalog cannot be a symbolic link.");
            entries = registry.read();
        } catch (StorageException _) {
            damagedRegistry = true;
            problems.add("The theme catalog is unreadable or incompatible. It has been preserved; restore or repair /data/themes/catalog.json before changing themes.");
            return;
        }
        entries.forEach((id, revision) -> {
            try {
                ThemePackage theme = readRevision(id, revision.current());
                installed.put(id, theme);
                publicAssets.putAll(theme.presentation());
            } catch (RuntimeException | IOException _) { problems.add("Imported theme '" + id + "' is damaged or incompatible and has been excluded."); }
            if (revision.previous() != null) {
                try {
                    ThemePackage previous = readRevision(id, revision.previous());
                    retained.put(id, previous);
                    publicAssets.putAll(previous.presentation());
                }
                catch (RuntimeException | IOException _) { problems.add("A retained revision of theme '" + id + "' is unavailable."); }
            }
        });
    }

    private ThemePackage readRevision(String id, String revision) throws IOException {
        Path source = revisionPath(id, revision).resolve("source");
        noLinks(source);
        Map<String, byte[]> files = new LinkedHashMap<>();
        long total = 0;
        try (var paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                if (Files.isSymbolicLink(path)) throw ThemeException.invalid("Theme files cannot be symbolic links.");
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) continue;
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw ThemeException.invalid("Theme source is not a file.");
                String name = source.relativize(path).toString().replace(path.getFileSystem().getSeparator(), "/");
                ThemeArchive.path(name, false);
                total += Files.size(path);
                if (total > ThemeArchive.MAX_EXPANDED || files.size() >= ThemeArchive.MAX_ENTRIES) throw ThemeException.tooLarge("Stored theme exceeds package limits.");
                files.put(name, Files.readAllBytes(path));
            }
        }
        if (!files.keySet().containsAll(Set.of("theme.json", TOKENS, "LICENSE"))) throw ThemeException.invalid("Incomplete stored theme.");
        ThemePackage theme = validate(files, false);
        if (!theme.manifest().id().equals(id) || !theme.sourceRevision().equals(revision)) throw ThemeException.invalid("Stored theme revision does not match the catalog.");
        return theme;
    }

    private boolean publish(ThemePackage theme) throws IOException {
        Path target = revisionPath(theme.manifest().id(), theme.sourceRevision());
        noLinks(target);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            readRevision(theme.manifest().id(), theme.sourceRevision());
            return false;
        }
        Files.createDirectories(target.getParent());
        Path staging = Files.createTempDirectory(target.getParent(), ".staging-");
        try {
            for (var file : theme.files().entrySet()) AtomicFiles.write(staging.resolve("source").resolve(file.getKey()), file.getValue(), false);
            AtomicFiles.write(staging.resolve(STYLESHEET), theme.presentation().get(theme.prefix() + STYLESHEET).bytes(), false);
            Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
            return true;
        } finally { deleteTree(staging); }
    }

    private long storedBytes() throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return 0;
        noLinks(directory);
        long total = 0;
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.toList()) {
                if (Files.isSymbolicLink(path)) throw new IOException("Symbolic links are not valid theme storage.");
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) total += Files.size(path);
            }
        }
        return total;
    }

    private void writable() {
        if (damagedRegistry) throw new ThemeException(409, "The theme catalog is damaged or incompatible. Restore or repair it before changing themes.");
    }
    private static void protect(String id) {
        if (ThemeArchive.reserved(id)) throw new ThemeException(409, "Built-in themes cannot be removed or replaced.");
    }
    private Path revisionPath(String id, String revision) {
        if (!ThemeArchive.validId(id) || ThemeArchive.reserved(id) || !revision.matches(REVISION_PATTERN)) {
            throw ThemeException.invalid("Invalid imported theme storage path.");
        }
        return directory.resolve("packages").resolve(id).resolve(revision);
    }
    private void noLinks(Path path) throws IOException {
        Path part = directory;
        if (Files.isSymbolicLink(part)) throw new IOException("Theme storage is a symbolic link.");
        for (Path segment : directory.relativize(path)) {
            part = part.resolve(segment);
            if (Files.isSymbolicLink(part)) throw new IOException("Theme storage is a symbolic link.");
        }
    }
    private void revoke(String id) { publicAssets.keySet().removeIf(path -> path.startsWith("/themes/packages/" + id + "/")); }
    private void reclaim(String id, String revision) {
        try { deleteTree(revisionPath(id, revision)); }
        catch (IOException _) { problems.add("An unused revision of theme '" + id + "' could not be reclaimed."); }
    }
    private void deleteTree(Path root) throws IOException {
        Path packages = directory.resolve("packages");
        Path normalized = root.toAbsolutePath().normalize();
        if (!normalized.startsWith(packages) || normalized.equals(packages)) {
            throw new IOException("Theme cleanup must stay inside package storage.");
        }
        noLinks(normalized);
        if (!Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) return;
        try (var paths = Files.walk(normalized)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    private static Map<String, Revision> readRegistry(JsonNode document) {
        JsonNode themes = document.path(THEMES);
        if (!themes.isObject()) throw new IllegalArgumentException("Theme catalog needs a themes object.");
        Map<String, Revision> entries = new LinkedHashMap<>();
        themes.properties().forEach(field -> {
            String id = field.getKey();
            if (!ThemeArchive.validId(id) || ThemeArchive.reserved(id)) throw new IllegalArgumentException("Invalid imported theme identity.");
            String current = field.getValue().path("current").asString("");
            JsonNode previousNode = field.getValue().path("previous");
            String previous = previousNode.isString() ? previousNode.asString() : null;
            if (!current.matches(REVISION_PATTERN) || (previous != null && !previous.matches(REVISION_PATTERN))) throw new IllegalArgumentException("Invalid imported theme revision.");
            entries.put(id, new Revision(current, previous));
        });
        if (entries.size() > MAX_IMPORTS) throw new IllegalArgumentException("Too many imported themes.");
        return Map.copyOf(entries);
    }
    private static ObjectNode writeRegistry(Map<String, Revision> entries) {
        ObjectNode document = Json.MAPPER.createObjectNode();
        ObjectNode themes = document.putObject(THEMES);
        new java.util.TreeMap<>(entries).forEach((id, revision) -> {
            ObjectNode item = themes.putObject(id).put("current", revision.current());
            if (revision.previous() != null) item.put("previous", revision.previous());
        });
        return document;
    }
    private record Revision(String current, String previous) { }
}
