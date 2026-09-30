package dev.andre.homecontrol.sources.pinned;

import dev.andre.homecontrol.config.ConditionalOnModule;
import dev.andre.homecontrol.config.Module;
import dev.andre.homecontrol.config.SetupSection;
import java.net.URI;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnModule(Module.PINNED)
public class PinnedSetupSection implements SetupSection {

    public record PinView(String id, String title, String subtitle, String url, boolean first, boolean last) {
    }

    /** What the setup page shows about pinned shortcuts. */
    public record View(List<PinView> pins, int maxPins) {
    }

    private final ObjectProvider<PinnedShortcuts> shortcuts;
    private final ObjectProvider<PinnedProperties> properties;

    public PinnedSetupSection(ObjectProvider<PinnedShortcuts> shortcuts, ObjectProvider<PinnedProperties> properties) {
        this.shortcuts = shortcuts;
        this.properties = properties;
    }

    @Override
    public String id() {
        return "pinned";
    }

    @Override
    public String title() {
        return "Pinned links";
    }

    @Override
    public String fragment() {
        return "fragments/pinned-setup";
    }

    @Override
    public Group group() {
        return Group.CONTENT_SOURCES;
    }

    @Override
    public int order() {
        return 40;
    }

    @Override
    public View view(URI baseUrl) {
        PinnedShortcuts service = shortcuts.getIfAvailable();
        PinnedProperties props = properties.getIfAvailable();
        if (service == null || props == null) {
            return null;
        }
        List<Pin> all = service.all();
        List<PinView> views = new java.util.ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            Pin pin = all.get(i);
            views.add(new PinView(pin.id(), pin.title(), pin.subtitle(), pin.url().toString(),
                    i == 0, i == all.size() - 1));
        }
        return new View(List.copyOf(views), props.maxPins());
    }
}
