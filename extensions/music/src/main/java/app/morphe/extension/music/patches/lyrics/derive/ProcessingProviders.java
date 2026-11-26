/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3877
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.derive;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Utils;

/**
 * Translation and romanization provider ids, and the AI request path they share. Provider
 * settings are comma separated priority lists; a leading {@code -} disables an entry.
 */
public final class ProcessingProviders {

    /** Translation and romanization already included in the lyrics source. */
    public static final String EMBEDDED = "embedded";
    public static final String GOOGLE = "google";
    public static final String LOCAL = "local";
    /** The Rime word tables, the user's own lists included. Named "Rime Wordlist" in the settings. */
    public static final String RIME = "rime";
    /** The system ICU transliterator, a fallback that needs no download. Named "ICU Transliterator". */
    public static final String ICU = "icu";
    public static final String OPENAI_COMPATIBLE = "openai_compatible";
    public static final String DEEPL = "deepl";

    /** Brand names, kept out of the string resources so no translation can restate them. */
    public static final String EMBEDDED_LABEL = "Embedded";
    public static final String GOOGLE_LABEL = "Google Translate";
    public static final String OPENAI_COMPATIBLE_LABEL = "Google Gemini";
    public static final String DEEPL_LABEL = "DeepL";

    public static final List<String> TRANSLATION_ORDER =
            List.of(EMBEDDED, GOOGLE, OPENAI_COMPATIBLE, DEEPL);
    public static final List<String> ROMANIZATION_ORDER =
            List.of(EMBEDDED, GOOGLE, LOCAL, RIME, ICU, OPENAI_COMPATIBLE);

    public static final String DEFAULT_TRANSLATION_ORDER =
            "embedded,google,-openai_compatible,-deepl";
    public static final String DEFAULT_ROMANIZATION_ORDER =
            "embedded,google,-local,-rime,icu,-openai_compatible";

    /** Native Google Gemini endpoints offered by name. */
    public static final List<EndpointPreset> ENDPOINT_PRESETS = List.of(
            EndpointPreset.of("Google Gemini (Hydrogen)",
                    "https://generativelanguage.googleapis.com/v1beta",
                    "gemini-4-hydrogen"),
            EndpointPreset.of("Google Gemini (2.5 Flash)",
                    "https://generativelanguage.googleapis.com/v1beta",
                    "gemini-2.5-flash"),
            EndpointPreset.of("Google Gemini (2.5 Pro)",
                    "https://generativelanguage.googleapis.com/v1beta",
                    "gemini-2.5-pro"));

    private ProcessingProviders() {
    }

    /** Parse the stored list; a provider missing from it is enabled by default. */
    public static List<ProviderEntry> parseOrder(@Nullable String stored, List<String> known) {
        List<ProviderEntry> entries = new ArrayList<>(known.size());
        Set<String> seen = new HashSet<>(known.size());

        if (stored != null) {
            for (String raw : stored.split(",")) {
                String token = raw.trim();
                if (token.isEmpty()) {
                    continue;
                }
                boolean enabled = true;
                if (token.startsWith("-")) {
                    enabled = false;
                    token = token.substring(1).trim();
                }
                if (token.isEmpty() || !known.contains(token) || !seen.add(token)) {
                    continue;
                }
                entries.add(new ProviderEntry(token, enabled));
            }
        }

        for (String id : known) {
            if (!seen.contains(id)) {
                if (EMBEDDED.equals(id)) {
                    entries.add(0, new ProviderEntry(id, true));
                } else if (RIME.equals(id)) {
                    entries.add(afterLocal(entries), new ProviderEntry(id, false));
                } else {
                    entries.add(new ProviderEntry(id, true));
                }
            }
        }
        return entries;
    }

    private static int afterLocal(List<ProviderEntry> entries) {
        for (int i = 0; i < entries.size(); i++) {
            if (LOCAL.equals(entries.get(i).id())) {
                return i + 1;
            }
        }
        return entries.size();
    }

    public static List<String> enabledProviders(@Nullable String stored, List<String> known) {
        List<String> enabled = new ArrayList<>();
        for (ProviderEntry entry : parseOrder(stored, known)) {
            if (entry.enabled()) {
                enabled.add(entry.id());
            }
        }
        return enabled;
    }

    public static String serializeOrder(List<ProviderEntry> entries) {
        StringBuilder sb = new StringBuilder();
        for (ProviderEntry entry : entries) {
            if (Utils.isNotEmpty(sb)) {
                sb.append(',');
            }
            if (!entry.enabled()) {
                sb.append('-');
            }
            sb.append(entry.id());
        }
        return sb.toString();
    }

    public static String withProviderEnabled(@Nullable String stored, List<String> known,
                                             String id, boolean enabled) {
        final List<ProviderEntry> entries = parseOrder(stored, known);
        boolean changed = false;
        final List<ProviderEntry> updated = new ArrayList<>(entries.size());
        for (ProviderEntry entry : entries) {
            if (entry.id().equals(id) && entry.enabled() != enabled) {
                updated.add(new ProviderEntry(id, enabled));
                changed = true;
            } else {
                updated.add(entry);
            }
        }
        return changed ? serializeOrder(updated) : stored;
    }

    public static boolean isAi(@Nullable String provider) {
        return OPENAI_COMPATIBLE.equals(provider);
    }

    @Nullable
    public static Result mapLines(String provider, String prompt, List<String> lines) {
        if (!OPENAI_COMPATIBLE.equals(provider)) {
            return null;
        }
        final String model = Settings.LYRICS_AI_MODEL.get();
        final List<String> mapped = OpenAIClient.mapLines(Settings.LYRICS_AI_BASE_URL.get(),
                Settings.LYRICS_AI_API_TOKEN.get(), model, prompt, lines);
        return mapped == null ? null : new Result(mapped, model);
    }

    public record ProviderEntry(String id, boolean enabled) {
    }

    public record EndpointPreset(String label, String baseUrl, String model) {
        public static EndpointPreset of(String label, String baseUrl) {
            return new EndpointPreset(label, OpenAIClient.chatUrl(baseUrl), "");
        }

        public static EndpointPreset of(String label, String baseUrl, String model) {
            return new EndpointPreset(label, OpenAIClient.chatUrl(baseUrl), model);
        }
    }

    public record Result(List<String> lines, String model) {
    }
}
