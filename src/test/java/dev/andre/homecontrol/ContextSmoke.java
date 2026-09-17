package dev.andre.homecontrol;

import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

/** Starts the whole application once and exits. Child-JVM tests run it. */
public final class ContextSmoke {

    private ContextSmoke() {
    }

    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(HomeControlApplication.class, args);
        System.out.println("CONTEXT-OK");
        context.close();
        System.exit(0);
    }
}
