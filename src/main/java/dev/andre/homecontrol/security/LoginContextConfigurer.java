package dev.andre.homecontrol.security;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/** Registers {@link LoginContextResolver}; a {@code WebMvcConfigurer}, so the web slice tests get it too. */
@Component
class LoginContextConfigurer implements WebMvcConfigurer {

    private final LoginService login;
    private final RememberedLogins remembered;

    /** The web slice tests have no {@link RememberedLogins}; their logins last as long as their session. */
    LoginContextConfigurer(LoginService login, ObjectProvider<RememberedLogins> remembered) {
        this.login = login;
        this.remembered = remembered.getIfAvailable();
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new LoginContextResolver(login, remembered));
    }
}
