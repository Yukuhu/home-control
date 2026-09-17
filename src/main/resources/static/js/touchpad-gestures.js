// Pure gesture mapping (vNext §5.4). Changing a constant requires changing its boundary tests.
export const TAP_SLOP_PX = 12;
export const SWIPE_THRESHOLD_PX = 24;
export const STEP_PX = 56;
export const MAX_STEPS = 4;
export const HOLD_MS = 450;

export function stepsFor(distance) {
    if (distance < SWIPE_THRESHOLD_PX) return 0;
    return Math.min(MAX_STEPS, 1 + Math.floor((distance - SWIPE_THRESHOLD_PX) / STEP_PX));
}

export function withinSlop(dx, dy) {
    return Math.abs(dx) <= TAP_SLOP_PX && Math.abs(dy) <= TAP_SLOP_PX;
}

export function classify(dx, dy) {
    if (withinSlop(dx, dy)) return { kind: "tap" };
    const horizontal = Math.abs(dx) >= Math.abs(dy);
    const distance = horizontal ? Math.abs(dx) : Math.abs(dy);
    const repeat = stepsFor(distance);
    if (repeat === 0) return { kind: "none" };
    const key = horizontal ? (dx > 0 ? "DPAD_RIGHT" : "DPAD_LEFT") : (dy > 0 ? "DPAD_DOWN" : "DPAD_UP");
    return { kind: "swipe", key, repeat };
}
