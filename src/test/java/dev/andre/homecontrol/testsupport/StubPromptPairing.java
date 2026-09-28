package dev.andre.homecontrol.testsupport;

import dev.andre.homecontrol.core.PromptPairing;
import dev.andre.homecontrol.core.PromptPairingResult;

/** The web slice's prompt-pairing adapter: answers what a test sets and records the last request. */
public final class StubPromptPairing implements PromptPairing {

    private volatile PromptPairingResult next;
    private volatile String host;
    private volatile String name;

    public void willAnswer(PromptPairingResult result) {
        next = result;
    }

    public String host() {
        return host;
    }

    public String name() {
        return name;
    }

    void reset() {
        next = null;
        host = null;
        name = null;
    }

    @Override
    public String adapterId() {
        return "webos";
    }

    @Override
    public String displayName() {
        return "LG webOS TV";
    }

    @Override
    public String instructions() {
        return "Accept the request on the TV.";
    }

    @Override
    public PromptPairingResult pair(String host, String name) {
        this.host = host;
        this.name = name;
        return next;
    }
}
