package dev.andre.homecontrol.web;

import dev.andre.homecontrol.content.RailCache;
import dev.andre.homecontrol.content.RailSnapshot;
import dev.andre.homecontrol.content.SearchOutcome;
import dev.andre.homecontrol.content.SearchService;
import dev.andre.homecontrol.core.content.ContentSources;
import dev.andre.homecontrol.core.content.RailDescriptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

/** Content sources and their rails, read from the {@link RailCache} that keeps them fresh in the background. */
@RestController
public class ContentController {

    public record RailView(String id, String title) {
    }

    public record SourceView(String id, String name, boolean available, boolean searchable, List<RailView> rails) {
    }

    public record RailContentView(String sourceId, String id, String title, String status, Instant fetchedAt,
                                  String error, List<ContentItemView> items) {
        static RailContentView of(RailSnapshot s) {
            return new RailContentView(s.sourceId(), s.railId(), s.descriptor().title(), s.status().name(),
                    s.fetchedAt(), s.error(), s.items().stream().map(ContentItemView::of).toList());
        }
    }

    private final ContentSources sources;
    private final RailCache rails;
    private final SearchService searchService;

    public ContentController(ContentSources sources, RailCache rails, SearchService searchService) {
        this.sources = sources;
        this.rails = rails;
        this.searchService = searchService;
    }

    @GetMapping("/sources")
    public List<SourceView> sources() {
        return sources.all().stream()
                .map(source -> new SourceView(source.id(), source.displayName(), source.available(),
                        source.searchable(), source.rails().stream().map(this::toRailView).toList()))
                .toList();
    }

    @GetMapping("/sources/{sourceId}/rails/{railId}")
    public ResponseEntity<?> rail(@PathVariable String sourceId, @PathVariable String railId) {
        return rails.snapshot(sourceId, railId).<ResponseEntity<?>>map(snapshot -> switch (snapshot.status()) {
            case LOADING -> ResponseEntity.status(HttpStatus.ACCEPTED).body(RailContentView.of(snapshot));
            case READY -> ResponseEntity.ok(RailContentView.of(snapshot));
            case FAILED -> snapshot.hasItems()
                    ? ResponseEntity.ok(RailContentView.of(snapshot))
                    : text(HttpStatus.BAD_GATEWAY, snapshot.error());
        }).orElseGet(() -> unknownRail(sourceId, railId));
    }

    @PostMapping("/sources/{sourceId}/rails/{railId}/refresh")
    public ResponseEntity<?> refresh(@PathVariable String sourceId, @PathVariable String railId) {
        return rails.refresh(sourceId, railId)
                .<ResponseEntity<?>>map(snapshot -> ResponseEntity.status(HttpStatus.ACCEPTED).body(RailContentView.of(snapshot)))
                .orElseGet(() -> unknownRail(sourceId, railId));
    }

    private ResponseEntity<?> unknownRail(String sourceId, String railId) {
        return sources.find(sourceId)
                .<ResponseEntity<?>>map(source -> text(HttpStatus.NOT_FOUND, source.displayName() + " has no rail '" + railId + "'"))
                .orElseGet(() -> text(HttpStatus.NOT_FOUND, "No content source " + sourceId));
    }

    private RailView toRailView(RailDescriptor descriptor) {
        return new RailView(descriptor.id(), descriptor.title());
    }

    public record SearchResult(String sourceId, String sourceName, List<ContentItemView> items) {
    }

    public record SearchError(String sourceId, String message) {
    }

    public record SearchResponse(String query, List<SearchResult> results, List<SearchError> errors) {
    }

    /** Used by the unified search box (D5): every enabled searchable source, in parallel, one deadline. */
    @GetMapping(path = "/search", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> search(@RequestParam(required = false) String q, @RequestParam(defaultValue = "20") int limit,
                                    @RequestParam(required = false) String source) {
        String query = q == null ? "" : q.strip();
        if (query.length() < 2 || query.length() > 100) {
            return ResponseEntity.badRequest().contentType(MediaType.TEXT_PLAIN).body("Search for 2 to 100 characters");
        }
        int clamped = Math.max(1, Math.min(50, limit));
        SearchOutcome outcome;
        if (source != null && !source.isBlank()) {
            try {
                outcome = searchService.searchSource(source, query, clamped);
            } catch (IllegalArgumentException e) {
                return text(HttpStatus.NOT_FOUND, e.getMessage());
            }
        } else {
            outcome = searchService.search(query, clamped);
        }
        List<SearchResult> results = outcome.hits().stream()
                .map(h -> new SearchResult(h.source().id(), h.source().displayName(),
                        h.items().stream().map(ContentItemView::of).toList()))
                .toList();
        List<SearchError> errors = outcome.failures().stream()
                .map(f -> new SearchError(f.source().id(), f.message()))
                .toList();
        return ResponseEntity.ok(new SearchResponse(query, results, errors));
    }

    private static ResponseEntity<String> text(HttpStatus status, String body) {
        // A message such as an unreachable-server reason may contain non-ASCII punctuation.
        return ResponseEntity.status(status)
                .contentType(new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8))
                .body(body);
    }
}
