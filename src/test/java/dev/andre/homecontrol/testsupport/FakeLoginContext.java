package dev.andre.homecontrol.testsupport;

import dev.andre.homecontrol.security.LoginContext;

import java.util.Optional;
import java.util.function.BooleanSupplier;

/** A browser's login without HTTP: logged in or not, and whether {@code LoginService} started a session. */
public final class FakeLoginContext implements LoginContext {

    private boolean loggedIn;
    private String startedVersion;

    private FakeLoginContext(boolean loggedIn) {
        this.loggedIn = loggedIn;
    }

    public static FakeLoginContext loggedInBrowser() {
        return new FakeLoginContext(true);
    }

    public static FakeLoginContext loggedOutBrowser() {
        return new FakeLoginContext(false);
    }

    @Override
    public boolean loggedIn() {
        return loggedIn;
    }

    @Override
    public BooleanSupplier whileLoggedIn() {
        return () -> loggedIn;
    }

    @Override
    public String sessionKey() {
        return "session-1";
    }

    @Override
    public void startSession(String version) {
        startedVersion = version;
        loggedIn = true;
    }

    @Override
    public void endSession() {
        loggedIn = false;
    }

    @Override
    public Optional<String> rememberedVersion() {
        return Optional.empty();
    }

    @Override
    public void resumeSession(String version) {
        startSession(version);
    }

    public boolean sessionStarted() {
        return startedVersion != null;
    }

    public String startedVersion() {
        return startedVersion;
    }
}
