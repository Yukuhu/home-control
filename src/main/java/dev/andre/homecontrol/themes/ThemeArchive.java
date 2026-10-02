package dev.andre.homecontrol.themes;

import dev.andre.homecontrol.config.Json;
import tools.jackson.databind.JsonNode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

final class ThemeArchive {
    static final int MAX_COMPRESSED = 10 * 1024 * 1024;
    static final int MAX_EXPANDED = 30 * 1024 * 1024;
    static final int MAX_ENTRIES = 256;
    static final int MAX_CSS = 256 * 1024;
    static final int MAX_JSON = 128 * 1024;
    private static final Set<String> ROOT_FILES = Set.of("theme.json", "tokens.json", "theme.css", "preview.png", "LICENSE");
    private static final String FORMAT_VERSION = "formatVersion";
    private static final String THEME_API_VERSION = "themeApiVersion";
    private static final Set<String> MANIFEST_FIELDS = Set.of(FORMAT_VERSION, THEME_API_VERSION, "id", "name", "version", "author", "description", "license");

    private static final tools.jackson.databind.json.JsonMapper STRICT_JSON = Json.MAPPER.rebuild()
            .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    private ThemeArchive() { }

    static Map<String, byte[]> read(byte[] bytes) {
        if (bytes.length > MAX_COMPRESSED) throw ThemeException.tooLarge("Theme ZIP exceeds 10 MiB.");
        Set<String> central = checkDirectory(bytes);
        Map<String, byte[]> files = new TreeMap<>();
        Set<String> seen = new HashSet<>();
        long total = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
                String name = entry.getName();
                path(name, entry.isDirectory());
                if (!seen.add(name) || !central.contains(name)) throw ThemeException.invalid("Duplicate or inconsistent ZIP path.");
                byte[] content = content(zip, entry, MAX_EXPANDED - total);
                total += content.length;
                if (!entry.isDirectory()) files.put(name, content);
            }
        } catch (IOException | IllegalArgumentException _) { throw ThemeException.invalid("Theme ZIP is damaged or unsupported."); }
        if (!seen.equals(central)) throw ThemeException.invalid("ZIP directory does not match its entries.");
        for (String required : Set.of("theme.json", "tokens.json", "LICENSE")) {
            if (!files.containsKey(required)) throw ThemeException.invalid("Theme package is missing " + required + ".");
        }
        return files;
    }

    private static byte[] content(ZipInputStream zip, ZipEntry entry, long remaining) throws IOException {
        int limit = entryLimit(entry.getName());
        ByteArrayOutputStream content = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        for (int count; (count = zip.read(buffer)) != -1;) {
            long expanded = (long) content.size() + count;
            if (expanded > remaining || expanded > limit) {
                throw ThemeException.tooLarge("Theme expanded content exceeds package limits.");
            }
            content.write(buffer, 0, count);
        }
        if (entry.isDirectory() && content.size() != 0) throw ThemeException.invalid("ZIP directories must be empty.");
        return content.toByteArray();
    }

    private static int entryLimit(String name) {
        if (name.endsWith(".json")) return MAX_JSON;
        if (name.equals("theme.css")) return MAX_CSS;
        return MAX_EXPANDED;
    }

    /** Check central metadata that ZipInputStream intentionally does not expose (links/encryption/duplicates). */
    private static Set<String> checkDirectory(byte[] bytes) {
        ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int end = directoryEnd(b, bytes.length);
        int count = u16(b, end + 10);
        if (count > MAX_ENTRIES) throw ThemeException.tooLarge("Theme ZIP exceeds 256 entries.");
        long start = Integer.toUnsignedLong(b.getInt(end + 16));
        long size = Integer.toUnsignedLong(b.getInt(end + 12));
        if (start + size != end || start > bytes.length || count == 0) throw ThemeException.invalid("Unsupported ZIP directory.");
        Set<String> names = new HashSet<>();
        int position = (int) start;
        for (int i = 0; i < count; i++) {
            position = directoryEntry(bytes, b, position, end, start, names);
        }
        if (position != end) throw ThemeException.invalid("Unsupported ZIP directory data.");
        return names;
    }

    private static int directoryEnd(ByteBuffer bytes, int length) {
        int end = -1;
        for (int position = length - 22; position >= Math.max(0, length - 65557); position--) {
            if (bytes.getInt(position) == 0x06054b50 && position + 22 + u16(bytes, position + 20) == length) {
                end = position;
                break;
            }
        }
        if (end < 0 || u16(bytes, end + 4) != 0 || u16(bytes, end + 6) != 0
                || u16(bytes, end + 8) != u16(bytes, end + 10)) {
            throw ThemeException.invalid("Unsupported or damaged ZIP directory.");
        }
        return end;
    }

    private static int directoryEntry(byte[] bytes, ByteBuffer metadata, int position, int end, long start, Set<String> names) {
        if (position + 46 > end || metadata.getInt(position) != 0x02014b50) throw ThemeException.invalid("Damaged ZIP entry.");
        int flags = u16(metadata, position + 8);
        int method = u16(metadata, position + 10);
        int nameLength = u16(metadata, position + 28);
        int next = position + 46 + nameLength + u16(metadata, position + 30) + u16(metadata, position + 32);
        int type = (metadata.getInt(position + 38) >>> 16) & 0170000;
        boolean supportedType = type == 0 || type == 0100000 || type == 0040000;
        if ((flags & ~0x080e) != 0 || (flags & 1) != 0 || (method != 0 && method != 8)
                || !supportedType || next > end || u16(metadata, position + 34) != 0) {
            throw ThemeException.invalid("Encrypted, linked or unsupported ZIP entry.");
        }
        String name = utf8(java.util.Arrays.copyOfRange(bytes, position + 46, position + 46 + nameLength));
        path(name, name.endsWith("/"));
        if (!names.add(name)) throw ThemeException.invalid("Duplicate ZIP path.");
        long local = Integer.toUnsignedLong(metadata.getInt(position + 42));
        if (local + 30 > start || metadata.getInt((int) local) != 0x04034b50) throw ThemeException.invalid("Damaged ZIP local entry.");
        return next;
    }

    private static int u16(ByteBuffer bytes, int position) { return Short.toUnsignedInt(bytes.getShort(position)); }

    static void path(String name, boolean directory) {
        if (ROOT_FILES.contains(name) && !directory) return;
        if (name.isEmpty() || name.length() > 200 || name.contains("..")) throw ThemeException.invalid("Invalid package path.");
        String plain = directory ? name.substring(0, name.length() - 1) : name;
        if (!(plain.equals("assets") && directory) && !plain.matches("assets/(?:[A-Za-z0-9_-]++/)*+[A-Za-z0-9_-]++(?:\\.[A-Za-z0-9_-]++)*+")) {
            throw ThemeException.invalid("Package paths must be canonical files under assets/.");
        }
        if (!directory && !(name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg")
                || name.endsWith(".webp") || name.endsWith(".woff2") || name.endsWith(".txt"))) {
            throw ThemeException.invalid("Unsupported theme asset type.");
        }
    }

    static ThemeManifest manifest(byte[] bytes, boolean builtIn) {
        JsonNode node = json(bytes);
        if (!node.isObject() || !node.propertyNames().equals(MANIFEST_FIELDS)) throw ThemeException.invalid("theme.json must contain exactly the version 1 manifest fields.");
        if (!node.path(FORMAT_VERSION).isIntegralNumber() || !node.path(FORMAT_VERSION).canConvertToInt() || node.path(FORMAT_VERSION).asInt() != 1
                || !node.path(THEME_API_VERSION).isIntegralNumber() || !node.path(THEME_API_VERSION).canConvertToInt() || node.path(THEME_API_VERSION).asInt() != 1) {
            throw ThemeException.invalid("Unsupported theme format or theme API version; this application supports version 1.");
        }
        String id = metadata(node, "id", 64, true);
        if (!validId(id)) throw ThemeException.invalid("Theme ID must start with a lowercase letter and use lowercase letters, digits or hyphens (up to 64 characters).");
        if (!builtIn && reserved(id)) throw new ThemeException(409, "This theme ID is reserved. Change its ID to import an edited built-in theme.");
        return new ThemeManifest(1, 1, id, metadata(node, "name", 120, true), metadata(node, "version", 64, true),
                metadata(node, "author", 120, false), metadata(node, "description", 2000, false), metadata(node, "license", 200, true));
    }

    static boolean validId(String id) { return id != null && id.matches("[a-z][a-z0-9-]{0,63}"); }
    static boolean reserved(String id) { return "default".equals(id) || "cyberpunk".equals(id); }
    private static String metadata(JsonNode node, String key, int maximum, boolean required) {
        JsonNode value = node.path(key);
        if (!value.isString()) throw ThemeException.invalid("Manifest " + key + " must be text.");
        String text = value.asString();
        if (text.length() > maximum || (required && text.isBlank()) || text.codePoints().anyMatch(Character::isISOControl)) {
            throw ThemeException.invalid("Manifest " + key + " is empty, too long, or contains control characters.");
        }
        return text;
    }

    static JsonNode json(byte[] bytes) {
        if (bytes.length > MAX_JSON) throw ThemeException.tooLarge("Theme JSON exceeds 128 KiB.");
        try {
            JsonNode node = STRICT_JSON.readTree(utf8(bytes));
            if (node == null) throw ThemeException.invalid("Theme JSON cannot be empty.");
            return node;
        } catch (tools.jackson.core.JacksonException _) { throw ThemeException.invalid("Theme JSON is malformed."); }
    }

    static String utf8(byte[] bytes) {
        try { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (CharacterCodingException _) { throw ThemeException.invalid("Theme text must be UTF-8."); }
    }

    static String revision(Map<String, byte[]> files) {
        Map<String, byte[]> compilation = new TreeMap<>(files);
        compilation.put("@contract/token-schema.json", ThemeTokens.resource("/themes/token-schema.json"));
        compilation.put("@contract/default-tokens.json", ThemeTokens.resource("/themes/default/tokens.json"));
        return digest(compilation, "home-control-theme-api-1-compiler-1");
    }

    static String sourceRevision(Map<String, byte[]> files) { return digest(files, "home-control-theme-source-1"); }

    private static String digest(Map<String, byte[]> files, String contract) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(contract.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            new TreeMap<>(files).forEach((path, bytes) -> {
                digest.update(path.getBytes(StandardCharsets.UTF_8)); digest.update((byte) 0);
                digest.update(ByteBuffer.allocate(Long.BYTES).putLong(bytes.length).array()); digest.update(bytes);
            });
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    static byte[] write(Map<String, byte[]> files) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
                for (var file : new TreeMap<>(files).entrySet()) {
                    ZipEntry entry = new ZipEntry(file.getKey()); entry.setTime(0);
                    zip.putNextEntry(entry); zip.write(file.getValue()); zip.closeEntry();
                }
            }
            return bytes.toByteArray();
        } catch (IOException e) { throw new ThemeException(507, "Could not export the theme package.", e); }
    }
}
