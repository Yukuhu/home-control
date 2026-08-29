package dev.andre.shield.apps;

/** A launchable app. {@code deepLink} is optional. */
public record AppEntry(String id, String name, String appPackage, String deepLink) {

    /**
     * The URI sent as an app-link launch request. Without a configured deep link this
     * uses the generic Android TV "open this app" form.
     */
    public String launchUri() {
        if (deepLink != null && !deepLink.isBlank()) {
            return deepLink;
        }
        return "market://launch?id=" + appPackage;
    }
}
