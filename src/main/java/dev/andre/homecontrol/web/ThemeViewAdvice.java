package dev.andre.homecontrol.web;

import dev.andre.homecontrol.themes.ThemeCatalog;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** Every rendered page uses the same public theme catalog, including login and the offline shell. */
@ControllerAdvice
public class ThemeViewAdvice {

    static final String RECOVERY = "/setup/appearance/recovery";
    private final ThemeCatalog themes;

    public ThemeViewAdvice(ThemeCatalog themes) {
        this.themes = themes;
    }

    @ModelAttribute
    public void appearance(HttpServletRequest request, Model model) {
        model.addAttribute("availableThemes", themes.themes());
        model.addAttribute("themeDefault", themes.require("default"));
        model.addAttribute("themeRecovery", isRecovery(request));
    }

    static boolean isRecovery(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return RECOVERY.equals(path)
                || ("/login".equals(path) && RECOVERY.equals(LoginController.safeNext(request.getParameter("next"))));
    }
}
