package dev.andre.homecontrol.sources.workflows;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowTemplateTest {
    @Test void valuesCannotIntroduceQueryParametersOrPathSegments() {
        var template = new WorkflowTemplate("https://media.example/{A}?token={C}", Set.of("A", "C"));
        var values = Map.of("A", new WorkflowJson.Value("a/b & ü", false),
                "C", new WorkflowJson.Value("x&admin=true%", true));
        assertThat(template.expand(values).toASCIIString()).isEqualTo(
                "https://media.example/a%2Fb%20%26%20%C3%BC?token=x%26admin%3Dtrue%25");
        assertThat(template.preview(values)).doesNotContain("admin", "true%").contains("•••");
    }

    @Test void rawPreEncodedValuesAreEncodedAgainAndRepeatedVariablesExpand() {
        var template = new WorkflowTemplate("https://media.example/{A}/{A}?id={A}", Set.of("A"));
        var values = Map.of("A", new WorkflowJson.Value("x%2Fy", false));
        assertThat(template.expand(values).toASCIIString()).isEqualTo(
                "https://media.example/x%252Fy/x%252Fy?id=x%252Fy");
    }

    @Test void rejectsDotSegmentsAfterExpansion() {
        var template = new WorkflowTemplate("https://media.example/{A}/play", Set.of("A"));
        for (String value : new String[]{".", ".."}) {
            var preparedArg31_0 = Map.of("A", new WorkflowJson.Value(value, false));
            assertThatThrownBy(() -> template.expand(preparedArg31_0))
                    .isInstanceOf(WorkflowException.class);
        }
    }

    @Test void unresolvedVariablesAndOversizedExpansionFail() {
        var template = new WorkflowTemplate("https://media.example/{A}", Set.of("A"));
        assertThatThrownBy(() -> template.expand(Map.of())).isInstanceOf(WorkflowException.class);
        var preparedArg39_0 = Map.of("A", new WorkflowJson.Value("x".repeat(8200), false));
        assertThatThrownBy(() -> template.expand(preparedArg39_0))
                .isInstanceOf(WorkflowException.class);
    }

    @Test void previewMasksLiteralPathAndQueryValuesWhileKeepingPublicVariables() {
        var template = new WorkflowTemplate("https://media.example/play/file-{A}.mp4?token=saved&id={A}&secret={C}", Set.of("A", "C"));
        var values = Map.of("A", new WorkflowJson.Value("news", false),
                "C", new WorkflowJson.Value("private", true));
        assertThat(template.preview(values)).isEqualTo(
                "https://media.example/•••/•••news•••?token=•••&id=news&secret=•••");
    }

    @Test void previewMasksExtraEqualsWithinLiteralQueryValue() {
        var template = new WorkflowTemplate("https://media.example/play?token=abc=secret", Set.of());
        assertThat(template.preview(Map.of())).isEqualTo("https://media.example/•••?token=•••");
    }

    @Test void invalidPlaceholdersAndQueryKeysFailAtConstruction() {
        for (String bad : new String[]{"https://media.example/?{A}&id=1", "https://media.example/?{A}=x",
                "https://{A}/play", "https://media.example/{A", "https://media.example/a}"}) {
            var preparedArg59_1 = Set.of("A");
            assertThatThrownBy(() -> new WorkflowTemplate(bad, preparedArg59_1))
                    .isInstanceOf(WorkflowException.class);
        }
        var preparedArg62_1 = Set.of("A");
        assertThatThrownBy(() -> new WorkflowTemplate("https://media.example/?id={B}", preparedArg62_1))
                .isInstanceOf(WorkflowException.class);
    }
}
