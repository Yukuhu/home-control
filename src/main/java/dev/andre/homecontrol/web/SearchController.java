package dev.andre.homecontrol.web;

import dev.andre.homecontrol.content.SearchOutcome;
import dev.andre.homecontrol.content.SearchService;
import dev.andre.homecontrol.core.content.ContentSource;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/** The dashboard's debounced search box (D5): renders {@link SearchService}'s outcome as a fragment. */
@Controller
public class SearchController {

    private static final int LIMIT = 20;

    public record SearchHitsView(String sourceId, String sourceName, List<ContentItemView> items) {
    }

    public record SearchFailureView(String sourceId, String sourceName, String message) {
    }

    /** A source left out of the unified search, offered as a button the user can press to search it too. */
    public record OnDemandView(String sourceId, String sourceName, String note) {
    }

    private final SearchService search;

    public SearchController(SearchService search) {
        this.search = search;
    }

    @GetMapping("/search/results")
    public String results(@RequestParam(required = false) String q, Model model) {
        String query = q == null ? "" : q.strip();
        model.addAttribute("query", query);

        String state = stateFor(query);
        if (state.equals("done")) {
            Filled filled = fillResults(search.search(query, LIMIT));
            model.addAttribute("hits", filled.hits().stream().filter(hit -> !hit.items().isEmpty()).toList());
            model.addAttribute("failures", filled.failures());
            model.addAttribute("onDemand", search.onDemandSources().stream()
                    .map(source -> new OnDemandView(source.id(), source.displayName(), source.searchNote().orElse(null)))
                    .toList());
        }
        model.addAttribute("state", state);
        return "fragments/search :: results";
    }

    @GetMapping("/search/results/{sourceId}")
    public String sourceResults(@PathVariable String sourceId, @RequestParam(required = false) String q, Model model) {
        String query = q == null ? "" : q.strip();
        model.addAttribute("query", query);

        String state = stateFor(query);
        if (state.equals("done")) {
            try {
                Filled filled = fillResults(search.searchSource(sourceId, query, LIMIT));
                model.addAttribute("hits", filled.hits());
                model.addAttribute("failures", filled.failures());
            } catch (IllegalArgumentException e) {
                model.addAttribute("sourceError", e.getMessage());
            }
        }
        model.addAttribute("state", state);
        return "fragments/search :: source-results";
    }

    private static String stateFor(String query) {
        if (query.isEmpty()) {
            return "empty";
        }
        if (query.length() < 2) {
            return "short";
        }
        if (query.length() > 100) {
            return "long";
        }
        return "done";
    }

    private record Filled(List<SearchHitsView> hits, List<SearchFailureView> failures) {
    }

    private static Filled fillResults(SearchOutcome outcome) {
        List<SearchHitsView> hits = outcome.hits().stream()
                .map(hit -> new SearchHitsView(hit.source().id(), hit.source().displayName(),
                        hit.items().stream().map(ContentItemView::of).toList()))
                .toList();
        List<SearchFailureView> failures = outcome.failures().stream()
                .map(failure -> new SearchFailureView(failure.source().id(), failure.source().displayName(), failure.message()))
                .toList();
        return new Filled(hits, failures);
    }
}
