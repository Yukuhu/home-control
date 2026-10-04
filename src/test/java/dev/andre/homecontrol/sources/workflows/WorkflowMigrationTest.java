package dev.andre.homecontrol.sources.workflows;

import dev.andre.homecontrol.core.playback.ContentKind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

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

    private static WorkflowMigration.V1Draft v1Draft(WorkflowMigration.V1Fetch fetch, List<WorkflowMigration.V1Variable> variables) {
        return new WorkflowMigration.V1Draft("Radio", true, Mode.SINGLE, ContentKind.TRACK,
                fetch, null, new Tile("Radio", null, null), variables,
                new Cast("https://media.example/radio.mp3", "audio/mpeg"));
    }

    @Test void anIncompleteV1DefinitionIsRefused() {
        var fetch = new WorkflowMigration.V1Fetch("https://api.example/radio", List.of());
        List<WorkflowMigration.V1Definition> incomplete = List.of(
                new WorkflowMigration.V1Definition(1, "w-0123456789ab", 1, null),
                new WorkflowMigration.V1Definition(1, "w-0123456789ab", 1, v1Draft(null, List.of())),
                new WorkflowMigration.V1Definition(1, "w-0123456789ab", 1, v1Draft(fetch, null)),
                new WorkflowMigration.V1Definition(1, "w-0123456789ab", 1,
                        v1Draft(new WorkflowMigration.V1Fetch("https://api.example/radio", null), List.of())));

        assertThatThrownBy(() -> WorkflowMigration.toV2(null)).hasMessage("Workflow: definition could not be parsed");
        for (var definition : incomplete) {
            assertThatThrownBy(() -> WorkflowMigration.toV2(definition))
                    .isInstanceOf(WorkflowException.class).hasMessage("Workflow: definition could not be parsed");
        }
    }

    @Test void aHeaderWithoutAValueAndAnEmptyVariableSlotSurviveTheMigrationAsSuch() {
        var variables = new ArrayList<WorkflowMigration.V1Variable>();
        variables.add(null);
        variables.add(new WorkflowMigration.V1Variable("C", "ROOT", "/token", true));
        var headers = new ArrayList<Header>();
        headers.add(new Header("X-Empty", null));

        var migrated = WorkflowMigration.toV2(new WorkflowMigration.V1Definition(1, "w-0123456789ab", 4,
                v1Draft(new WorkflowMigration.V1Fetch("https://api.example/radio", headers), variables)));

        var main = migrated.draft().calls().getFirst();
        assertThat(main.headers()).containsExactly(new Header("X-Empty", null));
        assertThat(main.variables()).containsExactly(new Variable("C", "/token", true));
        assertThat(migrated.draft().listing()).isNull();
    }

    @Test void v1PartsPrintNoSettings() {
        var fetch = new WorkflowMigration.V1Fetch("https://api.example/radio?key=secret", List.of());
        var variable = new WorkflowMigration.V1Variable("secret", "ROOT", "/secret", true);
        var listing = new WorkflowMigration.V1Listing("/items", "/id", "/title", null, null);

        assertThat(List.of(new WorkflowMigration.V1Definition(1, "w-0123456789ab", 1, v1Draft(fetch, List.of(variable))),
                v1Draft(fetch, List.of(variable)), fetch, listing, variable))
                .extracting(Object::toString)
                .containsExactly("V1Definition", "V1Draft", "V1Fetch", "V1Listing", "V1Variable");
    }
}
