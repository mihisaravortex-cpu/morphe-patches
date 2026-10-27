import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.regex.Pattern;

public final class OfflinePlaybackManager {

    private static final Pattern VIDEO_ID_PATTERN =
            Pattern.compile("[A-Za-z0-9_-]{1,128}");

    // Maximum cache size: 5 GiB
    private static final long MAX_CACHE_SIZE_BYTES =
            5L * 1024 * 1024 * 1024;

    private final File storageDir;

    public OfflinePlaybackManager(File appPrivateFilesDir) {
        if (appPrivateFilesDir == null) {
            throw new IllegalArgumentException(
                    "App private directory cannot be null"
            );
        }

        this.storageDir = new File(
                appPrivateFilesDir,
                "offline_cache"
        );
    }

    private boolean isValidVideoId(String videoId) {
        return videoId != null
                && VIDEO_ID_PATTERN.matcher(videoId).matches();
    }

    private File getVideoFile(String videoId) {
        if (!isValidVideoId(videoId)) {
            throw new IllegalArgumentException(
                    "Invalid video ID"
            );
        }

        return new File(storageDir, videoId + ".mp4");
    }

    /**
     * Removes oldest cached files when necessary.
     * The file being replaced is excluded from eviction.
     */
    private synchronized void enforceCacheLimits(
            long incomingDataSize,
            File destination
    ) throws IOException {

        if (incomingDataSize < 0
                || incomingDataSize > MAX_CACHE_SIZE_BYTES) {
            throw new IOException(
                    "Media exceeds maximum cache size"
            );
        }

        if (!storageDir.exists()) {
            return;
        }

        File[] files = storageDir.listFiles(
                (dir, name) -> name.endsWith(".mp4")
        );

        if (files == null) {
            throw new IOException(
                    "Unable to list cache files"
            );
        }

        long totalSize = 0;

        for (File file : files) {
            if (!file.equals(destination)) {
                totalSize = Math.addExact(
                        totalSize,
                        file.length()
                );
            }
        }

        long requiredSize = Math.addExact(
                totalSize,
                incomingDataSize
        );

        if (requiredSize <= MAX_CACHE_SIZE_BYTES) {
            return;
        }

        Arrays.sort(
                files,
                Comparator.comparingLong(File::lastModified)
        );

        for (File file : files) {
            if (file.equals(destination)) {
                continue;
            }

            if (requiredSize <= MAX_CACHE_SIZE_BYTES) {
                break;
            }

            long fileSize = file.length();

            if (file.delete()) {
                totalSize -= fileSize;
                requiredSize = Math.addExact(
                        totalSize,
                        incomingDataSize
                );
            }
        }

        if (requiredSize > MAX_CACHE_SIZE_BYTES) {
            throw new IOException(
                    "Insufficient cache capacity"
            );
        }
    }

    /**
     * Saves a complete media file using a temporary file.
     */
    public synchronized void saveMedia(
            String videoId,
            byte[] mediaData
    ) throws IOException {

        if (mediaData == null || mediaData.length == 0) {
            throw new IOException("Media data is empty");
        }

        // Validate the ID before changing the cache.
        File destination = getVideoFile(videoId);

        if (mediaData.length > MAX_CACHE_SIZE_BYTES) {
            throw new IOException(
                    "Media exceeds maximum cache size"
            );
        }

        if (!storageDir.exists()
                && !storageDir.mkdirs()) {
            throw new IOException(
                    "Unable to create storage directory"
            );
        }

        enforceCacheLimits(
                mediaData.length,
                destination
        );

        File temporary = new File(
                storageDir,
                videoId + ".tmp"
        );

        try {
            try (FileOutputStream output =
                         new FileOutputStream(temporary)) {

                output.write(mediaData);
                output.flush();
                output.getFD().sync();
            }

            // Replace an existing destination safely.
            if (destination.exists()
                    && !destination.delete()) {
                throw new IOException(
                        "Unable to replace existing media"
                );
            }

            if (!temporary.renameTo(destination)) {
                throw new IOException(
                        "Unable to finalize media file"
                );
            }

        } finally {
            if (temporary.exists() && !temporary.delete()) {
                // Cleanup could not complete.
                // The next cache maintenance pass can retry.
            }
        }
    }

    public synchronized Uri getLocalMediaUri(
            String videoId
    ) {

        if (!isValidVideoId(videoId)) {
            return null;
        }

        File mediaFile = getVideoFile(videoId);

        if (!mediaFile.isFile()
                || !mediaFile.canRead()
                || mediaFile.length() == 0) {
            return null;
        }

        return Uri.fromFile(mediaFile);
    }

    public synchronized Uri selectPlaybackSource(
            String videoId,
            Uri fallbackUri
    ) {

        Uri localUri = getLocalMediaUri(videoId);

        return localUri != null
                ? localUri
                : fallbackUri;
    }

    public synchronized boolean deleteMedia(
            String videoId
    ) {

        if (!isValidVideoId(videoId)) {
            return false;
        }

        File mediaFile = getVideoFile(videoId);

        return !mediaFile.exists() || mediaFile.delete();
    }
}
