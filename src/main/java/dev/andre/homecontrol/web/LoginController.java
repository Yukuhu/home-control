package dev.andre.homecontrol.web;

import dev.andre.homecontrol.security.LoginBusyException;
import dev.andre.homecontrol.security.LoginRateLimiter;
import dev.andre.homecontrol.security.LoginService;
import dev.andre.homecontrol.security.PasswordRejectedException;
import dev.andre.homecontrol.security.WrongPasswordException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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

    private final LoginService login;
    private final LoginRateLimiter limiter;

    public LoginController(LoginService login, LoginRateLimiter limiter) {
        this.login = login;
        this.limiter = limiter;
    }

    @GetMapping("/login")
    public String page(@RequestParam(required = false) String next, HttpServletRequest request, Model model) {
        if (!login.loginRequired()) {
            return HOME_REDIRECT;
        }
        if (login.isAuthenticated(request)) {
            return "redirect:" + safeNext(next);
        }
        model.addAttribute("next", safeNext(next));
        return LOGIN;
    }

    @PostMapping("/login")
    public String submit(@RequestParam(required = false) String password, @RequestParam(required = false) String next,
                         HttpServletRequest request, HttpServletResponse response, Model model) {
        if (!login.loginRequired()) {
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
            ok = login.authenticate(password, request);
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

    @PostMapping("/logout")
    public String logout(HttpServletRequest request) {
        login.logout(request);
        return login.loginRequired() ? "redirect:/login" : HOME_REDIRECT;
    }

    @PostMapping("/setup/password")
    public String changePassword(@RequestParam(required = false) String current, @RequestParam(required = false) String password,
                                 @RequestParam(required = false) String confirmation, HttpServletRequest request,
                                 RedirectAttributes redirect) {
        String address = request.getRemoteAddr();
        Optional<Duration> blocked = limiter.reserve(address);
        if (blocked.isPresent()) {
            redirect.addFlashAttribute(LOGIN_ERROR, tooManyAttempts(blocked.get()));
            return "redirect:/setup";
        }
        try {
            login.changePassword(current, password, confirmation, request);
            limiter.succeeded(address);
            redirect.addFlashAttribute("loginMessage", "Password changed. Other browsers need to log in again.");
        } catch (WrongPasswordException e) {
            redirect.addFlashAttribute(LOGIN_ERROR, e.getMessage()); // a wrong guess keeps counting
        } catch (PasswordRejectedException | LoginBusyException e) {
            limiter.release(address);
            redirect.addFlashAttribute(LOGIN_ERROR, e.getMessage());
        } catch (RuntimeException e) {
            limiter.release(address);
            throw e;
        }
        return "redirect:/setup";
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
