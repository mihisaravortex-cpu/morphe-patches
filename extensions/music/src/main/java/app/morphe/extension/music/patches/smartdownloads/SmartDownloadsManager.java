package app.morphe.extension.music.patches.smartdownloads;

import android.media.MediaMetadata;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;
import java.util.concurrent.TimeUnit;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.music.shared.VideoInformation;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

public final class SmartDownloadsManager {

    private static final double VALID_PLAY_RATIO = 0.65;
    private static final long TICK_INTERVAL_MS = 1000L;
    public static final int MIN_CAPACITY = 40;

    private static final SmartDownloadsManager INSTANCE = new SmartDownloadsManager();

    private final SmartDownloadStore store = new SmartDownloadStore();
    private final SmartDownloadWorker worker = new SmartDownloadWorker(this);

    private volatile String activeVideoId = "";
    private volatile String activeTitle = "";
    private volatile String activeArtist = "";
    private volatile long accumulatedPlayMs = 0L;
    private volatile long lastTickWallMs = 0L;
    private volatile long lastPlaybackPosMs = 0L;
    private volatile boolean countedCurrentPlay = false;

    private SmartDownloadsManager() {
        VideoInformation.addVideoIdListener(this::onNewVideoId);
    }

    public static SmartDownloadsManager getInstance() {
        return INSTANCE;
    }

    public static boolean isEnabled() {
        return Settings.SMART_DOWNLOADS_ENABLED.get();
    }

    public static boolean isWifiOnly() {
        return Settings.SMART_DOWNLOADS_WIFI_ONLY.get();
    }

    public static boolean isBatteryGuardEnabled() {
        return Settings.SMART_DOWNLOADS_BATTERY_GUARD.get();
    }

    public static boolean isLikeBoostEnabled() {
        return Settings.SMART_DOWNLOADS_LIKE_BOOST.get();
    }

    public static int getPlayThreshold() {
        return Math.max(3, parseIntSafe(Settings.SMART_DOWNLOADS_PLAY_THRESHOLD.get(), 3));
    }

    public static int getMaxCapacity() {
        return Math.max(MIN_CAPACITY, parseIntSafe(Settings.SMART_DOWNLOADS_MAX_SONGS.get(), 60));
    }

    public static int getInactivityDays() {
        return parseIntSafe(Settings.SMART_DOWNLOADS_INACTIVITY_DAYS.get(), 14);
    }

    public static int getRePersonalizeDays() {
        return parseIntSafe(Settings.SMART_DOWNLOADS_REPERSONALIZE_DAYS.get(), 0);
    }

    private static int parseIntSafe(@Nullable String value, int fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    public void onMetadataUpdated(@Nullable MediaMetadata metadata) {
        if (metadata == null) return;
        String title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
        String artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
        String mediaId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID);

        if (title != null && !title.isBlank()) activeTitle = title.trim();
        if (artist != null && !artist.isBlank()) activeArtist = artist.trim();

        String vid = (mediaId != null && VideoInformation.isVideoId(mediaId))
                ? mediaId
                : VideoInformation.getVideoId();
        if (vid != null && VideoInformation.isVideoId(vid)) {
            store.updateMetadata(vid, activeTitle, activeArtist);
        }
    }

