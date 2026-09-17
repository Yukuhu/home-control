package dev.andre.homecontrol.web;

import dev.andre.homecontrol.core.content.ContentSource;
import dev.andre.homecontrol.core.content.ContentSourceException;
import dev.andre.homecontrol.core.content.ContentSources;
import dev.andre.homecontrol.core.content.Rail;
import dev.andre.homecontrol.core.content.RailDescriptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Content sources and their rails. Interim: fetches upstream on every call. Sub-project D1 puts a cache in front. */
@RestController
public class ContentController {

    public record RailView(String id, String title) {
    }

    public record SourceView(String id, String name, boolean available, boolean searchable, List<RailView> rails) {
    }

    public record RailContentView(String sourceId, String id, String title, Instant fetchedAt,
                                  List<ContentItemView> items) {
    }

    private final ContentSources sources;

    public ContentController(ContentSources sources) {
        this.sources = sources;
    }

    @GetMapping("/sources")
    public List<SourceView> sources() {
        return sources.all().stream()
                .map(source -> new SourceView(source.id(), source.displayName(), source.available(),
                        source.searchable(), source.rails().stream().map(this::toRailView).toList()))
                .toList();
    }

    @GetMapping("/sources/{sourceId}/rails/{railId}")
    public RailContentView rail(@PathVariable String sourceId, @PathVariable String railId) {
        ContentSource source = sources.find(sourceId)
                .orElseThrow(() -> new IllegalArgumentException("No content source " + sourceId));
        Rail rail = source.rail(railId);
        return new RailContentView(rail.descriptor().sourceId(), rail.descriptor().id(), rail.descriptor().title(),
                rail.fetchedAt(), rail.items().stream().map(ContentItemView::of).toList());
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

    /** Used by the unified search box (D5). Sequential for now; a slow source delays the answer. */
    @GetMapping(path = "/search", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> search(@RequestParam(required = false) String q, @RequestParam(defaultValue = "20") int limit) {
        String query = q == null ? "" : q.strip();
        if (query.length() < 2 || query.length() > 100) {
            return ResponseEntity.badRequest().contentType(MediaType.TEXT_PLAIN).body("Search for 2 to 100 characters");
        }
        int clamped = Math.max(1, Math.min(50, limit));
        List<SearchResult> results = new ArrayList<>();
        List<SearchError> errors = new ArrayList<>();
        for (ContentSource source : sources.searchable()) {
            try {
                results.add(new SearchResult(source.id(), source.displayName(),
                        source.search(query, clamped).stream().map(ContentItemView::of).toList()));
            } catch (ContentSourceException e) {
                errors.add(new SearchError(source.id(), e.getMessage()));
            }
        }
        return ResponseEntity.ok(new SearchResponse(query, results, errors));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> notFound(IllegalArgumentException e) {
        return text(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(ContentSourceException.class)
    public ResponseEntity<String> upstreamFailure(ContentSourceException e) {
        return text(HttpStatus.BAD_GATEWAY, e.getMessage());
    }

    private static ResponseEntity<String> text(HttpStatus status, String body) {
        // A message such as an unreachable-server reason may contain non-ASCII punctuation.
        return ResponseEntity.status(status)
                .contentType(new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8))
                .body(body);
    }
}
