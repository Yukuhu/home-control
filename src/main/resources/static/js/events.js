// One EventSource per page. Modules register named handlers; EventSource reconnects on its own.
let source;
const handlers = new Map();

export function stream() {
    if (!source) {
        source = new EventSource("/events");
        source.addEventListener("open", () => document.dispatchEvent(
            new CustomEvent("homecontrol:stream", { detail: { connected: true } })));
        source.addEventListener("error", () => document.dispatchEvent(
            new CustomEvent("homecontrol:stream", { detail: { connected: false } })));
    }
    return source;
}

export function on(name, handler) {
    if (!handlers.has(name)) {
        handlers.set(name, []);
        stream().addEventListener(name, (event) => {
            const data = JSON.parse(event.data);
            for (const h of handlers.get(name)) h(data);
        });
    }
    handlers.get(name).push(handler);
}
