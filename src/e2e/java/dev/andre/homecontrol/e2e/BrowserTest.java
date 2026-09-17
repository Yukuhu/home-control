package dev.andre.homecontrol.e2e;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Runs the test once per browser named in -De2e.browsers (default chromium,webkit). */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@ParameterizedTest(name = "{displayName} [{0}]")
@MethodSource("dev.andre.homecontrol.e2e.Browsers#names")
public @interface BrowserTest {
}
