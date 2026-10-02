package dev.andre.homecontrol;

import dev.andre.homecontrol.config.PublicAssetPaths;
import dev.andre.homecontrol.storage.DataDirectory;
import dev.andre.homecontrol.themes.ThemeCatalog;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires appearance services without coupling the security and themes packages to one another. */
@Configuration(proxyBeanMethods = false)
public class ThemeConfiguration {

    @Bean
    public ThemeCatalog themeCatalog(DataDirectory data) {
        return new ThemeCatalog(data);
    }

    @Bean
    public PublicAssetPaths themePublicAssets(ThemeCatalog themes) {
        return path -> themes.asset(path).isPresent();
    }
}
