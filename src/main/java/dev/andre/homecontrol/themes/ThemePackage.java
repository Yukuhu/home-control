package dev.andre.homecontrol.themes;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** An immutable validation result; original source bytes are kept separately from compiled presentation. */
public final class ThemePackage {
    private static final String STYLESHEET = "theme.css";
    private final ThemeManifest manifest;
    private final String revision;
    private final String sourceRevision;
    private final Map<String, String> tokens;
    private final Map<String, byte[]> files;
    private final Map<String, ThemeAsset> presentation;

    ThemePackage(ThemeManifest manifest, String revision, Map<String, String> tokens, Map<String, byte[]> files,
                 String compiled, Map<String, String> contentTypes) {
        this.manifest = manifest;
        this.revision = revision;
        this.sourceRevision = ThemeArchive.sourceRevision(files);
        this.tokens = Map.copyOf(tokens);
        this.files = copy(files);
        Map<String, ThemeAsset> assets = new LinkedHashMap<>();
        assets.put(prefix() + STYLESHEET, new ThemeAsset("text/css", compiled.getBytes(StandardCharsets.UTF_8)));
        contentTypes.forEach((name, type) -> assets.put(prefix() + name, new ThemeAsset(type, files.get(name))));
        this.presentation = Map.copyOf(assets);
    }

    public ThemeManifest manifest() { return manifest; }
    public String revision() { return revision; }
    String sourceRevision() { return sourceRevision; }
    public Map<String, String> tokens() { return tokens; }
    public Map<String, byte[]> files() { return copy(files); }
    Map<String, ThemeAsset> presentation() { return presentation; }
    long storedSize() { return files.values().stream().mapToLong(b -> b.length).sum()
            + presentation.get(prefix() + STYLESHEET).bytes().length; }
    String prefix() { return "/themes/packages/" + manifest.id() + "/" + revision + "/"; }

    public ThemeDescriptor descriptor(boolean builtIn) {
        List<String> assets = presentation.keySet().stream().sorted().toList();
        return new ThemeDescriptor(manifest.id(), manifest.name(), manifest.version(), manifest.author(),
                manifest.description(), manifest.license(), builtIn, revision, prefix() + STYLESHEET,
                files.containsKey("preview.png") ? prefix() + "preview.png" : null, tokens.get("theme-color"), assets);
    }

    private static Map<String, byte[]> copy(Map<String, byte[]> input) {
        Map<String, byte[]> result = new LinkedHashMap<>();
        input.forEach((key, value) -> result.put(key, value.clone()));
        return java.util.Collections.unmodifiableMap(result);
    }
}
