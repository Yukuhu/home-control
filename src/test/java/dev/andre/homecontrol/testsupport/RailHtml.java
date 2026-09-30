package dev.andre.homecontrol.testsupport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads the rail fragment the dashboard renders ({@code GET /rails/{source}/{rail}}) the way a test needs it. */
public final class RailHtml {

    private static final Pattern STATUS = Pattern.compile("data-status=\"([A-Z]+)\"");

    private RailHtml() {
    }

    /** The rail's status ({@code LOADING}, {@code READY}, {@code FAILED}, …), or an empty string without one. */
    public static String status(String html) {
        Matcher matcher = STATUS.matcher(html);
        return matcher.find() ? matcher.group(1) : "";
    }

    /** The rail tile fragment's {@code data-*} attributes, one map per tile, in document order. */
    public static List<Map<String, String>> tiles(String html) {
        List<Map<String, String>> tiles = new ArrayList<>();
        // Whole <button ...> tags first (linear, no backtracking), then keep the tile buttons.
        Matcher buttonTag = Pattern.compile("<button[^>]*+>").matcher(html);
        Pattern attr = Pattern.compile("(data-[a-z-]+)=\"([^\"]*)\"");
        while (buttonTag.find()) {
            String tag = buttonTag.group();
            if (tag.contains("class=\"tile\"")) {
                Map<String, String> attrs = new LinkedHashMap<>();
                Matcher a = attr.matcher(tag);
                while (a.find()) {
                    attrs.put(a.group(1), a.group(2));
                }
                tiles.add(attrs);
            }
        }
        return tiles;
    }
}
