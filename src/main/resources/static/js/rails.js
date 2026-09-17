import { on } from "./events.js";

// Rails re-render on the server; SSE only says which rail changed.
function railElement(sourceId, railId) {
    return document.querySelector(`.rail[data-rail="${CSS.escape(`${sourceId}/${railId}`)}"]`);
}

// Highest version any `rail` event has named per rail, and whether a fetch for it is in flight.
// Two events arriving close together (a refresh's own "still refreshing" marker and its eventual
// result) must not turn into two overlapping ajax calls racing to swap the same element: only one
// runs at a time, and it re-checks the freshly-swapped element against the highest version seen
// once it resolves, firing another fetch if that element is still behind.
const highestSeen = new Map();
const inFlight = new Set();

function refetch(sourceId, railId) {
    const key = `${sourceId}/${railId}`;
    if (inFlight.has(key)) return;
    const el = railElement(sourceId, railId);
    const wanted = highestSeen.get(key) ?? 0;
    if (!el || Number(el.dataset.version) >= wanted) return;
    const path = `/rails/${encodeURIComponent(sourceId)}/${encodeURIComponent(railId)}`;
    inFlight.add(key);
    window.htmx.ajax("GET", path, { target: el, swap: "outerHTML" })
        .finally(() => { inFlight.delete(key); refetch(sourceId, railId); });
}

export function watchRails() {
    on("rail", ({ sourceId, railId, version }) => {
        const key = `${sourceId}/${railId}`;
        if (version > (highestSeen.get(key) ?? 0)) highestSeen.set(key, version);
        refetch(sourceId, railId);
    });
    on("rails", ({ rails }) => {
        const shown = [...document.querySelectorAll("#rails > .rail")].map((el) => el.dataset.rail);
        if (shown.join("|") === rails.join("|")) return;
        window.htmx.ajax("GET", "/rails", { target: "#rails", swap: "outerHTML" });
    });
}
