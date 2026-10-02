package dev.andre.homecontrol.testsupport;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FullAppResetStepsTest {

    @Test
    void aFailingStepLeavesTheLaterStepsToRunAndIsReportedWithItsCause() {
        List<String> ran = new ArrayList<>();
        IllegalStateException broken = new IllegalStateException("No bean of type PinnedShortcuts");

        assertThatThrownBy(() -> FullAppReset.runEvery(List.of(
                new FullAppReset.Step("themes", () -> ran.add("themes")),
                new FullAppReset.Step("pins", () -> {
                    throw broken;
                }),
                new FullAppReset.Step("fakes", () -> ran.add("fakes")))))
                .isInstanceOf(AssertionError.class)
                .hasMessage("Resetting the shared application failed at: pins")
                .satisfies(failure -> assertThat(failure.getSuppressed()).singleElement()
                        .satisfies(step -> assertThat(step).hasMessage("pins").hasCause(broken)));
        assertThat(ran).containsExactly("themes", "fakes");
    }

    @Test
    void stepsThatAllSucceedRunInOrder() {
        List<String> ran = new ArrayList<>();

        FullAppReset.runEvery(List.of(
                new FullAppReset.Step("themes", () -> ran.add("themes")),
                new FullAppReset.Step("fakes", () -> ran.add("fakes"))));

        assertThat(ran).containsExactly("themes", "fakes");
    }
}
