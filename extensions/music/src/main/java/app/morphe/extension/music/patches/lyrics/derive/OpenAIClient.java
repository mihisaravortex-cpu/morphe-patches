/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.derive;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.Logger;

public final class OpenAIClient {

    /**
     * The prompt template sent to the AI endpoint. One template covers both tasks;
     * {@link #renderPrompt} fills in its variables:
     * <ul>
     *   <li>{@code {task}} - "translation" or "romanization"</li>
     *   <li>{@code {language}} - the target language</li>
     *   <li>{@code {title}}, {@code {artist}} - the song</li>
     *   <li>{@code {count}} - the number of lines</li>
     *   <li>{@code {lines}} - the lyric lines, one per line</li>
     * </ul>
     * Unknown variables are left as typed.
     */
    public static final String DEFAULT_PROMPT = """
            You are a professional lyrics {task} engine. Perform {task} on every line below for {language}, keeping the original meaning, tone and punctuation.
            Song: {title} by {artist}
            
            Output format: Number every result on its own line, like:
            1. first result
            2. second result
            
            CRITICAL RULES:
            - Output exactly {count} numbered lines (same as input count)
            - Number format: "N. text" (number, period, space, text)
            - Do NOT include the original lines in your output
            - Do NOT use "Line N:" format
            - Follow the standard {language} conventions for {task}; no annotations, notes or tone marks
            - If a line already needs no {task}, output one "SKIP" for it
            - Output ONLY the {task}. No reasoning, no explanations, no step-by-step thinking.
            
            {lines}""";
    /**
     * The prompt template for the section task. Where {@link #DEFAULT_PROMPT} rewrites each line,
     * this one labels it: only the line opening a section is named, every other line is skipped,
     * so the returned list maps onto {@code LyricsLine.songPart()} without any post-processing.
     */
    public static final String SECTION_PROMPT = """
            You are a music structure analyst. Analyze the lyrics below and label the song structure.
            Song: {title} by {artist}
            
            For every line, output the section that line opens. Only the FIRST line of a section carries its name; every other line that continues the same section is output as "SKIP".
            
            Use these exact section names:
            Intro, Verse, Pre-Chorus, Chorus, Hook, Refrain, Bridge, Instrumental, Transition, False-Chorus, Outro, Reprise
            
            CRITICAL RULES:
            - Output exactly {count} numbered lines (same as input count)
            - Number format: "N. text" (number, period, space, text)
            - First line of a section: its name. All other lines: literally just "SKIP" (do NOT repeat or append the lyric text)
            - Repeated sections are numbered in order: Verse 1, Verse 2, Chorus 1, Chorus 2
            - Pick the most specific name from the list above
            - Output ONLY the labels. No reasoning, no explanations.
            
            {lines}""";
    /**
     * The prompt template for ruby. Each line comes back as it was, with the reading of every
     * kanji word in brackets right after it, which {@code RubyGenerator} then reads.
     */
    public static final String RUBY_PROMPT = """
            You are a professional reading engine. Add the {language} reading of every word written with kanji to each lyric line below.
            Song: {title} by {artist}
            
            Write the reading in square brackets right after the word, including its okurigana, like:
            1. 深く[ふかく]沈む[しずむ]
            2. 君[きみ]の名前[なまえ]
            
            CRITICAL RULES:
            - Output exactly {count} numbered lines (same as input count)
            - Number format: "N. text" (number, period, space, text)
            - Keep every line exactly as written, adding only the bracketed readings
            - Do not add readings to words written only in kana or Latin letters
            - If a line has no kanji, output one "SKIP" for it
            - Output ONLY the lines. No reasoning, no explanations.
            
            {lines}""";
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 45_000;
    private static final int MAX_CHARS = 3000;
    private static final String MODELS_PATH_SUFFIX = "/models";
    private static final String CHAT_PATH = "/chat/completions";
    private static final String COMPLETIONS_PATH = "/completions";
    /** "/v1", "/v1beta/openai", "/compatible-mode/v1" and the like. */
    private static final Pattern VERSION_SEGMENT =
            Pattern.compile("(?:/[^/]+)*/v\\d+(?:[a-z]+)?(?:/[^/]+)*");
    private static final Pattern NUMBERED_LINE_PATTERN =
            Pattern.compile("^(?:Line\\s+)?(\\d{1,4})[.)\\]:\\-]\\s*(.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern SKIP_TOKEN_PATTERN = Pattern.compile(
            "^(?:\\[SKIP]|<SKIP>|\\(SKIP\\))(?:\\s.*|\\s*[:.\\-–—|].*)?$|"
                    + "^SKIP(?:\\s*[:.\\-–—|].*)?$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PROMPT_VARIABLE = Pattern.compile("\\{(\\w+)\\}");

    private OpenAIClient() {
    }

    public static boolean validateConfig(String baseUrl, String apiToken, String model) {
        if (baseUrl == null || baseUrl.trim().isEmpty()
                || model == null || model.trim().isEmpty()) {
            return false;
        }
        if (isGeminiNative(baseUrl)) {
            return validateGemini(baseUrl, apiToken, model);
        }
        int code = postValidation(baseUrl, apiToken, model, true);
        if (code == 400) {
            // Some models refuse max_tokens, or spend it on thinking before they answer.
            // Try once more with no limit before calling the settings wrong.
            code = postValidation(baseUrl, apiToken, model, false);
        }
        return code == 200;
    }

    /** The HTTP status of a test request, or -1 when it could not be sent. */
    private static int postValidation(String baseUrl, @Nullable String apiToken, String model,
                                      boolean limitTokens) {
        HttpURLConnection conn = null;
        try {
            JSONObject body = new JSONObject();
            body.put("model", model);
            body.put("messages", new JSONArray().put(
                    new JSONObject().put("role", "user").put("content", "hi")));
            if (limitTokens) {
                body.put("max_tokens", 1);
            }
            // Validate with the same body the feature will send, so what works here works there.
            applyExtraBody(body);

            conn = (HttpURLConnection) new URL(chatUrl(baseUrl)).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("Content-Type", "application/json");
            if (apiToken != null && !apiToken.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + apiToken);
            }
            // Applied last, so user headers override defaults.
            applyExtraHeaders(conn);
            conn.setDoOutput(true);

            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(payload.length);
            try (OutputStream stream = conn.getOutputStream()) {
                stream.write(payload);
            }

            final int code = conn.getResponseCode();
            if (code != 200) {
                lastError = describeError(conn, code);
                Logger.printDebug(() -> "AI endpoint test failed: " + lastError);
            } else {
                lastError = "";
            }
            return code;
        } catch (Exception ex) {
            lastError = String.valueOf(ex);
            Logger.printDebug(() -> "Could not validate the AI endpoint", ex);
            return -1;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    public static String[] fetchReasoningEfforts(String baseUrl, String apiToken, String model) {
        if (isGeminiNative(baseUrl)) {
            return geminiReasoningEfforts(model);
        }
        final String models = listModels(baseUrl, apiToken);
        if (models == null) {
            return new String[0];
        }
        try {
            return parseSupportedEfforts(models, model);
        } catch (JSONException e) {
            Logger.printDebug(() -> "Could not read supported reasoning efforts", e);
            return new String[0];
        }
    }

    /** The ids of the models the endpoint lists, empty when it lists none. */
    public static List<String> fetchModels(String baseUrl, @Nullable String apiToken) {
        if (isGeminiNative(baseUrl)) {
            return fetchGeminiModels(baseUrl, apiToken);
        }
        List<String> ids = new ArrayList<>();
        final String models = listModels(baseUrl, apiToken);
        if (models == null) {
            return ids;
        }
        try {
            JSONArray data = new JSONObject(models).optJSONArray("data");
            if (data != null) {
                for (int i = 0; i < data.length(); i++) {
                    String id = data.getJSONObject(i).optString("id", "");
                    if (!id.isEmpty()) {
                        ids.add(id);
                    }
                }
            }
        } catch (JSONException e) {
            Logger.printDebug(() -> "Could not read the model list", e);
        }
        return ids;
    }

    /** The raw response of the endpoint's model list, or null if it has none. */
    @Nullable
    private static String listModels(String baseUrl, @Nullable String apiToken) {
        final String url = modelsUrl(chatUrl(baseUrl));
        if (url == null) {
            return null;
        }
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            try {
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(READ_TIMEOUT_MS);
                if (apiToken != null && !apiToken.isEmpty()) {
                    conn.setRequestProperty("Authorization", "Bearer " + apiToken);
                }
                applyExtraHeaders(conn);
                if (conn.getResponseCode() != 200) {
                    return null;
                }
                return readResponse(conn);
            } finally {
                conn.disconnect();
            }
        } catch (Exception e) {
            Logger.printDebug(() -> "Could not list the models of " + url, e);
            return null;
        }
    }

    /**
     * The address to call, whether the user gave the endpoint or the service root. A path ending in
     * a version segment gets the rest of the endpoint appended; any other path is left alone.
     */
    static String chatUrl(@Nullable String baseUrl) {
        if (baseUrl == null) {
            return null;
        }
        String base = baseUrl.trim();
        if (base.isEmpty()) {
            return null;
        }
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.endsWith(CHAT_PATH) || base.endsWith(COMPLETIONS_PATH)) {
            return base;
        }
        final int scheme = base.indexOf("://");
        final int slash = base.indexOf('/', scheme + 3);
        if (slash < 0) {
            return base + CHAT_PATH;
        }
        final String path = base.substring(slash);
        if (path.isEmpty() || VERSION_SEGMENT.matcher(path).matches()) {
            return base + CHAT_PATH;
        }
        return base;
    }

