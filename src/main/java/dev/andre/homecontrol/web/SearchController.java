package dev.andre.homecontrol.web;

import dev.andre.homecontrol.content.SearchOutcome;
import dev.andre.homecontrol.content.SearchService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
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

    private final SearchService search;

    public SearchController(SearchService search) {
        this.search = search;
    }

    @GetMapping("/search/results")
    public String results(@RequestParam(required = false) String q, Model model) {
        String query = q == null ? "" : q.strip();
        model.addAttribute("query", query);

        String state;
        if (query.isEmpty()) {
            state = "empty";
        } else if (query.length() < 2) {
            state = "short";
        } else if (query.length() > 100) {
            state = "long";
        } else {
            state = "done";
            SearchOutcome outcome = search.search(query, LIMIT);
            model.addAttribute("hits", outcome.hits().stream()
                    .filter(hit -> !hit.items().isEmpty())
                    .map(hit -> new SearchHitsView(hit.source().id(), hit.source().displayName(),
                            hit.items().stream().map(ContentItemView::of).toList()))
                    .toList());
            model.addAttribute("failures", outcome.failures().stream()
                    .map(failure -> new SearchFailureView(failure.source().id(), failure.source().displayName(), failure.message()))
                    .toList());
        }
        model.addAttribute("state", state);
        return "fragments/search :: results";
    }
}
