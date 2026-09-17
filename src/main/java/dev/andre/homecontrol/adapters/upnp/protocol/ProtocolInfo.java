package dev.andre.homecontrol.adapters.upnp.protocol;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** A renderer's ConnectionManager sink list, reduced to what it can fetch over HTTP. */
public final class ProtocolInfo {

    public static final ProtocolInfo UNKNOWN = new ProtocolInfo(false, List.of());

    private static final List<Set<String>> ALIASES = List.of(
            Set.of("audio/mpeg", "audio/mp3", "audio/x-mpeg"),
            Set.of("audio/flac", "audio/x-flac"),
            Set.of("audio/mp4", "audio/x-m4a", "audio/m4a"),
            Set.of("audio/ogg", "application/ogg", "audio/x-ogg"),
            Set.of("audio/wav", "audio/x-wav", "audio/wave"),
            Set.of("video/x-matroska", "video/x-mkv"));

    private final boolean known;
    /** Content formats of {@code http-get} entries, spelled as the renderer spells them. */
    private final List<String> httpFormats;

    private ProtocolInfo(boolean known, List<String> httpFormats) {
        this.known = known;
        this.httpFormats = List.copyOf(httpFormats);
    }

    public static ProtocolInfo parseSink(String sink) {
        if (sink == null || sink.isBlank()) {
            return UNKNOWN;
        }
        List<String> formats = new ArrayList<>();
        for (String entry : sink.split(",")) {
            String[] parts = entry.strip().split(":", 4);
            if (parts.length == 4 && parts[0].equalsIgnoreCase("http-get") && !parts[2].isBlank()) {
                formats.add(parts[2].strip());
            }
        }
        return new ProtocolInfo(true, formats);
    }

    public boolean known() {
        return known;
    }

    /** The content format to announce for {@code mimeType}, or empty when the renderer cannot fetch it over HTTP. */
    public Optional<String> match(String mimeType) {
        if (!known) {
            return Optional.of(mimeType);
        }
        String wanted = base(mimeType);
        for (String format : httpFormats) {
            if (format.equals("*")) {
                return Optional.of(mimeType);
            }
            if (base(format).equals(wanted)) {
                return Optional.of(format);
            }
        }
        Set<String> family = ALIASES.stream().filter(set -> set.contains(wanted)).findFirst().orElse(Set.of());
        return httpFormats.stream().filter(format -> family.contains(base(format))).findFirst();
    }

    private static String base(String mimeType) {
        int semicolon = mimeType.indexOf(';');
        return (semicolon < 0 ? mimeType : mimeType.substring(0, semicolon)).strip().toLowerCase(Locale.ROOT);
    }
}
