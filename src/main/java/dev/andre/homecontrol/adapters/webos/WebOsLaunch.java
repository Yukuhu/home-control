package dev.andre.homecontrol.adapters.webos;

import tools.jackson.databind.node.ObjectNode;

/** One SSAP request that opens something on the TV. */
record WebOsLaunch(String ssapUri, ObjectNode payload) {
}
