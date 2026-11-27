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
public class SmartCacheStatusPreference extends Preference {

    public SmartCacheStatusPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setSummary(SmartDownloadsManager.getInstance().getStatusSummary());
    }

    @Override
    protected void onClick() {
        super.onClick();
        Context context = getContext();
        SmartDownloadsManager manager = SmartDownloadsManager.getInstance();
        setSummary(manager.getStatusSummary());

        List<SmartDownloadTrack> tracks = manager.getAllTracksSorted();
        if (tracks.isEmpty()) {
            new AlertDialog.Builder(context)
                    .setTitle("Smart Cache Status")
                    .setMessage(manager.getStatusSummary() + "\n\nNo tracks tracked yet.")
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }

        String[] labels = new String[tracks.size()];
        int threshold = SmartDownloadsManager.getPlayThreshold();
        for (int i = 0; i < tracks.size(); i++) {
            SmartDownloadTrack t = tracks.get(i);
            String state = t.isCached ? "Cached" : (t.playCount + "/" + threshold + " plays");
            String pin = t.isPinned ? " 📌" : "";
            labels[i] = t.displayName() + "\n[" + state + pin + "]";
        }

        new AlertDialog.Builder(context)
                .setTitle(manager.getStatusSummary())
                .setItems(labels, (d, which) -> showTrackActions(context, tracks.get(which)))
                .setNeutralButton("Backup JSON", (d, w) -> {
                    String path = manager.exportBackup();
                    String msg = path != null ? "Saved: " + path : "Backup failed";
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show();
                })
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void showTrackActions(Context context, SmartDownloadTrack track) {
        SmartDownloadsManager manager = SmartDownloadsManager.getInstance();
        String pinLabel = track.isPinned ? "Unpin Track" : "Pin Track (Protect)";
        String[] actions = {pinLabel, "Remove from Smart Cache"};

        new AlertDialog.Builder(context)
                .setTitle(track.displayName())
                .setItems(actions, (d, which) -> {
                    if (which == 0) {
                        boolean pinned = manager.togglePin(track.videoId, track.title, track.artist);
                        Toast.makeText(context, pinned ? "Pinned" : "Unpinned", Toast.LENGTH_SHORT).show();
                    } else if (which == 1) {
                        manager.deleteSingleTrack(track.videoId);
                        Toast.makeText(context, "Removed: " + track.displayName(), Toast.LENGTH_SHORT).show();
                    }
                    setSummary(manager.getStatusSummary());
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
