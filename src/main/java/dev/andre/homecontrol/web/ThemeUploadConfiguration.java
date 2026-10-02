package dev.andre.homecontrol.web;

import jakarta.servlet.MultipartConfigElement;
import org.springframework.boot.servlet.MultipartConfigFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

/** Servlet resource limits for portable theme packages, including their local fonts and images. */
@Configuration(proxyBeanMethods = false)
public class ThemeUploadConfiguration {
    @Bean
    @SuppressWarnings("java:S5693") // The 10 MiB package contract is disk-spooled; reviews are capped at eight and expire after ten minutes.
    public MultipartConfigElement themeUploadLimits() {
        MultipartConfigFactory factory = new MultipartConfigFactory();
        factory.setMaxFileSize(DataSize.ofMegabytes(10));
        factory.setMaxRequestSize(DataSize.ofMegabytes(11));
        factory.setFileSizeThreshold(DataSize.ofBytes(0));
        return factory.createMultipartConfig();
    }
}
