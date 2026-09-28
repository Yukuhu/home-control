package dev.andre.homecontrol.testsupport;

import dev.andre.homecontrol.adapters.androidtv.AndroidTvProperties;
import dev.andre.homecontrol.adapters.androidtv.PairingService;
import dev.andre.homecontrol.content.RailCache;
import dev.andre.homecontrol.core.content.ContentSources;
import dev.andre.homecontrol.device.DeviceManager;
import dev.andre.homecontrol.security.LoginRateLimiter;
import dev.andre.homecontrol.security.LoginService;
import dev.andre.homecontrol.sources.pinned.PinnedShortcuts;
import dev.andre.homecontrol.sources.sports.SportsSettings;
import dev.andre.homecontrol.sources.sports.SportsSettingsService;
import dev.andre.homecontrol.sources.sports.thesportsdb.TheSportsDbSchedule;
import dev.andre.homecontrol.sources.tmdb.TmdbImages;
import dev.andre.homecontrol.sources.tmdb.TmdbWatchProviders;
import dev.andre.homecontrol.sources.workflows.WorkflowStore;
import dev.andre.homecontrol.sources.youtube.KnownVideos;
import dev.andre.homecontrol.sources.youtube.QuotaLedger;
import dev.andre.homecontrol.sources.youtube.YouTubeSearch;
import dev.andre.homecontrol.sources.youtube.YouTubeSetupService;
import dev.andre.homecontrol.storage.SecretStore;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Brings the shared full application back to a fresh install after each test class, with the application's own
 * operations and the reset methods that exist for this. Besides devices, secrets and settings, that includes the
 * in-memory caches of upstream answers (TMDB watch providers and image configuration, YouTube searches and known
 * videos, TheSportsDB fixtures) and a pending Android TV pairing, so a later class that uses the same fixture ids asks
 * the fakes again. Calendar feeds are cached per calendar id, which is new for every calendar added, and the sports
 * schedules remember that they have run once; neither changes what a later class sees. Every module that is on by
 * default is on in {@link FullAppTest}, so a missing bean fails the reset instead of being skipped. The order matters:
 * the secrets go before YouTube disconnects (so no revoke request reaches the shared Google fake) and before the
 * workflows reload, and the fakes are reset last (so a fetch the reset itself triggers cannot leave a recorded request
 * behind).
 */
public final class FullAppReset implements AfterAllCallback {

    @Override
    public void afterAll(ExtensionContext context) {
        reset(SpringExtension.getApplicationContext(context));
    }

    public static void reset(ApplicationContext app) {
        DeviceManager devices = app.getBean(DeviceManager.class);
        devices.devices().forEach(device -> devices.forget(device.id()));

        app.getBean(LoginService.class).removeSecrets(app.getBean(SecretStore.class).names());
        app.getBean(YouTubeSetupService.class).disconnect();
        app.getBean(WorkflowStore.class).reload();
        app.getBean(SportsSettingsService.class).update(current -> SportsSettings.empty());
        PinnedShortcuts pins = app.getBean(PinnedShortcuts.class);
        pins.all().forEach(pin -> pins.remove(pin.id()));
        app.getBean(QuotaLedger.class).reset();
        app.getBean(YouTubeSearch.class).reset();
        app.getBean(KnownVideos.class).reset();
        app.getBean(TmdbWatchProviders.class).reset();
        app.getBean(TmdbImages.class).reset();
        app.getBean(TheSportsDbSchedule.class).clear();
        app.getBean(PairingService.class).cancel();
        app.getBean(LoginRateLimiter.class).reset();

        Path dataDir = app.getBean(AndroidTvProperties.class).dataDir();
        for (String file : new String[]{"secrets.json", "secret.key", "sources.json", "sports.json", "pinned.json"}) {
            delete(dataDir.resolve(file));
        }
        RailCache rails = app.getBean(RailCache.class);
        app.getBean(ContentSources.class).all().forEach(source -> rails.invalidateSource(source.id()));

        SharedFakes.resetAll();
    }

    private static void delete(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not delete " + file, e);
        }
    }
}
