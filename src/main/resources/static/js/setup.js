// The setup page's own script: the app install card, forms that send once, and the deep-link test's failures under
// its button.
import { initPwa } from "./pwa.js";

initPwa();

// A pairing code is good for one try: a second click would send it again while the TV still checks the first.
for (const form of document.querySelectorAll("form[data-submit-once]")) {
    form.addEventListener("submit", () => {
        for (const button of form.querySelectorAll("button[type='submit']")) {
            button.disabled = true;
        }
    });
}

function showUnderTheButton(event, text) {
    const button = event.detail.elt;
    if (button?.classList.contains("deep-link-test")) {
        button.nextElementSibling.textContent = text;
    }
}

document.body.addEventListener("htmx:responseError", (event) =>
    showUnderTheButton(event, event.detail.xhr.responseText || "The test could not run"));
document.body.addEventListener("htmx:sendError", (event) => showUnderTheButton(event, "Cannot reach the server"));
