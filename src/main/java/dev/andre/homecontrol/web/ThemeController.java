package dev.andre.homecontrol.web;

import dev.andre.homecontrol.security.LoginContext;
import dev.andre.homecontrol.themes.ThemeCatalog;
import dev.andre.homecontrol.themes.ThemeDescriptor;
import dev.andre.homecontrol.themes.ThemeException;
import dev.andre.homecontrol.themes.ThemePackage;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Theme management; a confirmation is bound to its browser and the revision shown at review. */
@Controller
public class ThemeController {

    private static final String PAGE = "appearance";
    private static final String REDIRECT = "redirect:/setup/appearance";
    private static final int MAX_UPLOAD = 10 * 1024 * 1024;
    private static final int MAX_REVIEWS = 8;
    private final ThemeCatalog themes;
    // Bound retained uploads across all browsers, including abandoned sessions.
    private final Map<String, Pending> reviews = new LinkedHashMap<>();

    public ThemeController(ThemeCatalog themes) {
        this.themes = themes;
    }

    @GetMapping({"/setup/appearance", ThemeViewAdvice.RECOVERY})
    public String page(HttpServletRequest request, Model model) {
        expire();
        return render(model);
    }

    @PostMapping("/setup/appearance/preview")
    public String preview(@RequestParam("package") MultipartFile upload, LoginContext login,
                          HttpServletRequest request, Model model) throws IOException {
        login.requireLogin();
        if (upload.getSize() > MAX_UPLOAD) {
            throw new ThemeException(413, "Theme ZIP must be no larger than 10 MiB.");
        }
        byte[] bytes;
        try (var input = upload.getInputStream()) {
            bytes = input.readNBytes(MAX_UPLOAD + 1);
        }
        if (bytes.length > MAX_UPLOAD) {
            throw new ThemeException(413, "Theme ZIP must be no larger than 10 MiB.");
        }
        ThemePackage candidate = themes.inspect(bytes);
        ThemeDescriptor previous = themes.themes().stream()
                .filter(theme -> theme.id().equals(candidate.manifest().id())).findFirst().orElse(null);
        Pending pending = new Pending(UUID.randomUUID().toString(), bytes,
                previous == null ? null : previous.revision(), Instant.now().plusSeconds(600));
        remember(request.getSession(), pending);
        model.addAttribute("importManifest", candidate.manifest());
        model.addAttribute("importToken", pending.token());
        model.addAttribute("previousTheme", previous);
        // A validated PNG can be shown without publishing the candidate's CSS or any public asset URLs.
        byte[] preview = candidate.files().get("preview.png");
        if (preview != null) {
            model.addAttribute("importPreview", "data:image/png;base64," + Base64.getEncoder().encodeToString(preview));
        }
        model.addAttribute("importColour", candidate.descriptor(false).themeColor());
        return render(model);
    }

    @PostMapping("/setup/appearance/install")
    public String install(@RequestParam String token, LoginContext login, HttpServletRequest request,
                          RedirectAttributes redirect) {
        login.requireLogin();
        HttpSession session = request.getSession(false);
        Pending pending = consume(session, token);
        ThemeDescriptor installed = themes.install(pending.bytes(), pending.previousRevision());
        redirect.addFlashAttribute("themeMessage", installed.name() + " is installed. Choose it to use it in this browser.");
        return REDIRECT;
    }

    @PostMapping("/setup/appearance/{id}/remove")
    public String remove(@PathVariable String id, LoginContext login,
                         @RequestParam(defaultValue = "false") boolean recovery, RedirectAttributes redirect) {
        login.requireLogin();
        themes.remove(id);
        redirect.addFlashAttribute("themeMessage", "Theme removed.");
        return recovery ? "redirect:" + ThemeViewAdvice.RECOVERY : REDIRECT;
    }

    @GetMapping("/setup/appearance/{id}/export")
    public ResponseEntity<byte[]> export(@PathVariable String id, LoginContext login) {
        login.requireLogin();
        byte[] bytes = themes.export(id);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip"))
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(id + ".zip").build().toString())
                .body(bytes);
    }

    @ExceptionHandler(ThemeException.class)
    public String invalid(ThemeException failure, HttpServletResponse response, Model model) {
        response.setStatus(failure.status());
        model.addAttribute("themeError", failure.getMessage());
        return render(model);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public String oversized(HttpServletResponse response, Model model) {
        response.setStatus(413);
        model.addAttribute("themeError", "Theme ZIP must be no larger than 10 MiB.");
        return render(model);
    }

    private String render(Model model) {
        model.addAttribute("availableThemes", themes.themes());
        model.addAttribute("themeDefault", themes.require("default"));
        model.addAttribute("themeProblems", themes.problems());
        return PAGE;
    }

    private synchronized Pending consume(HttpSession session, String token) {
        expire();
        if (session != null) {
            Pending pending = reviews.get(session.getId());
            if (pending != null && pending.token().equals(token)) {
                reviews.remove(session.getId());
                return pending;
            }
        }
        throw new ThemeException(400, "Review the theme ZIP again before installing it.");
    }

    private synchronized void remember(HttpSession session, Pending pending) {
        expire();
        reviews.remove(session.getId());
        if (reviews.size() >= MAX_REVIEWS) {
            reviews.remove(reviews.keySet().iterator().next());
        }
        reviews.put(session.getId(), pending);
    }

    private synchronized void expire() {
        Instant now = Instant.now();
        reviews.values().removeIf(pending -> !pending.expires().isAfter(now));
    }

    private static final class Pending {
        private final String token;
        private final byte[] bytes;
        private final String previousRevision;
        private final Instant expires;

        private Pending(String token, byte[] bytes, String previousRevision, Instant expires) {
            this.token = token;
            this.bytes = bytes;
            this.previousRevision = previousRevision;
            this.expires = expires;
        }

        private String token() { return token; }
        private byte[] bytes() { return bytes; }
        private String previousRevision() { return previousRevision; }
        private Instant expires() { return expires; }
    }
}
