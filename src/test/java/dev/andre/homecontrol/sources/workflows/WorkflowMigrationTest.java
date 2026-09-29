package dev.andre.homecontrol.sources.workflows;

import org.junit.jupiter.api.Test;

import static dev.andre.homecontrol.sources.workflows.WorkflowDraft.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowMigrationTest {
    private final WorkflowCodec codec = new WorkflowCodec();

    private static final String GENERATED_V1 = """
            {"schemaVersion":1,"id":"w-0123456789ab","revision":7,"draft":{
              "name":"News","enabled":true,"mode":"GENERATED","kind":"VIDEO",
              "fetch":{"url":"https://api.example/catalog","headers":[{"name":"X-Filter","value":"{\\"a\\":1}"}]},
              "listing":{"arrayPointer":"/items","idPointer":"/id","titlePointer":"/title",
                         "subtitlePointer":"/sub","artworkPointer":null},
              "tile":null,
              "variables":[{"name":"A","scope":"ENTRY","pointer":"/id","sensitive":false},
                           {"name":"C","scope":"ROOT","pointer":"/token","sensitive":true}],
              "cast":{"template":"https://media.example/play?id={A}&token={C}","mimeType":"video/mp4"}}}
            """;

    @Test void generatedV1BecomesOneSharedCallThatIsTheEntrySource() {
        var definition = codec.decode(GENERATED_V1);
        assertThat(definition.schemaVersion()).isEqualTo(2);
        assertThat(definition.id()).isEqualTo("w-0123456789ab");
        assertThat(definition.revision()).isEqualTo(7);
        var draft = definition.draft();
        assertThat(draft.calls()).singleElement().satisfies(call -> {
            assertThat(call.name()).isEqualTo("main");
            assertThat(call.scope()).isEqualTo(CallScope.SHARED);
            assertThat(call.url()).isEqualTo("https://api.example/catalog");
            assertThat(call.variables()).containsExactly(new Variable("C", "/token", true));
        });
        assertThat(draft.listing()).isEqualTo(new Listing("main", "/items", "/id", "/title",
                new Field("/sub", null), null, java.util.List.of(new Variable("A", "/id", false))));
    }

    @Test void migrationKeepsBracesInV1HeadersLiteral() {
        var header = codec.decode(GENERATED_V1).draft().calls().getFirst().headers().getFirst();
        assertThat(header.value()).isEqualTo("{{\"a\":1}}");
        assertThat(new WorkflowHeaderTemplate(header.value()).expand(java.util.Map.of())).isEqualTo("{\"a\":1}");
    }

    @Test void encodingWritesVersionTwoAndReadsItBack() {
        var migrated = codec.decode(GENERATED_V1);
        String encoded = codec.encode(migrated);
        assertThat(encoded).contains("\"schemaVersion\":2").doesNotContain("\"fetch\"");
        assertThat(codec.decode(encoded)).isEqualTo(migrated);
    }

    @Test void singleV1WithoutMappingsStaysReadable() {
        String v1 = """
                {"schemaVersion":1,"id":"w-0123456789ab","revision":1,"draft":{
                  "name":"Radio","enabled":true,"mode":"SINGLE","kind":"TRACK",
                  "fetch":{"url":"https://api.example/ping","headers":[]},"listing":null,
                  "tile":{"title":"Radio","subtitle":null,"artwork":null},"variables":[],
                  "cast":{"template":"https://media.example/radio.mp3","mimeType":"audio/mpeg"}}}
                """;
        var draft = codec.decode(v1).draft();
        assertThat(draft.calls()).singleElement().extracting(Call::name).isEqualTo("main");
        assertThatThrownBy(() -> WorkflowValidator.validate(draft))
                .hasMessage("Workflow: call main: nothing uses this call");
        // The way out: remove the call. A single tile with a fixed media URL needs none.
        var withoutCall = new WorkflowDraft(draft.name(), draft.enabled(), draft.mode(), draft.kind(), java.util.List.of(),
                draft.listing(), draft.tile(), draft.cast());
        WorkflowValidator.validate(withoutCall);
    }

    @Test void singleV1WithBothVariableScopesBecomesOneMainCallWithoutListing() {
        String v1 = """
                {"schemaVersion":1,"id":"w-0123456789ab","revision":2,"draft":{
                  "name":"Radio","enabled":true,"mode":"SINGLE","kind":"VIDEO",
                  "fetch":{"url":"https://api.example/ping","headers":[]},"listing":null,
                  "tile":{"title":"Radio","subtitle":"Live","artwork":null},
                  "variables":[{"name":"A","scope":"ROOT","pointer":"/id","sensitive":false},
                               {"name":"C","scope":"ROOT","pointer":"/token","sensitive":true}],
                  "cast":{"template":"https://media.example/play?id={A}&token={C}","mimeType":"video/mp4"}}}
                """;
        var migrated = codec.decode(v1);
        var draft = migrated.draft();
        assertThat(draft.calls()).singleElement().satisfies(call -> {
            assertThat(call.name()).isEqualTo("main");
            assertThat(call.variables()).containsExactly(new Variable("A", "/id", false), new Variable("C", "/token", true));
        });
        assertThat(draft.listing()).isNull();
        assertThat(draft.tile()).isEqualTo(new Tile("Radio", "Live", null));
        assertThat(codec.decode(codec.encode(migrated))).isEqualTo(migrated);
    }

    @Test void aNullHeaderEntryInV1IsRefusedAsUnparsable() {
        String bad = GENERATED_V1.replace("\"headers\":[{\"name\":\"X-Filter\",\"value\":\"{\\\"a\\\":1}\"}]", "\"headers\":[null]");
        assertThat(bad).contains("[null]");
        assertThatThrownBy(() -> codec.decode(bad)).hasMessage("Workflow: definition could not be parsed");
    }

    @Test void unknownVersionsAreRefused() {
        assertThatThrownBy(() -> codec.decode(GENERATED_V1.replace("\"schemaVersion\":1", "\"schemaVersion\":3")))
                .hasMessage("Workflow: unsupported definition schema");
    }

    @Test void malformedV1DefinitionsAreRefusedWithoutParserText() {
        String noFetch = GENERATED_V1.replace("\"fetch\":{\"url\":\"https://api.example/catalog\",\"headers\":[{\"name\":\"X-Filter\","
                + "\"value\":\"{\\\"a\\\":1}\"}]},", "");
        assertThatThrownBy(() -> codec.decode(noFetch)).hasMessage("Workflow: definition could not be parsed");
        assertThatThrownBy(() -> codec.decode(GENERATED_V1.replace("\"mode\":\"GENERATED\"", "\"mode\":\"secret-marker\"")))
                .hasMessage("Workflow: definition could not be parsed");
    }

    @Test void v1VariablesWithAnUnknownScopeAreDropped() {
        var draft = codec.decode(GENERATED_V1.replace("\"scope\":\"ROOT\"", "\"scope\":\"OTHER\"")
                .replace("{C}", "{A}").replace("&token={A}", "")).draft();
        assertThat(draft.calls().getFirst().variables()).isEmpty();
    }
}
