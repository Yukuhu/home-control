// One EventSource per page; modules register named handlers. EventSource reconnects by itself only after a network
// error. An answer with an error status ends it for good: a 502 from a reverse proxy while the server restarts, or a
// 401 once this browser's login is gone. Then this module asks /session whether the login is gone, and either sends
// the browser to the login page or opens a new stream, waiting longer after each failure.
let source;
const handlers = new Map();
const FIRST_RETRY_MS = 1000;
const LAST_RETRY_MS = 30000;
let retryMs = FIRST_RETRY_MS;

function announce(connected) {
    document.dispatchEvent(new CustomEvent("homecontrol:stream", { detail: { connected } }));
}

function deliver(name) {
    return (event) => {
        const data = JSON.parse(event.data);
        for (const h of handlers.get(name)) h(data);
    };
}

function open() {
    source = new EventSource("/events");
    source.addEventListener("open", () => {
        retryMs = FIRST_RETRY_MS;
        announce(true);
    });
    source.addEventListener("error", () => {
        announce(false);
        if (source.readyState === EventSource.CLOSED) setTimeout(recover, nextRetry());
    });
    for (const name of handlers.keys()) source.addEventListener(name, deliver(name));
}

function nextRetry() {
    const wait = retryMs;
    retryMs = Math.min(retryMs * 2, LAST_RETRY_MS);
    return wait;
}

async function recover() {
    try {
        const answer = await fetch("/session", { cache: "no-store" });
        if (answer.status === 401) {
            location.assign(`/login?next=${encodeURIComponent(location.pathname + location.search)}`);
            return;
        }
    } catch {
        // The server is still unreachable; the new stream finds out and tries again.
    }
    open();
}

export function stream() {
    if (!source) open();
    return source;
}

export function on(name, handler) {
    if (!handlers.has(name)) {
        handlers.set(name, []);
        if (source) source.addEventListener(name, deliver(name));
    }
    handlers.get(name).push(handler);
    stream();
}
