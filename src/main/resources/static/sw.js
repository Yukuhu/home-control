// Public presentation only. Device state, API requests, fragments and writes stay on the network.
const SHELL_PREFIX = "home-control-presentation-";
const THEME_PREFIX = "home-control-theme-";
const LEGACY_PREFIX = "home-control-offline-";
const SHELL_METADATA = "/__home-control-presentation/catalog.json";
const THEME_METADATA = "/__home-control-theme/descriptor.json";
const OFFLINE = "/offline.html";
const SHELL_ASSETS = new Set([OFFLINE, "/app.css", "/js/theme.js", "/icons/icon.svg"]);
const CATALOG_PATHS = new Set(["/themes/catalog.js", "/themes/catalog.json"]);
let shellName;
let themeGeneration = 0;
let themeQueue = Promise.resolve();
let pendingTheme;

// This public bootstrap contains descriptors only and works in both window and worker globals.
try { importScripts("/themes/catalog.js"); }
catch { /* A previously published presentation cache can still serve an offline restart. */ }

function themePaths(theme) {
    if (!theme || !/^[a-z][a-z0-9-]{0,63}$/.test(theme.id)
            || !/^[a-zA-Z0-9_-]{1,128}$/.test(theme.revision) || !Array.isArray(theme.assets)) {
        throw new Error("Invalid theme descriptor");
    }
    const prefix = `/themes/packages/${theme.id}/${theme.revision}/`;
    const paths = [...new Set([theme.stylesheet, ...theme.assets])];
    if (theme.stylesheet !== `${prefix}theme.css`) throw new Error("Invalid theme stylesheet");
    for (const path of paths) {
        if (typeof path !== "string" || !path.startsWith(prefix)) throw new Error("Invalid theme asset");
        const url = new URL(path, self.location.origin);
        if (url.origin !== self.location.origin || url.pathname !== path || url.search || url.hash) {
            throw new Error("Invalid theme asset");
        }
    }
    return paths;
}

function builtIns(catalog) {
    const themes = catalog?.themes?.filter((theme) => theme.builtIn && ["default", "cyberpunk"].includes(theme.id));
    if (themes?.length !== 2 || !themes.some((theme) => theme.id === "default")
            || !themes.some((theme) => theme.id === "cyberpunk")) throw new Error("Built-in themes unavailable");
    for (const theme of themes) themePaths(theme);
    return { defaultId: "default", themes };
}

async function fetchPublic(path) {
    const response = await fetch(path, { cache: "reload", credentials: "same-origin", signal: AbortSignal.timeout(15000) });
    if (!response.ok || response.redirected || response.type === "opaque") throw new Error("Presentation asset unavailable");
    const type = response.headers.get("content-type") ?? "";
    if (path.endsWith(".css") && !type.includes("text/css")) throw new Error("Invalid stylesheet response");
    return response;
}

async function digest(bytes) {
    return Array.from(new Uint8Array(await crypto.subtle.digest("SHA-256", bytes)),
        (byte) => byte.toString(16).padStart(2, "0")).join("");
}

async function installShell() {
    const catalog = builtIns(self.homeControlThemes);
    const paths = [...new Set([...SHELL_ASSETS, ...catalog.themes.flatMap(themePaths)])].sort((a, b) => {
        if (a === b) return 0;
        return a < b ? -1 : 1;
    });
    const resources = await Promise.all(paths.map(async (path) => {
        const response = await fetchPublic(path);
        return { path, response, hash: await digest(await response.clone().arrayBuffer()) };
    }));
    // Include the whole shell and metadata, so script/layout changes also publish a new immutable cache.
    const fingerprint = JSON.stringify({ catalog, resources: resources.map(({ path, hash }) => [path, hash]) });
    const name = SHELL_PREFIX + await digest(new TextEncoder().encode(fingerprint));
    const cache = await caches.open(name);
    try {
        await putResources(cache, resources);
        await cache.put(SHELL_METADATA, Response.json({ catalog, createdAt: Date.now() }));
        shellName = name;
    } catch (error) {
        await caches.delete(name);
        throw error;
    }
}

async function putResources(cache, resources) {
    // Wait for every independent write before publishing metadata or rolling the cache back.
    const writes = await Promise.allSettled(resources.map(({ path, response }) => cache.put(path, response)));
    const failed = writes.find((write) => write.status === "rejected");
    if (failed) throw failed.reason;
}

async function readMetadata(cache, path) {
    try { return await (await cache.match(path))?.json(); }
    catch { return undefined; }
}

