package dev.andre.homecontrol.testsupport;

import dev.andre.homecontrol.adapters.bluetooth.BluetoothSetupSection;
import dev.andre.homecontrol.adapters.bluetooth.BluetoothSetupController;
import dev.andre.homecontrol.sources.jellyfin.JellyfinImageController;
import dev.andre.homecontrol.sources.jellyfin.JellyfinSetupSection;
import dev.andre.homecontrol.sources.jellyfin.JellyfinSetupController;
import dev.andre.homecontrol.sources.pinned.PinUpgradeController;
import dev.andre.homecontrol.sources.pinned.PinnedSetupSection;
import dev.andre.homecontrol.sources.pinned.PinnedSetupController;
import dev.andre.homecontrol.sources.sports.SportsSetupSection;
import dev.andre.homecontrol.sources.sports.SportsSetupController;
import dev.andre.homecontrol.sources.sports.thesportsdb.TheSportsDbSetupController;
import dev.andre.homecontrol.sources.tmdb.TmdbSetupSection;
import dev.andre.homecontrol.sources.tmdb.TmdbSetupController;
import dev.andre.homecontrol.sources.workflows.WorkflowSetupSection;
import dev.andre.homecontrol.sources.workflows.WorkflowSetupController;
import dev.andre.homecontrol.sources.youtube.YouTubeSetupSection;
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
import dev.andre.homecontrol.web.EventStreamController;
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
        List<Class<?>> webLayer = List.of(BluetoothSetupSection.class, BluetoothSetupController.class,
                JellyfinImageController.class, JellyfinSetupSection.class,
                JellyfinSetupController.class, PinUpgradeController.class, PinnedSetupSection.class,
                PinnedSetupController.class, SportsSetupSection.class, SportsSetupController.class,
                TheSportsDbSetupController.class, TmdbSetupSection.class, TmdbSetupController.class,
                WorkflowSetupSection.class, WorkflowSetupController.class, YouTubeSetupSection.class,
                YouTubeSetupController.class, YouTubeThumbnailController.class, ErrorAdvice.class,
                ContentPlayController.class, DashboardController.class, DeepLinkTestController.class,
                DeviceController.class, IconController.class, LoginController.class, PwaController.class,
                RailController.class, SearchController.class, SetupController.class, SourcesSetupAdvice.class,
                SourcesSetupController.class, EventStreamController.class);

        assertThat(webLayer).isNotEmpty().allSatisfy(type ->
                assertThat(context.getBeanNamesForType(type)).as(type.getSimpleName()).hasSize(1));
    }
}
