package dev.andre.homecontrol.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/** Gives a controller parameter of type {@link LoginContext} the login of the browser behind the request. */
final class LoginContextResolver implements HandlerMethodArgumentResolver {

    private final LoginService login;

    LoginContextResolver(LoginService login) {
        this.login = login;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return LoginContext.class.equals(parameter.getParameterType());
    }

    @Override
    public LoginContext resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                        NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        return new RequestLoginContext(webRequest.getNativeRequest(HttpServletRequest.class), login);
    }
}
