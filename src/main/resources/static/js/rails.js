import { on } from "./events.js";

// Rails re-render on the server; SSE only says which rail changed.
function railElement(sourceId, railId) {
    return document.querySelector(`.rail[data-rail="${CSS.escape(`${sourceId}/${railId}`)}"]`);
}

export function watchRails() {
    on("rail", ({ sourceId, railId, version }) => {
        const el = railElement(sourceId, railId);
        if (!el || Number(el.dataset.version) >= version) return;
        const path = `/rails/${encodeURIComponent(sourceId)}/${encodeURIComponent(railId)}`;
        window.htmx.ajax("GET", path, { target: el, swap: "outerHTML" });
    });
    on("rails", ({ rails }) => {
        const shown = [...document.querySelectorAll("#rails > .rail")].map((el) => el.dataset.rail);
        if (shown.join("|") === rails.join("|")) return;
        window.htmx.ajax("GET", "/rails", { target: "#rails", swap: "outerHTML" });
    });
}
