import { stateOf } from "./state-view.js";
import { toast } from "./toast.js";
import { bindChoiceKeys } from "./choice-keys.js";

export const APP_LINK_HINT_MS = 5000;

const sheet = () => document.getElementById("play-sheet");
let current = null;   // { source, item, title }
let target = null;    // device id
let previewSeq = 0;
let pinOffer = null;
let sheetKind = null; // the open item's ContentKind, e.g. "LIVE_EVENT"

function showPin(offer) {
    const form = document.getElementById("sheet-pin");
    if (!form) return;
    pinOffer = offer;
    document.getElementById("sheet-pin-error").hidden = true;
    if (!offer) { form.hidden = true; return; }
    const isLiveEvent = sheetKind === "LIVE_EVENT";
    document.getElementById("sheet-pin-text").textContent = offer.serviceName
        ? (isLiveEvent
            ? `This opens the ${offer.serviceName} app, not this event. Paste the ${offer.serviceName} link for this event to open it directly.`
            : `This opens the ${offer.serviceName} app, not this title. Paste the ${offer.serviceName} link for this title to open it directly.`)
        : isLiveEvent
            ? "Home Control cannot open this event directly. Paste a link to it (for example the event's page on your streaming service) to pin it."
            : "Home Control cannot open this title on your services. Paste a link to it (Netflix, Prime Video, YouTube, DAZN or any web link) to pin it.";
    form.hidden = false;
}

async function submitPin(event) {
    event.preventDefault();
    if (!pinOffer) return;
    const input = document.getElementById("sheet-pin-url");
    const errorEl = document.getElementById("sheet-pin-error");
    const button = document.getElementById("sheet-pin-submit");
    button.disabled = true;
    try {
        const response = await fetch("/setup/sources/pinned/upgrade", {
            method: "POST",
            headers: { Accept: "application/json" },
            body: form({ url: input.value, upgradeOf: pinOffer.upgradeOf }),
        });
        const data = await readJsonOrText(response);
        if (!response.ok) {
            errorEl.textContent = data.message || "Could not pin this link";
            errorEl.hidden = false;
            return;
        }
        input.value = "";
        toast(data.message, { ok: true });
        preview();
    } catch {
        errorEl.textContent = "Cannot reach the server";
        errorEl.hidden = false;
    } finally {
        button.disabled = false;
    }
}

function form(fields) {
    const body = new URLSearchParams();
    for (const [key, value] of Object.entries(fields)) {
        for (const v of [].concat(value)) body.append(key, v);
    }
    return body;
}

async function readJsonOrText(response) {
    const type = response.headers.get("Content-Type") || "";
    return type.includes("application/json") ? response.json() : { message: await response.text() };
}

function selectDevice(deviceId) {
    target = deviceId;
    for (const button of sheet().querySelectorAll("[data-sheet-device]")) {
        button.setAttribute("aria-checked", String(button.dataset.sheetDevice === deviceId));
        button.tabIndex = button.dataset.sheetDevice === deviceId ? 0 : -1;
    }
    preview();
}

async function preview() {
    const seq = ++previewSeq;
    const routeEl = document.getElementById("sheet-route");
    const play = document.getElementById("sheet-play");
    routeEl.textContent = "Working out how to play this…";
    routeEl.classList.remove("unroutable");
    play.disabled = true;
    showPin(null);
    const query = new URLSearchParams({ source: current.source, item: current.item });
    let data;
    try {
        const response = await fetch(`/devices/${encodeURIComponent(target)}/route-preview?${query}`,
            { headers: { Accept: "application/json" } });
        data = await readJsonOrText(response);
        if (!response.ok) throw new Error(data.message || "Cannot plan this right now");
    } catch (error) {
        if (seq !== previewSeq) return;
        routeEl.textContent = error.message;
        routeEl.classList.add("unroutable");
        return;
    }
    if (seq !== previewSeq) return;   // the user switched device meanwhile
    if (!data.playable) {
        routeEl.textContent = `Cannot play on ${data.deviceName}: ${data.reason}`;
        routeEl.classList.add("unroutable");
        play.textContent = "Play";
        showPin(data.pin);
        return;
    }
    routeEl.textContent = `Play on ${data.deviceName} · ${data.route.description}`;
    play.textContent = `Play on ${data.deviceName}`;
    play.disabled = false;
    showPin(data.pin);
}