async function hasAll(cache, paths) {
    const matches = await Promise.all(paths.map((path) => cache.match(path)));
    return matches.every(Boolean);
}

async function completeShell(name) {
    const cache = await caches.open(name);
    const metadata = await readMetadata(cache, SHELL_METADATA);
    try {
        const catalog = builtIns(metadata?.catalog);
        const fallback = catalog.themes.find((theme) => theme.id === "default");
        // A partially evicted decorative built-in must not take the intact Default shell down with it.
        if (!await hasAll(cache, [...SHELL_ASSETS, ...themePaths(fallback)])) return undefined;
        const decorative = await Promise.all(catalog.themes.filter((theme) => theme.id !== "default")
            .map(async (theme) => await hasAll(cache, themePaths(theme)) ? theme : undefined));
        return { name, cache, catalog: { ...catalog, themes: [fallback, ...decorative.filter(Boolean)] },
            createdAt: metadata.createdAt };
    } catch { return undefined; /* An incomplete/evicted cache is never advertised. */ }
}

async function completeShells() {
    const names = (await caches.keys()).filter((name) => name.startsWith(SHELL_PREFIX));
    const records = await Promise.all(names.map(completeShell));
    return records.filter(Boolean).sort((a, b) => b.createdAt - a.createdAt);
}

async function currentShell() {
    const shells = await completeShells();
    return shells.find((shell) => shell.name === shellName) ?? shells[0];
}

async function completeCustomTheme(name) {
    const cache = await caches.open(name);
    const metadata = await readMetadata(cache, THEME_METADATA);
    try {
        const theme = metadata?.theme;
        if (theme.builtIn || ["default", "cyberpunk"].includes(theme.id)) return undefined;
        if (await hasAll(cache, themePaths(theme))) return { name, cache, theme, createdAt: metadata.createdAt };
    } catch { /* Publication requires a descriptor and the entire revision, including fonts. */ }
    return undefined;
}

async function completeCustomThemes() {
    const names = (await caches.keys()).filter((name) => name.startsWith(THEME_PREFIX));
    const records = await Promise.all(names.map(completeCustomTheme));
    return records.filter(Boolean).sort((a, b) => b.createdAt - a.createdAt);
}

async function activate() {
    const shell = await currentShell();
    if (shell) shellName = shell.name;
    const custom = (await completeCustomThemes())[0];
    const stale = (await caches.keys()).filter((name) => {
        const staleShell = shell && name.startsWith(SHELL_PREFIX) && name !== shell.name;
        const staleTheme = name.startsWith(THEME_PREFIX) && name !== custom?.name;
        return staleShell || staleTheme || name.startsWith(LEGACY_PREFIX);
    });
    await Promise.all(stale.map((name) => caches.delete(name)));
    await self.clients.claim();
}

async function offlineCatalog() {
    const shell = await currentShell();
    if (!shell) throw new Error("Offline presentation unavailable");
    const custom = (await completeCustomThemes())[0];
    return { ...shell.catalog, themes: [...shell.catalog.themes, ...(custom ? [custom.theme] : [])], offline: true };
}

function authoritativeCatalog(catalog) {
    if (catalog?.offline || !Array.isArray(catalog?.themes)) throw new Error("Online theme catalog unavailable");
    builtIns(catalog);
    for (const theme of catalog.themes) themePaths(theme);
    return catalog;
}

async function revokeRemovedThemes(catalog) {
    // Installed IDs keep their previous complete revision until a replacement has fully loaded.
    const available = new Set(catalog.themes.map((theme) => theme.id));
    const revoked = (await completeCustomThemes())
        .filter(({ theme }) => !available.has(theme.id));
    await Promise.all(revoked.map(({ name }) => caches.delete(name)));
}

async function reconcileCatalog(response, mine) {
    try {
        const catalog = authoritativeCatalog(await response.json());
        // Reconciliation shares the publication queue so it cannot delete a revision while it is being written.
        themeQueue = themeQueue.catch(() => {}).then(() => {
            // An older network response must not revoke a theme selected and revalidated after its request began.
            if (mine === themeGeneration) return revokeRemovedThemes(catalog);
        });
        await themeQueue;
    } catch { /* A failed or malformed response is not evidence that a cached revision was removed. */ }
}

