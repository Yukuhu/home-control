package dev.andre.homecontrol.sources;

import dev.andre.homecontrol.core.content.ContentSourceException;
import dev.andre.homecontrol.sources.jellyfin.JellyfinException;
import dev.andre.homecontrol.sources.sports.calendar.CalendarFetchException;
import dev.andre.homecontrol.sources.sports.thesportsdb.TheSportsDbException;
import dev.andre.homecontrol.sources.tmdb.TmdbException;
import dev.andre.homecontrol.sources.youtube.YouTubeException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Each source's exception carries the shared kind rather than one of its own. */
class SourceExceptionsTest {

    @Test
    void everySourceExceptionCarriesTheSharedKind() {
        List<ContentSourceException> failures = List.of(
                new JellyfinException(ContentSourceException.Kind.NOT_FOUND, "Jellyfin"),
                new TmdbException(ContentSourceException.Kind.NOT_FOUND, "TMDB"),
                new TheSportsDbException(ContentSourceException.Kind.NOT_FOUND, "TheSportsDB"),
                new YouTubeException(ContentSourceException.Kind.NOT_FOUND, "YouTube"),
                new CalendarFetchException(ContentSourceException.Kind.NOT_FOUND, "Calendar"));

        assertThat(failures).extracting(ContentSourceException::kind).containsOnly(ContentSourceException.Kind.NOT_FOUND);
    }
}
