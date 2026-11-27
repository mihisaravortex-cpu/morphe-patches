package app.morphe.extension.music.patches.smartdownloads;

import android.graphics.Bitmap;
import android.os.Environment;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

public final class SmartDownloadStore {

    private static final String DIR_NAME = "SmartDownloads";
    private static final String THUMBS_DIR = "thumbs";
    private static final String INDEX_FILE = "index.json";

    private final Map<String, SmartDownloadTrack> tracks = new HashMap<>();
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
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

    public void saveThumbnailAsync(@NonNull String videoId, @NonNull Bitmap bitmap) {
        File thumb = getThumbnailFile(videoId);
        if (thumb == null || thumb.exists()) return;
        Bitmap copy = bitmap.copy(Bitmap.Config.ARGB_8888, false);
        if (copy == null) return;
        ioExecutor.submit(() -> {
            try {
                Bitmap scaled = Bitmap.createScaledBitmap(copy, 120, 120, true);
                try (FileOutputStream fos = new FileOutputStream(thumb)) {
                    scaled.compress(Bitmap.CompressFormat.JPEG, 82, fos);
                }
                if (scaled != copy) scaled.recycle();
            } catch (Exception ex) {
                Logger.printDebug(() -> "SmartDownloadStore: thumb save failed", ex);
            } finally {
                copy.recycle();
            }
        });
    }

    @Nullable
    public File getThumbnailFile(@NonNull String videoId) {
        if (Utils.getContext() == null) return null;
        File dir = new File(new File(Utils.getContext().getFilesDir(), DIR_NAME), THUMBS_DIR);
        if (!dir.exists() && !dir.mkdirs()) return null;
        return new File(dir, videoId + ".jpg");
    }

    private void deleteThumbnail(@NonNull String videoId) {
        File thumb = getThumbnailFile(videoId);
        if (thumb != null && thumb.exists()) {
            Utils.deleteQuietly(thumb);
        }
    }

    public synchronized void updateMetadata(@NonNull String videoId, @Nullable String title, @Nullable String artist) {
        ensureLoaded();
        SmartDownloadTrack track = tracks.get(videoId);
        if (track == null) {
            track = new SmartDownloadTrack(videoId);
            long now = System.currentTimeMillis();
            track.firstPlayedMs = now;
            track.lastPlayedMs = now;
            tracks.put(videoId, track);
        }
        boolean changed = false;
        if (title != null && !title.isBlank() && !title.equals(track.title)) {
            track.title = title;
            changed = true;
        }
        if (artist != null && !artist.isBlank() && !artist.equals(track.artist)) {
            track.artist = artist;
            changed = true;
        }
        if (changed) saveLocked();
    }

    public synchronized SmartDownloadTrack recordValidPlay(@NonNull String videoId, long nowMs,
                                                           @Nullable String title, @Nullable String artist) {
        ensureLoaded();
        SmartDownloadTrack track = tracks.get(videoId);
        if (track == null) {
            track = new SmartDownloadTrack(videoId);
            track.firstPlayedMs = nowMs;
            tracks.put(videoId, track);
        }
        if (title != null && !title.isBlank()) track.title = title;
        if (artist != null && !artist.isBlank()) track.artist = artist;
        track.playCount++;
        track.lastPlayedMs = nowMs;
        saveLocked();
        return copyOf(track);
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

    public synchronized void removeTrackCompletely(@NonNull String videoId) {
        ensureLoaded();
        if (tracks.remove(videoId) != null) {
            deleteThumbnail(videoId);
            saveLocked();
        }
    }

    public synchronized boolean togglePin(@NonNull String videoId, @Nullable String title, @Nullable String artist) {
        ensureLoaded();
        SmartDownloadTrack track = tracks.get(videoId);
        if (track == null) {
            track = new SmartDownloadTrack(videoId);
            long now = System.currentTimeMillis();
            track.firstPlayedMs = now;
            track.lastPlayedMs = now;
            tracks.put(videoId, track);
        }
        if (title != null && !title.isBlank()) track.title = title;
        if (artist != null && !artist.isBlank()) track.artist = artist;
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
        for (SmartDownloadTrack t : tracks.values()) if (t.isCached) count++;
        return count;
    }

    public synchronized int getPinnedCount() {
        ensureLoaded();
        int count = 0;
        for (SmartDownloadTrack t : tracks.values()) if (t.isPinned) count++;
        return count;
    }

    public synchronized List<SmartDownloadTrack> getAllTracksSorted() {
        ensureLoaded();
        List<SmartDownloadTrack> list = new ArrayList<>(tracks.size());
        for (SmartDownloadTrack t : tracks.values()) list.add(copyOf(t));
        list.sort((a, b) -> {
            if (a.isPinned != b.isPinned) return a.isPinned ? -1 : 1;
            if (a.isCached != b.isCached) return a.isCached ? -1 : 1;
            return Long.compare(b.lastPlayedMs, a.lastPlayedMs);
        });
        return list;
    }

    public synchronized List<SmartDownloadTrack> getCachedUnpinnedLruOrder() {
        ensureLoaded();
        List<SmartDownloadTrack> list = new ArrayList<>();
        for (SmartDownloadTrack t : tracks.values()) {
            if (t.isCached && !t.isPinned) list.add(copyOf(t));
        }
        list.sort(Comparator.comparingLong(t -> t.lastPlayedMs));
        return list;
    }

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

    public synchronized List<String> clearNonPinned(long nowMs) {
        ensureLoaded();
        List<String> toDelete = new ArrayList<>();
        Iterator<Map.Entry<String, SmartDownloadTrack>> it = tracks.entrySet().iterator();
        while (it.hasNext()) {
            SmartDownloadTrack t = it.next().getValue();
            if (t.isPinned) continue;
            if (t.isCached) toDelete.add(t.videoId);
            deleteThumbnail(t.videoId);
            it.remove();
        }
        lastPersonalizationResetMs = nowMs;
        saveLocked();
        return toDelete;
    }

    @Nullable
    public synchronized String exportBackup() {
        ensureLoaded();
        File src = indexFile();
        if (src == null || !src.exists() || Utils.getContext() == null) return null;
        try {
            File outDir = Utils.getContext().getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (outDir == null) return null;
            if (!outDir.exists() && !outDir.mkdirs()) return null;
            File dst = new File(outDir, "Morphe_SmartCache_Backup.json");
            Files.write(dst.toPath(), Files.readAllBytes(src.toPath()));
            return dst.getAbsolutePath();
        } catch (Exception ex) {
            Logger.printException(() -> "SmartDownloadStore: exportBackup failed", ex);
            return null;
        }
    }

    private void saveLocked() {
        File file = indexFile();
        if (file == null) return;
        File temp = new File(file.getParentFile(), INDEX_FILE + ".tmp");
        try {
            JSONObject root = new JSONObject();
            root.put("lastPersonalizationResetMs", lastPersonalizationResetMs);
            JSONArray array = new JSONArray();
            for (SmartDownloadTrack t : tracks.values()) array.put(t.toJson());
            root.put("tracks", array);
            Files.write(temp.toPath(), root.toString().getBytes(StandardCharsets.UTF_8));
            if (file.exists()) Utils.deleteQuietly(file);
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
        if (!dir.exists() && !dir.mkdirs()) return null;
        return new File(dir, INDEX_FILE);
    }

    private static SmartDownloadTrack copyOf(SmartDownloadTrack src) {
        SmartDownloadTrack copy = new SmartDownloadTrack(src.videoId);
        copy.title = src.title;
        copy.artist = src.artist;
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