    private static String modelsUrl(@Nullable String chatUrl) {
        if (chatUrl == null) {
            return null;
        }
        String path = chatUrl;
        for (String suffix : new String[]{CHAT_PATH, COMPLETIONS_PATH}) {
            if (path.endsWith(suffix)) {
                path = path.substring(0, path.length() - suffix.length());
                break;
            }
        }
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path.endsWith(MODELS_PATH_SUFFIX) ? path : path + MODELS_PATH_SUFFIX;
    }

    private static String[] parseSupportedEfforts(String json, String model) throws JSONException {
        final String trimmed = json.trim();
        JSONArray entries;
        if (trimmed.startsWith("[")) {
            entries = new JSONArray(trimmed);
        } else {
            final JSONObject root = new JSONObject(trimmed);
            entries = root.optJSONArray("data");
            if (entries == null) {
                entries = root.optJSONArray("models");
            }
        }
        if (entries == null) {
            return new String[0];
        }
        for (int i = 0; i < entries.length(); i++) {
            final JSONObject entry = entries.optJSONObject(i);
            if (entry == null || !describesSameModel(entry, model)) {
                continue;
            }
            final Object reasoning = entry.opt("reasoning");
            if (!(reasoning instanceof JSONObject detail)) {
                // A plain true/false says the model reasons but not how hard, so there is nothing
                // to choose from and the parameter is not worth offering.
                return new String[0];
            }
            final JSONArray efforts = detail.optJSONArray("supported_efforts");
            if (efforts == null || efforts.length() == 0) {
                return new String[0];
            }
            final List<String> values = new ArrayList<>(efforts.length());
            for (int j = 0; j < efforts.length(); j++) {
                final String value = efforts.optString(j, "").trim();
                if (!value.isEmpty() && !values.contains(value)) {
                    values.add(value);
                }
            }
            return values.toArray(new String[0]);
        }
        return new String[0];
    }

