package dev.andre.homecontrol.web;

import dev.andre.homecontrol.content.RailCache;
import dev.andre.homecontrol.content.RailSnapshot;
import dev.andre.homecontrol.core.content.ContentSources;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

/** Renders rails and their tiles as HTML fragments; the browser swaps them in via htmx. */
@Controller
public class RailController {

    private final RailCache rails;
    private final ContentSources sources;

    public RailController(RailCache rails, ContentSources sources) {
        this.rails = rails;
        this.sources = sources;
    }

    @GetMapping("/rails")
    public String rails(Model model) {
        model.addAttribute("rails", rails.snapshots().stream().map(s -> RailView.of(s, sources)).toList());
        model.addAttribute("hasSources", !sources.all().isEmpty());
        return "fragments/rails :: rails";
    }

    @GetMapping("/rails/{sourceId}/{railId}")
    public String rail(@PathVariable String sourceId, @PathVariable String railId, Model model) {
        return render(rails.snapshot(sourceId, railId), model);
    }

    @PostMapping("/rails/{sourceId}/{railId}/refresh")
    public String refresh(@PathVariable String sourceId, @PathVariable String railId, Model model) {
        return render(rails.refresh(sourceId, railId), model);
    }

    private String render(Optional<RailSnapshot> snapshot, Model model) {
        RailSnapshot found = snapshot.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such rail"));
        model.addAttribute("rail", RailView.of(found, sources));
        return "fragments/rails :: rail";
    }
}
