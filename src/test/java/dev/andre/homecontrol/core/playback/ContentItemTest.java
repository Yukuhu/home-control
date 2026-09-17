package dev.andre.homecontrol.core.playback;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContentItemTest {

    private static final PlayableRef.AppLink LINK =
            new PlayableRef.AppLink(URI.create("https://example.org/a"), "web");

    @Test
    void theSevenArgumentConstructorHasNoProgress() {
        ContentItem item = new ContentItem("id", "src", ContentKind.VIDEO, "Title", null, null, List.of(LINK));

        assertThat(item.progress()).isNull();
    }

    @ParameterizedTest
    @ValueSource(doubles = {-0.1, 1.1, Double.NaN})
    void progressIsAFractionBetweenZeroAndOne(double invalid) {
        assertThatThrownBy(() -> new ContentItem("id", "src", ContentKind.VIDEO, "Title", null, null, List.of(), invalid))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void zeroAndOneAreAccepted() {
        assertThat(new ContentItem("id", "src", ContentKind.VIDEO, "Title", null, null, List.of(), 0.0).progress()).isEqualTo(0.0);
        assertThat(new ContentItem("id", "src", ContentKind.VIDEO, "Title", null, null, List.of(), 1.0).progress()).isEqualTo(1.0);
    }

    @Test
    void playablesAreCopied() {
        List<PlayableRef> mutable = new ArrayList<>(List.of(LINK));

        ContentItem item = new ContentItem("id", "src", ContentKind.VIDEO, "Title", null, null, mutable);
        mutable.add(LINK);

        assertThat(item.playables()).containsExactly(LINK);
    }
}
