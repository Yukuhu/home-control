package dev.andre.homecontrol.core.playback;

import dev.andre.homecontrol.core.Capability;

import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Predicate;

/** Routes the first reference of one type that passes {@code accepts}, on a device that has {@code needs}. */
public record RefStrategy<R extends PlayableRef>(Rung rung, Capability needs, Class<R> type, Predicate<R> accepts,
                                                 BiFunction<R, ContentItem, Route> toRoute) implements RouteStrategy {

    public static <R extends PlayableRef> RefStrategy<R> of(Rung rung, Capability needs, Class<R> type,
                                                            BiFunction<R, ContentItem, Route> toRoute) {
        return new RefStrategy<>(rung, needs, type, ref -> true, toRoute);
    }

    @Override
    public Optional<Route> route(ContentItem item, Set<Capability> capabilities) {
        if (!capabilities.contains(needs)) {
            return Optional.empty();
        }
        return item.playables().stream().filter(type::isInstance).map(type::cast).filter(accepts).findFirst()
                .map(ref -> toRoute.apply(ref, item));
    }
}