    public void onLikeClicked(@Nullable String serviceName, @Nullable String videoId) {
        if (!isEnabled() || !isLikeBoostEnabled()) return;
        if (!"like/like".equals(serviceName)) return;
        String vid = (videoId != null && VideoInformation.isVideoId(videoId))
                ? videoId
                : VideoInformation.getVideoId();
        if (vid == null || !VideoInformation.isVideoId(vid)) return;

        store.updateMetadata(vid, activeTitle, activeArtist);
        SmartDownloadTrack track = store.getAllTracksSorted().stream()
                .filter(t -> t.videoId.equals(vid))
                .findFirst()
                .orElse(null);
        if (track == null || !track.isCached) {
            worker.enqueue(vid);
            Utils.runOnMainThreadNowOrLater(() -> {
                if (Utils.getContext() != null) {
                    Toast.makeText(Utils.getContext(), "Smart Cache: Queued liked song", Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    private void onNewVideoId(@NonNull String videoId) {
        activeVideoId = videoId;
        activeTitle = "";
        activeArtist = "";
        accumulatedPlayMs = 0L;
        lastTickWallMs = System.currentTimeMillis();
        lastPlaybackPosMs = 0L;
        countedCurrentPlay = false;

        if (isEnabled()) {
            runMaintenance();
        }
    }

    public void onPlaybackTick(long currentPlaybackTimeMs) {
        if (!isEnabled()) return;
        String videoId = VideoInformation.getVideoId();
        if (videoId.isEmpty() || !VideoInformation.isVideoId(videoId)) return;

        if (!videoId.equals(activeVideoId)) {
            onNewVideoId(videoId);
        }

        long lengthMs = VideoInformation.getVideoLength();

        // Loop / Replay detection: if user restarted the same song from near 0 after a counted play
        if (countedCurrentPlay && lengthMs > 0
                && currentPlaybackTimeMs >= 0 && currentPlaybackTimeMs <= 5000L
                && lastPlaybackPosMs > (long) (lengthMs * 0.55)) {
            accumulatedPlayMs = 0L;
            countedCurrentPlay = false;
            lastTickWallMs = System.currentTimeMillis();
        }
        lastPlaybackPosMs = currentPlaybackTimeMs;

        if (countedCurrentPlay) return;

        long now = System.currentTimeMillis();
        long delta = now - lastTickWallMs;
        lastTickWallMs = now;
        if (delta > 0 && delta <= 2500L) {
            accumulatedPlayMs += delta;
        } else {
            accumulatedPlayMs += TICK_INTERVAL_MS;
        }

        if (lengthMs > 0 && accumulatedPlayMs >= (long) (lengthMs * VALID_PLAY_RATIO)) {
            countedCurrentPlay = true;
            onValidPlayCompleted(videoId, now);
        }
    }

    private void onValidPlayCompleted(@NonNull String videoId, long nowMs) {
        SmartDownloadTrack track = store.recordValidPlay(videoId, nowMs, activeTitle, activeArtist);
        Logger.printDebug(() -> "SmartDownloads: valid play #" + track.playCount + " for " + track.displayName());

        if (!track.isCached && track.playCount >= getPlayThreshold()) {
            worker.enqueue(videoId);
        }
    }

    public void runMaintenance() {
        long now = System.currentTimeMillis();

        int resetDays = getRePersonalizeDays();
        if (resetDays > 0) {
            long resetMs = TimeUnit.DAYS.toMillis(resetDays);
            if (now - store.getLastPersonalizationResetMs() >= resetMs) {
                rePersonalizeNow();
                return;
            }
        }

        int inactiveDays = getInactivityDays();
        if (inactiveDays > 0) {
            long cutoffMs = now - TimeUnit.DAYS.toMillis(inactiveDays);
            for (SmartDownloadTrack track : store.getInactiveCachedTracks(cutoffMs)) {
                evictTrack(track.videoId);
            }
        }

        int cap = getMaxCapacity();
        if (store.getCachedCount() > cap) {
            List<SmartDownloadTrack> lru = store.getCachedUnpinnedLruOrder();
            int excess = store.getCachedCount() - cap;
            for (int i = 0; i < excess && i < lru.size(); i++) {
                evictTrack(lru.get(i).videoId);
            }
        }
    }

    public void ensureCapacityForNewTrack() {
        int cap = getMaxCapacity();
        if (store.getCachedCount() < cap) return;
        List<SmartDownloadTrack> lru = store.getCachedUnpinnedLruOrder();
        int toEvict = (store.getCachedCount() - cap) + 1;
        for (int i = 0; i < toEvict && i < lru.size(); i++) {
            evictTrack(lru.get(i).videoId);
        }
    }

    private void evictTrack(@NonNull String videoId) {
        SmartDownloadsPatch.dispatchOfflineRemove(videoId);
        store.markEvicted(videoId);
    }

    public void deleteSingleTrack(@NonNull String videoId) {
        SmartDownloadsPatch.dispatchOfflineRemove(videoId);
        store.removeTrackCompletely(videoId);
    }

    void onTrackDownloadDispatched(@NonNull String videoId) {
        store.markCached(videoId, System.currentTimeMillis(), 0L);
    }

    public void rePersonalizeNow() {
        worker.clearQueue();
        for (String videoId : store.clearNonPinned(System.currentTimeMillis())) {
            SmartDownloadsPatch.dispatchOfflineRemove(videoId);
        }
    }

    public boolean togglePin(@NonNull String videoId, @Nullable String title, @Nullable String artist) {
        return store.togglePin(videoId, title, artist);
    }

    public boolean isPinned(@NonNull String videoId) {
        return store.isPinned(videoId);
    }

    public List<SmartDownloadTrack> getAllTracksSorted() {
        return store.getAllTracksSorted();
    }

    @Nullable
    public String exportBackup() {
        return store.exportBackup();
    }

    public String getStatusSummary() {
        return store.getCachedCount() + " / " + getMaxCapacity() + " songs cached • "
                + store.getPinnedCount() + " pinned";
    }
}
