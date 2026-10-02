package dev.andre.homecontrol.themes;

import java.util.List;

public record ThemeDescriptor(String id, String name, String version, String author, String description,
                              String license, boolean builtIn, String revision, String stylesheet, String preview,
                              String themeColor, List<String> assets) {
    public ThemeDescriptor { assets = List.copyOf(assets); }
}
