package dev.andre.homecontrol.themes;

public record ThemeAsset(String contentType, byte[] bytes) {
    public ThemeAsset { bytes = bytes.clone(); }
    @Override public byte[] bytes() { return bytes.clone(); }
}
