// Classic, synchronous head script: reveal the saved theme only once its sheet is ready.
(() => {
    const KEY = "homecontrol.theme.v1";
    const LOAD_TIMEOUT = 4000;
    const root = document.documentElement;
    const defaultStylesheet = document.querySelector("link[data-theme-default]")?.getAttribute("href");
    const recoveryPath = "/setup/appearance/recovery";
    // The offline shell is shared, so its URL must preserve recovery mode as well as the server's meta tag.
    const recovery = document.querySelector('meta[name="theme-recovery"]')?.content === "true"
        || location.pathname === recoveryPath
        || (location.pathname === "/login" && new URLSearchParams(location.search).get("next") === recoveryPath);
    const fallback = { id: "default", name: "Default", builtIn: true, themeColor: "#101917", assets: [] };
    let catalog = readDescriptors(window.homeControlThemes);
    let current = catalog.get("default");
    let desiredId = "default";
    let activeLink;
    let pendingLoad;
    let generation = 0;
    let catalogRequest;
    let bootTimer;
    let notice = "";
    let reveal;

    function readDescriptors(data) {
        const descriptors = new Map([["default", fallback]]);
        for (const theme of Array.isArray(data?.themes) ? data.themes : []) {
            if (theme && /^[a-z][a-z0-9-]{0,63}$/.test(theme.id)
                    && typeof theme.name === "string" && typeof theme.stylesheet === "string") {
                try {
                    const url = new URL(theme.stylesheet, location.origin);
                    if (url.origin === location.origin && url.pathname === theme.stylesheet) descriptors.set(theme.id, theme);
                } catch { /* Ignore unusable presentation metadata and retain Default. */ }
            }
        }
        return descriptors;
    }

    function saved() {
        try { return localStorage.getItem(KEY) || "default"; }
        catch { return "default"; }
    }

    function save(id) {
        try { localStorage.setItem(KEY, id); }
        catch { /* Storage denial keeps the selection for this page. */ }
    }

    function syncControls() {
        document.querySelector('meta[name="theme-color"]')?.setAttribute("content", current.themeColor);
        for (const picker of document.querySelectorAll("[data-theme-picker]")) {
            const options = Array.from(catalog.values());
            if (picker.options.length !== options.length || options.some((theme, i) =>
                picker.options[i]?.value !== theme.id || picker.options[i]?.textContent !== theme.name)) {
                picker.replaceChildren(...options.map((theme) => {
                    const option = document.createElement("option");
                    option.value = theme.id;
                    option.textContent = theme.name;
                    return option;
                }));
            }
            picker.value = current.id;
            picker.disabled = recovery;
        }
        for (const button of document.querySelectorAll("[data-theme-select]")) {
            button.setAttribute("aria-pressed", String(button.dataset.themeSelect === current.id));
            button.disabled = recovery && button.dataset.themeSelect !== "default";
        }
        if (document.body) {
            let status = document.querySelector("[data-theme-status]");
            if (!status && notice) {
                status = document.createElement("p");
                status.dataset.themeStatus = "";
                status.className = "notice";
                status.setAttribute("role", "status");
                status.setAttribute("aria-live", "polite");
                document.body.prepend(status);
            }
            if (status) {
                status.textContent = notice;
                status.hidden = !notice;
            }
        }
    }

    function report(theme, initial) {
        notice = `Could not load ${theme.name}. ${initial ? "Default has been restored." : "Your current theme is still selected."}`;
        syncControls();
        window.dispatchEvent(new CustomEvent("homecontrol:themeerror", { detail: { id: theme.id, message: notice } }));
    }

    function cacheCurrentTheme() {
        if (recovery) return;
        try {
            navigator.serviceWorker?.controller?.postMessage({
                type: "homecontrol:cache-theme", id: current.id, revision: current.revision
            });
        } catch { /* A restarting worker must not interfere with a successful theme switch. */ }
    }

    function apply(theme, link, persist, animate) {
        const previous = activeLink;
        if (link) link.media = "all";
        root.dataset.theme = theme.id;
        activeLink = link;
        current = theme;
        previous?.remove();
        if (persist) save(theme.id);
        notice = "";
        syncControls();
        if (animate) {
            clearTimeout(bootTimer);
            root.classList.add("theme-boot");
            bootTimer = setTimeout(() => root.classList.remove("theme-boot"), 700);
        }
        cacheCurrentTheme();
        window.dispatchEvent(new CustomEvent("homecontrol:themechange", { detail: theme }));
    }

    function load(theme) {
        const link = document.createElement("link");
        link.rel = "stylesheet";
        link.media = "not all";
        link.href = theme.stylesheet;
        link.dataset.themeStylesheet = theme.id;
        let finish;
        const ready = new Promise((resolve) => { finish = resolve; });
        const timer = setTimeout(() => complete(false), LOAD_TIMEOUT);
        function complete(ok) {
            clearTimeout(timer);
            link.onload = null;
            link.onerror = null;
            if (!ok) link.remove();
            finish(ok ? link : null);
        }
        link.onload = () => complete(true);
        link.onerror = () => complete(false);
        const request = { theme, cancel: () => complete(false) };
        pendingLoad = request;
        document.head.append(link);
        return ready.finally(() => { if (pendingLoad === request) pendingLoad = undefined; });
    }

    async function fetchCatalog() {
        if (catalogRequest) return catalogRequest;
        const controller = new AbortController();
        const timer = setTimeout(() => controller.abort(), LOAD_TIMEOUT);
        catalogRequest = (async () => {
            try {
                const response = await fetch("/themes/catalog.json", { cache: "no-cache", signal: controller.signal });
                if (!response.ok) return false;
                const data = await response.json();
                if (!Array.isArray(data.themes)) return false;
                catalog = readDescriptors(data);
                window.homeControlThemes = data;
                syncControls();
                return true;
            } catch { return false; }
            finally { clearTimeout(timer); }
        })();
        try { return await catalogRequest; }
        finally { catalogRequest = undefined; }
    }

    async function select(id, { persist = true, initial = false, refreshUnknown = true } = {}) {
        if (recovery && id !== "default") {
            syncControls();
            return false;
        }
        const starting = initial || Boolean(reveal);
        const mine = ++generation;
        pendingLoad?.cancel();
        desiredId = typeof id === "string" ? id : "default";
        if (!catalog.has(desiredId) && refreshUnknown && !initial) await fetchCatalog();
        if (mine !== generation) return false;
        const theme = catalog.get(desiredId) ?? catalog.get("default");
        desiredId = theme.id;
        if (theme.id === current.id && theme.revision === current.revision) {
            if (persist) save(theme.id);
            notice = "";
            syncControls();
            cacheCurrentTheme();
            reveal?.();
            return true;
        }
        const needsSheet = theme.id !== "default" || (theme.stylesheet && theme.stylesheet !== defaultStylesheet);
        const link = needsSheet ? await load(theme) : undefined;
        if (mine !== generation) { link?.remove(); return false; }
        if (needsSheet && !link) {
            desiredId = current.id;
            report(theme, starting);
            reveal?.();
            return false;
        }
        apply(theme, link, persist, !starting);
        reveal?.();
        return true;
    }

    async function refresh() {
        if (recovery) return false;
        const before = generation;
        const refreshed = await fetchCatalog();
        if (refreshed && before === generation) {
            const requested = catalog.get(desiredId);
            if (pendingLoad && pendingLoad.theme.id === requested?.id
                    && pendingLoad.theme.revision === requested.revision) return true;
            return select(desiredId, { persist: false, refreshUnknown: false });
        }
        return refreshed;
    }

    root.dataset.theme = "default";
    window.homeControlTheme = { select: (id) => select(id), refresh };
    const initialId = recovery ? "default" : saved();
    if (initialId !== "default" && catalog.has(initialId)) {
        const visibility = root.style.getPropertyValue("visibility");
        const priority = root.style.getPropertyPriority("visibility");
        root.style.setProperty("visibility", "hidden", "important");
        // This guard also covers errors outside the stylesheet's own load/error callbacks.
        const guard = setTimeout(() => {
            ++generation;
            pendingLoad?.cancel();
            desiredId = "default";
            apply(catalog.get("default"), undefined, false, false);
            reveal();
        }, LOAD_TIMEOUT + 100);
        reveal = () => {
            clearTimeout(guard);
            if (visibility) root.style.setProperty("visibility", visibility, priority);
            else root.style.removeProperty("visibility");
            reveal = undefined;
        };
    }
    void select(initialId, { persist: false, initial: true });

    document.addEventListener("DOMContentLoaded", () => { syncControls(); cacheCurrentTheme(); });
    document.addEventListener("change", (event) => {
        if (event.target.matches?.("[data-theme-picker]")) void select(event.target.value);
    });
    document.addEventListener("click", (event) => {
        const reset = event.target.closest?.("[data-theme-reset]");
        const button = event.target.closest?.("[data-theme-select]");
        if (!reset && !button) return;
        event.preventDefault();
        void select(reset ? "default" : button.dataset.themeSelect);
    });
    if (!recovery) {
        window.addEventListener("storage", (event) => {
            if (event.key === KEY || event.key === null) void select(event.newValue || "default", { persist: false });
        });
        window.addEventListener("focus", () => { void refresh(); });
        window.addEventListener("pageshow", (event) => { if (event.persisted) void refresh(); });
        document.addEventListener("visibilitychange", () => { if (!document.hidden) void refresh(); });
        navigator.serviceWorker?.addEventListener("controllerchange", cacheCurrentTheme);
    }
})();
