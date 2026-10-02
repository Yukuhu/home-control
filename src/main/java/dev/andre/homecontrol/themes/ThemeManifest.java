package dev.andre.homecontrol.themes;

public record ThemeManifest(int formatVersion, int themeApiVersion, String id, String name, String version,
                            String author, String description, String license) { }
