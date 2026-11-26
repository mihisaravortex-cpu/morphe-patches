package app.morphe.extension.music.patches.smartdownloads;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import androidx.annotation.NonNull;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Sequential, throttled background download queue with Wi-Fi-only enforcement.
 */
public final class SmartDownloadWorker {

    private static final long DELAY_BETWEEN_DOWNLOADS_MS = 7_000L; // 5-10s throttle
    private static final long RETRY_NETWORK_DELAY_MS = 30_000L;

    private final Queue<String> queue = new ConcurrentLinkedQueue<>();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    private final AtomicBoolean scheduled = new AtomicBoolean(false);
    private final SmartDownloadsManager manager;

    public SmartDownloadWorker(@NonNull SmartDownloadsManager manager) {
        this.manager = manager;
    }

    public void enqueue(@NonNull String videoId) {
        if (!queue.contains(videoId)) {
            queue.offer(videoId);
            Logger.printDebug(() -> "SmartDownloadWorker: queued " + videoId);
        }
        scheduleNext(500L);
    }

    public void clearQueue() {
        queue.clear();
    }

    private void scheduleNext(long delayMs) {
        if (queue.isEmpty()) {
            scheduled.set(false);
            return;
        }
        if (scheduled.compareAndSet(false, true)) {
            executor.schedule(this::processOne, delayMs, TimeUnit.MILLISECONDS);
        }
    }

    private void processOne() {
        scheduled.set(false);
        if (!SmartDownloadsManager.isEnabled() || queue.isEmpty()) {
            return;
        }

        if (SmartDownloadsManager.isWifiOnly() && !isOnUnmeteredWifi()) {
            Logger.printDebug(() -> "SmartDownloadWorker: waiting for unmetered Wi-Fi");
            scheduleNext(RETRY_NETWORK_DELAY_MS);
            return;
        }

        String videoId = queue.poll();
        if (videoId == null) {
            return;
        }

        try {
            manager.ensureCapacityForNewTrack();
            boolean dispatched = SmartDownloadsPatch.dispatchOfflineAdd(videoId);
            if (dispatched) {
                manager.onTrackDownloadDispatched(videoId);
            } else {
                Logger.printDebug(() -> "SmartDownloadWorker: resolver not ready, re-queuing " + videoId);
                queue.offer(videoId);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "SmartDownloadWorker: failed to download " + videoId, ex);
        }

        if (!queue.isEmpty()) {
            scheduleNext(DELAY_BETWEEN_DOWNLOADS_MS);
        }
    }

    private static boolean isOnUnmeteredWifi() {
        Context context = Utils.getContext();
        if (context == null) return false;
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        Network active = cm.getActiveNetwork();
        if (active == null) return false;
        NetworkCapabilities caps = cm.getNetworkCapabilities(active);
        if (caps == null) return false;
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
    }
}
