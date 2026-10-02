package dev.andre.homecontrol.config;

/** Exact raw request paths for presentation assets that must be available before login. */
@FunctionalInterface
public interface PublicAssetPaths {
    boolean contains(String rawPath);
}
