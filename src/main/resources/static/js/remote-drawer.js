// The remote drawer: a modal dialog above the page on a phone or a touch screen, a dock beside the page on a desktop
// with a fine pointer. It follows the visible viewport, which zoom and the on-screen keyboard can make smaller than
// the layout viewport.

const DESKTOP_REMOTE = "(min-width: 64rem) and (hover: hover) and (pointer: fine)";

export function initRemoteDrawer() {
    const drawer = document.getElementById("remote-drawer");
    if (!drawer) return;
    const desktopRemote = window.matchMedia(DESKTOP_REMOTE);

    function shouldDockRemote() {
        return desktopRemote.matches && (window.visualViewport?.scale ?? 1) <= 1;
    }

    function syncRemoteViewport() {
        // Zoom and the on-screen keyboard can make the visible area smaller than the layout viewport.
        const viewport = window.visualViewport;
        drawer.style.setProperty("--remote-vw", `${viewport?.width ?? document.documentElement.clientWidth}px`);
        drawer.style.setProperty("--remote-vh", `${viewport?.height ?? document.documentElement.clientHeight}px`);
        drawer.style.setProperty("--remote-vx", `${viewport?.offsetLeft ?? 0}px`);
        drawer.style.setProperty("--remote-vy", `${viewport?.offsetTop ?? 0}px`);
    }

    function showDrawer() {
        syncRemoteViewport();
        // A native modal keeps the phone remote above the page and background controls inert.
        const docked = shouldDockRemote();
        if (docked) drawer.show();
        else drawer.showModal();
        // Toasts outside a modal would be hidden behind its backdrop.
        const toastElement = document.getElementById("toast");
        if (toastElement) (docked ? document.body : drawer).append(toastElement);
    }

    function setDrawer(open) {
        drawer.hidden = !open;
        if (open && !drawer.open) {
            showDrawer();
            drawer.scrollTop = 0;
        } else if (!open) {
            drawer.close();
            const toastElement = drawer.querySelector("#toast");
            if (toastElement) document.body.append(toastElement);
        }
        document.querySelectorAll('[aria-controls="remote-drawer"].drawer-toggle')
            .forEach((b) => b.setAttribute("aria-expanded", String(open)));
        if (open) drawer.querySelector(".drawer-close")?.focus({ preventScroll: true });
        else document.querySelector(".drawer-toggle")?.focus({ preventScroll: true });
    }
    drawer.addEventListener("close", () => {
        // close events are queued; a breakpoint change may already have reopened the dialog.
        if (!drawer.open) setDrawer(false);
    });

    function updateRemoteLayout() {
        if (!drawer.open) return;
        syncRemoteViewport();
        const modal = !shouldDockRemote();
        if (drawer.matches(":modal") === modal) return;
        const focused = document.activeElement;
        const scrollTop = drawer.scrollTop;
        drawer.close();
        showDrawer();
        drawer.scrollTop = scrollTop;
        if (drawer.contains(focused)) focused.focus({ preventScroll: true });
    }

    let remoteLayoutFrame;
    function scheduleRemoteLayout() {
        if (remoteLayoutFrame) return;
        remoteLayoutFrame = window.requestAnimationFrame(() => {
            remoteLayoutFrame = undefined;
            updateRemoteLayout();
        });
    }
    desktopRemote.addEventListener("change", scheduleRemoteLayout);
    window.addEventListener("resize", scheduleRemoteLayout);
    window.visualViewport?.addEventListener("resize", scheduleRemoteLayout);
    window.visualViewport?.addEventListener("scroll", scheduleRemoteLayout);
    if (!drawer.hidden) setDrawer(true);
    document.addEventListener("click", (event) => {
        if (event.target.closest(".drawer-toggle")) setDrawer(document.getElementById("remote-drawer").hidden);
        if (event.target.closest(".drawer-close")) setDrawer(false);
    });

    // Escape closes the drawer, docked or modal, unless another dialog is open: that one takes Escape first.
    document.addEventListener("keydown", (event) => {
        if (event.key !== "Escape" || event.defaultPrevented || event.altKey || event.ctrlKey || event.metaKey) return;
        if (document.querySelector("dialog[open]:not(#remote-drawer)")) return;
        if (!drawer.hidden) {
            event.preventDefault();
            setDrawer(false);
        }
    });
}
