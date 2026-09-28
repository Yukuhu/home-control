package dev.andre.homecontrol.testsupport;

import dev.andre.homecontrol.sources.sports.thesportsdb.FakeTheSportsDbServer;
import dev.andre.homecontrol.sources.tmdb.FakeTmdbServer;
import dev.andre.homecontrol.sources.youtube.FakeGoogleServer;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * The web-API fakes that {@link FullAppTest} points the shared application at: started once per test JVM, never
 * closed (the JVM's cached application contexts outlive them anyway), and reset after every test class by
 * {@link FullAppReset}. A test sets the routes it needs in {@code @BeforeAll} or in the test, never in a static
 * initializer, and never closes a shared fake.
 */
public final class SharedFakes {

    private static FakeTmdbServer tmdb;
    private static FakeGoogleServer google;
    private static FakeTheSportsDbServer theSportsDb;

    private SharedFakes() {
    }

    public static synchronized FakeTmdbServer tmdb() {
        if (tmdb == null) {
            tmdb = start(FakeTmdbServer::new);
        }
        return tmdb;
    }

    public static synchronized FakeGoogleServer google() {
        if (google == null) {
            google = start(FakeGoogleServer::new);
        }
        return google;
    }

    public static synchronized FakeTheSportsDbServer theSportsDb() {
        if (theSportsDb == null) {
            theSportsDb = start(FakeTheSportsDbServer::new);
        }
        return theSportsDb;
    }

    /** Resets every shared fake this JVM started. */
    static synchronized void resetAll() {
        if (tmdb != null) {
            tmdb.reset();
        }
        if (google != null) {
            google.reset();
        }
        if (theSportsDb != null) {
            theSportsDb.reset();
        }
    }

    private interface Starter<T> {
        T start() throws IOException;
    }

    private static <T> T start(Starter<T> starter) {
        try {
            return starter.start();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not start a shared fake", e);
        }
    }
}
