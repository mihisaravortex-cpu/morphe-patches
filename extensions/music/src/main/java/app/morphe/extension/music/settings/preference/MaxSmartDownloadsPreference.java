package app.morphe.extension.music.settings.preference;

import android.app.AlertDialog;
import android.content.Context;
import android.preference.ListPreference;
import android.text.InputType;
import android.util.AttributeSet;
import android.widget.EditText;
import android.widget.Toast;

import app.morphe.extension.music.patches.smartdownloads.SmartDownloadsManager;
import app.morphe.extension.music.settings.Settings;

@SuppressWarnings({"deprecation", "unused"})
public class MaxSmartDownloadsPreference extends ListPreference {

    public MaxSmartDownloadsPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onDialogClosed(boolean positiveResult) {
        super.onDialogClosed(positiveResult);
        if (!positiveResult) return;

        String value = getValue();
        if ("custom".equals(value)) {
            showCustomInputDialog();
        } else {
            try {
                int parsed = Math.max(SmartDownloadsManager.MIN_CAPACITY, Integer.parseInt(value));
                Settings.SMART_DOWNLOADS_MAX_SONGS.save(parsed);
            } catch (NumberFormatException ignored) {
            }
        }
    }

    private void showCustomInputDialog() {
        Context context = getContext();
        EditText input = new EditText(context);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(SmartDownloadsManager.getMaxCapacity()));

        new AlertDialog.Builder(context)
                .setTitle(getTitle())
                .setMessage("Enter max cached songs (minimum 40):")
                .setView(input)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    try {
                        int val = Integer.parseInt(input.getText().toString().trim());
                        if (val < SmartDownloadsManager.MIN_CAPACITY) {
                            Toast.makeText(context, "Minimum is 40 songs", Toast.LENGTH_SHORT).show();
                            val = SmartDownloadsManager.MIN_CAPACITY;
                        }
                        Settings.SMART_DOWNLOADS_MAX_SONGS.save(val);
                        setSummary(val + " songs");
                    } catch (NumberFormatException ex) {
                        Toast.makeText(context, "Invalid number", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
