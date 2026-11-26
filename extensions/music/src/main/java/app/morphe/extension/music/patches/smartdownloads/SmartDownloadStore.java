package app.morphe.extension.music.patches.smartdownloads;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Thread-safe persistent JSON store at {filesDir}/SmartDownloads/index.json.
 */
public final class SmartDownloadStore {

    private static final String DIR_NAME = "SmartDownloads";
    private static final String INDEX_FILE = "index.json";

    private final Map<String, SmartDownloadTrack> tracks = new HashMap<>();
    private long lastPersonalizationResetMs;
    private boolean loaded;

    public synchronized void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        File file = indexFile();
        if (file == null || !file.exists()) {
            lastPersonalizationResetMs = System.currentTimeMillis();
            return;
        }
        try {
            String json = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            if (json.isEmpty()) return;
            JSONObject root = new JSONObject(json);
            lastPersonalizationResetMs = root.optLong("lastPersonalizationResetMs", System.currentTimeMillis());
            JSONArray array = root.optJSONArray("tracks");
            if (array != null) {
                for (int i = 0; i < array.length(); i++) {
                    SmartDownloadTrack track = SmartDownloadTrack.fromJson(array.getJSONObject(i));
                    tracks.put(track.videoId, track);
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "SmartDownloadStore: failed to load index.json", ex);
        }
    }

    public synchronized SmartDownloadTrack recordValidPlay(@NonNull String videoId, long nowMs) {
        ensureLoaded();
        SmartDownloadTrack track = tracks.get(videoId);
        if (track == null) {
            track = new SmartDownloadTrack(videoId);
            track.firstPlayedMs = nowMs;
            tracks.put(videoId, track);
        }
        track.playCount++;
        track.lastPlayedMs = nowMs;
        saveLocked();
        return copyOf(track);
    }

    @Nullable
    public synchronized SmartDownloadTrack getTrack(@NonNull String videoId) {
        ensureLoaded();
        SmartDownloadTrack track = tracks.get(videoId);
        return track != null ? copyOf(track) : null;
    }

    public synchronized void markCached(@NonNull String videoId, long nowMs, long sizeBytes) {
        ensureLoaded();
        SmartDownloadTrack track = tracks.get(videoId);
        if (track == null) {
            track = new SmartDownloadTrack(videoId);
            track.firstPlayedMs = nowMs;
            track.lastPlayedMs = nowMs;
            tracks.put(videoId, track);
        }
        track.isCached = true;
        track.cachedAtMs = nowMs;
        track.sizeBytes = sizeBytes;
        saveLocked();
    }

    public synchronized void markEvicted(@NonNull String videoId) {
        ensureLoaded();
        SmartDownloadTrack track = tracks.get(videoId);
        if (track != null) {
            track.isCached = false;
            track.cachedAtMs = 0L;
            track.sizeBytes = 0L;
            track.playCount = 0;
            saveLocked();
        }
    }

    public synchronized boolean togglePin(@NonNull String videoId) {
        ensureLoaded();
        SmartDownloadTrack track = tracks.get(videoId);
        if (track == null) {
            track = new SmartDownloadTrack(videoId);
            long now = System.currentTimeMillis();
            track.firstPlayedMs = now;
            track.lastPlayedMs = now;
            tracks.put(videoId, track);
        }
        track.isPinned = !track.isPinned;
        saveLocked();
        return track.isPinned;
    }

    public synchronized boolean isPinned(@NonNull String videoId) {
        ensureLoaded();
        SmartDownloadTrack track = tracks.get(videoId);
        return track != null && track.isPinned;
    }

    public synchronized int getCachedCount() {
        ensureLoaded();
        int count = 0;
        for (SmartDownloadTrack t : tracks.values()) {
            if (t.isCached) count++;
        }
        return count;
    }

