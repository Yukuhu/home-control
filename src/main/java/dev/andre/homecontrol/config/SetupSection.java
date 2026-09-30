package dev.andre.homecontrol.config;

import java.net.URI;

/**
 * What one module shows on the setup page. A module that is switched off has no bean, so no section. The page puts
 * {@link #view} in its model under {@link #id} and includes {@link #fragment}'s {@code section} fragment, which reads it.
 */
public abstract class SetupSection {

    /** Where a section goes on the page: with the devices, or with the content sources. */
    public enum Group { DEVICES, CONTENT_SOURCES }

    private final String id;
    private final String title;
    private final Group group;
    private final int order;

    /**
     * {@code id}: the section's anchor and the model name its fragment reads, e.g. {@code jellyfin}.
     * {@code title}: the link text in the page's navigation, e.g. {@code Movies & series}.
     * {@code order}: its place within its group, lowest first.
     */
    protected SetupSection(String id, String title, Group group, int order) {
        this.id = id;
        this.title = title;
        this.group = group;
        this.order = order;
    }

    public final String id() {
        return id;
    }

    public final String title() {
        return title;
    }

    /** The template that holds its {@code th:fragment="section"}: {@code fragments/<id>-setup}. */
    public final String fragment() {
        return "fragments/" + id + "-setup";
    }

    public final Group group() {
        return group;
    }

    public final int order() {
        return order;
    }

    /**
     * The model its fragment renders, or null when the section has nothing to show (its module's services are
     * missing). {@code baseUrl} is this server's root as the browser reaches it.
     */
    public abstract Object view(URI baseUrl);
}
