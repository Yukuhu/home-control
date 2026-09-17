// Minimal service worker (registered on HTTPS only): an offline explanation page for navigations.
// It never answers the event stream, writes, JSON, fragments or assets, so live state and the login flow are untouched.
const CACHE = "home-control-offline-v1";
const OFFLINE = "/offline.html";

self.addEventListener("install", (event) => {
    event.waitUntil(caches.open(CACHE).then((cache) => cache.addAll([OFFLINE, "/app.css", "/icons/icon.svg"])));
    self.skipWaiting();
});

self.addEventListener("activate", (event) => {
    event.waitUntil(caches.keys()
        .then((keys) => Promise.all(keys.filter((key) => key !== CACHE).map((key) => caches.delete(key))))
        .then(() => self.clients.claim()));
});

self.addEventListener("fetch", (event) => {
    const request = event.request;
    if (request.mode !== "navigate" || request.method !== "GET") return;
    event.respondWith(fetch(request).catch(() => caches.match(OFFLINE)));
});
