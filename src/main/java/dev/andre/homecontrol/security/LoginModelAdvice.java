package dev.andre.homecontrol.security;

import dev.andre.homecontrol.web.SetupController;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.util.List;

/** Tells the setup page's Account section whether a login exists and which accounts need it. */
@ControllerAdvice(assignableTypes = SetupController.class)
public class LoginModelAdvice {

    private final ObjectProvider<LoginService> login;

    public LoginModelAdvice(ObjectProvider<LoginService> login) {
        this.login = login;
    }

    @ModelAttribute("loginRequired")
    public boolean loginRequired() {
        LoginService service = login.getIfAvailable();
        return service != null && service.loginRequired();
    }

    @ModelAttribute("connectedAccounts")
    public List<String> connectedAccounts() {
        LoginService service = login.getIfAvailable();
        return service == null ? List.of() : service.connectedAccounts();
    }
}
