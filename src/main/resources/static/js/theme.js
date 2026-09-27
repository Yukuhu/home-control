// The look of every page: the default theme or the Cyberpunk one, remembered per browser.
// A classic script loaded synchronously in <head>, not a module, so the saved theme is on
// <html> before the first paint and a reload never flashes the other theme.
(() => {
    const KEY = "homecontrol.theme.v1";
    // Browser UI (address bar, installed-app title bar) color per theme.
    const THEME_COLORS = { default: "#101917", cyberpunk: "#07080d" };
    const root = document.documentElement;
    let bootTimer;

    const known = (theme) => (Object.hasOwn(THEME_COLORS, theme) ? theme : "default");

    function saved() {
        try {
            return known(localStorage.getItem(KEY));
        } catch {
            return "default";
        }
    }

    function save(theme) {
        try { localStorage.setItem(KEY, theme); } catch { /* private mode: keep it for this page only */ }
    }

    function syncControls(theme) {
        document.querySelector('meta[name="theme-color"]')?.setAttribute("content", THEME_COLORS[theme]);
        for (const toggle of document.querySelectorAll("[data-theme-toggle]")) {
            toggle.setAttribute("aria-pressed", String(toggle.dataset.themeToggle === theme));
        }
    }

    function apply(theme) {
        root.dataset.theme = theme;
        syncControls(theme);
    }

    apply(saved());
    // The toggles are parsed after this script ran.
    document.addEventListener("DOMContentLoaded", () => syncControls(root.dataset.theme));

    document.addEventListener("click", (event) => {
        const toggle = event.target.closest?.("[data-theme-toggle]");
        if (!toggle) return;
        const wanted = known(toggle.dataset.themeToggle);
        const theme = root.dataset.theme === wanted ? "default" : wanted;
        apply(theme);
        save(theme);
        // A short switch-over effect; the theme's stylesheet decides whether there is one.
        clearTimeout(bootTimer);
        root.classList.add("theme-boot");
        bootTimer = setTimeout(() => root.classList.remove("theme-boot"), 700);
    });

    // Another open tab switched themes: follow it live.
    window.addEventListener("storage", (event) => {
        if (event.key === KEY || event.key === null) apply(saved());
    });
})();
