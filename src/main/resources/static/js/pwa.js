// Install guidance and, on trustworthy HTTPS only, the offline-page service worker (vNext §5.6).
export function initPwa() {
    if ("serviceWorker" in navigator && location.protocol === "https:") {
        navigator.serviceWorker.register("/sw.js").catch(() => { /* optional feature */ });
    }
    const section = document.getElementById("install");
    if (!section) return;
    const standalone = matchMedia("(display-mode: standalone)").matches || navigator.standalone === true;
    const show = (id) => { const el = document.getElementById(id); if (el) el.hidden = false; };
    if (standalone) {
        show("install-done");
        return;
    }
    let deferred = null;
    window.addEventListener("beforeinstallprompt", (event) => {
        event.preventDefault();
        deferred = event;
        show("install-button");
    });
    document.getElementById("install-button")?.addEventListener("click", async () => {
        if (!deferred) return;
        deferred.prompt();
        await deferred.userChoice;
        deferred = null;
        document.getElementById("install-button").hidden = true;
    });
    if ("standalone" in navigator) show("install-ios");      // Safari on iPhone and iPad
    else show("install-other");
    if (location.protocol !== "https:") show("install-http-note");
}
