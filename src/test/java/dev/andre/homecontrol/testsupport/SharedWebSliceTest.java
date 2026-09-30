package dev.andre.homecontrol.testsupport;

import dev.andre.homecontrol.adapters.bluetooth.BluetoothSetupAdvice;
import dev.andre.homecontrol.adapters.bluetooth.BluetoothSetupController;
import dev.andre.homecontrol.security.LoginModelAdvice;
import dev.andre.homecontrol.sources.jellyfin.JellyfinImageController;
import dev.andre.homecontrol.sources.jellyfin.JellyfinSetupAdvice;
import dev.andre.homecontrol.sources.jellyfin.JellyfinSetupController;
import dev.andre.homecontrol.sources.pinned.PinUpgradeController;
import dev.andre.homecontrol.sources.pinned.PinnedSetupAdvice;
import dev.andre.homecontrol.sources.pinned.PinnedSetupController;
import dev.andre.homecontrol.sources.sports.SportsSetupAdvice;
import dev.andre.homecontrol.sources.sports.SportsSetupController;
import dev.andre.homecontrol.sources.sports.thesportsdb.TheSportsDbSetupController;
import dev.andre.homecontrol.sources.tmdb.TmdbSetupAdvice;
import dev.andre.homecontrol.sources.tmdb.TmdbSetupController;
import dev.andre.homecontrol.sources.workflows.WorkflowSetupAdvice;
import dev.andre.homecontrol.sources.workflows.WorkflowSetupController;
import dev.andre.homecontrol.sources.youtube.YouTubeSetupAdvice;
import dev.andre.homecontrol.sources.youtube.YouTubeSetupController;
import dev.andre.homecontrol.sources.youtube.YouTubeThumbnailController;
import dev.andre.homecontrol.web.ContentPlayController;
import dev.andre.homecontrol.web.DashboardController;
import dev.andre.homecontrol.web.DeepLinkTestController;
import dev.andre.homecontrol.web.DeviceController;
import dev.andre.homecontrol.web.ErrorAdvice;
import dev.andre.homecontrol.web.IconController;
import dev.andre.homecontrol.web.LoginController;
import dev.andre.homecontrol.web.PwaController;
import dev.andre.homecontrol.web.RailController;
import dev.andre.homecontrol.web.SearchController;
import dev.andre.homecontrol.web.SetupController;
import dev.andre.homecontrol.web.SourcesSetupAdvice;
import dev.andre.homecontrol.web.SourcesSetupController;
import dev.andre.homecontrol.web.StateController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The shared slice holds every controller and controller advice of the application, once. */
class SharedWebSliceTest extends WebSliceTest {

    @Autowired
    ApplicationContext context;

    @Test
    void holdsEveryControllerAndControllerAdvice() {
        List<Class<?>> webLayer = List.of(BluetoothSetupAdvice.class, BluetoothSetupController.class,
                LoginModelAdvice.class, JellyfinImageController.class, JellyfinSetupAdvice.class,
                JellyfinSetupController.class, PinUpgradeController.class, PinnedSetupAdvice.class,
                PinnedSetupController.class, SportsSetupAdvice.class, SportsSetupController.class,
                TheSportsDbSetupController.class, TmdbSetupAdvice.class, TmdbSetupController.class,
                WorkflowSetupAdvice.class, WorkflowSetupController.class, YouTubeSetupAdvice.class,
                YouTubeSetupController.class, YouTubeThumbnailController.class, ErrorAdvice.class,
                ContentPlayController.class, DashboardController.class, DeepLinkTestController.class,
                DeviceController.class, IconController.class, LoginController.class, PwaController.class,
                RailController.class, SearchController.class, SetupController.class, SourcesSetupAdvice.class,
                SourcesSetupController.class, StateController.class);

        assertThat(webLayer).isNotEmpty().allSatisfy(type ->
                assertThat(context.getBeanNamesForType(type)).as(type.getSimpleName()).hasSize(1));
    }
}
