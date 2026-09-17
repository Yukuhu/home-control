package dev.andre.homecontrol.sources.tmdb;

import dev.andre.homecontrol.core.content.StreamingProviders;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Maps a TMDB watch provider to one of Home Control's streaming-service keys ({@link StreamingProviders#KNOWN}),
 * first by TMDB provider id, then by a normalised-name rule. A provider whose normalised name mentions
 * a "channel" add-on never matches by name — TMDB lists those as separate provider entries with their
 * own (unconfirmed) ids, and a name match alone would wrongly count them as the base subscription.
 */
public final class ProviderMatcher {

    private static final Map<String, Predicate<String>> NAME_RULES = nameRules();

    private final Map<String, Set<Integer>> ids;

    public ProviderMatcher(Map<String, List<Integer>> providerIds) {
        Map<String, Set<Integer>> copy = new LinkedHashMap<>();
        if (providerIds != null) {
            providerIds.forEach((key, values) -> copy.put(key, values == null ? Set.of() : Set.copyOf(values)));
        }
        this.ids = copy;
    }

    public Optional<String> keyOf(WatchProvider provider) {
        for (String key : StreamingProviders.KNOWN.keySet()) {
            Set<Integer> configuredIds = ids.get(key);
            if (configuredIds != null && configuredIds.contains(provider.id())) {
                return Optional.of(key);
            }
        }
        String normalised = normalise(provider.name());
        if (normalised.contains("channel")) {
            return Optional.empty();
        }
        for (String key : StreamingProviders.KNOWN.keySet()) {
            Predicate<String> rule = NAME_RULES.get(key);
            if (rule != null && rule.test(normalised)) {
                return Optional.of(key);
            }
        }
        return Optional.empty();
    }

    /** The configured keys, in configured order, that some subscription-category provider matched. */
    public List<String> matches(List<WatchProvider> providers, List<String> configuredKeys) {
        Set<String> subscribed = new HashSet<>();
        for (WatchProvider provider : providers) {
            if (provider.category().subscription()) {
                keyOf(provider).ifPresent(subscribed::add);
            }
        }
        List<String> result = new ArrayList<>();
        for (String key : configuredKeys) {
            if (subscribed.contains(key)) {
                result.add(key);
            }
        }
        return result;
    }

    static String normalise(String name) {
        String replaced = name.toLowerCase(Locale.ROOT).replace("+", "plus");
        StringBuilder result = new StringBuilder(replaced.length());
        for (int i = 0; i < replaced.length(); i++) {
            char c = replaced.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                result.append(c);
            }
        }
        return result.toString();
    }

    private static Map<String, Predicate<String>> nameRules() {
        Map<String, Predicate<String>> rules = new LinkedHashMap<>();
        rules.put("netflix", n -> n.startsWith("netflix"));
        rules.put("primevideo", n -> n.startsWith("amazonprimevideo"));
        rules.put("dazn", n -> n.startsWith("dazn"));
        rules.put("disneyplus", n -> n.startsWith("disneyplus"));
        rules.put("appletvplus", n -> n.startsWith("appletvplus"));
        rules.put("paramountplus", n -> n.startsWith("paramountplus"));
        rules.put("wowtv", n -> n.equals("wow") || n.startsWith("wowtv"));
        rules.put("joyn", n -> n.startsWith("joyn"));
        rules.put("rtlplus", n -> n.startsWith("rtlplus"));
        return rules;
    }
}
