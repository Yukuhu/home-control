package dev.andre.homecontrol.web;

import java.util.List;

/** What the setup page's "Content sources" section shows (spec D4). */
public record SourcesSetupView(List<SourceRow> sources, List<RailRow> rails, String locale, String region,
                               List<ProviderRow> providers) {

    /** {@code refreshMinutes} is the stored override, or {@code null} to show {@code defaultMinutes} as a hint. */
    public record SourceRow(String id, String name, boolean available, boolean enabled, boolean searchable,
                            Integer refreshMinutes, long defaultMinutes) {
    }

    public record RailRow(String key, String title, String sourceName, boolean visible, boolean first, boolean last) {
    }

    public record ProviderRow(String key, String name, boolean selected) {
    }
}
