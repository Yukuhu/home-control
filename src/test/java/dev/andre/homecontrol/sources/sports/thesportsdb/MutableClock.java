package dev.andre.homecontrol.sources.sports.thesportsdb;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/** A {@link Clock} a test can advance by hand, e.g. to exercise cache TTLs. */
final class MutableClock extends Clock {

    private volatile Instant instant;
    private final ZoneId zone;

    MutableClock(Instant instant, ZoneId zone) {
        this.instant = instant;
        this.zone = zone;
    }

    static MutableClock at(Instant instant) {
        return new MutableClock(instant, ZoneId.of("UTC"));
    }

    void advance(Duration amount) {
        instant = instant.plus(amount);
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new MutableClock(instant, zone);
    }

    @Override
    public Instant instant() {
        return instant;
    }
}
