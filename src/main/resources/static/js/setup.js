// The setup page's own script: the app install card, forms that send once, and the deep-link test's failures under
// its button.
import { initPwa } from "./pwa.js";

initPwa();

// Pairing forms send once: a second click would start the pairing again, or send the code again, while the TV
// still answers the first.
const sendOnce = document.querySelectorAll("form[data-submit-once]");

function setSubmitButtons(form, disabled) {
    for (const button of form.querySelectorAll("button[type='submit']")) {
        button.disabled = disabled;
    }
}

for (const form of sendOnce) {
    form.addEventListener("submit", () => setSubmitButtons(form, true));
}
// A page the browser brings back from its cache, after Back, can be sent again.
window.addEventListener("pageshow", (event) => {
    if (event.persisted) {
        sendOnce.forEach((form) => setSubmitButtons(form, false));
    }
});

function showUnderTheButton(event, text) {
    const button = event.detail.elt;
    if (button?.classList.contains("deep-link-test")) {
        button.nextElementSibling.textContent = text;
    }
}

document.body.addEventListener("htmx:responseError", (event) =>
    showUnderTheButton(event, event.detail.xhr.responseText || "The test could not run"));
document.body.addEventListener("htmx:sendError", (event) => showUnderTheButton(event, "Cannot reach the server"));