async function catalogResponse(event, pathname) {
    const mine = themeGeneration;
    try {
        const response = await fetch(event.request);
        if (pathname.endsWith(".json") && response.ok && !response.redirected) {
            // Cleanup has its own lifetime: a slow theme download must not delay a successful online catalog.
            event.waitUntil(reconcileCatalog(response.clone(), mine));
        }
        return response;
    }
    catch {
        try {
            const catalog = await offlineCatalog();
            if (pathname.endsWith(".json")) return Response.json(catalog);
            return new Response(`globalThis.homeControlThemes=${JSON.stringify(catalog)};`, {
                headers: { "Content-Type": "text/javascript; charset=utf-8", "X-Content-Type-Options": "nosniff" }
            });
        } catch { return Response.error(); }
    }
}

async function cachedPresentation(path) {
    const shell = await currentShell();
    if (shell && (SHELL_ASSETS.has(path) || shell.catalog.themes.some((theme) => themePaths(theme).includes(path)))) {
        return shell.cache.match(path);
    }
    const custom = (await completeCustomThemes())[0];
    if (custom && themePaths(custom.theme).includes(path)) return custom.cache.match(path);
    return undefined;
}

async function presentationResponse(request, path) {
    try { return await fetch(request); }
    catch { return await cachedPresentation(path) ?? Response.error(); }
}

async function cacheTheme(id, revision, mine) {
    if (mine !== themeGeneration) return;
    // Re-read server-owned descriptors; a page message cannot choose arbitrary URLs to cache.
    const response = await fetch("/themes/catalog.json", {
        cache: "no-cache", credentials: "same-origin", signal: AbortSignal.timeout(15000)
    });
    if (!response.ok || response.redirected) throw new Error("Theme catalog unavailable");
    const catalog = authoritativeCatalog(await response.json());
    if (mine !== themeGeneration) return;
    await revokeRemovedThemes(catalog);
    const theme = catalog.themes.find((item) => item.id === id && item.revision === revision);
    if (!theme || theme.builtIn || mine !== themeGeneration) return;
    const paths = themePaths(theme);
    const name = `${THEME_PREFIX}${id}-${revision}`;
    const existing = (await completeCustomThemes()).some((item) => item.name === name);
    if (existing) return;
    // Fetch the full revision before opening its cache. A missing font preserves the previous complete theme.
    const resources = await Promise.all(paths.map(async (path) => ({ path, response: await fetchPublic(path) })));
    if (mine !== themeGeneration) return;
    const cache = await caches.open(name);
    try {
        await putResources(cache, resources);
        if (mine !== themeGeneration) { await caches.delete(name); return; }
        await cache.put(THEME_METADATA, Response.json({ theme, createdAt: Date.now() }));
        if (mine !== themeGeneration) { await caches.delete(name); return; }
        const stale = (await caches.keys()).filter((key) => key.startsWith(THEME_PREFIX) && key !== name);
        await Promise.all(stale.map((key) => caches.delete(key)));
    } catch (error) {
        await caches.delete(name);
        throw error;
    }
}

self.addEventListener("install", (event) => {
    event.waitUntil(installShell().then(() => self.skipWaiting()));
});

self.addEventListener("activate", (event) => { event.waitUntil(activate()); });

self.addEventListener("message", (event) => {
    if (event.origin !== self.location.origin) return;
    const message = event.data;
    if (message?.type !== "homecontrol:cache-theme" || typeof message.id !== "string") return;
    if (!/^[a-z][a-z0-9-]{0,63}$/.test(message.id) || !/^[a-zA-Z0-9_-]{1,128}$/.test(message.revision)) return;
    const identity = `${message.id}/${message.revision}`;
    if (pendingTheme === identity) {
        event.waitUntil(themeQueue.catch(() => {}));
        return;
    }
    const mine = ++themeGeneration;
    pendingTheme = identity;
    themeQueue = themeQueue.catch(() => {})
        .then(() => cacheTheme(message.id, message.revision, mine))
        .finally(() => { if (mine === themeGeneration) pendingTheme = undefined; });
    event.waitUntil(themeQueue.catch(() => { /* Theme selection also works without offline storage. */ }));
});

self.addEventListener("fetch", (event) => {
    const request = event.request;
    if (request.method !== "GET") return;
    const url = new URL(request.url);
    if (url.origin !== self.location.origin) return;
    if (request.mode === "navigate") {
        event.respondWith(fetch(request).catch(async () => {
            const shell = await currentShell();
            return await shell?.cache.match(OFFLINE) ?? Response.error();
        }));
        return;
    }
    if (url.search || url.hash) return;
    if (CATALOG_PATHS.has(url.pathname)) {
        event.respondWith(catalogResponse(event, url.pathname));
    } else if (SHELL_ASSETS.has(url.pathname) || url.pathname.startsWith("/themes/packages/")) {
        event.respondWith(presentationResponse(request, url.pathname));
    }
});
