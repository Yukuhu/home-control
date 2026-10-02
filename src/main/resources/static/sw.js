// Public presentation only. Device state, API requests, fragments and writes stay on the network.
const SHELL_PREFIX = "home-control-presentation-";
const THEME_PREFIX = "home-control-theme-";
const LEGACY_PREFIX = "home-control-offline-";
const SHELL_METADATA = "/__home-control-presentation/catalog.json";
const THEME_METADATA = "/__home-control-theme/descriptor.json";
const OFFLINE = "/offline.html";
const SHELL_ASSETS = [OFFLINE, "/app.css", "/js/theme.js", "/icons/icon.svg"];
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
    const response = await fetch(path, { cache: "reload", credentials: "omit", signal: AbortSignal.timeout(15000) });
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
    const paths = [...new Set([...SHELL_ASSETS, ...catalog.themes.flatMap(themePaths)])].sort();
    const resources = await Promise.all(paths.map(async (path) => {
        const response = await fetchPublic(path);
        return { path, response, hash: await digest(await response.clone().arrayBuffer()) };
    }));
    // Include the whole shell and metadata, so script/layout changes also publish a new immutable cache.
    const fingerprint = JSON.stringify({ catalog, resources: resources.map(({ path, hash }) => [path, hash]) });
    const name = SHELL_PREFIX + await digest(new TextEncoder().encode(fingerprint));
    const cache = await caches.open(name);
    try {
        for (const { path, response } of resources) await cache.put(path, response);
        await cache.put(SHELL_METADATA, Response.json({ catalog, createdAt: Date.now() }));
        shellName = name;
    } catch (error) {
        await caches.delete(name);
        throw error;
    }
}

async function readMetadata(cache, path) {
    try { return await (await cache.match(path))?.json(); }
    catch { return undefined; }
}

async function hasAll(cache, paths) {
    const matches = await Promise.all(paths.map((path) => cache.match(path)));
    return matches.every(Boolean);
}

async function completeShells() {
    const records = [];
    for (const name of await caches.keys()) {
        if (!name.startsWith(SHELL_PREFIX)) continue;
        const cache = await caches.open(name);
        const metadata = await readMetadata(cache, SHELL_METADATA);
        try {
            const catalog = builtIns(metadata?.catalog);
            const fallback = catalog.themes.find((theme) => theme.id === "default");
            // A partially evicted decorative built-in must not take the intact Default shell down with it.
            if (await hasAll(cache, [...SHELL_ASSETS, ...themePaths(fallback)])) {
                const available = [fallback];
                for (const theme of catalog.themes.filter((item) => item.id !== "default")) {
                    if (await hasAll(cache, themePaths(theme))) available.push(theme);
                }
                records.push({ name, cache, catalog: { ...catalog, themes: available }, createdAt: metadata.createdAt });
            }
        } catch { /* An incomplete/evicted cache is never advertised. */ }
    }
    return records.sort((a, b) => b.createdAt - a.createdAt);
}

async function currentShell() {
    const shells = await completeShells();
    return shells.find((shell) => shell.name === shellName) ?? shells[0];
}

async function completeCustomThemes() {
    const records = [];
    for (const name of await caches.keys()) {
        if (!name.startsWith(THEME_PREFIX)) continue;
        const cache = await caches.open(name);
        const metadata = await readMetadata(cache, THEME_METADATA);
        try {
            const theme = metadata?.theme;
            if (theme.builtIn || ["default", "cyberpunk"].includes(theme.id)) continue;
            if (await hasAll(cache, themePaths(theme))) records.push({ name, cache, theme, createdAt: metadata.createdAt });
        } catch { /* Publication requires a descriptor and the entire revision, including fonts. */ }
    }
    return records.sort((a, b) => b.createdAt - a.createdAt);
}

async function activate() {
    const shell = await currentShell();
    if (shell) shellName = shell.name;
    const custom = (await completeCustomThemes())[0];
    for (const name of await caches.keys()) {
        const staleShell = shell && name.startsWith(SHELL_PREFIX) && name !== shell.name;
        const staleTheme = name.startsWith(THEME_PREFIX) && name !== custom?.name;
        if (staleShell || staleTheme || name.startsWith(LEGACY_PREFIX)) await caches.delete(name);
    }
    await self.clients.claim();
}

async function offlineCatalog() {
    const shell = await currentShell();
    if (!shell) throw new Error("Offline presentation unavailable");
    const custom = (await completeCustomThemes())[0];
    return { ...shell.catalog, themes: [...shell.catalog.themes, ...(custom ? [custom.theme] : [])], offline: true };
}

async function catalogResponse(request, pathname) {
    try { return await fetch(request); }
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
    if (shell && (SHELL_ASSETS.includes(path) || shell.catalog.themes.some((theme) => themePaths(theme).includes(path)))) {
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
        cache: "no-cache", credentials: "omit", signal: AbortSignal.timeout(15000)
    });
    if (!response.ok) throw new Error("Theme catalog unavailable");
    const catalog = await response.json();
    const theme = catalog.themes?.find((item) => item.id === id && item.revision === revision);
    if (!theme || theme.builtIn || mine !== themeGeneration) return;
    const paths = themePaths(theme);
    const name = `${THEME_PREFIX}${id}-${revision}`;
    const existing = (await completeCustomThemes()).find((item) => item.name === name);
    if (existing) return;
    // Fetch the full revision before opening its cache. A missing font preserves the previous complete theme.
    const resources = await Promise.all(paths.map(async (path) => ({ path, response: await fetchPublic(path) })));
    if (mine !== themeGeneration) return;
    const cache = await caches.open(name);
    try {
        for (const { path, response: asset } of resources) await cache.put(path, asset);
        if (mine !== themeGeneration) { await caches.delete(name); return; }
        await cache.put(THEME_METADATA, Response.json({ theme, createdAt: Date.now() }));
        for (const key of await caches.keys()) {
            if (key.startsWith(THEME_PREFIX) && key !== name) await caches.delete(key);
        }
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
    const message = event.data;
    if (message?.type !== "homecontrol:cache-theme" || typeof message.id !== "string") return;
    if (!/^[a-z][a-z0-9-]{0,63}$/.test(message.id) || !/^[a-zA-Z0-9_-]{1,128}$/.test(message.revision)) return;
    const identity = `${message.id}/${message.revision}`;
    if (pendingTheme === identity) {
        event.waitUntil(themeQueue.catch(() => {}));
        return;
    }
    const mine = ++themeGeneration;
    pendingTheme = undefined;
    if (["default", "cyberpunk"].includes(message.id)) return;
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
        event.respondWith(catalogResponse(request, url.pathname));
    } else if (SHELL_ASSETS.includes(url.pathname) || url.pathname.startsWith("/themes/packages/")) {
        event.respondWith(presentationResponse(request, url.pathname));
    }
});
