package dev.andre.homecontrol;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class HomeControlApplication {

    public static void main(String[] args) {
        SpringApplication.run(HomeControlApplication.class, args);
    }
}
