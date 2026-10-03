package dev.andre.homecontrol.web;

import dev.andre.homecontrol.security.LoginBusyException;
import dev.andre.homecontrol.security.LoginContext;
import dev.andre.homecontrol.security.LoginRateLimiter;
import dev.andre.homecontrol.security.LoginService;
import dev.andre.homecontrol.security.PasswordRejectedException;
import dev.andre.homecontrol.security.WrongPasswordException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.Duration;
import java.util.Optional;

@Controller
public class LoginController {

    private static final String HOME_REDIRECT = "redirect:/";
    private static final String LOGIN = "login";
    private static final String ERROR = "error";
    private static final String LOGIN_ERROR = "loginError";
    private static final String LOGIN_MESSAGE = "loginMessage";
    private static final String SETUP_REDIRECT = "redirect:/setup";

    private final LoginService loginService;
    private final LoginRateLimiter limiter;

    public LoginController(LoginService loginService, LoginRateLimiter limiter) {
        this.loginService = loginService;
        this.limiter = limiter;
    }

    /**
     * What a page asks when its live updates stopped: the login gate answers 401 when this browser's login is gone,
     * and the request reaches this when the browser may still see the app.
     */
    @GetMapping("/session")
    public ResponseEntity<Void> session() {
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/login")
    public String page(@RequestParam(required = false) String next, LoginContext context, Model model) {
        if (!loginService.loginRequired()) {
            return HOME_REDIRECT;
        }
        // The login page is open, so the gate resumes no remembered login for it: a browser that has one goes on.
        if (context.loggedIn() || loginService.resume(context)) {
            return "redirect:" + safeNext(next);
        }
        model.addAttribute("next", safeNext(next));
        return LOGIN;
    }

    @PostMapping("/login")
    public String submit(@RequestParam(required = false) String password, @RequestParam(required = false) String next,
                         HttpServletRequest request, LoginContext context, HttpServletResponse response, Model model) {
        if (!loginService.loginRequired()) {
            return HOME_REDIRECT;
        }
        model.addAttribute("next", safeNext(next));
        String address = request.getRemoteAddr();
        Optional<Duration> blocked = limiter.reserve(address);
        if (blocked.isPresent()) {
            response.setStatus(429);
            response.setHeader("Retry-After", String.valueOf(Math.max(1, blocked.get().toSeconds())));
            model.addAttribute(ERROR, tooManyAttempts(blocked.get()));
            return LOGIN;
        }
        boolean ok;
        try {
            ok = loginService.authenticate(password, context);
        } catch (LoginBusyException e) {
            limiter.release(address);
            response.setStatus(429);
            model.addAttribute(ERROR, e.getMessage());
            return LOGIN;
        } catch (RuntimeException e) {
            limiter.release(address);
            throw e;
        }
        if (!ok) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            model.addAttribute(ERROR, "Wrong password");
            return LOGIN;
        }
        limiter.succeeded(address);
        return "redirect:" + safeNext(next);
    }

    /** A logout that could not be stored still logs this browser out, and says what is left to do. */
    @PostMapping("/logout")
    public String logout(LoginContext context, RedirectAttributes redirect) {
        if (!loginService.logout(context)) {
            redirect.addFlashAttribute(ERROR, "Logged out on this browser, but the logout could not be saved to /data:"
                    + " a copy of this login would work again after a restart. Change the password once /data can be"
                    + " written.");
        }
        return loginService.loginRequired() ? "redirect:/login" : HOME_REDIRECT;
    }

    @PostMapping("/setup/password")
    public String changePassword(@RequestParam(required = false) String current, @RequestParam(required = false) String password,
                                 @RequestParam(required = false) String confirmation, HttpServletRequest request,
                                 LoginContext context, RedirectAttributes redirect) {
        guessing(request, redirect, () -> loginService.changePassword(current, password, confirmation, context),
                "Password changed. Other browsers need to log in again.");
        return SETUP_REDIRECT;
    }

    /** No guess is involved, so it is not rate-limited. Only possible while no login exists. */
    @PostMapping("/setup/password/set")
    public String setPassword(@RequestParam(required = false) String password,
                              @RequestParam(required = false) String confirmation, LoginContext context,
                              RedirectAttributes redirect) {
        try {
            loginService.setPassword(password, confirmation, context);
            redirect.addFlashAttribute(LOGIN_MESSAGE, "Password set. Every browser now needs it to open Home Control.");
        } catch (PasswordRejectedException e) {
            redirect.addFlashAttribute(LOGIN_ERROR, e.getMessage());
        }
        return SETUP_REDIRECT;
    }

    @PostMapping("/setup/password/remove")
    public String removePassword(@RequestParam(required = false) String current, HttpServletRequest request,
                                 RedirectAttributes redirect) {
        guessing(request, redirect, () -> loginService.removePassword(current),
                "Password removed. Anyone on your network can open Home Control.");
        return SETUP_REDIRECT;
    }

    /**
     * Runs an action that checks the current password. Every checked guess counts against the address. A rejected
     * request, a busy verifier or an unexpected failure gives the reservation back.
     */
    private void guessing(HttpServletRequest request, RedirectAttributes redirect, Runnable attempt, String success) {
        String address = request.getRemoteAddr();
        Optional<Duration> blocked = limiter.reserve(address);
        if (blocked.isPresent()) {
            redirect.addFlashAttribute(LOGIN_ERROR, tooManyAttempts(blocked.get()));
            return;
        }
        try {
            attempt.run();
            limiter.succeeded(address);
            redirect.addFlashAttribute(LOGIN_MESSAGE, success);
        } catch (WrongPasswordException e) {
            redirect.addFlashAttribute(LOGIN_ERROR, e.getMessage()); // a wrong guess keeps counting
        } catch (PasswordRejectedException | LoginBusyException e) {
            limiter.release(address);
            redirect.addFlashAttribute(LOGIN_ERROR, e.getMessage());
        } catch (RuntimeException e) {
            limiter.release(address);
            throw e;
        }
    }

    private static String tooManyAttempts(Duration wait) {
        long minutes = Math.max(1, (wait.toSeconds() + 59) / 60);
        return "Too many attempts. Try again in " + minutes + (minutes == 1 ? " minute." : " minutes.");
    }

    /** Only same-application paths: no scheme-relative, backslash or header-splitting tricks. */
    static String safeNext(String next) {
        if (next == null || !next.startsWith("/") || next.startsWith("//") || next.startsWith("/\\")
                || next.chars().anyMatch(c -> c < 0x20 || c == 0x7f || c == '\\' || c == '{' || c == '}')) {
            return "/";
        }
        return next;
    }
}
