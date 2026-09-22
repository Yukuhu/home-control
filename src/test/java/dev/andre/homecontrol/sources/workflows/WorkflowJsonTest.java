package dev.andre.homecontrol.sources.workflows;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static dev.andre.homecontrol.sources.workflows.WorkflowDraft.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowJsonTest {
    @Test void selectsEntriesAndMapsRootAndEntryValues() {
        var root = parse(WorkflowFixtures.CHANNELS);
        var draft = channels();
        var entries = WorkflowJson.entries(draft, root);
        assertThat(entries).extracting(WorkflowJson.Entry::title).containsExactly("News", "Music");
        assertThat(entries).extracting(WorkflowJson.Entry::key).doesNotHaveDuplicates();
        var values = WorkflowJson.values(draft.variables(), root, entries.getFirst().node());
        assertThat(values.get("A")).isEqualTo(new WorkflowJson.Value("news", false));
        assertThat(values.get("C")).isEqualTo(new WorkflowJson.Value("example-token", true));
        assertThat(values.get("D")).isEqualTo(new WorkflowJson.Value("hd", false));
        assertThat(values.get("C").toString()).doesNotContain("example-token");
    }

    @Test void keysSurviveReorderTokenAndTitleChanges() {
        var first = WorkflowJson.entries(channels(), parse(WorkflowFixtures.CHANNELS));
        var changed = WorkflowJson.entries(channels(), parse(WorkflowFixtures.REORDERED_CHANNELS));
        assertThat(changed.get(1).key()).isEqualTo(first.getFirst().key());
        assertThat(changed.getFirst().key()).isEqualTo(first.get(1).key());
        assertThat(WorkflowJson.stableKey(parse("\"1\"")))
                .isNotEqualTo(WorkflowJson.stableKey(parse("1")));
        assertThat(WorkflowJson.stableKey(parse("\"1\"")))
                .isEqualTo("1b25f38c1aa8553b03240e793e2562c520496a0469d683b8fcf52ce8206a7e81");
        assertThat(WorkflowJson.stableKey(parse("1")))
                .isEqualTo("c96504c8e4c90470266843fe8563dad9b1b9422c63d6c1bf37fc3cbb4985a317");
        assertThat(WorkflowJson.stableKey(parse("1"))).isEqualTo(WorkflowJson.stableKey(parse("1.0")));
        assertThat(first.getFirst().key()).matches("[0-9a-f]{64}");
    }

    @Test void pointerEscapesArrayIndexAndScalarsWork() {
        var root = parse("{\"a/b\":{\"~token\":true},\"streams\":[{\"quality\":12}]}");
        var vars = java.util.List.of(new Variable("A", Scope.ROOT, "/a~1b/~0token", false),
                new Variable("B", Scope.ROOT, "/streams/0/quality", false));
        assertThat(WorkflowJson.values(vars, root, null))
                .containsEntry("A", new WorkflowJson.Value("true", false))
                .containsEntry("B", new WorkflowJson.Value("12", false));
    }

    @Test void missingNullAndContainerMappingsFail() {
        var root = parse("{\"empty\":null,\"object\":{},\"array\":[]}");
        for (String pointer : new String[]{"/missing", "/empty", "/object", "/array"}) {
            assertThatThrownBy(() -> WorkflowJson.values(
                    java.util.List.of(new Variable("A", Scope.ROOT, pointer, false)), root, null))
                    .isInstanceOf(WorkflowException.class).hasMessageContaining("A");
        }
    }

    @Test void rejectsDuplicateIdsAndOversizedCatalog() {
        assertThatThrownBy(() -> WorkflowJson.entries(channels(), parse(
                "{\"channels\":[{\"id\":1,\"title\":\"A\"},{\"id\":1.0,\"title\":\"B\"}]}")))
                .isInstanceOf(WorkflowException.class).hasMessageContaining("ID");
        var items = new StringBuilder("{\"channels\":[");
        for (int i = 0; i < 201; i++) {
            if (i > 0) items.append(',');
            items.append("{\"id\":").append(i).append(",\"title\":\"T\"}");
        }
        items.append("]}");
        assertThatThrownBy(() -> WorkflowJson.entries(channels(), parse(items.toString())))
                .isInstanceOf(WorkflowException.class);
    }

    @Test void invalidRequiredTitlesAndArtworkAreHandled() {
        assertThatThrownBy(() -> WorkflowJson.entries(channels(), parse(
                "{\"channels\":[{\"id\":\"a\",\"title\":\" \"}]}")))
                .isInstanceOf(WorkflowException.class).hasMessageContaining("title");
        var draft = channels();
        var withArt = new WorkflowDraft(draft.name(), true, draft.mode(), draft.kind(), draft.fetch(),
                new Listing("/channels", "/id", "/title", null, "/art"), null, draft.variables(), draft.cast());
        assertThat(WorkflowJson.entries(withArt, parse(
                "{\"channels\":[{\"id\":\"a\",\"title\":\"A\",\"art\":\"https://example.com/a?token=x\"}]}"))
                .getFirst().artwork()).isNull();
        assertThat(WorkflowJson.entries(withArt, parse(
                "{\"channels\":[{\"id\":\"a\",\"title\":\"A\",\"art\":\"https://127.0.0.1/a\"}]}"))
                .getFirst().artwork()).isNull();
        assertThat(WorkflowJson.entries(withArt, parse(
                "{\"channels\":[{\"id\":\"a\",\"title\":\"A\",\"art\":\"https://8.8.8.8/a\"}]}"))
                .getFirst().artwork()).isEqualTo(java.net.URI.create("https://8.8.8.8/a"));
    }

    @Test void generatedArtworkOmitsRootDotLocalNamesAndDocumentationIpv6() {
        var draft = channels();
        var withArt = new WorkflowDraft(draft.name(), true, draft.mode(), draft.kind(), draft.fetch(),
                new Listing("/channels", "/id", "/title", null, "/art"), null, draft.variables(), draft.cast());
        for (String host : new String[]{"feed.local.", "localhost.", "[2001:db8::1]"}) {
            var response = parse("{\"channels\":[{\"id\":\"a\",\"title\":\"A\",\"art\":\"https://"
                    + host + "/cover.png\"}]}");
            assertThat(WorkflowJson.entries(withArt, response).getFirst().artwork()).isNull();
        }
        var publicResponse = parse("{\"channels\":[{\"id\":\"a\",\"title\":\"A\","
                + "\"art\":\"https://[2606:4700::1111]/cover.png\"}]}");
        assertThat(WorkflowJson.entries(withArt, publicResponse).getFirst().artwork())
                .isEqualTo(java.net.URI.create("https://[2606:4700::1111]/cover.png"));
    }

    @Test void singleModeUsesSavedDisplayAndKey() {
        var draft = WorkflowFixtures.single(java.net.URI.create("https://api.example/catalog"));
        var entries = WorkflowJson.entries(draft, parse("{}"));
        assertThat(entries).hasSize(1);
        assertThat(entries.getFirst().key()).isEqualTo("single");
        assertThat(entries.getFirst().title()).isEqualTo("News");
    }

    @Test void rejectsExcessiveDepthAndTrailingDocuments() {
        String deep = "[".repeat(65) + "0" + "]".repeat(65);
        assertThatThrownBy(() -> parse(deep)).isInstanceOf(WorkflowException.class);
        assertThatThrownBy(() -> parse("{} {}")) .isInstanceOf(WorkflowException.class);
    }

    private static tools.jackson.databind.JsonNode parse(String json) {
        return WorkflowJson.parse(json.getBytes(StandardCharsets.UTF_8));
    }

    private static WorkflowDraft channels() {
        var base = WorkflowFixtures.generated();
        return new WorkflowDraft(base.name(), true, base.mode(), base.kind(), base.fetch(),
                new Listing("/channels", "/id", "/title", null, null), null,
                java.util.List.of(new Variable("A", Scope.ENTRY, "/id", false),
                        new Variable("C", Scope.ROOT, "/auth/token", true),
                        new Variable("D", Scope.ENTRY, "/quality", false)), base.cast());
    }
}
