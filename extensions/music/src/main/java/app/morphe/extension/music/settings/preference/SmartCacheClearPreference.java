package app.morphe.extension.music.settings.preference;

import android.app.AlertDialog;
import android.content.Context;
import android.preference.Preference;
import android.util.AttributeSet;
import android.widget.Toast;

import java.util.List;

import app.morphe.extension.music.patches.smartdownloads.SmartDownloadTrack;
import app.morphe.extension.music.patches.smartdownloads.SmartDownloadsManager;

@SuppressWarnings({"deprecation", "unused"})
public class SmartCacheClearPreference extends Preference {

    public SmartCacheClearPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onClick() {
        super.onClick();
        Context context = getContext();
        String[] options = {
                "Select Songs to Delete (Song-by-Song)",
                "Clear All Non-Pinned Tracks"
        };

        new AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Re-personalize / Clear Smart Cache")
                .setItems(options, (d, which) -> {
                    if (which == 0) {
                        showSongBySongDialog(context);
                    } else {
                        confirmClearAll(context);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showSongBySongDialog(Context context) {
        SmartDownloadsManager manager = SmartDownloadsManager.getInstance();
        List<SmartDownloadTrack> tracks = manager.getAllTracksSorted();
        if (tracks.isEmpty()) {
            Toast.makeText(context, "No tracks in Smart Cache", Toast.LENGTH_SHORT).show();
            return;
        }

        String[] names = new String[tracks.size()];
        boolean[] checked = new boolean[tracks.size()];
        for (int i = 0; i < tracks.size(); i++) {
            SmartDownloadTrack t = tracks.get(i);
            names[i] = t.displayName() + (t.isPinned ? " 📌" : "");
        }

        new AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Select Songs to Delete")
                .setMultiChoiceItems(names, checked, (d, idx, isChecked) -> checked[idx] = isChecked)
                .setPositiveButton("Delete Selected", (d, w) -> {
                    int deleted = 0;
                    for (int i = 0; i < tracks.size(); i++) {
                        if (checked[i]) {
                            manager.deleteSingleTrack(tracks.get(i).videoId);
                            deleted++;
                        }
                    }
                    Toast.makeText(context, "Removed " + deleted + " song(s)", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void confirmClearAll(Context context) {
        new AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Clear All Non-Pinned?")
                .setMessage("Pinned songs will be kept safe. All other cached songs and play counts will be cleared.")
                .setPositiveButton("Clear", (d, w) -> {
                    SmartDownloadsManager.getInstance().rePersonalizeNow();
                    Toast.makeText(context, "Smart Cache cleared", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
