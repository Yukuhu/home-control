package dev.andre.homecontrol.security;

import org.springframework.stereotype.Component;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/** Registers {@link LoginContextResolver}; a {@code WebMvcConfigurer}, so the web slice tests get it too. */
@Component
class LoginContextConfigurer implements WebMvcConfigurer {

    private final LoginService login;

    LoginContextConfigurer(LoginService login) {
        this.login = login;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new LoginContextResolver(login));
    }
}
