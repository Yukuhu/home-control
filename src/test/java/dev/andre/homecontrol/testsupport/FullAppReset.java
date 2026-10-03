package dev.andre.homecontrol.testsupport;

import dev.andre.homecontrol.adapters.androidtv.PairingService;
import dev.andre.homecontrol.content.RailCache;
import dev.andre.homecontrol.core.DeviceEnrollment;
import dev.andre.homecontrol.core.DeviceQueries;
import dev.andre.homecontrol.core.content.ContentSources;
import dev.andre.homecontrol.security.LoginRateLimiter;
import dev.andre.homecontrol.security.RememberedLogins;
import dev.andre.homecontrol.security.LoginService;
import dev.andre.homecontrol.sources.pinned.PinnedShortcuts;
import dev.andre.homecontrol.sources.sports.settings.SportsSettings;
import dev.andre.homecontrol.sources.sports.settings.SportsSettingsService;
import dev.andre.homecontrol.sources.sports.thesportsdb.TheSportsDbSchedule;
import dev.andre.homecontrol.sources.tmdb.TmdbImages;
import dev.andre.homecontrol.sources.tmdb.TmdbWatchProviders;
import dev.andre.homecontrol.sources.workflows.WorkflowStore;
import dev.andre.homecontrol.sources.youtube.KnownVideos;
import dev.andre.homecontrol.sources.youtube.QuotaLedger;
import dev.andre.homecontrol.sources.youtube.YouTubeSearch;
import dev.andre.homecontrol.sources.youtube.YouTubeSetupService;
import dev.andre.homecontrol.storage.JsonFileSourceSettings;
import dev.andre.homecontrol.storage.SecretStore;
import dev.andre.homecontrol.themes.ThemeCatalog;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.util.ArrayList;
import java.util.List;

/**
 * Brings the shared full application back to a fresh install after each test class, with the application's own
 * operations and the reset methods that exist for this, never by deleting files behind the stores, which cache what
 * they read. The account credentials and the login go; device secrets stay, like the keystore they protect, and the
 * devices' own secrets go with the devices. Besides devices, secrets and settings, that includes the
 * in-memory caches of upstream answers (TMDB watch providers and image configuration, YouTube searches and known
 * videos, TheSportsDB fixtures) and a pending Android TV pairing, so a later class that uses the same fixture ids asks
 * the fakes again. Calendar feeds are cached per calendar id, which is new for every calendar added, and the sports
 * schedules remember that they have run once; neither changes what a later class sees. Every module that is on by
 * default is on in {@link FullAppTest}, so a missing bean fails the reset instead of being skipped. The order matters:
 * the secrets go before YouTube disconnects (so no revoke request reaches the shared Google fake) and before the
 * workflows reload, and the fakes are reset last, which clears what the reset's own calls recorded. A fetch that runs
 * in the background, such as a rail refreshed after a content change, can still reach a fake after its reset, so a
 * test that sets one off waits for it to end.
 */
public final class FullAppReset implements AfterAllCallback {

    @Override
    public void afterAll(ExtensionContext context) {
        reset(SpringExtension.getApplicationContext(context));
    }

    public static void reset(ApplicationContext app) {
        runEvery(List.of(
                new Step("themes", () -> {
                    ThemeCatalog themes = app.getBean(ThemeCatalog.class);
                    themes.themes().stream().filter(theme -> !theme.builtIn())
                            .forEach(theme -> themes.remove(theme.id()));
                }),
                new Step("devices", () -> {
                    DeviceEnrollment enrollment = app.getBean(DeviceEnrollment.class);
                    app.getBean(DeviceQueries.class).devices().forEach(device -> enrollment.forget(device.id()));
                }),
                new Step("account credentials and login", () -> {
                    SecretStore secrets = app.getBean(SecretStore.class);
                    app.getBean(LoginService.class).removeSecrets(secrets.accountCredentialNames());
                    secrets.removeLogin();
                }),
                new Step("YouTube connection", () -> app.getBean(YouTubeSetupService.class).disconnect()),
                new Step("workflows", () -> app.getBean(WorkflowStore.class).reload()),
                new Step("sports settings",
                        () -> app.getBean(SportsSettingsService.class).update(current -> SportsSettings.empty())),
                new Step("pins", () -> {
                    PinnedShortcuts pins = app.getBean(PinnedShortcuts.class);
                    pins.all().forEach(pin -> pins.remove(pin.id()));
                }),
                new Step("YouTube quota", () -> app.getBean(QuotaLedger.class).reset()),
                new Step("YouTube searches", () -> app.getBean(YouTubeSearch.class).reset()),
                new Step("known YouTube videos", () -> app.getBean(KnownVideos.class).reset()),
                new Step("TMDB watch providers", () -> app.getBean(TmdbWatchProviders.class).reset()),
                new Step("TMDB images", () -> app.getBean(TmdbImages.class).reset()),
                new Step("TheSportsDB fixtures", () -> app.getBean(TheSportsDbSchedule.class).clear()),
                new Step("Android TV pairing", () -> app.getBean(PairingService.class).cancel()),
                new Step("login rate limit", () -> app.getBean(LoginRateLimiter.class).reset()),
                new Step("remembered logins", () -> app.getBean(RememberedLogins.class).clear()),
                new Step("source settings", () -> app.getBean(JsonFileSourceSettings.class).reset()),
                new Step("rails", () -> {
                    RailCache rails = app.getBean(RailCache.class);
                    app.getBean(ContentSources.class).all().forEach(source -> rails.invalidateSource(source.id()));
                }),
                new Step("fakes", SharedFakes::resetAll)));
    }

    /** One part of the reset, named in the failure if it throws. */
    record Step(String name, Runnable action) {
    }

    /**
     * Runs every step, also after one failed, so a broken step does not leave the later ones' state behind for every
     * class that shares the application; then fails once, naming each step that failed, with its exception attached.
     */
    static void runEvery(List<Step> steps) {
        List<AssertionError> failures = new ArrayList<>();
        for (Step step : steps) {
            try {
                step.action().run();
            } catch (RuntimeException | AssertionError e) {
                failures.add(new AssertionError(step.name(), e));
            }
        }
        if (!failures.isEmpty()) {
            AssertionError failed = new AssertionError("Resetting the shared application failed at: "
                    + String.join(", ", failures.stream().map(Throwable::getMessage).toList()));
            failures.forEach(failed::addSuppressed);
            throw failed;
        }
    }

}
