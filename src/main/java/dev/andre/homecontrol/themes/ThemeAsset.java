package dev.andre.homecontrol.themes;

import java.util.Arrays;
import java.util.Objects;

public record ThemeAsset(String contentType, byte[] bytes) {
    public ThemeAsset { bytes = bytes.clone(); }
    @Override public byte[] bytes() { return bytes.clone(); }

    @Override public boolean equals(Object other) {
        return other instanceof ThemeAsset asset
                && Objects.equals(contentType, asset.contentType) && Arrays.equals(bytes, asset.bytes);
    }

    @Override public int hashCode() {
        return 31 * Objects.hashCode(contentType) + Arrays.hashCode(bytes);
    }

    @Override public String toString() {
        return "ThemeAsset[contentType=" + contentType + ", bytes=" + bytes.length + "]";
    }
}
