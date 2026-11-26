package app.morphe.extension.music.patches.smartdownloads;

import androidx.annotation.NonNull;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Metadata model for a single track tracked by the Smart Downloads engine.
 */
public final class SmartDownloadTrack {

    public final String videoId;
    public int playCount;
    public long firstPlayedMs;
    public long lastPlayedMs;
    public long cachedAtMs;
    public boolean isCached;
    public boolean isPinned;
    public long sizeBytes;

    public SmartDownloadTrack(@NonNull String videoId) {
        this.videoId = videoId;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put("videoId", videoId);
        obj.put("playCount", playCount);
        obj.put("firstPlayedMs", firstPlayedMs);
        obj.put("lastPlayedMs", lastPlayedMs);
        obj.put("cachedAtMs", cachedAtMs);
        obj.put("isCached", isCached);
        obj.put("isPinned", isPinned);
        obj.put("sizeBytes", sizeBytes);
        return obj;
    }

    @NonNull
    public static SmartDownloadTrack fromJson(@NonNull JSONObject obj) throws JSONException {
        SmartDownloadTrack track = new SmartDownloadTrack(obj.getString("videoId"));
        track.playCount = obj.optInt("playCount", 0);
        track.firstPlayedMs = obj.optLong("firstPlayedMs", 0L);
        track.lastPlayedMs = obj.optLong("lastPlayedMs", 0L);
        track.cachedAtMs = obj.optLong("cachedAtMs", 0L);
        track.isCached = obj.optBoolean("isCached", false);
        track.isPinned = obj.optBoolean("isPinned", false);
        track.sizeBytes = obj.optLong("sizeBytes", 0L);
        return track;
    }
}
