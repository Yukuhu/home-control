// The setup page's own script: the app install card, and the deep-link test's failures under its button.
import { initPwa } from "./pwa.js";

initPwa();

function showUnderTheButton(event, text) {
    const button = event.detail.elt;
    if (button?.classList.contains("deep-link-test")) {
        button.nextElementSibling.textContent = text;
    }
}

document.body.addEventListener("htmx:responseError", (event) =>
    showUnderTheButton(event, event.detail.xhr.responseText || "The test could not run"));
document.body.addEventListener("htmx:sendError", (event) => showUnderTheButton(event, "Cannot reach the server"));
