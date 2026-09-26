package dev.andre.homecontrol.sources.sports.thesportsdb;

import dev.andre.homecontrol.core.content.ContentSourceException;
import dev.andre.homecontrol.security.LoginRequiredException;
import dev.andre.homecontrol.security.PasswordRejectedException;
import dev.andre.homecontrol.sources.sports.SportsSettings;
import dev.andre.homecontrol.sources.sports.SportsSettingsService;
import dev.andre.homecontrol.storage.StorageException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

/** Adds/removes TheSportsDB competitions, searches leagues and switches keys from the setup page. */
@Controller
@ConditionalOnProperty(name = {"home-control.sports.enabled", "home-control.sports.thesportsdb.enabled"},
        havingValue = "true", matchIfMissing = true)
public class TheSportsDbSetupController {

    private static final String MESSAGE = "sportsMessage";
    private static final String ERROR = "sportsError";
    private static final String SAVE_ERROR = "Could not save sports settings";
    private static final String REDIRECT = "redirect:/setup#sports";

    private static final Logger log = LoggerFactory.getLogger(TheSportsDbSetupController.class);

    public record LeagueView(String id, String name, String sport, String country, boolean added) {
    }

    public record SearchView(String country, String sport, List<LeagueView> leagues) {
    }

    private final SportsCompetitions competitions;
    private final SportsSettingsService settings;

    public TheSportsDbSetupController(SportsCompetitions competitions, SportsSettingsService settings) {
        this.competitions = competitions;
        this.settings = settings;
    }

    @PostMapping("/setup/sources/sports/competitions")
    public String add(@RequestParam(required = false) String leagueId, RedirectAttributes redirect) {
        try {
            SportsSettings.CompetitionEntry entry = competitions.add(leagueId);
            redirect.addFlashAttribute(MESSAGE, "Added " + entry.name());
        } catch (IllegalArgumentException | TheSportsDbException e) {
            redirect.addFlashAttribute(ERROR, e.getMessage());
        } catch (StorageException e) {
            log.warn(SAVE_ERROR, e);
            redirect.addFlashAttribute(ERROR, SAVE_ERROR);
        }
        return REDIRECT;
    }

    @PostMapping("/setup/sources/sports/competitions/{leagueId}/remove")
    public String remove(@PathVariable String leagueId, RedirectAttributes redirect) {
        try {
            SportsSettings.CompetitionEntry entry = competitions.remove(leagueId);
            redirect.addFlashAttribute(MESSAGE, "Removed " + entry.name());
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute(ERROR, e.getMessage());
        } catch (StorageException e) {
            log.warn(SAVE_ERROR, e);
            redirect.addFlashAttribute(ERROR, SAVE_ERROR);
        }
        return REDIRECT;
    }

    @PostMapping("/setup/sources/sports/thesportsdb/search")
    public String search(@RequestParam(required = false) String country, @RequestParam(required = false) String sport,
                         RedirectAttributes redirect) {
        try {
            List<League> found = competitions.search(country, sport);
            SportsSettings current = settings.current();
            List<LeagueView> views = found.stream()
                    .map(league -> new LeagueView(league.id(), league.name(), league.sport(), league.country(),
                            current.competition(league.id()).isPresent()))
                    .toList();
            redirect.addFlashAttribute("sportsSearch", new SearchView(country, sport, views));
        } catch (IllegalArgumentException | ContentSourceException e) {
            redirect.addFlashAttribute(ERROR, e.getMessage());
        } catch (StorageException e) {
            log.warn(SAVE_ERROR, e);
            redirect.addFlashAttribute(ERROR, SAVE_ERROR);
        }
        return REDIRECT;
    }

    @PostMapping("/setup/sources/sports/thesportsdb/key")
    public String key(@RequestParam(required = false) String key, @RequestParam(required = false) String loginPassword,
                      @RequestParam(required = false) String loginPasswordConfirmation,
                      HttpServletRequest request, RedirectAttributes redirect) {
        try {
            competitions.usePersonalKey(new SportsCompetitions.PersonalKey(key, loginPassword, loginPasswordConfirmation), request);
            redirect.addFlashAttribute(MESSAGE, "Using your TheSportsDB key");
        } catch (LoginRequiredException _) {
            redirect.addFlashAttribute(ERROR, "Log in again to change sources");
        } catch (IllegalArgumentException | ContentSourceException | PasswordRejectedException e) {
            redirect.addFlashAttribute(ERROR, e.getMessage());
        } catch (StorageException e) {
            log.warn(SAVE_ERROR, e);
            redirect.addFlashAttribute(ERROR, SAVE_ERROR);
        }
        return REDIRECT;
    }

    @PostMapping("/setup/sources/sports/thesportsdb/free-key")
    public String freeKey(RedirectAttributes redirect) {
        try {
            competitions.useFreeKey();
            redirect.addFlashAttribute(MESSAGE, "Using the free TheSportsDB key");
        } catch (StorageException e) {
            log.warn(SAVE_ERROR, e);
            redirect.addFlashAttribute(ERROR, SAVE_ERROR);
        }
        return REDIRECT;
    }
}
