package dev.andre.homecontrol.security;

import dev.andre.homecontrol.storage.DataDirectory;
import dev.andre.homecontrol.config.PublicAssetPaths;
import dev.andre.homecontrol.storage.SecretKeySource;
import dev.andre.homecontrol.storage.SecretStore;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.ObjectProvider;
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
    public FilterRegistrationBean<LoginGateFilter> loginGateFilter(LoginService login,
                                                                  ObjectProvider<PublicAssetPaths> assets) {
        FilterRegistrationBean<LoginGateFilter> registration = new FilterRegistrationBean<>(
                new LoginGateFilter(login, path -> assets.stream().anyMatch(paths -> paths.contains(path))));
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }
}
