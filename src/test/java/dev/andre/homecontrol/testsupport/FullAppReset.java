package dev.andre.homecontrol.testsupport;

import dev.andre.homecontrol.adapters.androidtv.AndroidTvProperties;
import dev.andre.homecontrol.content.RailCache;
import dev.andre.homecontrol.core.content.ContentSources;
import dev.andre.homecontrol.device.DeviceManager;
import dev.andre.homecontrol.security.LoginRateLimiter;
import dev.andre.homecontrol.security.LoginService;
import dev.andre.homecontrol.sources.pinned.PinnedShortcuts;
import dev.andre.homecontrol.sources.sports.SportsSettings;
import dev.andre.homecontrol.sources.sports.SportsSettingsService;
import dev.andre.homecontrol.sources.workflows.WorkflowStore;
import dev.andre.homecontrol.sources.youtube.QuotaLedger;
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
 * operations and the three reset methods that exist for this. Every module is on in {@link FullAppTest}, so a missing
 * bean fails the reset instead of being skipped. The order matters: the secrets go before YouTube
 * disconnects (so no revoke request reaches the shared Google fake) and before the workflows reload, and the fakes
 * are reset last (so a fetch the reset itself triggers cannot leave a recorded request behind).
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
