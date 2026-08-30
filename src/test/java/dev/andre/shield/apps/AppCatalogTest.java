package dev.andre.shield.apps;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AppCatalogTest {

    @TempDir
    Path dir;

    @Test
    void fallsBackToTheGenericLaunchUriWhenNoDeepLinkIsConfigured() {
        AppEntry entry = new AppEntry("netflix", "Netflix", "com.netflix.ninja", null);

        assertThat(entry.launchUri()).isEqualTo("market://launch?id=com.netflix.ninja");
    }

    @Test
    void prefersAConfiguredDeepLink() {
        AppEntry entry = new AppEntry("plex", "Plex", "com.plexapp.android", "plex://");

        assertThat(entry.launchUri()).isEqualTo("plex://");
    }

    @Test
    void addsAndPersistsAnAppPackageReportedByTheDevice() throws Exception {
        Path file = dir.resolve("apps.yaml");
        Files.writeString(file, """
                apps:
                  - id: plex
                    name: Plex
                    package: com.plexapp.android
                    deepLink: "plex://"
                """);
        AppCatalog catalog = new AppCatalog(file);

        AppEntry added = catalog.addPackage("com.example.player");

        assertThat(added).isEqualTo(new AppEntry(
                "com.example.player", "com.example.player", "com.example.player", null));
        assertThat(new AppCatalog(file).entries()).containsExactly(
                new AppEntry("plex", "Plex", "com.plexapp.android", "plex://"), added);
    }

    @Test
    void addingTheSamePackageTwiceKeepsOneCatalogEntry() throws Exception {
        Path file = dir.resolve("apps.yaml");
        Files.writeString(file, "apps: []\n");
        AppCatalog catalog = new AppCatalog(file);

        AppEntry first = catalog.addPackage("com.example.player");
        AppEntry second = catalog.addPackage("com.example.player");

        assertThat(second).isEqualTo(first);
        assertThat(new AppCatalog(file).entries()).containsExactly(first);
    }

    @Test
    void writesTheBundledCatalogOnFirstRun() {
        Path file = dir.resolve("apps.yaml");

        AppCatalog catalog = new AppCatalog(file);

        assertThat(Files.exists(file)).isTrue();
        assertThat(catalog.entries()).isNotEmpty();
        assertThat(catalog.byId("netflix")).isPresent();
    }

    @Test
    void readsAUserEditedCatalog() throws Exception {
        Path file = dir.resolve("apps.yaml");
        Files.writeString(file, """
                apps:
                  - id: kodi
                    name: Kodi
                    package: org.xbmc.kodi
                  - id: plex
                    name: Plex
                    package: com.plexapp.android
                    deepLink: "plex://"
                """);

        AppCatalog catalog = new AppCatalog(file);

        assertThat(catalog.entries()).hasSize(2);
        assertThat(catalog.byId("kodi")).get().extracting(AppEntry::name).isEqualTo("Kodi");
        assertThat(catalog.byId("plex")).get().extracting(AppEntry::launchUri).isEqualTo("plex://");
    }

    @Test
    void findsAnEntryByItsPackageSoTheUiCanNameTheForegroundApp() {
        AppCatalog catalog = new AppCatalog(dir.resolve("apps.yaml"));

        assertThat(catalog.byPackage("com.netflix.ninja")).get()
                .extracting(AppEntry::name).isEqualTo("Netflix");
    }

    @Test
    void handlesEmptyAppsYamlWithoutThrowingOrNpe() throws Exception {
        Path file = dir.resolve("apps.yaml");
        Files.writeString(file, "# This is just a comment");

        AppCatalog catalog = new AppCatalog(file);

        assertThat(catalog.entries()).isEmpty();
    }

    @Test
    void handlesFileWithNoAppsKeyByReturningEmptyCatalog() throws Exception {
        Path file = dir.resolve("apps.yaml");
        Files.writeString(file, """
                other:
                  - key: value
                """);

        AppCatalog catalog = new AppCatalog(file);

        assertThat(catalog.entries()).isEmpty();
    }

    @Test
    void skipsMalformedRowsAndKeepsValidOnes() throws Exception {
        Path file = dir.resolve("apps.yaml");
        Files.writeString(file, """
                apps:
                  - id: kodi
                    name: Kodi
                    package: org.xbmc.kodi
                  - id: missing-name
                    package: com.example.app
                  - id: plex
                    name: Plex
                    package: com.plexapp.android
                """);

        AppCatalog catalog = new AppCatalog(file);

        assertThat(catalog.entries()).hasSize(2);
        assertThat(catalog.byId("kodi")).isPresent();
        assertThat(catalog.byId("plex")).isPresent();
        assertThat(catalog.byId("missing-name")).isEmpty();
    }

    @Test
    void handlesAppsKeyWithScalarValueInsteadOfList() throws Exception {
        Path file = dir.resolve("apps.yaml");
        Files.writeString(file, "apps: netflix");

        AppCatalog catalog = new AppCatalog(file);

        assertThat(catalog.entries()).isEmpty();
    }
}
