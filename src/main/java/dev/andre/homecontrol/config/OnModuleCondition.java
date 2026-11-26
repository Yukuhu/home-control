package dev.andre.homecontrol.config;

import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * A module is on when {@code home-control.<segment>.enabled} is {@code true} (in any case), or is unset and the
 * module is on by default, and its parent is on too. Any other value switches it off, as the
 * {@code @ConditionalOnProperty(havingValue = "true")} it replaces did.
 */
public class OnModuleCondition extends SpringBootCondition {

    /** Whether {@code module} is on by this rule, for code that must agree with {@link ConditionalOnModule}. */
    public static boolean isOn(Environment environment, Module module) {
        for (Module each = module; each != null; each = each.parent().orElse(null)) {
            if (!enabled(environment, each)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Module module = metadata.getAnnotations().get(ConditionalOnModule.class).getEnum("value", Module.class);
        for (Module each = module; each != null; each = each.parent().orElse(null)) {
            if (!enabled(context.getEnvironment(), each)) {
                return ConditionOutcome.noMatch("module " + each.segment() + " is off (" + each.property() + ")");
            }
        }
        return ConditionOutcome.match("module " + module.segment() + " is on");
    }

    private static boolean enabled(Environment environment, Module module) {
        String value = environment.getProperty(module.property());
        return value == null ? module.enabledByDefault() : "true".equalsIgnoreCase(value);
    }
}
