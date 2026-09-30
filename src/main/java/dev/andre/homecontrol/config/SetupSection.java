package dev.andre.homecontrol.config;

import java.net.URI;

/**
 * What one module shows on the setup page. A module that is switched off has no bean, so no section. The page puts
 * {@link #view} in its model under {@link #id} and includes {@link #fragment}'s {@code section} fragment, which reads it.
 */
public interface SetupSection {

    /** Where a section goes on the page: with the devices, or with the content sources. */
    enum Group { DEVICES, CONTENT_SOURCES }

    /** The section's anchor and the model name its fragment reads, e.g. {@code jellyfin}. */
    String id();

    /** The link text in the page's navigation, e.g. {@code Movies & series}. */
    String title();

    /** The template that holds its {@code th:fragment="section"}, e.g. {@code fragments/jellyfin-setup}. */
    String fragment();

    Group group();

    /** Its place within its group, lowest first. */
    int order();

    /**
     * The model its fragment renders, or null when the section has nothing to show (its module's services are
     * missing). {@code baseUrl} is this server's root as the browser reaches it.
     */
    Object view(URI baseUrl);
}
