package dev.andre.homecontrol.core.content;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A household's choices about which rails and sources it wants, and which streaming services it
 * subscribes to (spec D4). Stored verbatim in {@code sources.json}'s {@code preferences} object.
 *
 * <p>{@code locale}/{@code region} may be {@code null} here only while reading a stored document
 * that predates them; {@link dev.andre.homecontrol.content.SourcePreferencesService} fills the
 * property defaults in before anyone sees a {@code SourcePreferences} with a null locale/region.
 * {@link #withLocale} always requires both to be non-null.
 */
public record SourcePreferences(List<String> railOrder, Set<String> hiddenRails, Set<String> disabledSources,
                                Map<String, Integer> refreshMinutes, String locale, String region,
                                List<String> providers) {

    private static final Pattern RAIL_KEY = Pattern.compile("^[a-z0-9][a-z0-9._-]{0,63}/[a-z0-9][a-z0-9._-]{0,63}$");
    private static final Pattern SOURCE_ID = Pattern.compile("^[a-z0-9][a-z0-9._-]{0,63}$");
    private static final Pattern REGION = Pattern.compile("^[A-Z]{2}$");
    private static final int MAX_RAIL_ORDER = 200;

    public SourcePreferences {
        railOrder = dedupeOrdered(railOrder);
        if (railOrder.size() > MAX_RAIL_ORDER) {
            throw new IllegalArgumentException("At most " + MAX_RAIL_ORDER + " rails can be ordered");
        }
        railOrder.forEach(SourcePreferences::requireRailKey);

        hiddenRails = unmodifiableOrderedSet(hiddenRails);
        hiddenRails.forEach(SourcePreferences::requireRailKey);

        disabledSources = unmodifiableOrderedSet(disabledSources);
        disabledSources.forEach(SourcePreferences::requireSourceId);

        refreshMinutes = refreshMinutes == null ? Map.of() : Map.copyOf(refreshMinutes);
        refreshMinutes.forEach((sourceId, minutes) -> {
            requireSourceId(sourceId);
            if (minutes == null || minutes < 1 || minutes > 1440) {
                throw new IllegalArgumentException("Refresh every 1 to 1440 minutes");
            }
        });

        if (locale != null) {
            requireLocale(locale);
        }
        if (region != null && !REGION.matcher(region).matches()) {
            throw new IllegalArgumentException("Use a two-letter country code such as DE");
        }

        providers = dedupeOrdered(providers);
        providers.forEach(key -> {
            if (!StreamingProviders.KNOWN.containsKey(key)) {
                throw new IllegalArgumentException("Unknown streaming service " + key);
            }
        });
    }

    public static SourcePreferences defaults(String locale, String region) {
        return new SourcePreferences(List.of(), Set.of(), Set.of(), Map.of(), locale, region, List.of());
    }

    public SourcePreferences withRailOrder(List<String> railOrder) {
        return new SourcePreferences(railOrder, hiddenRails, disabledSources, refreshMinutes, locale, region, providers);
    }

    public SourcePreferences withRailVisible(String railKey, boolean visible) {
        Set<String> next = new LinkedHashSet<>(hiddenRails);
        if (visible) {
            next.remove(railKey);
        } else {
            next.add(railKey);
        }
        return new SourcePreferences(railOrder, next, disabledSources, refreshMinutes, locale, region, providers);
    }

    public SourcePreferences withSourceEnabled(String sourceId, boolean enabled) {
        Set<String> next = new LinkedHashSet<>(disabledSources);
        if (enabled) {
            next.remove(sourceId);
        } else {
            next.add(sourceId);
        }
        return new SourcePreferences(railOrder, hiddenRails, next, refreshMinutes, locale, region, providers);
    }

    /** {@code minutesOrNull == null} removes the stored override, falling back to the source's default. */
    public SourcePreferences withRefreshMinutes(String sourceId, Integer minutesOrNull) {
        Map<String, Integer> next = new LinkedHashMap<>(refreshMinutes);
        if (minutesOrNull == null) {
            next.remove(sourceId);
        } else {
            next.put(sourceId, minutesOrNull);
        }
        return new SourcePreferences(railOrder, hiddenRails, disabledSources, next, locale, region, providers);
    }

    public SourcePreferences withLocale(String locale, String region, List<String> providers) {
        Objects.requireNonNull(locale, "locale");
        Objects.requireNonNull(region, "region");
        return new SourcePreferences(railOrder, hiddenRails, disabledSources, refreshMinutes, locale, region, providers);
    }

    private static void requireRailKey(String key) {
        if (key == null || !RAIL_KEY.matcher(key).matches()) {
            throw new IllegalArgumentException(
                    "A rail key must look like source/rail (lowercase, digits, '.', '_' or '-'): " + key);
        }
    }

    private static void requireSourceId(String id) {
        if (id == null || !SOURCE_ID.matcher(id).matches()) {
            throw new IllegalArgumentException(
                    "A source id must be lowercase letters, digits, '.', '_' or '-': " + id);
        }
    }

    /**
     * {@code Locale.forLanguageTag} only checks BCP-47 syntax, so it happily round-trips words
     * that are not language codes at all (e.g. "english"). We additionally require the primary
     * subtag to be a real ISO 639 language code, which is what actually distinguishes "de-DE"
     * from a typo.
     */
    private static void requireLocale(String locale) {
        Locale parsed = Locale.forLanguageTag(locale);
        String canonical = parsed.toLanguageTag();
        boolean knownLanguage = Arrays.asList(Locale.getISOLanguages()).contains(parsed.getLanguage());
        if (!canonical.equals(locale) || canonical.equals("und") || !knownLanguage) {
            throw new IllegalArgumentException("Use a language tag such as de-DE");
        }
    }

    private static List<String> dedupeOrdered(Collection<String> values) {
        return List.copyOf(new LinkedHashSet<>(values == null ? List.of() : values));
    }

    private static Set<String> unmodifiableOrderedSet(Collection<String> values) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(values == null ? Set.of() : values));
    }
}
