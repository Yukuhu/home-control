package dev.andre.homecontrol.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/** Gives a controller parameter of type {@link LoginContext} the login of the browser behind the request. */
final class LoginContextResolver implements HandlerMethodArgumentResolver {

    private final LoginService login;
    private final RememberedLogins remembered;

    /** {@code remembered} may be null: logins then last only as long as their session. */
    LoginContextResolver(LoginService login, RememberedLogins remembered) {
        this.login = login;
        this.remembered = remembered;
    }

    /** The login of the browser behind a request; also for the login gate, which runs before any controller. */
    LoginContext contextOf(HttpServletRequest request, HttpServletResponse response) {
        return new RequestLoginContext(request, response, login, remembered);
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return LoginContext.class.equals(parameter.getParameterType());
    }

    @Override
    public LoginContext resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                        NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        return contextOf(webRequest.getNativeRequest(HttpServletRequest.class),
                webRequest.getNativeResponse(HttpServletResponse.class));
    }
}
