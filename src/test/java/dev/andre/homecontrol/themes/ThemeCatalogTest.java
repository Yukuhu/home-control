package dev.andre.homecontrol.themes;

import dev.andre.homecontrol.storage.DataDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.*;
import static dev.andre.homecontrol.themes.ThemeTestPackages.*;

class ThemeCatalogTest {
    @TempDir Path data;
    private ThemeCatalog catalog() { return new ThemeCatalog(new DataDirectory(data)); }

    @Test void installsRestartsUpdatesWithCompareAndSwapAndRetainsOneRevision() {
        ThemeCatalog catalog = catalog();
        byte[] original = zip(files("custom", ":root { color: #123; }"));
        var first = catalog.install(original, null);
        assertThat(catalog().require("custom").revision()).isEqualTo(first.revision());
        assertThatThrownBy(() -> catalog.install(original, first.revision())).hasMessageContaining("already installed");
        byte[] update = zip(files("custom", ":root { color: #234; }"));
        assertThatThrownBy(() -> catalog.install(update, null)).isInstanceOf(ThemeException.class);
        var second = catalog.install(update, first.revision());
        assertThat(catalog.asset(first.stylesheet())).isPresent();
        assertThatThrownBy(() -> catalog.install(original, first.revision())).isInstanceOf(ThemeException.class);
        var third = catalog.install(zip(files("custom", ":root { color: #345; }")), second.revision());
        assertThat(catalog.asset(first.stylesheet())).isEmpty();
        assertThat(catalog().asset(second.stylesheet())).isPresent();
        catalog.remove("custom");
        assertThat(catalog.asset(third.stylesheet())).isEmpty();
        assertThat(catalog().themes()).extracting(ThemeDescriptor::id).containsExactly("default", "cyberpunk");
    }

    @Test void exposesOnlyExactCommittedPresentationPaths() {
        ThemeCatalog catalog = catalog();
        var inspected = catalog.inspect(zip(files("custom", null)));
        assertThat(catalog.asset(inspected.descriptor(false).stylesheet())).isEmpty();
        var installed = catalog.install(zip(files("custom", null)), null);
        String path = installed.stylesheet();
        assertThat(catalog.asset(path)).isPresent();
        for (String attack : new String[] {path + "?x", path + "/", path.replace("theme.css", "theme.json"),
                path.replace("/custom/", "/%63ustom/"), path.replace("/custom/", "/custom//"),
                path.replace("theme.css", "../theme.css"), path.replace("theme.css", "LICENSE")}) {
            assertThat(catalog.asset(attack)).as(attack).isEmpty();
        }
    }

    @Test void damagedCatalogKeepsBuiltinsAndRefusesMutationWithoutOverwriting() throws Exception {
        Files.createDirectories(data.resolve("themes"));
        Path registry = data.resolve("themes/catalog.json");
        Files.writeString(registry, "broken original");
        ThemeCatalog catalog = catalog();
        assertThat(catalog.themes()).hasSize(2);
        assertThat(catalog.problems()).isNotEmpty();
        assertThatThrownBy(() -> catalog.install(zip(files("custom", null)), null)).isInstanceOf(ThemeException.class);
        assertThat(Files.readString(registry)).isEqualTo("broken original");
    }

    @Test void failedCatalogWriteKeepsOldRevisionAndDoesNotExposeCandidate() throws Exception {
        ThemeCatalog catalog = catalog();
        var original = catalog.install(zip(files("custom", ":root { color: #123; }")), null);
        Path registry = data.resolve("themes/catalog.json");
        Files.delete(registry);
        Files.createDirectory(registry);
        byte[] candidate = zip(files("custom", ":root { color: #234; }"));
        var reviewed = catalog.inspect(candidate);
        assertThatThrownBy(() -> catalog.install(candidate, original.revision())).isInstanceOf(ThemeException.class);
        assertThat(catalog.require("custom").revision()).isEqualTo(original.revision());
        assertThat(catalog.asset(reviewed.descriptor(false).stylesheet())).isEmpty();
        assertThat(catalog.asset(original.stylesheet())).isPresent();
    }

