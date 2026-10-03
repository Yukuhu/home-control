package dev.andre.homecontrol.security;

import dev.andre.homecontrol.storage.DataDirectory;
import dev.andre.homecontrol.config.PublicAssetPaths;
import dev.andre.homecontrol.storage.SecretKeySource;
import dev.andre.homecontrol.storage.SecretStore;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;

import java.security.SecureRandom;
import java.time.Clock;

/** Secrets and login. Declared here (not as @Components) so controller test slices do not need them. */
@Configuration
public class SecurityConfiguration {

    @Bean
    public SecureRandom secureRandom() {
        return new SecureRandom();
    }

    /** Takes the data directory from its bean, which checks it before the first file in it is read. */
    @Bean
    public SecretStore secretStore(DataDirectory data, SecurityProperties security, SecureRandom random) {
        SecretKeySource keys = new SecretKeySource(security.secret(), data.resolve(DataDirectory.SECRET_KEY), random);
        SecretStore store = new SecretStore(data.resolve(DataDirectory.SECRETS), keys, random);
        LoginReset.apply(security.resetLogin(), store, data);
        return store;
    }

    @Bean
    public Argon2PasswordHasher argon2PasswordHasher(SecureRandom random) {
        return new Argon2PasswordHasher(random);
    }

    @Bean
    public LoginService loginService(SecretStore store, Argon2PasswordHasher hasher, SecureRandom random,
                                     RememberedLogins remembered) {
        LoginService login = new LoginService(store, hasher, random);
        login.rememberedBy(remembered);
        return login;
    }

    /** The cookie is Secure exactly when the session cookie is ({@code HOME_CONTROL_SECURE_COOKIE}). */
    @Bean
    public RememberedLogins rememberedLogins(DataDirectory data, SecureRandom random,
                                             @Value("${server.servlet.session.cookie.secure:false}") boolean secure) {
        return new RememberedLogins(data.resolve(DataDirectory.LOGINS), Clock.systemUTC(), random, secure);
    }

    @Bean
    public LoginRateLimiter loginRateLimiter(SecurityProperties security) {
        return new LoginRateLimiter(Clock.systemUTC(), security.loginAttemptsPerAddress(),
                security.loginAttemptsTotal(), security.loginWindow());
    }

    /** Fails startup when both the trusted proxies and Spring's forwarded-header support are set. */
    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> trustedProxies(SecurityProperties security,
                                                                                   Environment environment) {
        return new TrustedProxies(security.trustedProxies(), environment);
    }

    /** First filter of all: independent of the login gate, whether or not a login exists. */
    @Bean
    public FilterRegistrationBean<CrossOriginFilter> crossOriginFilter(SecurityProperties security) {
        FilterRegistrationBean<CrossOriginFilter> registration =
                new FilterRegistrationBean<>(new CrossOriginFilter(new CrossOriginGuard(security.trustedOrigins()),
                        new HostAllowlist(security.trustedOrigins(), security.allowedHosts())));
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    @Bean
    public FilterRegistrationBean<LoginGateFilter> loginGateFilter(LoginService login, RememberedLogins remembered,
                                                                  ObjectProvider<PublicAssetPaths> assets) {
        FilterRegistrationBean<LoginGateFilter> registration = new FilterRegistrationBean<>(
                new LoginGateFilter(login, path -> assets.stream().anyMatch(paths -> paths.contains(path)),
                        new LoginContextResolver(login, remembered)::contextOf));
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }
}
