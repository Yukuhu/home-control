package dev.andre.homecontrol.sources.youtube;

import dev.andre.homecontrol.config.ConditionalOnModule;
import dev.andre.homecontrol.config.Module;
import dev.andre.homecontrol.config.SetupSection;
import dev.andre.homecontrol.core.Capability;
import dev.andre.homecontrol.core.DeviceQueries;
import dev.andre.homecontrol.security.LoginService;
import org.springframework.beans.factory.ObjectProvider;
import java.net.URI;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnModule(Module.YOUTUBE)
public class YouTubeSetupSection extends SetupSection {

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
                       boolean watchLater, List<PlaylistOption> playlists, List<LoungeDeviceView> loungeDevices,
                       String callbackUrl, boolean browserSupported) {
    }

    private static final QuotaView EMPTY_QUOTA = new QuotaView(0, 0, 0, 0, "", List.of());

    private final ObjectProvider<YouTubeSetupService> setup;
    private final ObjectProvider<LoginService> login;
    private final ObjectProvider<QuotaLedger> ledger;
    private final ObjectProvider<YouTubePlaylists> playlists;
    private final ObjectProvider<DeviceQueries> devices;

    public YouTubeSetupSection(ObjectProvider<YouTubeSetupService> setup, ObjectProvider<LoginService> login,
                              ObjectProvider<QuotaLedger> ledger, ObjectProvider<YouTubePlaylists> playlists,
                              ObjectProvider<DeviceQueries> devices) {
        super("youtube", "YouTube", Group.CONTENT_SOURCES, 20);
        this.setup = setup;
        this.login = login;
        this.ledger = ledger;
        this.playlists = playlists;
        this.devices = devices;
    }

    @Override
    public View view(URI baseUrl) {
        URI callback = YouTubeOAuthCallback.uri(baseUrl);
        String callbackUrl = callback.toString();
        boolean browserSupported = YouTubeOAuthCallback.supported(callback);
        YouTubeSetupService service = setup.getIfAvailable();
        LoginService loginService = login.getIfAvailable();
        boolean needsPassword = loginService == null || !loginService.loginRequired();
        if (service == null) {
            return new View(false, false, false, null,
                    YouTubeAuthorizationService.Status.of(YouTubeAuthorizationService.State.IDLE, null), needsPassword,
                    EMPTY_QUOTA, false, List.of(), List.of(), callbackUrl, browserSupported);
        }
        YouTubeSettings settings = service.settings();
        return new View(service.hasClient(), service.connected(), service.revoked(), settings.channelTitle(),
                service.authorizationStatus(), needsPassword, quota(), settings.watchLater(), playlistOptions(settings),
                loungeDevices(settings), callbackUrl, browserSupported);
    }

    /** Every registered Cast receiver, in the device list's order. */
    private List<LoungeDeviceView> loungeDevices(YouTubeSettings settings) {
        DeviceQueries registered = devices.getIfAvailable();
        if (registered == null) {
            return List.of();
        }
        return registered.devices().stream()
                .filter(device -> registered.capabilities(device.id()).contains(Capability.CAST_RECEIVER))
                .map(device -> new LoungeDeviceView(device.id(), device.name(),
                        settings.loungeDevices().contains(device.id())))
                .toList();
    }

    private List<PlaylistOption> playlistOptions(YouTubeSettings settings) {
        YouTubePlaylists p = playlists.getIfAvailable();
        List<YouTubePlaylists.PlaylistSummary> loaded = p == null ? List.of() : p.loadedList();
        List<PlaylistOption> options = new ArrayList<>(loaded.stream()
                .map(summary -> new PlaylistOption(summary.id(), summary.title(), summary.itemCount(),
                        settings.playlists().containsKey(summary.id())))
                .toList());
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
