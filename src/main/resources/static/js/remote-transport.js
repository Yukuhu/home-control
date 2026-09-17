// Every command names its device. A non-2xx reply carries a plain-text reason from the
// server; callers surface it, never retry, never queue (commands are ephemeral).
async function post(url, body) {
    const response = await fetch(url, { method: "POST", body });
    if (!response.ok) {
        throw new Error((await response.text()) || "The device is not connected");
    }
    return response;
}

export function sendKey(deviceId, key, { repeat = 1, press = "short" } = {}) {
    const query = new URLSearchParams();
    if (repeat !== 1) query.set("repeat", String(repeat));
    if (press !== "short") query.set("press", press);
    const suffix = query.size ? `?${query}` : "";
    return post(`/devices/${encodeURIComponent(deviceId)}/key/${key}${suffix}`);
}

export async function openLink(deviceId, uri) {
    const form = new URLSearchParams({ uri });
    const response = await post(`/devices/${encodeURIComponent(deviceId)}/play`, form);
    return response.text();
}
