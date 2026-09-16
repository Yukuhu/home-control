package dev.andre.homecontrol.security;

import dev.andre.homecontrol.adapters.androidtv.AndroidTvProperties;
import dev.andre.homecontrol.storage.SecretKeySource;
import dev.andre.homecontrol.storage.SecretStore;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import java.security.SecureRandom;
import java.time.Clock;

/** Secrets and login. Declared here (not as @Components) so controller test slices do not need them. */
@Configuration
public class SecurityConfiguration {

    @Bean
    public SecureRandom secureRandom() {
        return new SecureRandom();
    }

    @Bean
    public SecretStore secretStore(AndroidTvProperties storage, SecurityProperties security, SecureRandom random) {
        SecretKeySource keys = new SecretKeySource(security.secret(), storage.dataDir().resolve("secret.key"), random);
        return new SecretStore(storage.dataDir().resolve("secrets.json"), keys, random);
    }

    @Bean
    public Argon2PasswordHasher argon2PasswordHasher(SecureRandom random) {
        return new Argon2PasswordHasher(random);
    }

    @Bean
    public LoginService loginService(SecretStore store, Argon2PasswordHasher hasher, SecureRandom random) {
        return new LoginService(store, hasher, random);
    }

    @Bean
    public LoginRateLimiter loginRateLimiter(SecurityProperties security) {
        return new LoginRateLimiter(Clock.systemUTC(), security.loginAttemptsPerAddress(),
                security.loginAttemptsTotal(), security.loginWindow());
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
    public FilterRegistrationBean<LoginGateFilter> loginGateFilter(LoginService login) {
        FilterRegistrationBean<LoginGateFilter> registration = new FilterRegistrationBean<>(new LoginGateFilter(login));
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }
}