    private static boolean describesSameModel(JSONObject entry, String model) {
        if (model == null) {
            return false;
        }
        final String wanted = model.trim();
        for (String key : new String[]{"id", "name", "canonical_slug"}) {
            final String candidate = entry.optString(key, "").trim();
            if (candidate.isEmpty()) {
                continue;
            }
            if (candidate.equalsIgnoreCase(wanted)
                    || stripVariant(candidate).equalsIgnoreCase(stripVariant(wanted))) {
                return true;
            }
        }
        final JSONArray aliases = entry.optJSONArray("aliases");
        if (aliases != null) {
            for (int i = 0; i < aliases.length(); i++) {
                if (aliases.optString(i, "").equalsIgnoreCase(wanted)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String stripVariant(String id) {
        final int colon = id.indexOf(':');
        return colon > 0 ? id.substring(0, colon) : id;
    }

    /** The stored effort, sent as typed, or empty when nothing is stored. */
    @SuppressWarnings("ConstantValue")
    public static String reasoningEffort() {
        final String configured = Settings.LYRICS_AI_REASONING_EFFORT.get();
        if (configured != null && !configured.trim().isEmpty()) {
            return configured.trim();
        }
        return "";
    }

    /** An array or a bare value has no keys to lay over a body, so neither counts as one. */
    public static boolean isValidExtraBody(@Nullable String body) {
        if (body == null) return true;
        final String trimmed = body.trim();
        if (trimmed.isEmpty()) return true;
        try {
            new JSONObject(trimmed);
            return true;
        } catch (JSONException e) {
            return false;
        }
    }

    /**
     * A header list has the same shape as a body, but a value has to fit on one line and a name
     * cannot be blank, so those are refused here rather than left to fail when a request is made.
     */
    public static boolean isValidExtraHeaders(@Nullable String headers) {
        if (headers == null) return true;
        final String trimmed = headers.trim();
        if (trimmed.isEmpty()) return true;
        try {
            final JSONObject parsed = new JSONObject(trimmed);
            final Iterator<String> keys = parsed.keys();
            while (keys.hasNext()) {
                final String key = keys.next();
                final Object value = parsed.get(key);
                if (key.trim().isEmpty() || !(value instanceof String text)
                        || text.indexOf('\r') >= 0 || text.indexOf('\n') >= 0) {
                    return false;
                }
            }
            return true;
        } catch (JSONException e) {
            return false;
        }
    }

    /**
     * Adds the headers the endpoint asks for. An empty value removes a header we would otherwise
     * send, so an endpoint that wants its own key instead of a bearer token can drop ours.
     */
    private static void applyExtraHeaders(HttpURLConnection conn) {
        final String extra = Settings.LYRICS_AI_EXTRA_HEADERS.get();
        final String trimmed = extra.trim();
        if (trimmed.isEmpty()) return;

        try {
            final JSONObject overlay = new JSONObject(trimmed);
            final Iterator<String> keys = overlay.keys();
            while (keys.hasNext()) {
                final String key = keys.next();
                final String value = overlay.optString(key, "");
                if (value.isEmpty()) {
                    conn.setRequestProperty(key, null);
                } else {
                    conn.setRequestProperty(key, value);
                }
            }
        } catch (JSONException e) {
            // A header list that will not parse is ignored; it must not take the feature down.
            Logger.printDebug(() -> "The stored extra request headers are not valid JSON");
        }
    }

    /**
     * Two keys stay ours: the model, which has a setting the endpoint was validated against, and the
     * messages, whose length decides where the answer is cut into lines.
     */
    private static void applyExtraBody(JSONObject body) {
        final String extra = Settings.LYRICS_AI_EXTRA_BODY.get();
        final String trimmed = extra.trim();
        if (trimmed.isEmpty()) return;

        try {
            final JSONObject overlay = new JSONObject(trimmed);
            final Iterator<String> keys = overlay.keys();
            while (keys.hasNext()) {
                final String key = keys.next();
                if (key.equals("model") || key.equals("messages")) continue;
                // get rather than opt, so an explicit null is sent as the null it was written as.
                body.put(key, overlay.get(key));
            }
        } catch (JSONException e) {
            // An invalid stored body is ignored; it must not take the feature down.
            Logger.printDebug(() -> "The stored extra request body is not valid JSON");
        }
    }

    @Nullable
    static String request(String baseUrl, String apiToken, String model,
                          String userPrompt) {
        if (isGeminiNative(baseUrl)) {
            return requestGemini(baseUrl, apiToken, model, userPrompt);
        }
        try {
            JSONObject body = new JSONObject();
            body.put("model", model);
            final String effort = reasoningEffort();
            if (!effort.isEmpty()) {
                body.put("reasoning_effort", effort);
            }

            JSONArray messages = new JSONArray();
            messages.put(new JSONObject()
                    .put("role", "user")
                    .put("content", userPrompt));
            body.put("messages", messages);

            // Applied last, so user body wins even where it disagrees with the effort.
            applyExtraBody(body);

            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);

            URL url = new URL(chatUrl(baseUrl));
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            try {
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(READ_TIMEOUT_MS);
                conn.setRequestProperty("Content-Type", "application/json");
                if (apiToken != null && !apiToken.isEmpty()) {
                    conn.setRequestProperty("Authorization", "Bearer " + apiToken);
                }
                applyExtraHeaders(conn);
                conn.setDoOutput(true);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(payload);
                }

                int code = conn.getResponseCode();
                if (code != 200) {
                    conn.disconnect();
                    return null;
                }

                return parseContentFromChoices(readResponse(conn));
            } finally {
                conn.disconnect();
            }
        } catch (Exception e) {
            Logger.printDebug(() -> "OpenAI request failed", e);
            return null;
        }
    }

    // ---------------------------------------------------------------------------------------
    // Gemini native API. Used instead of the OpenAI-style path when the base URL points at
    // generativelanguage.googleapis.com without "/openai" in it, so no compatibility layer sits
    // between the app and the model. Every entry point above routes here by that one test.
    // ---------------------------------------------------------------------------------------

    private static final String GEMINI_HOST = "generativelanguage.googleapis.com";
    private static final String GEMINI_API_KEY_HEADER = "x-goog-api-key";
    private static final String GEMINI_MODELS_PREFIX = "models/";
    private static final String GEMINI_DEFAULT_VERSION = "/v1beta";
    private static final Pattern GEMINI_MODEL_ID = Pattern.compile("[A-Za-z0-9._\\-]+");

    /** What the last failed test or request got back, for the log; empty after a success. */
    private static volatile String lastError = "";

    public static String lastError() {
        return lastError;
    }

    static boolean isGeminiNative(@Nullable String baseUrl) {
        if (baseUrl == null) {
            return false;
        }
        try {
            final URL url = new URL(baseUrl.trim());
            return GEMINI_HOST.equalsIgnoreCase(url.getHost())
                    && !url.getPath().contains("/openai");
        } catch (MalformedURLException e) {
            return false;
        }
    }

    /**
     * The API root, e.g. {@code https://generativelanguage.googleapis.com/v1beta}, whatever the
     * user pasted: the root itself, a models address, or a chat completions address.
     */
    static String geminiRoot(String baseUrl) {
        String base = baseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        final int pathStart = base.indexOf('/', base.indexOf("://") + 3);
        if (pathStart < 0) {
            return base + GEMINI_DEFAULT_VERSION;
        }
        String path = base.substring(pathStart);
        for (String marker : new String[]{"/models", "/chat/completions", "/completions",
                "/interactions"}) {
            final int cut = path.indexOf(marker);
            if (cut >= 0) {
                path = path.substring(0, cut);
            }
        }
        return base.substring(0, pathStart) + (path.isEmpty() ? GEMINI_DEFAULT_VERSION : path);
    }

    /** The model id without the optional "models/" prefix, or null when it is not a usable id. */
    @Nullable
    static String geminiModelId(@Nullable String model) {
        if (model == null) {
            return null;
        }
        String id = model.trim();
        if (id.startsWith(GEMINI_MODELS_PREFIX)) {
            id = id.substring(GEMINI_MODELS_PREFIX.length());
        }
        return GEMINI_MODEL_ID.matcher(id).matches() ? id : null;
    }

    /**
     * Maps the stored effort onto Gemini's thinking settings: a whole number is a token budget
     * (Gemini 2.x models), a word is a thinking level (Gemini 3 and later). Null for no effort.
     */
    @Nullable
    static JSONObject geminiThinkingConfig(@Nullable String effort) throws JSONException {
        if (effort == null || effort.trim().isEmpty()) {
            return null;
        }
        String value = effort.trim().toLowerCase(Locale.ROOT);
        if (value.matches("-?\\d+")) {
            return new JSONObject().put("thinkingBudget", Integer.parseInt(value));
        }
        if (value.equals("none") || value.equals("off")) {
            value = "minimal";
        }
        return new JSONObject().put("thinkingLevel", value);
    }

    private static String[] geminiReasoningEfforts(@Nullable String model) {
        final String id = geminiModelId(model);
        // Gemini 2.x models take a token budget, not a level; offering levels there would fail.
        if (id == null || id.toLowerCase(Locale.ROOT).startsWith("gemini-2")) {
            return new String[0];
        }
        return new String[]{"low", "medium", "high"};
    }

    private static JSONObject geminiBody(String userPrompt) throws JSONException {
        final JSONObject body = new JSONObject();
        body.put("contents", new JSONArray().put(new JSONObject()
                .put("role", "user")
                .put("parts", new JSONArray().put(new JSONObject().put("text", userPrompt)))));
        final JSONObject thinking = geminiThinkingConfig(reasoningEffort());
        if (thinking != null) {
            body.put("generationConfig", new JSONObject().put("thinkingConfig", thinking));
        }
        // Applied last, so user body wins even where it disagrees with the effort.
        applyExtraBody(body);
        return body;
    }

    private static boolean validateGemini(String baseUrl, @Nullable String apiToken, String model) {
        try {
            final String id = geminiModelId(model);
            if (id == null) {
                lastError = "Not a usable Gemini model id: " + model;
                return false;
            }
            return postGemini(geminiRoot(baseUrl) + "/models/" + id + ":generateContent",
                    apiToken, geminiBody("hi")) != null;
        } catch (Exception ex) {
            lastError = String.valueOf(ex);
            Logger.printDebug(() -> "Could not validate the Gemini endpoint", ex);
            return false;
        }
    }

    @Nullable
    private static String requestGemini(String baseUrl, @Nullable String apiToken, String model,
                                        String userPrompt) {
        try {
            final String id = geminiModelId(model);
            if (id == null) {
                lastError = "Not a usable Gemini model id: " + model;
                return null;
            }
            final String response = postGemini(
                    geminiRoot(baseUrl) + "/models/" + id + ":generateContent",
                    apiToken, geminiBody(userPrompt));
            return response == null ? null : parseGeminiContent(response);
        } catch (Exception e) {
            lastError = String.valueOf(e);
            Logger.printDebug(() -> "Gemini request failed", e);
            return null;
        }
    }

    /** The response body of a 200, or null with {@link #lastError} set. */
    @Nullable
    private static String postGemini(String url, @Nullable String apiToken, JSONObject body) {
        HttpURLConnection conn = null;
        try {
            final byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("Content-Type", "application/json");
            if (apiToken != null && !apiToken.isEmpty()) {
                conn.setRequestProperty(GEMINI_API_KEY_HEADER, apiToken);
            }
            applyExtraHeaders(conn);
            conn.setDoOutput(true);
            conn.setFixedLengthStreamingMode(payload.length);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload);
            }

            final int code = conn.getResponseCode();
            if (code != 200) {
                lastError = describeError(conn, code);
                Logger.printDebug(() -> "Gemini request failed: " + lastError);
                return null;
            }
            lastError = "";
            return readResponse(conn);
        } catch (Exception e) {
            lastError = String.valueOf(e);
            Logger.printDebug(() -> "Gemini request failed", e);
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** The visible answer. Parts marked as thoughts are the model's reasoning and are left out. */
    @Nullable
    static String parseGeminiContent(String jsonResponse) throws JSONException {
        final JSONArray candidates = new JSONObject(jsonResponse).optJSONArray("candidates");
        if (candidates == null || candidates.length() == 0) {
            return null;
        }
        final JSONObject content = candidates.getJSONObject(0).optJSONObject("content");
        if (content == null) {
            return null;
        }
        final JSONArray parts = content.optJSONArray("parts");
        if (parts == null) {
            return null;
        }
        final StringBuilder text = new StringBuilder();
        for (int i = 0; i < parts.length(); i++) {
            final JSONObject part = parts.optJSONObject(i);
            if (part == null || part.optBoolean("thought", false)) {
                continue;
            }
            text.append(part.optString("text", ""));
        }
        return text.length() == 0 ? null : text.toString();
    }

    /** The models that can generate text, as ids without the "models/" prefix. */
    private static List<String> fetchGeminiModels(String baseUrl, @Nullable String apiToken) {
        final List<String> ids = new ArrayList<>();
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(
                    geminiRoot(baseUrl) + "/models?pageSize=1000").openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            if (apiToken != null && !apiToken.isEmpty()) {
                conn.setRequestProperty(GEMINI_API_KEY_HEADER, apiToken);
            }
            applyExtraHeaders(conn);
            final int code = conn.getResponseCode();
            if (code != 200) {
                lastError = describeError(conn, code);
                return ids;
            }
            final JSONArray models = new JSONObject(readResponse(conn)).optJSONArray("models");
            if (models == null) {
                return ids;
            }
            for (int i = 0; i < models.length(); i++) {
                final JSONObject entry = models.optJSONObject(i);
                if (entry == null) {
                    continue;
                }
                String name = entry.optString("name", "");
                if (name.startsWith(GEMINI_MODELS_PREFIX)) {
                    name = name.substring(GEMINI_MODELS_PREFIX.length());
                }
                if (!name.isEmpty() && canGenerateContent(entry.optJSONArray(
                        "supportedGenerationMethods"))) {
                    ids.add(name);
                }
            }
        } catch (Exception e) {
            lastError = String.valueOf(e);
            Logger.printDebug(() -> "Could not list the Gemini models", e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
        return ids;
    }

    /** A model that does not say what it supports is kept rather than hidden. */
    private static boolean canGenerateContent(@Nullable JSONArray methods) {
        if (methods == null) {
            return true;
        }
        for (int i = 0; i < methods.length(); i++) {
            if ("generateContent".equals(methods.optString(i, ""))) {
                return true;
            }
        }
        return false;
    }

    /** "HTTP 400: ..." with the start of the error body, which says what the provider objected to. */
    private static String describeError(HttpURLConnection conn, int code) {
        final StringBuilder detail = new StringBuilder();
        try (InputStream stream = conn.getErrorStream()) {
            if (stream != null) {
                final BufferedReader reader = new BufferedReader(
                        new InputStreamReader(stream, StandardCharsets.UTF_8));
                final char[] buffer = new char[300];
                final int read = reader.read(buffer);
                if (read > 0) {
                    detail.append(buffer, 0, read);
                }
            }
        } catch (IOException ignored) {
            // The status alone still says something.
        }
        return "HTTP " + code + (detail.length() == 0 ? "" : ": " + detail.toString().trim());
    }

    static String readResponse(HttpURLConnection conn) throws IOException {
        StringBuilder sb = new StringBuilder(512);
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }
        }
        return sb.toString();
    }

    @Nullable
    private static String parseContentFromChoices(String jsonResponse) throws JSONException {
        JSONObject json = new JSONObject(jsonResponse);
        JSONArray choices = json.optJSONArray("choices");
        if (choices == null || choices.length() == 0) {
            return null;
        }

        JSONObject message = choices.getJSONObject(0).optJSONObject("message");
        if (message == null) {
            return null;
        }

        String content = message.optString("content", "");
        if (!content.isEmpty()) {
            return content;
        }

        String reasoning = message.optString("reasoning", "");
        if (!reasoning.isEmpty()) {
            String extracted = extractLastNumberedBlock(reasoning);
            return Objects.requireNonNullElse(extracted, reasoning);
        }

        return null;
    }

    @Nullable
    static String extractLastNumberedBlock(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        String[] lines = text.split("\n", -1);
        int blockEnd = -1;
        for (int i = lines.length - 1; i >= 0; i--) {
            String trimmed = lines[i].trim();
            if (trimmed.isEmpty() || trimmed.startsWith("Line")) {
                break;
            }
            if (trimmed.matches("\\d+\\..+")) {
                if (blockEnd == -1) {
                    blockEnd = i;
                }
            } else if (blockEnd != -1) {
                break;
            }
        }
        if (blockEnd == -1) {
            return null;
        }
        return joinNumberedBlock(blockEnd, lines).toString();
    }

    private static StringBuilder joinNumberedBlock(int blockEnd, String[] lines) {
        int blockStart = blockEnd;
        while (blockStart > 0 && lines[blockStart - 1].trim().matches("\\d+\\..+")) {
            blockStart--;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = blockStart; i <= blockEnd; i++) {
            String line = lines[i].trim();
            int dot = line.indexOf('.');
            if (dot >= 0 && dot + 1 < line.length()) {
                line = line.substring(dot + 1).trim();
            }
            if (Utils.isNotEmpty(sb)) {
                sb.append('\n');
            }
            sb.append(line);
        }
        return sb;
    }

    public static boolean isSkipToken(@Nullable String s) {
        if (s == null) return true;
        String t = s.trim();
        return t.isEmpty() || SKIP_TOKEN_PATTERN.matcher(t).matches();
    }

    @Nullable
    private static List<String> parseNumberedLines(String text, int expectedCount) {
        if (text == null || expectedCount <= 0) return null;
        String[] raw = text.split("\n", -1);
        int end = raw.length;
        while (end > 0 && raw[end - 1].trim().isEmpty()) {
            end--;
        }
        if (end == 0) return null;

        String[] byIndex = new String[expectedCount];
        int matchedNumberedCount = 0;
        boolean anyIndexedNonEmpty = false;

        for (int i = 0; i < end; i++) {
            String line = raw[i].trim();
            if (line.isEmpty()) continue;
            Matcher m = NUMBERED_LINE_PATTERN.matcher(line);
            if (m.matches()) {
                int idx = Integer.parseInt(Objects.requireNonNull(m.group(1))) - 1;
                if (idx >= 0 && idx < expectedCount) {
                    matchedNumberedCount++;
                    String val = Objects.requireNonNull(m.group(2)).trim();
                    if (isSkipToken(val)) {
                        byIndex[idx] = "";
                    } else {
                        byIndex[idx] = val;
                        if (!val.isEmpty()) {
                            anyIndexedNonEmpty = true;
                        }
                    }
                }
            }
        }

        if (matchedNumberedCount >= Math.max(1, expectedCount / 2)) {
            if (!anyIndexedNonEmpty) {
                return null;
            }
            List<String> indexedList = new ArrayList<>(expectedCount);
            for (int i = 0; i < expectedCount; i++) {
                indexedList.add(byIndex[i] != null ? byIndex[i] : "");
            }
            return indexedList;
        }

        List<String> list = new ArrayList<>(expectedCount);
        boolean anyNonEmpty = false;
        for (int i = 0; i < end && list.size() < expectedCount; i++) {
            String line = raw[i].trim();
            if (line.isEmpty()) continue;
            line = line.replaceFirst("^\\d+[.)\\]:\\-]\\s*", "").trim();
            if (isSkipToken(line)) {
                line = "";
            } else {
                anyNonEmpty = true;
            }
            list.add(line);
        }

        if (!anyNonEmpty) {
            return null;
        }

        while (list.size() < expectedCount) {
            list.add("");
        }
        return list;
    }

    private static boolean isNoteOrEmptyLine(String text) {
        if (text == null) return true;
        String trimmed = text.trim();
        if (trimmed.isEmpty()) return true;
        return !trimmed.matches(".*\\p{L}.*");
    }

    @Nullable
    public static List<String> mapLines(String baseUrl, String apiToken, String model,
                                        String prompt, List<String> sourceLines) {
        if (!fitsOneRequest(sourceLines)) {
            return null;
        }
        return mapResponse(request(baseUrl, apiToken, model, prompt), sourceLines);
    }

    static boolean fitsOneRequest(List<String> sourceLines) {
        int totalChars = 0;
        for (String line : sourceLines) {
            totalChars += line.length() + 1;
        }
        return totalChars <= MAX_CHARS;
    }

    /** Lines up a numbered model response with the source lines, or null if it says nothing. */
    @Nullable
    static List<String> mapResponse(@Nullable String response, List<String> sourceLines) {
        if (response == null) {
            return null;
        }
        List<String> parsed = parseNumberedLines(response, sourceLines.size());
        if (parsed == null) {
            return null;
        }
        List<String> out = new ArrayList<>(sourceLines.size());
        boolean anyValid = false;
        for (int i = 0; i < sourceLines.size(); i++) {
            String trimmed = parsed.get(i).trim();
            if (isSkipToken(trimmed) || isNoteOrEmptyLine(sourceLines.get(i))) {
                out.add("");
            } else {
                out.add(trimmed);
                anyValid = true;
            }
        }
        return anyValid ? out : null;
    }

    public static String renderPrompt(String template, String task, String language,
                                      String title, String artist, List<String> lines) {
        if (template == null || template.isEmpty()) {
            template = DEFAULT_PROMPT;
        }
        StringBuilder linesText = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            String line = Objects.requireNonNullElse(lines.get(i), "").trim();
            if (!line.matches("^\\d+\\.\\s.*")) {
                linesText.append(i + 1).append(". ");
            }
            linesText.append(line.isEmpty() ? "SKIP" : line).append('\n');
        }
        String count = String.valueOf(lines.size());
        Matcher matcher = PROMPT_VARIABLE.matcher(template);

        // Use StringBuffer instead of StringBuilder for Matcher.appendReplacement
        // to maintain compatibility with API levels below 34.
        @SuppressWarnings("StringBufferMayBeStringBuilder")
        StringBuffer out = new StringBuffer(template.length() + 256);
        while (matcher.find()) {
            String name = matcher.group(1);
            String value = promptValue(name, task, language, title, artist, count,
                    linesText.toString());
            matcher.appendReplacement(out,
                    Matcher.quoteReplacement(Objects.requireNonNullElse(value, matcher.group())));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    @Nullable
    private static String promptValue(String name, String task, String language,
                                      String title, String artist, String count, String lines) {
        if ("task".equals(name)) {
            return task;
        }
        if ("language".equals(name)) {
            return language;
        }
        if ("title".equals(name)) {
            return Objects.requireNonNullElse(title, "");
        }
        if ("artist".equals(name)) {
            return Objects.requireNonNullElse(artist, "");
        }
        if ("count".equals(name)) {
            return count;
        }
        if ("lines".equals(name)) {
            return lines;
        }
        return null;
    }
}
