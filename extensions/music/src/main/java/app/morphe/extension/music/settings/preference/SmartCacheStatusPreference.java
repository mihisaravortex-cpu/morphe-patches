package app.morphe.extension.music.settings.preference;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.preference.Preference;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
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
            new AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Smart Cache Status")
                    .setMessage(manager.getStatusSummary() + "\n\nNo tracks tracked yet.")
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }

        TrackListAdapter adapter = new TrackListAdapter(context, tracks, manager);
        new AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(manager.getStatusSummary())
                .setAdapter(adapter, (d, which) -> showTrackActions(context, tracks.get(which)))
                .setNeutralButton("Backup JSON", (d, w) -> {
                    String path = manager.exportBackup();
                    Toast.makeText(context, path != null ? "Saved: " + path : "Backup failed", Toast.LENGTH_LONG).show();
                })
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void showTrackActions(Context context, SmartDownloadTrack track) {
        SmartDownloadsManager manager = SmartDownloadsManager.getInstance();
        String pinLabel = track.isPinned ? "Unpin Track" : "Pin Track (Protect)";
        String[] actions = {pinLabel, "Remove from Smart Cache"};

        new AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
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

    private static int dp(Context c, int dp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, c.getResources().getDisplayMetrics());
    }

    private static final class TrackListAdapter extends BaseAdapter {
        private final Context context;
        private final List<SmartDownloadTrack> tracks;
        private final SmartDownloadsManager manager;
        private final int threshold;

        TrackListAdapter(Context context, List<SmartDownloadTrack> tracks, SmartDownloadsManager manager) {
            this.context = context;
            this.tracks = tracks;
            this.manager = manager;
            this.threshold = SmartDownloadsManager.getPlayThreshold();
        }

        @Override public int getCount() { return tracks.size(); }
        @Override public Object getItem(int pos) { return tracks.get(pos); }
        @Override public long getItemId(int pos) { return pos; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            int padH = dp(context, 16);
            int padV = dp(context, 10);
            row.setPadding(padH, padV, padH, padV);

            SmartDownloadTrack t = tracks.get(position);

            ImageView thumb = new ImageView(context);
            int size = dp(context, 48);
            LinearLayout.LayoutParams imgParams = new LinearLayout.LayoutParams(size, size);
            imgParams.setMarginEnd(dp(context, 12));
            thumb.setLayoutParams(imgParams);
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);

            GradientDrawable placeholder = new GradientDrawable();
            placeholder.setColor(0xFF2C2C2C);
            placeholder.setCornerRadius(dp(context, 6));
            thumb.setBackground(placeholder);
            thumb.setClipToOutline(true);

            File thumbFile = manager.getThumbnailFile(t.videoId);
            if (thumbFile != null && thumbFile.exists()) {
                Bitmap bmp = BitmapFactory.decodeFile(thumbFile.getAbsolutePath());
                if (bmp != null) thumb.setImageBitmap(bmp);
            }
            row.addView(thumb);

            LinearLayout textCol = new LinearLayout(context);
            textCol.setOrientation(LinearLayout.VERTICAL);
            textCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView titleView = new TextView(context);
            titleView.setText(t.displayName());
            titleView.setTextColor(Color.WHITE);
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            titleView.setTypeface(Typeface.DEFAULT_BOLD);
            titleView.setMaxLines(2);
            titleView.setEllipsize(TextUtils.TruncateAt.END);
            textCol.addView(titleView);

            TextView statusView = new TextView(context);
            String state = t.isCached ? "Cached" : (t.playCount + "/" + threshold + " plays");
            if (t.isPinned) state += "  •  📌 Pinned";
            statusView.setText(state);
            statusView.setTextColor(t.isPinned ? 0xFFFFB74D : (t.isCached ? 0xFF81C784 : 0xFFB0B0B0));
            statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            statusView.setPadding(0, dp(context, 2), 0, 0);
            textCol.addView(statusView);

            row.addView(textCol);
            return row;
        }
    }
}
