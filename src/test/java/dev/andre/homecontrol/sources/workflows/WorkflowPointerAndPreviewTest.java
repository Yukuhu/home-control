package dev.andre.homecontrol.sources.workflows;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static dev.andre.homecontrol.sources.workflows.WorkflowDraft.*;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

/** JSON pointer escapes at save time, and the preview of templates without a path or with bare query names. */
class WorkflowPointerAndPreviewTest {

    private static WorkflowDraft withPointer(String pointer) {
        WorkflowDraft draft = WorkflowFixtures.single(URI.create("https://api.example/catalog"));
        return new WorkflowDraft(draft.name(), draft.enabled(), draft.mode(), draft.kind(), draft.fetch(),
                draft.listing(), draft.tile(), List.of(new Variable("A", Scope.ROOT, pointer, false)),
                new Cast("https://media.example/play", "video/mp4"));
    }

    @Test
    void everyTildeInAPointerMustStartAnEscape() {
        for (String valid : List.of("/a~0b~1c", "/~0~1", "/~01", "/plain")) {
            assertThatCode(() -> WorkflowValidator.validate(withPointer(valid))).as(valid).doesNotThrowAnyException();
        }
        for (String invalid : List.of("/a~", "/a~2", "/~~0", "/~0~", "/a~0~b")) {
            assertThatThrownBy(() -> WorkflowValidator.validate(withPointer(invalid))).as(invalid)
                    .isInstanceOf(WorkflowException.class)
                    .hasMessage("Workflow: invalid mapping A pointer escape");
        }
    }

    @Test
    void aTemplateWithoutAPathShowsEverythingButQueryValues() {
        var template = new WorkflowTemplate("https://media.example?id={A}&token=saved", Set.of("A"));

        assertThat(template.preview(Map.of("A", new WorkflowJson.Value("news", false))))
                .isEqualTo("https://media.example?id=news&token=•••");
        assertThat(new WorkflowTemplate("https://media.example", Set.of()).preview(Map.of()))
                .isEqualTo("https://media.example");
    }

    @Test
    void aQueryNameWithoutAValueIsShown() {
        var template = new WorkflowTemplate("https://media.example/p?id={A}&flag", Set.of("A"));

        assertThat(template.preview(Map.of("A", new WorkflowJson.Value("news", false))))
                .isEqualTo("https://media.example/•••?id=news&flag");
    }
}
