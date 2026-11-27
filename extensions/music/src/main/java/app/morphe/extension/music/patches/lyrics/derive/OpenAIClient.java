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
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
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
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

public final class OpenAIClient {

    public static final String DEFAULT_PROMPT = """
            You are an expert Sinhala lyricist and translator. Translate every numbered lyric line below into natural, expressive Sinhala while preserving the original meaning, emotion, tone, and punctuation.
            Song: {title} - {artist}

            Step 1 — Match the song's vibe (do not output this):
            - Poetic, devotional, romantic, or emotional songs: use graceful, natural standard Sinhala.
            - Humorous, playful, dance, or item songs: use lively, natural spoken/colloquial Sinhala (e.g., උඹ, බං, යකෝ, වරෙන්, බලපන්).
            - Rap or street-style songs: use bold, rhythmic Sri Lankan street Sinhala.

            Step 2 — Translation quality rules:
            - Translate idioms, wordplay, and metaphors by their true intended meaning so it sounds like natural Sri Lankan Sinhala, never stiff word-for-word machine translation.
            - Translate embedded foreign words into natural Sinhala or Sinhala-script slang (e.g., ස්ටෝකර්, සයිකෝ).
            - For specific cultural terms, transliterate into Sinhala with a brief Sinhala meaning in parentheses on the FIRST occurrence only (e.g., වෛදේහි (සීතා දේවිය), ච්‍යවන්ප්‍රාශ් (ඉන්දියානු ආයුර්වේද ඖෂධීය ආහාරයක්)).
            - Keep repeated chorus lines consistent and rhythmic.
            - Use ONLY Sinhala script (never Singlish, Devanagari, or English letters), except for "SKIP".
            - Output "N. SKIP" for non-translatable vocalizations (e.g., hey, oh, la la).

            Step 3 — STRICT line alignment & format:
            - Input has {count} lines numbered 1 to {count}. Output ALL {count} lines using the EXACT same line number from the input—never skip, merge, or shift numbers even when lines repeat.
            - Output ONLY "N. translation". NEVER include the original lyrics, arrows (->), "Line N:", markdown, or notes.

            Example:
            If input is:
            1. आई तू इधर जी
            2. hey

            CORRECT output:
            1. උඹ මෙහෙට ආවා විතරයි
            2. SKIP

            WRONG output (NEVER do this):
            1. आई तू इधर जी -> 1. උඹ මෙහෙට ආවා විතරයි

            {lines}""";

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

    private static final String DEFAULT_GEMINI_BASE_URL =
            "https://generativelanguage.googleapis.com/v1beta";
    private static final String DEFAULT_GEMINI_MODEL = "gemini-4-hydrogen";
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int VALIDATION_TIMEOUT_MS = 20_000;
    private static final int READ_TIMEOUT_MS = 60_000;
    private static final int MAX_CHARS = 12_000;

