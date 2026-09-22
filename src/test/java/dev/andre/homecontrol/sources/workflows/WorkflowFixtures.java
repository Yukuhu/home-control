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
                new Fetch(source.toString(), List.of(new Header("Authorization", "Bearer saved-secret"))),
                null, new Tile("News", null, null),
                List.of(new Variable("A", Scope.ROOT, "/id", false),
                        new Variable("C", Scope.ROOT, "/token", true)),
                new Cast("https://media.example/play?id={A}&token={C}", "video/mp4"));
    }

    public static WorkflowDraft generated() {
        WorkflowDraft single = single(URI.create("https://api.example/catalog"));
        return new WorkflowDraft(single.name(), single.enabled(), Mode.GENERATED, single.kind(),
                single.fetch(), new Listing("/items", "/id", "/title", null, null), null,
                List.of(new Variable("A", Scope.ENTRY, "/id", false),
                        new Variable("C", Scope.ROOT, "/token", true)), single.cast());
    }
}
