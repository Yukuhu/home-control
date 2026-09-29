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
        var entries = WorkflowJson.entries(draft.listing(), root, 200);
        assertThat(entries).extracting(WorkflowJson.Entry::title).containsExactly("News", "Music");
        assertThat(entries).extracting(WorkflowJson.Entry::key).doesNotHaveDuplicates();
        var values = new java.util.HashMap<>(WorkflowJson.values(draft.calls().getFirst().variables(), root));
        values.putAll(WorkflowJson.values(draft.listing().variables(), entries.getFirst().node()));
        assertThat(values)
                .containsEntry("A", new WorkflowJson.Value("news", false))
                .containsEntry("C", new WorkflowJson.Value("example-token", true))
                .containsEntry("D", new WorkflowJson.Value("hd", false));
        assertThat(values.get("C").toString()).doesNotContain("example-token");
    }

    @Test void keysSurviveReorderTokenAndTitleChanges() {
        var first = WorkflowJson.entries(channels().listing(), parse(WorkflowFixtures.CHANNELS), 200);
        var changed = WorkflowJson.entries(channels().listing(), parse(WorkflowFixtures.REORDERED_CHANNELS), 200);
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
        var vars = java.util.List.of(new Variable("A", "/a~1b/~0token", false),
                new Variable("B", "/streams/0/quality", false));
        assertThat(WorkflowJson.values(vars, root))
                .containsEntry("A", new WorkflowJson.Value("true", false))
                .containsEntry("B", new WorkflowJson.Value("12", false));
    }

    @Test void missingNullAndContainerMappingsFail() {
        var root = parse("{\"empty\":null,\"object\":{},\"array\":[]}");
        for (String pointer : new String[]{"/missing", "/empty", "/object", "/array"}) {
            var mappings = java.util.List.of(new Variable("A", pointer, false));
            assertThatThrownBy(() -> WorkflowJson.values(mappings, root))
                    .isInstanceOf(WorkflowException.class).hasMessageContaining("A");
        }
    }

    @Test void rejectsDuplicateIdsAndOversizedCatalog() {
        var draft = channels();
        var duplicateIds = parse(
                "{\"channels\":[{\"id\":1,\"title\":\"A\"},{\"id\":1.0,\"title\":\"B\"}]}");
        assertThatThrownBy(() -> WorkflowJson.entries(draft.listing(), duplicateIds, 200))
                .isInstanceOf(WorkflowException.class).hasMessageContaining("ID");
        var items = new StringBuilder("{\"channels\":[");
        for (int i = 0; i < 201; i++) {
            if (i > 0) items.append(',');
            items.append("{\"id\":").append(i).append(",\"title\":\"T\"}");
        }
        items.append("]}");
        var oversizedCatalog = parse(items.toString());
        assertThatThrownBy(() -> WorkflowJson.entries(draft.listing(), oversizedCatalog, 200))
                .isInstanceOf(WorkflowException.class);
    }

    @Test void invalidRequiredTitlesAndArtworkAreHandled() {
        var draft = channels();
        var blankTitle = parse(
                "{\"channels\":[{\"id\":\"a\",\"title\":\" \"}]}");
        assertThatThrownBy(() -> WorkflowJson.entries(draft.listing(), blankTitle, 200))
                .isInstanceOf(WorkflowException.class).hasMessageContaining("title");
        var withArt = new Listing("main", "/channels", "/id", "/title", null, new Field("/art", null), java.util.List.of());
        assertThat(WorkflowJson.entries(withArt, parse(
                "{\"channels\":[{\"id\":\"a\",\"title\":\"A\",\"art\":\"https://example.com/a?token=x\"}]}"), 200)
                .getFirst().artwork()).isNull();
        assertThat(WorkflowJson.entries(withArt, parse(
                "{\"channels\":[{\"id\":\"a\",\"title\":\"A\",\"art\":\"https://127.0.0.1/a\"}]}"), 200)
                .getFirst().artwork()).isNull();
        assertThat(WorkflowJson.entries(withArt, parse(
                "{\"channels\":[{\"id\":\"a\",\"title\":\"A\",\"art\":\"https://8.8.8.8/a\"}]}"), 200)
                .getFirst().artwork()).isEqualTo(java.net.URI.create("https://8.8.8.8/a"));
    }

    @Test void generatedArtworkOmitsRootDotLocalNamesAndDocumentationIpv6() {
        var withArt = new Listing("main", "/channels", "/id", "/title", null, new Field("/art", null), java.util.List.of());
        for (String host : new String[]{"feed.local.", "localhost.", "[2001:db8::1]"}) {
            var response = parse("{\"channels\":[{\"id\":\"a\",\"title\":\"A\",\"art\":\"https://"
                    + host + "/cover.png\"}]}");
            assertThat(WorkflowJson.entries(withArt, response, 200).getFirst().artwork()).isNull();
        }
        var publicResponse = parse("{\"channels\":[{\"id\":\"a\",\"title\":\"A\","
                + "\"art\":\"https://[2606:4700::1111]/cover.png\"}]}");
        assertThat(WorkflowJson.entries(withArt, publicResponse, 200).getFirst().artwork())
                .isEqualTo(java.net.URI.create("https://[2606:4700::1111]/cover.png"));
    }

    @Test void generatedArtworkAcceptsAllocatedIpv6AndRejectsReservedBoundaries() {
        for (String host : new String[]{"[2001:200::1]", "[2001:4860::1]", "[2400::1]",
                "[2606:4700::1111]", "[2800::1]", "[2a00::1]", "[2c00::1]"}) {
            assertThat(WorkflowJson.artwork("https://" + host + "/cover.png"))
                    .isEqualTo(java.net.URI.create("https://" + host + "/cover.png"));
        }
        for (String host : new String[]{"[2001:100::1]", "[2002::1]", "[2003:4000::1]",
                "[23ff::1]", "[2d00::1]", "[3ffe::1]", "[3fff::1]"}) {
            assertThat(WorkflowJson.artwork("https://" + host + "/cover.png")).isNull();
        }
    }

    @Test void generatedArtworkRejectsEveryNonPublicIpv4Range() {
        for (String host : new String[]{"0.0.0.0", "0.1.2.3", "10.0.0.1", "10.255.255.255", "127.0.0.1",
                "127.255.255.254", "100.64.0.1", "100.127.255.254", "169.254.169.254", "172.16.0.1",
                "172.31.255.255", "192.168.1.1", "192.0.0.8", "192.0.2.1", "198.18.0.1", "198.19.255.254",
                "198.51.100.7", "203.0.113.9", "224.0.0.251", "239.255.255.250", "255.255.255.255"}) {
            assertThat(WorkflowJson.artwork("https://" + host + "/cover.png")).as(host).isNull();
        }
    }

    @Test void generatedArtworkAcceptsPublicIpv4NextToReservedBoundaries() {
        for (String host : new String[]{"1.1.1.1", "9.255.255.255", "11.0.0.1", "100.63.255.255",
                "100.128.0.1", "126.255.255.255", "128.0.0.1", "169.253.0.1", "169.255.0.1", "172.15.255.255",
                "172.32.0.1", "192.0.1.1", "192.0.3.1", "192.1.0.1", "192.167.1.1", "192.169.0.1", "198.17.0.1",
                "198.20.0.1", "198.51.99.1", "198.51.101.1", "203.0.112.1", "203.1.113.1", "223.255.255.254"}) {
            assertThat(WorkflowJson.artwork("https://" + host + "/cover.png")).as(host)
                    .isEqualTo(java.net.URI.create("https://" + host + "/cover.png"));
        }
    }

    @Test void generatedArtworkRejectsAmbiguousIpv4AndNonGlobalIpv6Literals() {
        // Leading zeros are octal to some resolvers: 010.0.0.1 could mean 8.0.0.1.
        for (String host : new String[]{"010.0.0.1", "8.8.8.08", "0177.0.0.1", "256.1.1.1", "1.2.3",
                "[::1]", "[::]", "[fc00::1]", "[fe80::1]", "[ff02::1]", "[::ffff:127.0.0.1]",
                "[::ffff:8.8.8.8]", "[fe80::1%25eth0]", "[2001:db8:1::1]"}) {
            assertThat(WorkflowJson.artwork("https://" + host + "/cover.png")).as(host).isNull();
        }
    }

    @Test void generatedArtworkNeedsAPlainHttpsUrlOnAQualifiedHost() {
        for (String url : new String[]{"http://cdn.example.com/a.png", "ftp://cdn.example.com/a.png",
                "//cdn.example.com/a.png", "/a.png", "https://user@cdn.example.com/a.png",
                "https://cdn.example.com/a.png#x", "https://cdn.example.com/a.png?size=1",
                "https://cdn.example.com/art/../admin", "https://cdn.example.com/./a.png",
                "https://cdn/a.png", "https://PRINTER.LOCAL/a.png", "https://a.b.localhost/a.png",
                "https://cdn example.com/a.png", "not a url"}) {
            assertThat(WorkflowJson.artwork(url)).as(url).isNull();
        }
        assertThat(WorkflowJson.artwork(null)).isNull();
        assertThat(WorkflowJson.artwork("HTTPS://CDN.Example.COM./a.png"))
                .isEqualTo(java.net.URI.create("HTTPS://CDN.Example.COM./a.png"));
    }

    @Test void generatedEntriesRejectInvalidIdsTitlesAndArrays() {
        var draft = channels();
        assertSelectFails(draft, "{\"items\":[]}", "entry array is missing or invalid");
        assertSelectFails(draft, "{\"channels\":{\"id\":\"a\"}}", "entry array is missing or invalid");
        assertSelectFails(draft, "{\"channels\":[" + "{\"id\":1,\"title\":\"A\"},".repeat(200)
                + "{\"id\":2,\"title\":\"B\"}]}", "the list has 201 entries; the limit is 200");
        assertSelectFails(draft, "{\"channels\":[{\"title\":\"A\"}]}", "entry 0 has invalid ID");
        assertSelectFails(draft, "{\"channels\":[{\"id\":\"\",\"title\":\"A\"}]}", "entry 0 has invalid ID");
        assertSelectFails(draft, "{\"channels\":[{\"id\":{\"x\":1},\"title\":\"A\"}]}", "entry 0 has invalid ID");
        assertSelectFails(draft, "{\"channels\":[{\"id\":\"a\",\"title\":\"A\"},{\"id\":\"a\",\"title\":\"B\"}]}",
                "entry 1 has duplicate ID");
        assertSelectFails(draft, "{\"channels\":[{\"id\":\"a\"}]}", "entry 0 has invalid title");
        assertSelectFails(draft, "{\"channels\":[{\"id\":\"a\",\"title\":\"  \"}]}", "entry 0 has invalid title");
        assertSelectFails(draft, "{\"channels\":[{\"id\":\"a\",\"title\":7}]}", "entry 0 has invalid title");
        assertSelectFails(draft, "{\"channels\":[{\"id\":\"a\",\"title\":\"" + "x".repeat(121) + "\"}]}",
                "entry 0 has invalid title");
        String atTheLimits = java.util.stream.IntStream.range(0, 200)
                .mapToObj(i -> "{\"id\":" + i + ",\"title\":\"" + "x".repeat(120) + "\"}")
                .collect(java.util.stream.Collectors.joining(",", "{\"channels\":[", "]}"));
        assertThat(WorkflowJson.entries(draft.listing(), parse(atTheLimits), 200)).hasSize(200);
    }

    private static void assertSelectFails(WorkflowDraft draft, String json, String detail) {
        var root = parse(json);
        assertThatThrownBy(() -> WorkflowJson.entries(draft.listing(), root, 200)).as(json)
                .isInstanceOf(WorkflowException.class)
                .hasMessage("Choose entries: " + detail);
    }

    @Test void generatedEntriesKeepOnlyShortTextSubtitlesAndTextArtwork() {
        var withExtras = new Listing("main", "/channels", "/id", "/title", new Field("/sub", null), new Field("/art", null),
                java.util.List.of());
        var entries = WorkflowJson.entries(withExtras, parse("""
                {"channels":[{"id":"a","title":"A","sub":"Live","art":"https://cdn.example.com/a.png"},
                             {"id":"b","title":"B","sub":7,"art":{"url":"https://cdn.example.com/b.png"}},
                             {"id":"c","title":"C","sub":"%s"},
                             {"id":"d","title":"D","sub":"%s"}]}
                """.formatted("x".repeat(240), "x".repeat(241))), 200);
        assertThat(entries).extracting(WorkflowJson.Entry::subtitle)
                .containsExactly("Live", null, "x".repeat(240), null);
        assertThat(entries).extracting(WorkflowJson.Entry::artwork)
                .containsExactly(java.net.URI.create("https://cdn.example.com/a.png"), null, null, null);
        assertThat(entries.getFirst()).hasToString("Entry[key=" + entries.getFirst().key() + "]");
    }

    @Test void mappingsNeedAScalarValue() {
        for (String json : new String[]{"{}", "{\"auth\":{\"token\":null}}", "{\"auth\":{\"token\":{}}}",
                "{\"auth\":{\"token\":[\"t\"]}}"}) {
            var root = parse(json);
            var variables = java.util.List.of(new Variable("C", "/auth/token", true));
            assertThatThrownBy(() -> WorkflowJson.values(variables, root))
                    .isInstanceOf(WorkflowException.class)
                    .hasMessage("Map fields: mapping C has no scalar value")
                    .extracting(e -> ((WorkflowException) e).stage()).isEqualTo(WorkflowException.Stage.MAP);
        }
        var values = WorkflowJson.values(java.util.List.of(new Variable("B", "/on", false)),
                parse("{\"on\":true}"));
        assertThat(values).containsEntry("B", new WorkflowJson.Value("true", false));
    }

    @Test void rejectsMissingOversizedAndEmptyBodies() {
        assertThatThrownBy(() -> WorkflowJson.parse(null)).isInstanceOf(WorkflowException.class)
                .hasMessage("Parse JSON: invalid JSON response size");
        var oversized = new byte[2 * 1024 * 1024 + 1];
        assertThatThrownBy(() -> WorkflowJson.parse(oversized)).isInstanceOf(WorkflowException.class)
                .hasMessage("Parse JSON: invalid JSON response size");
        for (String body : new String[]{"", "   ", "{", "nope"}) {
            assertThatThrownBy(() -> parse(body)).as(body).isInstanceOf(WorkflowException.class)
                    .hasMessage("Parse JSON: invalid JSON response");
        }
    }

    @Test void theEntryLimitIsAParameter() {
        var listing = channels().listing();
        var root = parse(java.util.stream.IntStream.range(0, 51).mapToObj(i -> "{\"id\":" + i + ",\"title\":\"A\"}")
                .collect(java.util.stream.Collectors.joining(",", "{\"channels\":[", "]}")));
        assertThat(WorkflowJson.entries(listing, root, 51)).hasSize(51);
        assertThatThrownBy(() -> WorkflowJson.entries(listing, root, 50)).isInstanceOf(WorkflowException.class)
                .hasMessage("Choose entries: the list has 51 entries; the limit is 50");
    }

    @Test void rejectsExcessiveDepthAndTrailingDocuments() {
        String deep = "[".repeat(65) + "0" + "]".repeat(65);
        assertThatThrownBy(() -> parse(deep)).isInstanceOf(WorkflowException.class);
        assertThatThrownBy(() -> parse("{} {}")) .isInstanceOf(WorkflowException.class);
    }

    @Test void numericIdentitiesKeepExactPrecisionAndRejectFractionalIds() {
        var entries = WorkflowJson.entries(channels().listing(), parse("""
                {"channels":[{"id":9007199254740992.0,"title":"A"},
                             {"id":9007199254740993.0,"title":"B"}]}
                """), 200);
        assertThat(entries).extracting(WorkflowJson.Entry::key).doesNotHaveDuplicates();
        assertThat(entries.get(1).key()).isEqualTo(WorkflowJson.stableKey(parse("9007199254740993")));
        var fractionalId = parse("1.0000000000000001");
        assertThatThrownBy(() -> WorkflowJson.stableKey(fractionalId))
                .isInstanceOf(WorkflowException.class).hasMessageContaining("invalid entry ID");
        assertThat(WorkflowJson.stableKey(parse("10e-1"))).isEqualTo(WorkflowJson.stableKey(parse("1")));
        assertThat(WorkflowJson.stableKey(parse("-0.0"))).isEqualTo(WorkflowJson.stableKey(parse("0")));
    }

    @Test void numericMappingsKeepExactValuesWithoutExpandingExponents() {
        for (String number : new String[]{"9007199254740993.0", "1.0000000000000001", "1e100000000", "1e-100000000"}) {
            var values = WorkflowJson.values(java.util.List.of(new Variable("A", "", false)), parse(number));
            String text = values.get("A").text();
            assertThat(text.length()).isLessThanOrEqualTo(32);
            assertThat(new java.math.BigDecimal(text)).isEqualByComparingTo(new java.math.BigDecimal(number));
            var url = new WorkflowTemplate("https://media.example/?value={A}", java.util.Set.of("A")).expand(values);
            assertThat(java.net.URLDecoder.decode(url.getRawQuery(), StandardCharsets.UTF_8)).isEqualTo("value=" + text);
        }
    }

    @Test void numericIdsAndTokensHaveExplicitBoundsBeforeIntegerExpansion() {
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), () -> {
            String boundary = "1" + "0".repeat(999);
            assertThat(WorkflowJson.stableKey(parse("1e999"))).isEqualTo(WorkflowJson.stableKey(parse(boundary)));
            for (String number : new String[]{"1e1000", "1e100000000", "1e-100000000", "1e2147483647", "10e2147483647"}) {
                var outOfRange = parse(number);
                assertThatThrownBy(() -> WorkflowJson.stableKey(outOfRange)).isInstanceOf(WorkflowException.class);
            }
            var tooManyDigits = "1".repeat(1001);
            assertThatThrownBy(() -> parse(tooManyDigits)).isInstanceOf(WorkflowException.class);
            assertThatThrownBy(() -> parse("1e2147483648")).isInstanceOf(WorkflowException.class);
        });
    }

    private static tools.jackson.databind.JsonNode parse(String json) {
        return WorkflowJson.parse(json.getBytes(StandardCharsets.UTF_8));
    }

    private static WorkflowDraft channels() {
        var base = WorkflowFixtures.generated();
        var call = base.calls().getFirst();
        return new WorkflowDraft(base.name(), true, base.mode(), base.kind(),
                java.util.List.of(new Call("main", CallScope.SHARED, call.url(), call.headers(),
                        java.util.List.of(new Variable("C", "/auth/token", true)))),
                new Listing("main", "/channels", "/id", "/title", null, null,
                        java.util.List.of(new Variable("A", "/id", false), new Variable("D", "/quality", false))),
                null, base.cast());
    }
}
