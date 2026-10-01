package dev.andre.homecontrol.adapters.tizen;

/** One entry of {@code ed.installedApp.get}. {@code appType} 2 = web app (DEEP_LINK), 4 = native (NATIVE_LAUNCH). */
record TizenApp(String appId, String name, int appType) {
}
