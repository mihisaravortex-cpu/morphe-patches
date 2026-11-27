/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.settings.preference;

import static app.morphe.extension.shared.StringRef.str;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.text.InputType;
import android.util.Pair;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Supplier;

import app.morphe.extension.music.patches.lyrics.derive.OpenAIClient;
import app.morphe.extension.music.patches.lyrics.derive.ProcessingProviders;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.StringSetting;
import app.morphe.extension.shared.settings.preference.CustomDialogListPreference;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.shared.translation.DeepLClient;
import app.morphe.extension.shared.ui.CustomDialog;
import app.morphe.extension.shared.ui.Dim;

@SuppressWarnings("unused")
final class LyricsProviderConfigDialog {

    private static final ExecutorService VALIDATION = Executors.newSingleThreadExecutor(
            runnable -> {
                final Thread thread = new Thread(runnable, "lyrics-provider-validate");
                thread.setDaemon(true);
                return thread;
            });

    private LyricsProviderConfigDialog() {
    }

    static void show(Activity context, String provider, @Nullable StringSetting prompt,
                     @Nullable StringSetting rubyPrompt, Runnable onSaved) {
        final Dialog dialog;
        try {
            dialog = switch (provider) {
                case ProcessingProviders.OPENAI_COMPATIBLE ->
                        compatibleDialog(context, prompt, rubyPrompt, onSaved, onSaved);
                case ProcessingProviders.DEEPL -> deepLDialog(context, onSaved);
                default -> null;
            };
        } catch (WindowManager.BadTokenException ex) {
            Logger.printException(() -> "Could not show the provider settings dialog", ex);
            return;
        }
        if (dialog == null) {
            return;
        }
        try {
            dialog.show();
        } catch (WindowManager.BadTokenException ex) {
            Logger.printException(() -> "Could not show the provider settings dialog", ex);
        }
    }

    static void validate(String provider, Consumer<Boolean> callback) {
        try {
            VALIDATION.execute(() -> {
                boolean valid = false;
                try {
                    switch (provider) {
                        case ProcessingProviders.OPENAI_COMPATIBLE -> valid = OpenAIClient.validateConfig(
                                Settings.LYRICS_AI_BASE_URL.get(),
                                Settings.LYRICS_AI_API_TOKEN.get(),
                                Settings.LYRICS_AI_MODEL.get());
                        case ProcessingProviders.DEEPL -> {
                            final String key = Settings.LYRICS_DEEPL_API_KEY.get();
                            valid = !key.isEmpty() && !DeepLClient.translate(
                                    Collections.singletonList("hello"), "EN", key).isEmpty();
                        }
                        default -> {
                        }
                    }
                } catch (Exception ex) {
                    Logger.printDebug(() -> "provider validation failed: " + provider, ex);
                }
                final boolean result = valid;
                Utils.runOnMainThread(() -> callback.accept(result));
            });
        } catch (Exception ex) {
            Logger.printException(() -> "provider validation failure: " + provider, ex);
            Utils.runOnMainThread(() -> callback.accept(false));
        }
    }

    static Dialog compatibleDialog(Activity context, @Nullable StringSetting prompt,
                                   @Nullable StringSetting rubyPrompt,
                                   Runnable onSaved, @Nullable Runnable onReset) {
        LinearLayout content = createContent(context);
        EditText tokenInput = addInput(content,
                "morphe_music_lyrics_ai_config_api_token_label",
                Settings.LYRICS_AI_API_TOKEN.get(), true);
        final EditText[] urlBox = new EditText[1];
        EditText modelInput = addModelInput(content, Settings.LYRICS_AI_MODEL.get(),
                () -> urlBox[0] == null ? Collections.emptyList()
                        : OpenAIClient.fetchModels(urlBox[0].getText().toString().trim(),
                                tokenInput.getText().toString().trim()));
        EditText extraHeadersInput = addInput(content,
                "morphe_music_lyrics_ai_config_extra_headers_label",
                Settings.LYRICS_AI_EXTRA_HEADERS.get(), false);
        makeMultiLine(extraHeadersInput, 2, 5);
        setHint(extraHeadersInput, "morphe_music_lyrics_ai_config_extra_headers_hint");
        EditText extraBodyInput = addInput(content,
                "morphe_music_lyrics_ai_config_extra_body_label",
                Settings.LYRICS_AI_EXTRA_BODY.get(), false);
        makeMultiLine(extraBodyInput, 2, 5);
        setHint(extraBodyInput, "morphe_music_lyrics_ai_config_extra_body_hint");
        urlBox[0] = addBaseUrlInput(content, Settings.LYRICS_AI_BASE_URL.get(),
                preset -> modelInput.setText(preset.model()));
        final EditText urlInput = urlBox[0];
        addTestButton(context, content, () -> {
            final String url = urlInput.getText().toString().trim();
            test(context, () -> OpenAIClient.validateConfig(
                    url,
                    tokenInput.getText().toString().trim(),
                    modelInput.getText().toString().trim(),
                    extraHeadersInput.getText().toString().trim(),
                    extraBodyInput.getText().toString().trim()) ? List.of("ok") : null,
                    ProcessingProviders.OPENAI_COMPATIBLE_LABEL);
        });
        addReasoningRow(content);
        final PromptField promptField = addPromptSection(content, prompt,
                "morphe_music_lyrics_ai_config_prompt_label");
        final PromptField rubyPromptField = addPromptSection(content, rubyPrompt,
                "morphe_music_lyrics_ai_config_ruby_prompt_label");

        return createDialog(context,
                ProcessingProviders.OPENAI_COMPATIBLE_LABEL,
                content,
                () -> {
                    final String extraBody = extraBodyInput.getText().toString().trim();
                    Settings.LYRICS_AI_EXTRA_BODY.save(extraBody);
                    Settings.LYRICS_AI_EXTRA_HEADERS.save(
                            extraHeadersInput.getText().toString().trim());
                    Settings.LYRICS_AI_BASE_URL.save(urlInput.getText().toString().trim());
                    Settings.LYRICS_AI_API_TOKEN.save(tokenInput.getText().toString().trim());
                    Settings.LYRICS_AI_MODEL.save(modelInput.getText().toString().trim());
                    promptField.save();
                    rubyPromptField.save();
                    onSaved.run();
                },
                () -> {
                    Settings.LYRICS_AI_BASE_URL.resetToDefault();
                    Settings.LYRICS_AI_API_TOKEN.resetToDefault();
                    Settings.LYRICS_AI_MODEL.resetToDefault();
                    Settings.LYRICS_AI_EXTRA_BODY.resetToDefault();
                    Settings.LYRICS_AI_EXTRA_HEADERS.resetToDefault();
                    urlInput.setText(Settings.LYRICS_AI_BASE_URL.get());
                    tokenInput.setText(Settings.LYRICS_AI_API_TOKEN.get());
                    modelInput.setText(Settings.LYRICS_AI_MODEL.get());
                    extraHeadersInput.setText(Settings.LYRICS_AI_EXTRA_HEADERS.get());
                    extraBodyInput.setText(Settings.LYRICS_AI_EXTRA_BODY.get());
                    promptField.reset();
                    rubyPromptField.reset();
                    if (onReset != null) {
                        onReset.run();
                    }
                },
                () -> !OpenAIClient.isValidExtraBody(
                        extraBodyInput.getText().toString().trim()),
                () -> !OpenAIClient.isValidExtraHeaders(
                        extraHeadersInput.getText().toString().trim()),
                () -> {
                    final String baseUrl = urlInput.getText().toString().trim();
                    return !baseUrl.isEmpty()
                            && !baseUrl.startsWith("http://")
                            && !baseUrl.startsWith("https://");
                });
    }

