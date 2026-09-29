package dev.andre.homecontrol.sources.workflows;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowHeaderTemplateTest {
    private static Map<String, WorkflowJson.Value> token(String value) {
        return Map.of("token", new WorkflowJson.Value(value, true));
    }

    @Test void substitutesVariablesAndKeepsDoubledBracesLiteral() {
        var template = new WorkflowHeaderTemplate("Bearer {token} {{json}}");
        assertThat(template.references()).containsExactly("token");
        assertThat(template.expand(token("abc"))).isEqualTo("Bearer abc {json}");
    }

    @Test void literalEscapingSendsAnyTextUnchanged() {
        String raw = "{\"a\":1} }{ {{";
        assertThat(new WorkflowHeaderTemplate(WorkflowHeaderTemplate.literal(raw)).expand(Map.of())).isEqualTo(raw);
    }

    @Test void rejectsMalformedPlaceholders() {
        for (String bad : List.of("{", "}", "{1a}", "{a", "{a b}", "x}y", "{}")) {
            assertThatThrownBy(() -> new WorkflowHeaderTemplate(bad)).as(bad)
                    .isInstanceOf(WorkflowException.class).hasMessage("Workflow: invalid header placeholder");
        }
    }

    @Test void rejectsSubstitutedLineBreaksControlCharactersAndLongValues() {
        var template = new WorkflowHeaderTemplate("Bearer {token}");
        for (String value : List.of("a\r\nX-Injected: 1", "a\u0000b", "a\u007fb", "x".repeat(1025))) {
            assertThatThrownBy(() -> template.expand(token(value)))
                    .isInstanceOf(WorkflowException.class)
                    .hasMessage("Fetch JSON: header value from token is not allowed");
        }
    }

    @Test void acceptsTabsAndTheLongestValue() {
        var template = new WorkflowHeaderTemplate("{token}");
        assertThat(template.expand(token("a\tb"))).isEqualTo("a\tb");
        assertThat(template.expand(token("x".repeat(1024)))).hasSize(1024);
    }

    @Test void aMissingValueIsAnError() {
        assertThatThrownBy(() -> new WorkflowHeaderTemplate("{token}").expand(Map.of()))
                .hasMessage("Fetch JSON: unresolved header placeholder");
    }
}
