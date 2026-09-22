// Minimal service worker (registered on HTTPS only): an offline page and its presentation assets.
// Live state, writes, JSON, fragments and other assets always require the server.
const CACHE = "home-control-offline-v2";
const OFFLINE = "/offline.html";
const OFFLINE_ASSETS = ["/app.css", "/icons/icon.svg"];

self.addEventListener("install", (event) => {
    event.waitUntil(caches.open(CACHE).then((cache) => cache.addAll([OFFLINE, ...OFFLINE_ASSETS])));
    self.skipWaiting();
});

self.addEventListener("activate", (event) => {
    event.waitUntil(caches.keys()
        .then((keys) => Promise.all(keys.filter((key) => key !== CACHE).map((key) => caches.delete(key))))
        .then(() => self.clients.claim()));
});

self.addEventListener("fetch", (event) => {
    const request = event.request;
    if (request.method !== "GET") return;
    if (request.mode === "navigate") {
        event.respondWith(fetch(request).catch(() => caches.match(OFFLINE)));
        return;
    }
    const url = new URL(request.url);
    if (url.origin !== self.location.origin || !OFFLINE_ASSETS.includes(url.pathname)) return;
    event.respondWith(fetch(request).catch(() => caches.match(url.pathname)));
});
