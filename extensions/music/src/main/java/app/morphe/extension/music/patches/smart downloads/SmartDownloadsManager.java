package app.morphe.extension.music.patches.smartdownloads;

import androidx.annotation.NonNull;

import java.util.List;
import java.util.concurrent.TimeUnit;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.music.shared.VideoInformation;
import app.morphe.extension.shared.Logger;

/**
 * Core Smart Cache / Smart Downloads engine:
 * - Tracks valid plays (>= 65% of track duration).
 * - Triggers caching at >= configured play threshold (default 3).
 * - Enforces capacity cap (>= 40, default 60) via LRU unpinned eviction.
 * - Enforces inactivity eviction (default 14 days) and optional periodic re-personalization.
 */
public final class SmartDownloadsManager {

    private static final double VALID_PLAY_RATIO = 0.65; // 60%-70% threshold
    private static final long TICK_INTERVAL_MS = 1000L;
    public static final int MIN_CAPACITY = 40;

    private static final SmartDownloadsManager INSTANCE = new SmartDownloadsManager();

    private final SmartDownloadStore store = new SmartDownloadStore();
    private final SmartDownloadWorker worker = new SmartDownloadWorker(this);

    private volatile String activeVideoId = "";
    private volatile long accumulatedPlayMs = 0L;
    private volatile long lastTickWallMs = 0L;
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

    public static int getPlayThreshold() {
        return Math.max(3, Settings.SMART_DOWNLOADS_PLAY_THRESHOLD.get());
    }

    public static int getMaxCapacity() {
        return Math.max(MIN_CAPACITY, Settings.SMART_DOWNLOADS_MAX_SONGS.get());
    }

    public static int getInactivityDays() {
        return Settings.SMART_DOWNLOADS_INACTIVITY_DAYS.get(); // 0 = Never, 7, 14, 30
    }

    public static int getRePersonalizeDays() {
        return Settings.SMART_DOWNLOADS_REPERSONALIZE_DAYS.get(); // 0 = Off, 30, 60
    }

    private void onNewVideoId(@NonNull String videoId) {
        activeVideoId = videoId;
        accumulatedPlayMs = 0L;
        lastTickWallMs = System.currentTimeMillis();
        countedCurrentPlay = false;

        if (isEnabled()) {
            runMaintenance();
        }
    }

    /**
     * Called ~every 1000ms from VideoInformation.setVideoTime / musicVideoTimeHook.
     */
    public void onPlaybackTick(long currentPlaybackTimeMs) {
        if (!isEnabled()) return;
        String videoId = VideoInformation.getVideoId();
        if (videoId.isEmpty() || !VideoInformation.isVideoId(videoId)) return;

        if (!videoId.equals(activeVideoId)) {
            onNewVideoId(videoId);
        }

        if (countedCurrentPlay) return;

        long now = System.currentTimeMillis();
        long delta = now - lastTickWallMs;
        lastTickWallMs = now;
        if (delta > 0 && delta <= 2500L) {
            accumulatedPlayMs += delta;
        } else {
            accumulatedPlayMs += TICK_INTERVAL_MS;
        }

        long lengthMs = VideoInformation.getVideoLength();
        if (lengthMs > 0 && accumulatedPlayMs >= (long) (lengthMs * VALID_PLAY_RATIO)) {
            countedCurrentPlay = true;
            onValidPlayCompleted(videoId, now);
        }
    }

    private void onValidPlayCompleted(@NonNull String videoId, long nowMs) {
        SmartDownloadTrack track = store.recordValidPlay(videoId, nowMs);
        Logger.printDebug(() -> "SmartDownloads: valid play #" + track.playCount + " for " + videoId);

        if (!track.isCached && track.playCount >= getPlayThreshold()) {
            worker.enqueue(videoId);
        }
    }

    public void runMaintenance() {
        long now = System.currentTimeMillis();

        // 1. Optional Periodic Re-Personalization
        int resetDays = getRePersonalizeDays();
        if (resetDays > 0) {
            long resetMs = TimeUnit.DAYS.toMillis(resetDays);
            if (now - store.getLastPersonalizationResetMs() >= resetMs) {
                Logger.printInfo(() -> "SmartDownloads: running periodic re-personalization");
                rePersonalizeNow();
                return;
            }
        }

        // 2. Inactivity / LRU Eviction
        int inactiveDays = getInactivityDays();
        if (inactiveDays > 0) {
            long cutoffMs = now - TimeUnit.DAYS.toMillis(inactiveDays);
            List<SmartDownloadTrack> inactive = store.getInactiveCachedTracks(cutoffMs);
            for (SmartDownloadTrack track : inactive) {
                evictTrack(track.videoId);
            }
        }

        // 3. Enforce cap if user lowered limit
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
        Logger.printDebug(() -> "SmartDownloads: evicting " + videoId);
        SmartDownloadsPatch.dispatchOfflineRemove(videoId);
        store.markEvicted(videoId);
    }

    void onTrackDownloadDispatched(@NonNull String videoId) {
        // Approximate size or 0 until exported/measured; native engine handles actual storage
        store.markCached(videoId, System.currentTimeMillis(), 0L);
    }

    public void rePersonalizeNow() {
        worker.clearQueue();
        List<String> toDelete = store.clearNonPinned(System.currentTimeMillis());
        for (String videoId : toDelete) {
            SmartDownloadsPatch.dispatchOfflineRemove(videoId);
        }
    }

    public boolean togglePin(@NonNull String videoId) {
        return store.togglePin(videoId);
    }

    public boolean isPinned(@NonNull String videoId) {
        return store.isPinned(videoId);
    }

    public String getStatusSummary() {
        int count = store.getCachedCount();
        int cap = getMaxCapacity();
        long mb = store.getTotalCachedBytes() / (1024L * 1024L);
        return count + " / " + cap + " songs cached • " + mb + " MB";
    }
}