function watchAppLink(deviceId, deviceName) {
    const before = stateOf(deviceId);
    const snapshot = (s) => JSON.stringify([s?.currentApp ?? null, s?.nowPlaying?.title ?? null]);
    const initial = snapshot(before);
    let changed = false;
    const listener = (event) => {
        if (event.detail.deviceId === deviceId && snapshot(event.detail.state) !== initial) changed = true;
    };
    document.addEventListener("homecontrol:state", listener);
    setTimeout(() => {
        document.removeEventListener("homecontrol:state", listener);
        if (!changed) {
            toast(`If nothing started on ${deviceName}, the app for this link may not be installed.`);
        }
    }, APP_LINK_HINT_MS);
}

async function attempt(deviceId, skip) {
    const play = document.getElementById("sheet-play");
    play.disabled = true;
    play.textContent = "Starting playback…";
    const request = { ...current };
    let response;
    let data;
    try {
        response = await fetch(`/devices/${encodeURIComponent(deviceId)}/play-attempt`, {
            method: "POST",
            headers: { Accept: "application/json" },
            body: form({ source: request.source, item: request.item, skip }),
        });
        data = await readJsonOrText(response);
    } catch {
        // Same reasoning as the outcome branches below: the modal sheet would otherwise sit in
        // front of this toast too.
        sheet().close();
        toast("Cannot reach the server");
        play.disabled = false;
        return;
    }
    if (response.ok && data.played) {
        sheet().close();
        toast(`${data.message} on ${data.deviceName}`, { ok: true });
        if (data.route.optimistic) watchAppLink(deviceId, data.deviceName);
        return;
    }
    // The sheet is a modal <dialog>: while it stays open, everything outside it — including this
    // toast and, worse, its own "Try …" retry button — sits behind the dialog's top layer and
    // is inert (unclickable) in every browser. Close it before reporting any outcome, exactly
    // like the success path above, so the retry toast is actually usable.
    sheet().close();
    play.disabled = false;
    if (!data.route) {
        toast(data.message || "Cannot play this");
        return;
    }
    const failed = `${data.route.description} failed: ${data.message}`;
    if (data.next) {
        current = request;
        toast(`${failed}. Next: ${data.next.description}`, {
            action: `Try ${data.next.description}`,
            onAction: () => { current = request; attempt(deviceId, [...skip, data.route.key]); },
        });
    } else {
        toast(`${failed}. There is no other way to play this on ${data.deviceName}.`);
    }
}

export function openPlaySheet(tile) {
    const d = tile.dataset;
    current = { source: d.source, item: d.item, title: d.title };
    sheetKind = d.kind || null;
    document.getElementById("sheet-title").textContent = d.title || "";
    document.getElementById("sheet-subtitle").textContent = d.subtitle || "";
    const art = document.getElementById("sheet-art");
    art.hidden = !d.artwork;
    if (d.artwork) art.src = d.artwork; else art.removeAttribute("src");
    sheet().showModal();
    selectDevice(target && sheet().querySelector(`[data-sheet-device="${CSS.escape(target)}"]`)
        ? target : document.body.dataset.device);
}

export function initPlaySheet() {
    if (!sheet()) return;
    bindChoiceKeys(sheet().querySelector(".sheet-devices"), "[data-sheet-device]",
        (button) => selectDevice(button.dataset.sheetDevice));
    document.addEventListener("click", (event) => {
        const tile = event.target.closest("button.tile");
        if (tile && tile.dataset.item) openPlaySheet(tile);
        const device = event.target.closest("[data-sheet-device]");
        if (device) selectDevice(device.dataset.sheetDevice);
    });
    document.getElementById("sheet-play").addEventListener("click", () => attempt(target, []));
    document.getElementById("sheet-pin")?.addEventListener("submit", submitPin);
    sheet().addEventListener("close", () => { previewSeq++; });
}
