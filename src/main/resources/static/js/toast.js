// One toast at a time. Failure toasts may carry an action ("Try Cast with …").
let timer;

export function toast(message, { action, onAction, duration, ok } = {}) {
    const el = document.getElementById("toast");
    if (!el) return;
    el.querySelector(".toast-text").textContent = message;
    const button = el.querySelector(".toast-action");
    button.hidden = !action;
    button.textContent = action || "";
    button.onclick = action ? () => { el.hidden = true; onAction(); } : null;
    el.classList.toggle("ok", Boolean(ok));
    el.hidden = false;
    clearTimeout(timer);
    timer = setTimeout(() => (el.hidden = true), duration ?? (action ? 10000 : 4000));
}
