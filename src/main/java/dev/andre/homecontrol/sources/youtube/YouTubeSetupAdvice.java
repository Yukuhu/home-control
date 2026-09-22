package dev.andre.homecontrol.sources.youtube;

import dev.andre.homecontrol.core.Capability;
import dev.andre.homecontrol.device.DeviceManager;
import dev.andre.homecontrol.security.LoginService;
import dev.andre.homecontrol.web.SetupController;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import jakarta.servlet.http.HttpServletRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@ControllerAdvice(assignableTypes = SetupController.class)
@ConditionalOnProperty(name = "home-control.youtube.enabled", havingValue = "true", matchIfMissing = true)
public class YouTubeSetupAdvice {

    /** One API call's usage today, e.g. {@code playlistItems.list: 103 calls, 103 units}. */
    public record CallCount(String api, int count, int units) {
    }

    /** Today's YouTube Data API v3 usage against the daily budget. */
    public record QuotaView(int units, int dailyUnits, int searches, int searchesPerDay, String resets, List<CallCount> calls) {
    }

    /** One of the user's own playlists, loaded or (still) only remembered from a past selection. */
    public record PlaylistOption(String id, String title, int itemCount, boolean selected) {
    }

    /** A registered Cast receiver and whether best-effort YouTube Cast is switched on for it. */
    public record LoungeDeviceView(String id, String name, boolean enabled) {
    }

    /** What the setup page shows about YouTube. Never holds a secret, a device code or a lounge token. */
    public record View(boolean hasClient, boolean connected, boolean revoked, String channelTitle,
                       YouTubeAuthorizationService.Status authorization, boolean needsLoginPassword, QuotaView quota,
                       boolean watchLater, List<PlaylistOption> playlists, List<LoungeDeviceView> loungeDevices) {
    }

    private static final QuotaView EMPTY_QUOTA = new QuotaView(0, 0, 0, 0, "", List.of());

    @ModelAttribute("youtubeCallbackUrl")
    public String callbackUrl(HttpServletRequest request) {
        return YouTubeOAuthCallback.uri(request).toString();
    }

    @ModelAttribute("youtubeBrowserSupported")
    public boolean browserSupported(HttpServletRequest request) {
        return YouTubeOAuthCallback.supported(YouTubeOAuthCallback.uri(request));
    }

    private final ObjectProvider<YouTubeSetupService> setup;
    private final ObjectProvider<LoginService> login;
    private final ObjectProvider<QuotaLedger> ledger;
    private final ObjectProvider<YouTubePlaylists> playlists;
    private final ObjectProvider<DeviceManager> devices;

    public YouTubeSetupAdvice(ObjectProvider<YouTubeSetupService> setup, ObjectProvider<LoginService> login,
                              ObjectProvider<QuotaLedger> ledger, ObjectProvider<YouTubePlaylists> playlists,
                              ObjectProvider<DeviceManager> devices) {
        this.setup = setup;
        this.login = login;
        this.ledger = ledger;
        this.playlists = playlists;
        this.devices = devices;
    }

    @ModelAttribute("youtube")
    public View youtube() {
        YouTubeSetupService service = setup.getIfAvailable();
        LoginService loginService = login.getIfAvailable();
        boolean needsPassword = loginService == null || !loginService.loginRequired();
        if (service == null) {
            return new View(false, false, false, null,
                    YouTubeAuthorizationService.Status.of(YouTubeAuthorizationService.State.IDLE, null), needsPassword,
                    EMPTY_QUOTA, false, List.of(), List.of());
        }
        YouTubeSettings settings = service.settings();
        return new View(service.hasClient(), service.connected(), service.revoked(), settings.channelTitle(),
                service.authorizationStatus(), needsPassword, quota(), settings.watchLater(), playlistOptions(settings),
                loungeDevices(settings));
    }

    /** Every registered Cast receiver, in the device list's order. */
    private List<LoungeDeviceView> loungeDevices(YouTubeSettings settings) {
        DeviceManager manager = devices.getIfAvailable();
        if (manager == null) {
            return List.of();
        }
        return manager.devices().stream()
                .filter(device -> manager.capabilities(device.id()).contains(Capability.CAST_RECEIVER))
                .map(device -> new LoungeDeviceView(device.id(), device.name(),
                        settings.loungeDevices().contains(device.id())))
                .toList();
    }

    private List<PlaylistOption> playlistOptions(YouTubeSettings settings) {
        YouTubePlaylists p = playlists.getIfAvailable();
        List<YouTubePlaylists.PlaylistSummary> loaded = p == null ? List.of() : p.loadedList();
        List<PlaylistOption> options = new ArrayList<>();
        for (YouTubePlaylists.PlaylistSummary summary : loaded) {
            options.add(new PlaylistOption(summary.id(), summary.title(), summary.itemCount(),
                    settings.playlists().containsKey(summary.id())));
        }
        Set<String> loadedIds = loaded.stream().map(YouTubePlaylists.PlaylistSummary::id).collect(Collectors.toSet());
        settings.playlists().forEach((id, title) -> {
            if (!loadedIds.contains(id)) {
                options.add(new PlaylistOption(id, title, 0, true));
            }
        });
        return options;
    }

    private QuotaView quota() {
        QuotaLedger quotaLedger = ledger.getIfAvailable();
        if (quotaLedger == null) {
            return EMPTY_QUOTA;
        }
        QuotaLedger.Usage usage = quotaLedger.usage();
        List<CallCount> calls = new ArrayList<>();
        usage.calls().forEach((api, count) -> calls.add(new CallCount(api, count, count * unitsFor(api))));
        return new QuotaView(usage.units(), usage.dailyUnits(), usage.searches(), usage.searchesPerDay(),
                QuotaLedger.resetPhrase(usage.resetsAt()), List.copyOf(calls));
    }

    private static int unitsFor(String apiName) {
        for (QuotaLedger.Call call : QuotaLedger.Call.values()) {
            if (call.apiName().equals(apiName)) {
                return call.units();
            }
        }
        return 0;
    }
}
