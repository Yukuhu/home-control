package dev.andre.homecontrol.sources.workflows;

import dev.andre.homecontrol.core.playback.ContentKind;

import java.net.URI;
import java.util.List;

import static dev.andre.homecontrol.sources.workflows.WorkflowDraft.*;

public final class WorkflowFixtures {
    private WorkflowFixtures() {}

    public static final String CHANNELS = """
            {"auth":{"token":"example-token"},"channels":[
              {"id":"news","title":"News","quality":"hd"},
              {"id":"music","title":"Music","quality":"sd"}
            ]}
            """;
    public static final String REORDERED_CHANNELS = """
            {"auth":{"token":"fresh-token"},"channels":[
              {"id":"music","title":"Renamed Music","quality":"sd"},
              {"id":"news","title":"Renamed News","quality":"hd"}
            ]}
            """;

    public static WorkflowDraft single(URI source) {
        return new WorkflowDraft("News", true, Mode.SINGLE, ContentKind.VIDEO,
                List.of(new Call("main", CallScope.SHARED, source.toString(),
                        List.of(new Header("Authorization", "Bearer saved-secret")),
                        List.of(new Variable("A", "/id", false), new Variable("C", "/token", true)))),
                null, new Tile("News", null, null),
                new Cast("https://media.example/play?id={A}&token={C}", "video/mp4"));
    }

    public static WorkflowDraft generated() {
        return new WorkflowDraft("News", true, Mode.GENERATED, ContentKind.VIDEO,
                List.of(new Call("main", CallScope.SHARED, "https://api.example/catalog",
                        List.of(new Header("Authorization", "Bearer saved-secret")),
                        List.of(new Variable("C", "/token", true)))),
                new Listing("main", "/items", "/id", "/title", null, null, List.of(new Variable("A", "/id", false))),
                null, new Cast("https://media.example/play?id={A}&token={C}", "video/mp4"));
    }

    /** A single-tile workflow with the given calls and a media URL that uses none of their values. */
    public static WorkflowDraft singleWith(List<Call> calls) {
        return new WorkflowDraft("Calls", true, Mode.SINGLE, ContentKind.VIDEO, calls, null,
                new Tile("Calls", null, null), new Cast("https://media.example/play", "video/mp4"));
    }

    /**
     * A list call, a per-entry artwork lookup for the tiles, and a per-entry stream call for Play:
     * list → {token} and entries with {id}; images/{id} → {art}; stream/{id}?token={token} → {path}.
     */
    public static WorkflowDraft chain(URI base) {
        String root = base.toString().replaceAll("/$", "");
        return new WorkflowDraft("Chain", true, Mode.GENERATED, ContentKind.VIDEO,
                List.of(new Call("list", CallScope.SHARED, root + "/list", List.of(),
                                List.of(new Variable("token", "/token", true))),
                        new Call("images", CallScope.ENTRY, root + "/images/{id}", List.of(),
                                List.of(new Variable("art", "/url", false))),
                        new Call("stream", CallScope.ENTRY, root + "/stream/{id}?token={token}",
                                List.of(new Header("Authorization", "Bearer {token}")),
                                List.of(new Variable("path", "/path", true)))),
                new Listing("list", "/items", "/id", "/title", null, new Field(null, "art"),
                        List.of(new Variable("id", "/id", false))),
                null, new Cast("https://media.example/play/{path}?t={token}", "video/mp4"));
    }
}
