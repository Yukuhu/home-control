package dev.andre.shield;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ShieldApplication {

    public static void main(String[] args) {
        SpringApplication.run(ShieldApplication.class, args);
    }
}