    private static final String DEEPL_DASHBOARD_URL = "https://www.deepl.com/en/pro-account";

    private static Dialog deepLDialog(Activity context, Runnable onSaved) {
        LinearLayout content = createContent(context);
        EditText keyInput = addInput(content,
                "morphe_music_lyrics_ai_config_deepl_key_label",
                Settings.LYRICS_DEEPL_API_KEY.get(), true);

        final Button getKeyButton = CustomDialog.createButton(context, null,
                str("morphe_music_token_dialog_get_token"),
                () -> ThemedInput.openInBrowser(context, DEEPL_DASHBOARD_URL, "DeepL dashboard button"),
                false, false);
        final LinearLayout.LayoutParams getKeyParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Dim.dp36);
        getKeyParams.topMargin = Dim.dp12;
        content.addView(getKeyButton, getKeyParams);

        return createDialog(context,
                ProcessingProviders.DEEPL_LABEL,
                content,
                () -> {
                    Settings.LYRICS_DEEPL_API_KEY.save(keyInput.getText().toString().trim());
                    onSaved.run();
                },
                () -> {
                    Settings.LYRICS_DEEPL_API_KEY.resetToDefault();
                    onSaved.run();
                },
                () -> keyInput.getText().toString().trim().isEmpty());
    }

    private static final class PromptField {
        @Nullable
        private final StringSetting setting;
        @Nullable
        private final EditText input;

        PromptField(@Nullable StringSetting setting, @Nullable EditText input) {
            this.setting = setting;
            this.input = input;
        }

        void save() {
            if (setting == null || input == null) {
                return;
            }
            final String text = input.getText().toString().trim();
            if (text.isEmpty()) {
                setting.resetToDefault();
            } else {
                setting.save(text);
            }
            input.setText(setting.get());
        }

        void reset() {
            if (setting == null) {
                return;
            }
            setting.resetToDefault();
            if (input != null) {
                input.setText(setting.get());
            }
        }
    }

    private static PromptField addPromptSection(LinearLayout content,
                                                @Nullable StringSetting prompt,
                                                String labelKey) {
        if (prompt == null) {
            return new PromptField(null, null);
        }
        final EditText input = addInput(content, labelKey, prompt.get(), false);
        makeMultiLine(input, 4, 8);
        content.addView(createHint(content.getContext(),
                "morphe_music_lyrics_ai_prompt_dialog_hint"));
        return new PromptField(prompt, input);
    }

    @SuppressWarnings("SameParameterValue")
    static TextView createHint(Context context, String textKey) {
        final int fg = ThemeUtils.getAppForegroundColor();
        TextView hint = new TextView(context);
        hint.setText(str(textKey));
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        hint.setTextColor((0x99 << 24) | (fg & 0x00FFFFFF));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = Dim.dp8;
        hint.setLayoutParams(params);
        return hint;
    }

    interface Validator {
        boolean invalid();
    }

    static Dialog createDialog(Activity context, String title, LinearLayout content,
                               Runnable save, Runnable reset,
                               @Nullable Validator... validators) {
        final Pair<Dialog, LinearLayout> pair = CustomDialog.create(
                context,
                title,
                null,
                null,
                str("morphe_settings_save"),
                () -> {
                    if (validators != null) {
                        for (Validator validator : validators) {
                            if (validator.invalid()) {
                                Utils