    private static final Pattern NUMBERED_LINE_PATTERN =
            Pattern.compile("^(?:Line\\s+)?(\\d{1,4})[.)\\]:\\-]\\s*(.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern SKIP_TOKEN_PATTERN = Pattern.compile(
            "^(?:\\[SKIP]|<SKIP>|\\(SKIP\\))(?:\\s.*|\\s*[:.\\-–—|].*)?$|"
                    + "^SKIP(?:\\s*[:.\\-–—|].*)?$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PROMPT_VARIABLE = Pattern.compile("\\{(\\w+)\\}");
    private static final Pattern HAS_VERSION_PATTERN = Pattern.compile(".*/v\\d+(?:[a-z]+)?$");

    @Nullable
    private static volatile String lastError;

    private OpenAIClient() {
    }

    @Nullable
    public static String getLastError() {
        return lastError;
    }

    private static void setLastError(@Nullable String error) {
        lastError = error;
    }

    private static void showErrorToast(String prefix) {
        final String err = lastError;
        if (err == null || err.isEmpty()) {
            return;
        }
        Utils.runOnMainThread(() -> Utils.showToastShort(prefix + ": " + err));
    }

    /** Normalizes any entered Gemini URL to the native API versioned root (e.g. .../v1beta). */
    private static String geminiBaseUrl(@Nullable String baseUrl) {
        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            return DEFAULT_GEMINI_BASE_URL;
        }
        String base = baseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        for (String suffix : new String[]{"/chat/completions", "/completions", "/openai", "/models"}) {
            if (base.endsWith(suffix)) {
                base = base.substring(0, base.length() - suffix.length());
            }
        }
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.isEmpty()) {
            return DEFAULT_GEMINI_BASE_URL;
        }
        if (base.contains("generativelanguage.googleapis.com")
                && !HAS_VERSION_PATTERN.matcher(base).matches()) {
            return base + "/v1beta";
        }
        return base;
    }

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
        if (base.endsWith("/chat/completions") || base.endsWith("/completions")) {
            return base;
        }
        final int scheme = base.indexOf("://");
        final int slash = base.indexOf('/', scheme + 3);
        if (slash < 0) {
            return base + "/chat/completions";
        }
        final String path = base.substring(slash);
        if (path.isEmpty() || Pattern.compile("(?:/[^/]+)*/v\\d+(?:[a-z]+)?(?:/[^/]+)*").matcher(path).matches()) {
            return base + "/chat/completions";
        }
        return base;
    }

    private static String normalizeModel(@Nullable String model) {
        if (model == null) {
            return DEFAULT_GEMINI_MODEL;
        }
        String clean = model.trim().replaceFirst("^models/", "");
        int colon = clean.indexOf(':');
        if (colon > 0) {
            clean = clean.substring(0, colon);
        }
        return clean.isEmpty() ? DEFAULT_GEMINI_MODEL : clean;
    }

    private static String cleanApiKey(@Nullable String apiToken) {
        return apiToken == null ? "" : apiToken.replaceAll("\\s+", "");
    }

    private static String generateContentUrl(@Nullable String baseUrl, @Nullable String model) {
        return geminiBaseUrl(baseUrl) + "/models/" + normalizeModel(model) + ":generateContent";
    }

    private static String modelsListUrl(@Nullable String baseUrl) {
        return geminiBaseUrl(baseUrl) + "/models";
    }

    public static boolean validateConfig(String baseUrl, String apiToken, String model) {
        return validateConfig(baseUrl, apiToken, model,
                Settings.LYRICS_AI_EXTRA_HEADERS.get(),
                Settings.LYRICS_AI_EXTRA_BODY.get());
    }

    public static boolean validateConfig(String baseUrl, String apiToken, String model,
                                         @Nullable String extraHeaders, @Nullable String extraBody) {
        setLastError(null);
        if (cleanApiKey(apiToken).isEmpty()) {
            setLastError("Missing Gemini API Key");
            return false;
        }
        if (model == null || model.trim().isEmpty()) {
            setLastError("Missing Model Name");
            return false;
        }
        return sendGeminiRequest(baseUrl, apiToken, model, "Reply with OK",
                true, extraHeaders, extraBody) != null;
    }

    public static String[] fetchReasoningEfforts(String baseUrl, String apiToken, String model) {
        String lower = normalizeModel(model).toLowerCase(Locale.ROOT);
        if (lower.contains("hydrogen") || lower.startsWith("gemini-3")
                || lower.startsWith("gemini-4")) {
            return new String[]{"low", "medium", "high", "xhigh"};
        }
        return new String[0];
    }

    /** Fetches native Gemini models that support generateContent. */
    public static List<String> fetchModels(String baseUrl, @Nullable String apiToken) {
        setLastError(null);
        List<String> ids = new ArrayList<>();
        String cleanKey = cleanApiKey(apiToken);
        if (cleanKey.isEmpty()) {
            setLastError("Missing Gemini API Key");
            return ids;
        }
        HttpURLConnection conn = null;
        try {
            String url = modelsListUrl(baseUrl);
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(VALIDATION_TIMEOUT_MS);
            conn.setRequestProperty("x-goog-api-key", cleanKey);
            applyExtraHeaders(conn, Settings.LYRICS_AI_EXTRA_HEADERS.get());

            int code = conn.getResponseCode();
            if (code != 200) {
                setLastError(formatHttpError(code, readErrorStream(conn)));
                return ids;
            }

            JSONObject root = new JSONObject(readResponse(conn));
            JSONArray models = root.optJSONArray("models");
            if (models == null) {
                setLastError("No models returned");
                return ids;
            }
            for (int i = 0; i < models.length(); i++) {
                JSONObject m = models.optJSONObject(i);
                if (m == null) continue;
                JSONArray methods = m.optJSONArray("supportedGenerationMethods");
                if (methods != null && !supportsGenerateContent(methods)) {
                    continue;
                }
                String name = m.optString("name", "").replaceFirst("^models/", "").trim();
                if (!name.isEmpty()) {
                    ids.add(name);
                }
            }
            if (ids.isEmpty()) {
                setLastError("No compatible Gemini models found");
            }
        } catch (Exception e) {
            setLastError("Network error: " + safeMsg(e));
            Logger.printDebug(() -> "Could not fetch Gemini models", e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
        return ids;
    }

    private static boolean supportsGenerateContent(JSONArray methods) {
        for (int i = 0; i < methods.length(); i++) {
            if ("generateContent".equalsIgnoreCase(methods.optString(i, ""))) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("ConstantValue")
    public static String reasoningEffort() {
        final String configured = Settings.LYRICS_AI_REASONING_EFFORT.get();
        if (configured != null && !configured.trim().isEmpty()) {
            return configured.trim();
        }
        return "";
    }

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

    private static void applyExtraHeaders(HttpURLConnection conn, @Nullable String extra) {
        if (extra == null) return;
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
            Logger.printDebug(() -> "Invalid extra request headers JSON");
        }
    }

    private static void applyExtraBody(JSONObject body, @Nullable String extra) {
        if (extra == null) return;
        final String trimmed = extra.trim();
        if (trimmed.isEmpty()) return;

        try {
            final JSONObject overlay = new JSONObject(trimmed);
            final Iterator<String> keys = overlay.keys();
            while (keys.hasNext()) {
                final String key = keys.next();
                if (key.equals("contents")) continue;
                Object val = overlay.get(key);
                if (key.equals("generationConfig")
                        && val instanceof JSONObject extraGen
                        && body.optJSONObject("generationConfig") != null) {
                    JSONObject targetGen = body.getJSONObject("generationConfig");
                    Iterator<String> genKeys = extraGen.keys();
                    while (genKeys.hasNext()) {
                        String gk = genKeys.next();
                        targetGen.put(gk, extraGen.get(gk));
                    }
                } else {
                    body.put(key, val);
                }
            }
        } catch (JSONException e) {
            Logger.printDebug(() -> "Invalid extra request body JSON");
        }
    }

    @Nullable
    static String request(String baseUrl, String apiToken, String model, String userPrompt) {
        setLastError(null);
        return sendGeminiRequest(baseUrl, apiToken, model, userPrompt, false,
                Settings.LYRICS_AI_EXTRA_HEADERS.get(),
                Settings.LYRICS_AI_EXTRA_BODY.get());
    }

    @Nullable
    private static String sendGeminiRequest(@Nullable String baseUrl, @Nullable String apiToken,
                                            @Nullable String model, String userPrompt,
                                            boolean isValidation,
                                            @Nullable String extraHeaders,
                                            @Nullable String extraBody) {
        String cleanKey = cleanApiKey(apiToken);
        if (cleanKey.isEmpty()) {
            setLastError("Missing Gemini API Key");
            return null;
        }

        HttpURLConnection conn = null;
        try {
            String cleanModel = normalizeModel(model);
            String endpoint = generateContentUrl(baseUrl, cleanModel);

            JSONObject body = new JSONObject();
            body.put("contents", new JSONArray().put(
                    new JSONObject()
                            .put("role", "user")
                            .put("parts", new JSONArray().put(
                                    new JSONObject().put("text", userPrompt)))));

            JSONObject genConfig = new JSONObject();
            genConfig.put("maxOutputTokens", isValidation ? 512 : 8192);

            String lowerModel = cleanModel.toLowerCase(Locale.ROOT);
            if (lowerModel.contains("hydrogen")
                    || lowerModel.startsWith("gemini-3")
                    || lowerModel.startsWith("gemini-4")) {
                String level = reasoningEffort().toLowerCase(Locale.ROOT);
                if (!level.equals("low") && !level.equals("medium")
                        && !level.equals("high") && !level.equals("xhigh")) {
                    level = "low";
                }
                genConfig.put("thinkingConfig", new JSONObject().put("thinkingLevel", level));
            }
            body.put("generationConfig", genConfig);

            JSONArray safety = new JSONArray();
            for (String category : new String[]{
                    "HARM_CATEGORY_HARASSMENT",
                    "HARM_CATEGORY_HATE_SPEECH",
                    "HARM_CATEGORY_SEXUALLY_EXPLICIT",
                    "HARM_CATEGORY_DANGEROUS_CONTENT"}) {
                safety.put(new JSONObject()
                        .put("category", category)
                        .put("threshold", "BLOCK_NONE"));
            }
            body.put("safetySettings", safety);

            applyExtraBody(body, extraBody);

            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);

            conn = (HttpURLConnection) new URL(endpoint).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(isValidation ? VALIDATION_TIMEOUT_MS : READ_TIMEOUT_MS);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("x-goog-api-key", cleanKey);
            applyExtraHeaders(conn, extraHeaders);
            conn.setDoOutput(true);
            conn.setFixedLengthStreamingMode(payload.length);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload);
            }

            int code = conn.getResponseCode();
            if (code != 200) {
                String errMsg = formatHttpError(code, readErrorStream(conn));
                setLastError(errMsg);
                Logger.printDebug(() -> "Gemini HTTP error: " + errMsg);
                return null;
            }

            return parseGeminiResponse(readResponse(conn));
        } catch (Exception e) {
            setLastError("Error: " + safeMsg(e));
            Logger.printDebug(() -> "Gemini request failed", e);
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    @Nullable
    private static String parseGeminiResponse(String jsonResponse) throws JSONException {
        JSONObject json = new JSONObject(jsonResponse);

        JSONObject feedback = json.optJSONObject("promptFeedback");
        if (feedback != null) {
            String blockReason = feedback.optString("blockReason", "");
            if (!blockReason.isEmpty()) {
                setLastError("Blocked (" + blockReason + ")");
                return null;
            }
        }

        JSONArray candidates = json.optJSONArray("candidates");
        if (candidates == null || candidates.length() == 0) {
            setLastError("Empty response (no candidates)");
            return null;
        }

        JSONObject candidate = candidates.getJSONObject(0);
        String finishReason = candidate.optString("finishReason", "");

        JSONObject content = candidate.optJSONObject("content");
        if (content == null) {
            setLastError(finishReason.isEmpty()
                    ? "Empty response (no content)"
                    : "Blocked (" + finishReason + ")");
            return null;
        }

        JSONArray parts = content.optJSONArray("parts");
        if (parts == null) {
            setLastError(finishReason.isEmpty()
                    ? "Empty response (no parts)"
                    : "Blocked (" + finishReason + ")");
            return null;
        }

        StringBuilder out = new StringBuilder();
        for (int i = 0; i < parts.length(); i++) {
            JSONObject part = parts.getJSONObject(i);
            if (!part.optBoolean("thought", false)) {
                String text = part.optString("text", "");
                if (!text.isEmpty()) {
                    if (out.length() > 0) {
                        out.append('\n');
                    }
                    out.append(text);
                }
            }
        }

        if (out.length() == 0) {
            setLastError(finishReason.isEmpty()
                    ? "Empty model output"
                    : "No text (" + finishReason + ")");
            return null;
        }

        return out.toString();
    }

    private static String formatHttpError(int code, @Nullable String errBody) {
        String reason = switch (code) {
            case 400 -> "Bad request";
            case 401 -> "Unauthorized (Invalid API key)";
            case 403 -> "Forbidden (API key restricted/invalid)";
            case 404 -> "Model or URL not found";
            case 429 -> "Too many requests (Rate limit)";
            case 500 -> "Google server error";
            case 503 -> "Model overloaded / unavailable";
            default -> "HTTP error";
        };

        String detail = "";
        if (errBody != null && !errBody.isEmpty()) {
            try {
                JSONObject obj = new JSONObject(errBody);
                JSONObject errObj = obj.optJSONObject("error");
                if (errObj != null) {
                    detail = errObj.optString("message", "");
                }
            } catch (JSONException ignored) {
                detail = errBody;
            }
        }

        detail = detail.replace('\n', ' ').trim();
        if (detail.length() > 90) {
            detail = detail.substring(0, 90) + "...";
        }
        return detail.isEmpty()
                ? code + " - " + reason
                : code + " - " + reason + ": " + detail;
    }

    private static String readErrorStream(HttpURLConnection conn) {
        try {
            if (conn.getErrorStream() == null) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line);
                }
            }
            return sb.toString();
        } catch (IOException ignored) {
            return "";
        }
    }

    private static String safeMsg(Exception e) {
        String msg = e.getMessage();
        return (msg == null || msg.trim().isEmpty()) ? e.getClass().getSimpleName() : msg;
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
            if (line.isEmpty() || line.startsWith("```")) continue;
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
            if (line.isEmpty() || line.startsWith("```")) continue;
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
        setLastError(null);
        if (!fitsOneRequest(sourceLines)) {
            setLastError("Lyrics too long (>" + MAX_CHARS + " chars)");
            showErrorToast("Gemini");
            return null;
        }
        String response = request(baseUrl, apiToken, model, prompt);
        List<String> result = mapResponse(response, sourceLines);
        if (result == null) {
            if (lastError == null && response != null) {
                setLastError("Format mismatch (could not parse numbered lines)");
            }
            showErrorToast("Gemini");
        }
        return result;
    }

    static boolean fitsOneRequest(List<String> sourceLines) {
        int totalChars = 0;
        for (String line : sourceLines) {
            totalChars += line.length() + 1;
        }
        return totalChars <= MAX_CHARS;
    }

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
