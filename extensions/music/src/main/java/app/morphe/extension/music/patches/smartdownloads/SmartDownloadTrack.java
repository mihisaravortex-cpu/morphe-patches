package app.morphe.extension.music.patches.smartdownloads;

import androidx.annotation.NonNull;

import org.json.JSONException;
import org.json.JSONObject;

public final class SmartDownloadTrack {

    public final String videoId;
    public String title = "";
    public String artist = "";
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

    @NonNull
    public String displayName() {
        if (!title.isEmpty() && !artist.isEmpty()) {
            return title + " — " + artist;
        }
        if (!title.isEmpty()) {
            return title;
        }
        return videoId;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put("videoId", videoId);
        obj.put("title", title);
        obj.put("artist", artist);
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
        track.title = obj.optString("title", "");
        track.artist = obj.optString("artist", "");
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
