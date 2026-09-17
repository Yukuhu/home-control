package dev.andre.homecontrol.sources.pinned;

import dev.andre.homecontrol.web.SetupController;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.util.List;

@ControllerAdvice(assignableTypes = SetupController.class)
@ConditionalOnProperty(name = "home-control.pinned.enabled", havingValue = "true", matchIfMissing = true)
public class PinnedSetupAdvice {

    public record PinView(String id, String title, String subtitle, String url, boolean first, boolean last) {
    }

    /** What the setup page shows about pinned shortcuts. */
    public record View(List<PinView> pins, int maxPins) {
    }

    private final ObjectProvider<PinnedShortcuts> shortcuts;
    private final ObjectProvider<PinnedProperties> properties;

    public PinnedSetupAdvice(ObjectProvider<PinnedShortcuts> shortcuts, ObjectProvider<PinnedProperties> properties) {
        this.shortcuts = shortcuts;
        this.properties = properties;
    }

    @ModelAttribute("pinned")
    public View pinned() {
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
