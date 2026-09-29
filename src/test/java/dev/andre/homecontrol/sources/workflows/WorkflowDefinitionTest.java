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
        var definition = new WorkflowDefinition(WorkflowDefinition.SCHEMA_VERSION, "w-0123456789ab", 1,
                WorkflowFixtures.single(URI.create("https://api.example/catalog?key=saved-secret")));
        assertThat(codec.decode(codec.encode(definition))).isEqualTo(definition);
        assertThat(definition.toString()).doesNotContain("saved-secret", "api.example", "token=");
        assertThat(definition.draft().calls().getFirst().toString()).doesNotContain("saved-secret", "api.example");
        assertThat(definition.draft().calls().toString()).doesNotContain("saved-secret", "api.example");
        assertThat(definition.draft().toString()).doesNotContain("saved-secret", "api.example", "token=");
        assertThat(definition.draft().calls().getFirst().headers().getFirst().toString()).doesNotContain("saved-secret");
        assertThat(definition.draft().cast().toString()).doesNotContain("token=");
    }

    @Test void rejectsMissingModeSpecificFields() {
        var single = WorkflowFixtures.single(URI.create("https://api.example/catalog"));
        var generated = WorkflowFixtures.generated();
        invalid(new WorkflowDraft(single.name(), true, Mode.SINGLE, single.kind(), single.calls(),
                generated.listing(), null, single.cast()));
        invalid(new WorkflowDraft(single.name(), true, Mode.GENERATED, single.kind(), single.calls(), null, null,
                single.cast()));
        var listing = generated.listing();
        invalid(withListing(generated, new Listing(listing.call(), listing.arrayPointer(), null, listing.titlePointer(),
                null, null, listing.variables())));
        WorkflowValidator.validate(generated);
    }

    @Test void rejectsInvalidVariableNamesAndDuplicates() {
        var draft = WorkflowFixtures.single(URI.create("https://api.example/catalog"));
        for (String name : List.of("", "1A", "A-B", "A".repeat(33))) {
            invalid(withVariables(draft, List.of(new Variable(name, "/id", false))));
        }
        invalid(withVariables(draft, List.of(new Variable("A", "/id", false), new Variable("A", "/token", true))));
    }

    @Test void validatesNameTitleAndSubtitleBoundaries() {
        var draft = WorkflowFixtures.single(URI.create("https://api.example/catalog"));
        invalid(new WorkflowDraft(" ", true, draft.mode(), draft.kind(), draft.calls(), null, draft.tile(),
                draft.cast()));
        invalid(withTile(draft, new Tile("", null, null)));
        invalid(withTile(draft, new Tile("T".repeat(121), null, null)));
        invalid(withTile(draft, new Tile("Title", "S".repeat(241), null)));
        WorkflowValidator.validate(withTile(draft, new Tile("T".repeat(120), "S".repeat(240), null)));
        WorkflowValidator.validate(new WorkflowDraft("N".repeat(120), true, draft.mode(), draft.kind(),
                draft.calls(), null, draft.tile(), draft.cast()));
        invalid(new WorkflowDraft("N".repeat(121), true, draft.mode(), draft.kind(),
                draft.calls(), null, draft.tile(), draft.cast()));
    }

    @Test void validatesPointersAndHeaderCountBoundaries() {
        var draft = WorkflowFixtures.single(URI.create("https://api.example/catalog"));
        for (String pointer : List.of("id", "/bad~", "/bad~2")) {
            invalid(withVariables(draft, List.of(new Variable("A", pointer, false))));
        }
        WorkflowValidator.validate(withVariables(draft, List.of(new Variable("A", "", false))));
        WorkflowValidator.validate(withVariables(draft, List.of(new Variable("A", "/" + "x".repeat(511), false))));
        invalid(withVariables(draft, List.of(new Variable("A", "/" + "x".repeat(512), false))));
        var generated = WorkflowFixtures.generated();
        invalid(withListing(generated, new Listing("main", "/bad~2", "/id", "/title", null, null,
                generated.listing().variables())));
        List<Header> headers = new ArrayList<>();
        for (int i = 0; i < 16; i++) headers.add(new Header("X-Header-" + i, "value"));
        WorkflowValidator.validate(withHeaders(draft, headers));
        headers.add(new Header("X-Header-16", "value"));
        invalid(withHeaders(draft, headers));
    }

    @Test void rejectsDangerousHeadersAndControlCharacters() {
        var draft = WorkflowFixtures.single(URI.create("https://api.example/catalog"));
        for (String name : List.of("Host", "Cookie", "Connection", "Content-Length", "Transfer-Encoding",
                "TE", "Trailer", "Upgrade", "Keep-Alive", "Expect", "Accept-Encoding",
                "Proxy", "Proxy-Authorization", "Proxy-Anything", "Bad Header", "Bad:Header")) {
            invalid(withHeaders(draft, List.of(new Header(name, "value"))));
        }
        invalid(withHeaders(draft, List.of(new Header("X-Test", "one\r\ntwo"))));
        invalid(withHeaders(draft, List.of(
                new Header("X-Test", "one"), new Header("x-test", "two"))));
    }

    @Test void rejectsUnsafeFetchUrlsAndMediaTemplates() {
        var draft = WorkflowFixtures.single(URI.create("https://api.example/catalog"));
        for (String url : List.of("ftp://api.example/catalog", "https://u:p@api.example/catalog",
                "https://api.example/catalog#fragment", "https://api.example/a/../b",
                "https://api.example/a/%2e%2E/b", "https://api.example/" + "x".repeat(8192))) {
            invalid(withUrl(draft, url));
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
        assertThat(new Variable("saved-secret", "/secret", true).toString())
                .doesNotContain("saved-secret", "/secret");
    }

    @Test void validatesMimeAndKind() {
        var draft = WorkflowFixtures.single(URI.create("https://api.example/catalog"));
        for (String mime : List.of("", "video/mp4; charset=utf-8", "video/☃", "video/", "a".repeat(50) + "/" + "b".repeat(50))) {
            invalid(withCast(draft, new Cast(draft.cast().template(), mime)));
        }
        WorkflowValidator.validate(withCast(draft, new Cast(draft.cast().template(), "a".repeat(49) + "/" + "b".repeat(50))));
        invalid(new WorkflowDraft(draft.name(), true, draft.mode(), ContentKind.MOVIE, draft.calls(),
                draft.listing(), draft.tile(), draft.cast()));
        WorkflowValidator.validate(new WorkflowDraft(draft.name(), true, draft.mode(), ContentKind.TRACK, draft.calls(),
                draft.listing(), draft.tile(), draft.cast()));
    }

    @Test void rejectsUnsupportedSchemaRevisionAndId() {
        var draft = WorkflowFixtures.single(URI.create("https://api.example/catalog"));
        for (int version : List.of(0, 3)) invalidDefinition(new WorkflowDefinition(version, "w-0123456789ab", 1, draft));
        invalidDefinition(new WorkflowDefinition(WorkflowDefinition.SCHEMA_VERSION, "w-0123456789ab", 0, draft));
        for (String id : List.of("", "w-short", "w-0123456789ABC", "w-0123456789ab-extra")) {
            invalidDefinition(new WorkflowDefinition(WorkflowDefinition.SCHEMA_VERSION, id, 1, draft));
        }
        assertThatThrownBy(() -> codec.decode("{\"schemaVersion\":3,\"id\":\"w-0123456789ab\",\"revision\":1,\"draft\":{}}"))
                .isInstanceOf(WorkflowException.class);
    }

    @Test void enforcesSerializedSizeOnEncodeAndDecode() {
        var draft = WorkflowFixtures.single(URI.create("https://api.example/catalog"));
        var empty = new WorkflowDefinition(WorkflowDefinition.SCHEMA_VERSION, "w-0123456789ab", 1,
                withHeaders(draft, List.of(new Header("X-Pad", ""))));
        int padding = 16_384 - codec.encode(empty).length();
        var exact = new WorkflowDefinition(WorkflowDefinition.SCHEMA_VERSION, empty.id(), 1,
                withHeaders(draft, List.of(new Header("X-Pad", "x".repeat(padding)))));
        String encoded = codec.encode(exact);
        assertThat(encoded).hasSize(16_384);
        assertThat(codec.decode(encoded)).isEqualTo(exact);
        invalidDefinition(new WorkflowDefinition(WorkflowDefinition.SCHEMA_VERSION, empty.id(), 1,
                withHeaders(draft, List.of(new Header("X-Pad", "x".repeat(padding + 1))))));
        assertThatThrownBy(() -> codec.decode(encoded + " ")).isInstanceOf(WorkflowException.class);
    }

    @Test void copiesListsAndDoesNotExposeSecretsFromParserErrors() {
        var draft = WorkflowFixtures.single(URI.create("https://api.example/catalog"));
        var first = draft.calls().getFirst();
        List<Header> headers = new ArrayList<>(first.headers());
        List<Variable> variables = new ArrayList<>(first.variables());
        List<Call> calls = new ArrayList<>(List.of(new Call("main", CallScope.SHARED, first.url(), headers, variables)));
        var copied = new WorkflowDraft(draft.name(), true, draft.mode(), draft.kind(), calls, null, draft.tile(),
                draft.cast());
        headers.clear();
        variables.clear();
        calls.clear();
        assertThat(copied.calls()).hasSize(1);
        assertThat(copied.calls().getFirst().headers()).hasSize(1);
        assertThat(copied.calls().getFirst().variables()).hasSize(2);
        var copiedCalls = copied.calls();
        assertThatThrownBy(copiedCalls::clear).isInstanceOf(UnsupportedOperationException.class);
        var copiedHeaders = copied.calls().getFirst().headers();
        assertThatThrownBy(copiedHeaders::clear).isInstanceOf(UnsupportedOperationException.class);
        var copiedVariables = copied.calls().getFirst().variables();
        assertThatThrownBy(copiedVariables::clear).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> codec.decode("{\"schemaVersion\":1,\"secret\":\"saved-secret\", malformed"))
                .isInstanceOf(WorkflowException.class).hasMessageNotContaining("saved-secret");
    }

    @Test void validatorReportsNullListMembersAsSafeWorkflowErrors() {
        var draft = WorkflowFixtures.single(URI.create("https://api.example/catalog"));
        List<Header> headers = new ArrayList<>();
        headers.add(null);
        invalid(withHeaders(draft, headers));
        List<Variable> variables = new ArrayList<>();
        variables.add(null);
        invalid(withVariables(draft, variables));
    }

    @Test void callNamesAreLowerCaseUniqueAndAtMostEight() {
        var draft = WorkflowFixtures.generated();
        for (String name : List.of("", "Main", "1st", "a-b", "a".repeat(25))) {
            invalid(withCalls(draft, List.of(call(name, "https://api.example/x"))));
        }
        invalid(withCalls(draft, List.of(call("main", "https://api.example/a"), call("main", "https://api.example/b"))));
        List<Call> eight = new ArrayList<>(draft.calls());
        for (int i = 1; i < 8; i++) eight.add(call("c" + i, "https://api.example/" + i));
        WorkflowValidator.validateStored(withCalls(draft, eight));
        eight.add(call("c8", "https://api.example/8"));
        invalid(withCalls(draft, eight));
        invalid(withCalls(draft, List.of()));
    }

    @Test void variableNamesAreUniqueAcrossTheWholeWorkflow() {
        var draft = WorkflowFixtures.generated();
        var clash = new Call("second", CallScope.SHARED, "https://api.example/b", List.of(),
                List.of(new Variable("A", "/a", false)));
        var calls = new ArrayList<>(draft.calls());
        calls.add(clash);
        assertThatThrownBy(() -> WorkflowValidator.validateStored(withCalls(draft, calls)))
                .hasMessage("Workflow: duplicate mapping name: A");
    }

    @Test void atMostSixtyFourVariables() {
        var first = WorkflowFixtures.single(URI.create("https://api.example/catalog")).calls().getFirst();
        List<Variable> many = new ArrayList<>();
        for (int i = 0; i < 64; i++) many.add(new Variable("V" + i, "/id", false));
        WorkflowValidator.validateStored(WorkflowFixtures.singleWith(List.of(
                new Call("main", CallScope.SHARED, first.url(), first.headers(), many))));
        many.add(new Variable("V64", "/id", false));
        invalid(WorkflowFixtures.singleWith(List.of(new Call("main", CallScope.SHARED, first.url(), first.headers(), many))));
    }

    @Test void perEntryCallsNeedGeneratedTiles() {
        var draft = WorkflowFixtures.single(URI.create("https://api.example/catalog"));
        var first = draft.calls().getFirst();
        invalid(withCalls(draft, List.of(new Call("main", CallScope.ENTRY, first.url(), first.headers(), first.variables()))));
    }

    @Test void theEntrySourceMustBeASharedCall() {
        var draft = WorkflowFixtures.generated();
        var listing = draft.listing();
        invalid(withListing(draft, new Listing("missing", listing.arrayPointer(), listing.idPointer(),
                listing.titlePointer(), null, null, listing.variables())));
    }

    @Test void aDisplayFieldReadsEitherAPointerOrAVariable() {
        var draft = WorkflowFixtures.generated();
        var l = draft.listing();
        invalid(withListing(draft, new Listing(l.call(), l.arrayPointer(), l.idPointer(), l.titlePointer(),
                new Field("/sub", "A"), null, l.variables())));
        invalid(withListing(draft, new Listing(l.call(), l.arrayPointer(), l.idPointer(), l.titlePointer(),
                new Field(null, null), null, l.variables())));
        invalid(withListing(draft, new Listing(l.call(), l.arrayPointer(), l.idPointer(), l.titlePointer(),
                null, new Field(null, "Unknown"), l.variables())));
        WorkflowValidator.validateStored(withListing(draft, new Listing(l.call(), l.arrayPointer(), l.idPointer(),
                l.titlePointer(), new Field("/sub", null), new Field(null, "A"), l.variables())));
    }

    @Test void headerValuesMustBeValidTemplates() {
        var draft = WorkflowFixtures.single(URI.create("https://api.example/catalog"));
        var first = draft.calls().getFirst();
        invalid(withCalls(draft, List.of(new Call("main", CallScope.SHARED, first.url(),
                List.of(new Header("X-Bad", "{unclosed")), first.variables()))));
    }

    private void invalid(WorkflowDraft draft) {
        assertThatThrownBy(() -> WorkflowValidator.validateStored(draft)).isInstanceOf(WorkflowException.class);
    }

    private void invalidDefinition(WorkflowDefinition definition) {
        assertThatThrownBy(() -> codec.encode(definition)).isInstanceOf(WorkflowException.class);
    }

    private static WorkflowDraft withVariables(WorkflowDraft draft, List<Variable> variables) {
        var first = draft.calls().getFirst();
        return new WorkflowDraft(draft.name(), draft.enabled(), draft.mode(), draft.kind(),
                List.of(new Call(first.name(), first.scope(), first.url(), first.headers(), variables)),
                draft.listing(), draft.tile(), new Cast("https://media.example/play", "video/mp4"));
    }

    private static WorkflowDraft withHeaders(WorkflowDraft draft, List<Header> headers) {
        var first = draft.calls().getFirst();
        return withCalls(draft, List.of(new Call(first.name(), first.scope(), first.url(), headers, first.variables())));
    }

    private static WorkflowDraft withUrl(WorkflowDraft draft, String url) {
        var first = draft.calls().getFirst();
        return withCalls(draft, List.of(new Call(first.name(), first.scope(), url, first.headers(), first.variables())));
    }

    private static WorkflowDraft withTile(WorkflowDraft draft, Tile tile) {
        return new WorkflowDraft(draft.name(), draft.enabled(), draft.mode(), draft.kind(), draft.calls(),
                draft.listing(), tile, draft.cast());
    }

    private static WorkflowDraft withCast(WorkflowDraft draft, Cast cast) {
        return new WorkflowDraft(draft.name(), draft.enabled(), draft.mode(), draft.kind(), draft.calls(),
                draft.listing(), draft.tile(), cast);
    }

    private static Call call(String name, String url) {
        return new Call(name, CallScope.SHARED, url, List.of(), List.of());
    }

    private static WorkflowDraft withCalls(WorkflowDraft d, List<Call> calls) {
        return new WorkflowDraft(d.name(), d.enabled(), d.mode(), d.kind(), calls, d.listing(), d.tile(), d.cast());
    }

    private static WorkflowDraft withListing(WorkflowDraft d, Listing listing) {
        return new WorkflowDraft(d.name(), d.enabled(), d.mode(), d.kind(), d.calls(), listing, d.tile(), d.cast());
    }
}