    public synchronized long getTotalCachedBytes() {
        ensureLoaded();
        long total = 0L;
        for (SmartDownloadTrack t : tracks.values()) {
            if (t.isCached) total += Math.max(0L, t.sizeBytes);
        }
        return total;
    }

    /**
     * Returns unpinned cached tracks sorted by lastPlayedMs ascending (LRU first).
     */
    public synchronized List<SmartDownloadTrack> getCachedUnpinnedLruOrder() {
        ensureLoaded();
        List<SmartDownloadTrack> list = new ArrayList<>();
        for (SmartDownloadTrack t : tracks.values()) {
            if (t.isCached && !t.isPinned) {
                list.add(copyOf(t));
            }
        }
        list.sort(Comparator.comparingLong(t -> t.lastPlayedMs));
        return list;
    }

    /**
     * Returns unpinned cached tracks whose lastPlayedMs is older than cutoffMs.
     */
    public synchronized List<SmartDownloadTrack> getInactiveCachedTracks(long cutoffMs) {
        ensureLoaded();
        List<SmartDownloadTrack> list = new ArrayList<>();
        for (SmartDownloadTrack t : tracks.values()) {
            if (t.isCached && !t.isPinned && t.lastPlayedMs > 0 && t.lastPlayedMs < cutoffMs) {
                list.add(copyOf(t));
            }
        }
        return list;
    }

    public synchronized long getLastPersonalizationResetMs() {
        ensureLoaded();
        return lastPersonalizationResetMs;
    }

    /**
     * Clears all non-pinned tracks (both cached and play history) for fresh personalization.
     * Returns the videoIds of cached tracks that must be deleted from offline storage.
     */
    public synchronized List<String> clearNonPinned(long nowMs) {
        ensureLoaded();
        List<String> toDelete = new ArrayList<>();
        Iterator<Map.Entry<String, SmartDownloadTrack>> it = tracks.entrySet().iterator();
        while (it.hasNext()) {
            SmartDownloadTrack t = it.next().getValue();
            if (t.isPinned) continue;
            if (t.isCached) {
                toDelete.add(t.videoId);
            }
            it.remove();
        }
        lastPersonalizationResetMs = nowMs;
        saveLocked();
        return toDelete;
    }

    private void saveLocked() {
        File file = indexFile();
        if (file == null) return;
        File temp = new File(file.getParentFile(), INDEX_FILE + ".tmp");
        try {
            JSONObject root = new JSONObject();
            root.put("lastPersonalizationResetMs", lastPersonalizationResetMs);
            JSONArray array = new JSONArray();
            for (SmartDownloadTrack t : tracks.values()) {
                array.put(t.toJson());
            }
            root.put("tracks", array);
            Files.write(temp.toPath(), root.toString().getBytes(StandardCharsets.UTF_8));
            if (file.exists() && !file.delete()) {
                Logger.printDebug(() -> "SmartDownloadStore: could not replace index.json");
            }
            if (!temp.renameTo(file)) {
                Files.write(file.toPath(), root.toString().getBytes(StandardCharsets.UTF_8));
                Utils.deleteQuietly(temp);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "SmartDownloadStore: failed to save index.json", ex);
        }
    }

    @Nullable
    private File indexFile() {
        if (Utils.getContext() == null) return null;
        File dir = new File(Utils.getContext().getFilesDir(), DIR_NAME);
        if (!dir.exists() && !dir.mkdirs()) {
            return null;
        }
        return new File(dir, INDEX_FILE);
    }

    private static SmartDownloadTrack copyOf(SmartDownloadTrack src) {
        SmartDownloadTrack copy = new SmartDownloadTrack(src.videoId);
        copy.playCount = src.playCount;
        copy.firstPlayedMs = src.firstPlayedMs;
        copy.lastPlayedMs = src.lastPlayedMs;
        copy.cachedAtMs = src.cachedAtMs;
        copy.isCached = src.isCached;
        copy.isPinned = src.isPinned;
        copy.sizeBytes = src.sizeBytes;
        return copy;
    }
}
