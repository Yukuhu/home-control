package dev.andre.homecontrol.sources.workflows;

import dev.andre.homecontrol.core.playback.ContentKind;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import static dev.andre.homecontrol.sources.workflows.WorkflowDraft.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowDefinitionTest {
    private final WorkflowCodec codec = new WorkflowCodec();

    @Test void roundTripsWithoutPrintingCredentials() {
        var definition = new WorkflowDefinition(1, "w-0123456789ab", 1,
                WorkflowFixtures.single(URI.create("https://api.example/catalog?key=saved-secret")));
        assertThat(codec.decode(codec.encode(definition))).isEqualTo(definition);
        assertThat(definition.toString()).doesNotContain("saved-secret", "api.example", "token=");
        assertThat(definition.draft().fetch().toString()).doesNotContain("saved-secret");
        assertThat(definition.draft().toString()).doesNotContain("saved-secret", "api.example", "token=");
        assertThat(definition.draft().fetch().headers().getFirst().toString()).doesNotContain("saved-secret");
        assertThat(definition.draft().cast().toString()).doesNotContain("token=");
    }

    @Test void rejectsMissingModeSpecificFields() {
        var single = WorkflowFixtures.single(URI.create("https://api.example/catalog"));
        invalid(with(single, Mode.SINGLE, single.fetch(), new Listing("/items", "/id", "/title", null, null), null,
                single.variables(), single.cast()));
        invalid(with(single, Mode.GENERATED, single.fetch(), null, null, single.variables(), single.cast()));
        invalid(with(single, Mode.GENERATED, single.fetch(), new Listing("/items", null, "/title", null, null), null,
                single.variables(), single.cast()));
        WorkflowValidator.validate(WorkflowFixtures.generated());
    }

    @Test void rejectsInvalidVariableNamesDuplicatesAndEntryScopeInSingleMode() {
        var draft = WorkflowFixtures.single(URI.create("https://api.example/catalog"));
        for (String name : List.of("", "1A", "A-B", "A".repeat(33))) {
            invalid(withVariables(draft, List.of(new Variable(name, Scope.ROOT, "/id", false))));
        }
        invalid(withVariables(draft, List.of(new Variable("A", Scope.ROOT, "/id", false),
                new Variable("A", Scope.ROOT, "/token", true))));
        invalid(withVariables(draft, List.of(new Variable("A", Scope.ENTRY, "/id", false))));
    }

    @Test void validatesNameTitleSubtitleAndMappingCountBoundaries() {
        var draft = WorkflowFixtures.single(URI.create("https://api.example/catalog"));
        invalid(new WorkflowDraft(" ", true, draft.mode(), draft.kind(), draft.fetch(), null, draft.tile(),
                draft.variables(), draft.cast()));
        invalid(withTile(draft, new Tile("", null, null)));
        invalid(withTile(draft, new Tile("T".repeat(121), null, null)));
        invalid(withTile(draft, new Tile("Title", "S".repeat(241), null)));
        WorkflowValidator.validate(withTile(draft, new Tile("T".repeat(120), "S".repeat(240), null)));
        WorkflowValidator.validate(new WorkflowDraft("N".repeat(120), true, draft.mode(), draft.kind(),
                draft.fetch(), null, draft.tile(), draft.variables(), draft.cast()));
        invalid(new WorkflowDraft("N".repeat(121), true, draft.mode(), draft.kind(),
                draft.fetch(), null, draft.tile(), draft.variables(), draft.cast()));
        List<Variable> many = new ArrayList<>();
        for (int i = 0; i < 32; i++) many.add(new Variable("V" + i, Scope.ROOT, "/id", false));
        WorkflowValidator.validate(withVariables(draft, many));
        many.add(new Variable("V32", Scope.ROOT, "/id", false));
        invalid(withVariables(draft, many));
    }

    @Test void validatesPointersAndHeaderCountBoundaries() {
        var draft = WorkflowFixtures.single(URI.create("https://api.example/catalog"));
        for (String pointer : List.of("id", "/bad~", "/bad~2")) {
            invalid(withVariables(draft, List.of(new Variable("A", Scope.ROOT, pointer, false))));
        }
        WorkflowValidator.validate(withVariables(draft, List.of(new Variable("A", Scope.ROOT, "", false))));
        WorkflowValidator.validate(withVariables(draft, List.of(new Variable("A", Scope.ROOT, "/" + "x".repeat(511), false))));
        invalid(withVariables(draft, List.of(new Variable("A", Scope.ROOT, "/" + "x".repeat(512), false))));
        var generated = WorkflowFixtures.generated();
        invalid(new WorkflowDraft(generated.name(), true, generated.mode(), generated.kind(), generated.fetch(),
                new Listing("/bad~2", "/id", "/title", null, null), null, generated.variables(), generated.cast()));
        List<Header> headers = new ArrayList<>();
        for (int i = 0; i < 16; i++) headers.add(new Header("X-Header-" + i, "value"));
        WorkflowValidator.validate(withFetch(draft, new Fetch(draft.fetch().url(), headers)));
        headers.add(new Header("X-Header-16", "value"));
        invalid(withFetch(draft, new Fetch(draft.fetch().url(), headers)));
    }

    @Test void rejectsDangerousHeadersAndControlCharacters() {
        var draft = WorkflowFixtures.single(URI.create("https://api.example/catalog"));
        for (String name : List.of("Host", "Cookie", "Connection", "Content-Length", "Transfer-Encoding",
                "TE", "Trailer", "Upgrade", "Keep-Alive", "Expect", "Accept-Encoding",
                "Proxy", "Proxy-Authorization", "Proxy-Anything", "Bad Header", "Bad:Header")) {
            invalid(withFetch(draft, new Fetch(draft.fetch().url(), List.of(new Header(name, "value")))));
        }
        invalid(withFetch(draft, new Fetch(draft.fetch().url(), List.of(new Header("X-Test", "one\r\ntwo")))));
        invalid(withFetch(draft, new Fetch(draft.fetch().url(), List.of(
                new Header("X-Test", "one"), new Header("x-test", "two")))));
    }

    @Test void rejectsUnsafeFetchUrlsAndMediaTemplates() {
        var draft = WorkflowFixtures.single(URI.create("https://api.example/catalog"));
        for (String url : List.of("ftp://api.example/catalog", "https://u:p@api.example/catalog",
                "https://api.example/catalog#fragment", "https://api.example/a/../b",
                "https://api.example/a/%2e%2E/b", "https://api.example/" + "x".repeat(8192))) {
            invalid(withFetch(draft, new Fetch(url, List.of())));
        }
        for (String template : List.of("https://{A}/play", "{A}://media.example/play",
                "https://media.example/play?{A}=value", "https://media.example/play?id={MISSING}",
                "https://media.example/play?id={A", "https://media.example/play?id=A}",
                "https://media.example/play#fragment", "https://media.example/a/%2e%2e/b",
                "https://media.example/" + "x".repeat(8192))) {
            invalid(withCast(draft, new Cast(template, "video/mp4")));
        }
        WorkflowValidator.validate(withCast(draft, new Cast("https://media.example/file-{A}.mp4?token={C}", "video/mp4")));
    }

    @Test void savedSingleArtworkRejectsRootDotLocalNamesAndDocumentationIpv6() {
        var draft = WorkflowFixtures.single(URI.create("https://api.example/catalog"));
        for (String host : List.of("feed.local.", "localhost.", "[2001:db8::1]", "[3ffe::1]")) {
            invalid(withTile(draft, new Tile("News", null, "https://" + host + "/cover.png")));
        }
        WorkflowValidator.validate(withTile(draft,
                new Tile("News", null, "https://[2606:4700::1111]/cover.png")));
    }

    @Test void acceptsQueryValuePlaceholderButRejectsKeyBeforeAnotherParameter() {
        var draft = WorkflowFixtures.single(URI.create("https://api.example/catalog"));
        invalid(withCast(draft, new Cast("https://media.example/play?{A}&id=1", "video/mp4")));
        WorkflowValidator.validate(withCast(draft, new Cast("https://media.example/play?id={A}", "video/mp4")));
    }

    @Test void rawUnvalidatedDtoNamesNeverPrintCredentials() {
        assertThat(new Header("secret-header-name", "saved-secret").toString())
                .doesNotContain("secret-header-name", "saved-secret");
        assertThat(new Variable("saved-secret", Scope.ROOT, "/secret", true).toString())
                .doesNotContain("saved-secret", "/secret");
    }

    @Test void validatesMimeAndKind() {
        var draft = WorkflowFixtures.single(URI.create("https://api.example/catalog"));
        for (String mime : List.of("", "video/mp4; charset=utf-8", "video/☃", "video/", "a".repeat(50) + "/" + "b".repeat(50))) {
            invalid(withCast(draft, new Cast(draft.cast().template(), mime)));
        }
        WorkflowValidator.validate(withCast(draft, new Cast(draft.cast().template(), "a".repeat(49) + "/" + "b".repeat(50))));
        invalid(new WorkflowDraft(draft.name(), true, draft.mode(), ContentKind.MOVIE, draft.fetch(),
                draft.listing(), draft.tile(), draft.variables(), draft.cast()));
        WorkflowValidator.validate(new WorkflowDraft(draft.name(), true, draft.mode(), ContentKind.TRACK, draft.fetch(),
                draft.listing(), draft.tile(), draft.variables(), draft.cast()));
    }

    @Test void rejectsUnsupportedSchemaRevisionAndId() {
        var draft = WorkflowFixtures.single(URI.create("https://api.example/catalog"));
        for (int version : List.of(0, 2)) invalidDefinition(new WorkflowDefinition(version, "w-0123456789ab", 1, draft));
        invalidDefinition(new WorkflowDefinition(1, "w-0123456789ab", 0, draft));
        for (String id : List.of("", "w-short", "w-0123456789ABC", "w-0123456789ab-extra")) {
            invalidDefinition(new WorkflowDefinition(1, id, 1, draft));
        }
        assertThatThrownBy(() -> codec.decode("{\"schemaVersion\":2,\"id\":\"w-0123456789ab\",\"revision\":1,\"draft\":{}}"))
                .isInstanceOf(WorkflowException.class);
    }

    @Test void enforcesSerializedSizeOnEncodeAndDecode() {
        var draft = WorkflowFixtures.single(URI.create("https://api.example/catalog"));
        var empty = new WorkflowDefinition(1, "w-0123456789ab", 1,
                withFetch(draft, new Fetch(draft.fetch().url(), List.of(new Header("X-Pad", "")))));
        int padding = 16_384 - codec.encode(empty).length();
        var exact = new WorkflowDefinition(1, empty.id(), 1,
                withFetch(draft, new Fetch(draft.fetch().url(), List.of(new Header("X-Pad", "x".repeat(padding))))));
        String encoded = codec.encode(exact);
        assertThat(encoded).hasSize(16_384);
        assertThat(codec.decode(encoded)).isEqualTo(exact);
        invalidDefinition(new WorkflowDefinition(1, empty.id(), 1,
                withFetch(draft, new Fetch(draft.fetch().url(), List.of(new Header("X-Pad", "x".repeat(padding + 1)))))));
        assertThatThrownBy(() -> codec.decode(encoded + " ")).isInstanceOf(WorkflowException.class);
    }

    @Test void copiesListsAndDoesNotExposeSecretsFromParserErrors() {
        var draft = WorkflowFixtures.single(URI.create("https://api.example/catalog"));
        List<Header> headers = new ArrayList<>(draft.fetch().headers());
        List<Variable> variables = new ArrayList<>(draft.variables());
        var copied = new WorkflowDraft(draft.name(), true, draft.mode(), draft.kind(),
                new Fetch(draft.fetch().url(), headers), null, draft.tile(), variables, draft.cast());
        headers.clear();
        variables.clear();
        assertThat(copied.fetch().headers()).hasSize(1);
        assertThat(copied.variables()).hasSize(2);
        var preparedReceiver184 = copied.fetch().headers();
        assertThatThrownBy(() -> preparedReceiver184.clear()).isInstanceOf(UnsupportedOperationException.class);
        var preparedReceiver185 = copied.variables();
        assertThatThrownBy(() -> preparedReceiver185.clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> codec.decode("{\"schemaVersion\":1,\"secret\":\"saved-secret\", malformed"))
                .isInstanceOf(WorkflowException.class).hasMessageNotContaining("saved-secret");
    }

    @Test void validatorReportsNullListMembersAsSafeWorkflowErrors() {
        var draft = WorkflowFixtures.single(URI.create("https://api.example/catalog"));
        List<Header> headers = new ArrayList<>();
        headers.add(null);
        var malformedFetch = new Fetch(draft.fetch().url(), headers);
        invalid(withFetch(draft, malformedFetch));
        List<Variable> variables = new ArrayList<>();
        variables.add(null);
        invalid(withVariables(draft, variables));
    }

    private void invalid(WorkflowDraft draft) {
        assertThatThrownBy(() -> WorkflowValidator.validate(draft)).isInstanceOf(WorkflowException.class);
    }

    private void invalidDefinition(WorkflowDefinition definition) {
        assertThatThrownBy(() -> codec.encode(definition)).isInstanceOf(WorkflowException.class);
    }

    private static WorkflowDraft with(WorkflowDraft draft, Mode mode, Fetch fetch, Listing listing, Tile tile,
                                      List<Variable> variables, Cast cast) {
        return new WorkflowDraft(draft.name(), draft.enabled(), mode, draft.kind(), fetch, listing, tile, variables, cast);
    }

    private static WorkflowDraft withVariables(WorkflowDraft draft, List<Variable> variables) {
        return with(draft, draft.mode(), draft.fetch(), draft.listing(), draft.tile(), variables,
                new Cast("https://media.example/play", "video/mp4"));
    }

    private static WorkflowDraft withFetch(WorkflowDraft draft, Fetch fetch) {
        return with(draft, draft.mode(), fetch, draft.listing(), draft.tile(), draft.variables(), draft.cast());
    }

    private static WorkflowDraft withTile(WorkflowDraft draft, Tile tile) {
        return with(draft, draft.mode(), draft.fetch(), draft.listing(), tile, draft.variables(), draft.cast());
    }

    private static WorkflowDraft withCast(WorkflowDraft draft, Cast cast) {
        return with(draft, draft.mode(), draft.fetch(), draft.listing(), draft.tile(), draft.variables(), cast);
    }
}