    @Test void capsImportedThemeCount() {
        ThemeCatalog catalog = catalog();
        for (int i = 0; i < 32; i++) catalog.install(zip(files("custom-" + i, null)), null);
        assertThatThrownBy(() -> catalog.install(zip(files("custom-extra", null)), null)).isInstanceOf(ThemeException.class)
                .satisfies(e -> assertThat(((ThemeException) e).status()).isEqualTo(507));
        assertThat(catalog.themes()).hasSize(34);
    }
    @Test void storageQuotaCountsRetainedAndUncommittedFiles() throws Exception {
        ThemeCatalog catalog = catalog();
        Path orphan = data.resolve("themes/uncommitted.bin");
        Files.createDirectories(orphan.getParent());
        try (var file = new java.io.RandomAccessFile(orphan.toFile(), "rw")) { file.setLength(256L * 1024 * 1024); }
        assertThatThrownBy(() -> catalog.install(zip(files("custom", null)), null)).isInstanceOf(ThemeException.class)
                .satisfies(e -> assertThat(((ThemeException) e).status()).isEqualTo(507));
        assertThat(catalog.themes()).hasSize(2);
    }

    @Test void damagedPackageIsExcludedWithoutLosingBuiltinsOrOtherImports() throws Exception {
        ThemeCatalog catalog = catalog();
        var theme = catalog.install(zip(files("custom", null)), null);
        catalog.install(zip(files("another", null)), null);
        try (var paths = Files.walk(data.resolve("themes/packages/custom"))) {
            Files.writeString(paths.filter(path -> path.getFileName().toString().equals("theme.json")).findFirst().orElseThrow(), "bad");
        }
        ThemeCatalog restarted = catalog();
        assertThat(restarted.themes()).extracting(ThemeDescriptor::id).containsExactly("default", "cyberpunk", "another");
        assertThat(restarted.problems()).isNotEmpty();
        assertThat(restarted.asset(theme.stylesheet())).isEmpty();
    }

    @Test void assetAndPackageByteArraysCannotMutateInstalledState() {
        ThemeCatalog catalog = catalog();
        var descriptor = catalog.require("cyberpunk");
        var original = catalog.asset(descriptor.stylesheet()).orElseThrow().bytes();
        var leaked = catalog.asset(descriptor.stylesheet()).orElseThrow().bytes();
        leaked[0] = 0;
        assertThat(catalog.asset(descriptor.stylesheet()).orElseThrow().bytes()).isEqualTo(original);
        var reviewed = catalog.inspect(zip(files("custom", null)));
        var manifest = reviewed.files().get("theme.json");
        manifest[0] = 0;
        assertThat(reviewed.files().get("theme.json")[0]).isEqualTo((byte) '{');
    }

    @Test void reloadRecompilesSourceStoredByAnEarlierCompilerWithoutTrustingItsCss() throws Exception {
        var source = files("custom", ":root { color: #123456; }");
        String sourceRevision = ThemeArchive.sourceRevision(source);
        Path revision = data.resolve("themes/packages/custom/" + sourceRevision);
        for (var file : source.entrySet()) {
            Path path = revision.resolve("source").resolve(file.getKey());
            Files.createDirectories(path.getParent());
            Files.write(path, file.getValue());
        }
        Files.writeString(revision.resolve("theme.css"), "legacy compiler output must never be served");
        Files.writeString(data.resolve("themes/catalog.json"), "{\"version\":1,\"themes\":{\"custom\":{\"current\":\"" + sourceRevision + "\"}}}");
        ThemeCatalog catalog = catalog();
        var current = catalog.require("custom");
        assertThat(catalog.problems()).isEmpty();
        String compiled = new String(catalog.asset(current.stylesheet()).orElseThrow().bytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(compiled).contains("#123456").doesNotContain("legacy compiler");
        assertThat(catalog.install(zip(files("custom", ":root { color: #abcdef; }")), current.revision()).revision())
                .isNotEqualTo(current.revision());
    }

}
