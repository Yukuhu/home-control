package dev.andre.homecontrol.sources.http;

import dev.andre.homecontrol.core.content.ContentSourceException.Kind;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** A failure's default sentence names the source and the host, never more. */
class OutboundFailureTest {

    @Test
    void describesEachFailureForAPerson() {
        assertThat(new OutboundFailure(Kind.UNREACHABLE, "cal.example", OutboundFailure.TIMED_OUT, 0).describe("the calendar"))
                .isEqualTo("Could not reach the calendar at cal.example (request timed out)");
        assertThat(new OutboundFailure(Kind.BLOCKED, "cal.example", OutboundFailure.ADDRESS_NOT_ALLOWED, 0).describe("the calendar"))
                .isEqualTo("Home Control does not connect to cal.example (address not allowed)");
        assertThat(new OutboundFailure(Kind.TOO_LARGE, "cal.example", OutboundFailure.TOO_LARGE, 5_242_880).describe("the calendar"))
                .isEqualTo("The calendar at cal.example sent more than 5 MB");
        assertThat(new OutboundFailure(Kind.TOO_LARGE, "cal.example", OutboundFailure.TOO_LARGE, 64).describe("the calendar"))
                .isEqualTo("The calendar at cal.example sent more than 64 bytes");
        assertThat(new OutboundFailure(Kind.BAD_RESPONSE, "cal.example", OutboundFailure.COMPRESSED, 0).describe("the calendar"))
                .isEqualTo("The calendar at cal.example sent a response Home Control cannot read (response compression is not supported)");
        assertThat(new OutboundFailure(Kind.RATE_LIMITED, "cal.example", OutboundFailure.BUSY, 0).describe("the calendar"))
                .isEqualTo("Home Control is busy talking to the calendar; try again in a moment");
    }
}
