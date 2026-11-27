package app.morphe.extension.music.patches.smartdownloads;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.BatteryManager;
import android.os.StatFs;

import androidx.annotation.NonNull;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

public final class SmartDownloadWorker {

    private static final long DELAY_BETWEEN_DOWNLOADS_MS = 7_000L;
    private static final long RETRY_GUARD_DELAY_MS = 30_000L;
    private static final long MIN_FREE_STORAGE_BYTES = 500L * 1024L * 1024L; // 500 MB
    private static final int MIN_BATTERY_PCT = 15;

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
            scheduleNext(RETRY_GUARD_DELAY_MS);
            return;
        }

        if (SmartDownloadsManager.isBatteryGuardEnabled() && isLowBatteryAndNotCharging()) {
            Logger.printDebug(() -> "SmartDownloadWorker: paused due to low battery (<15%)");
            scheduleNext(RETRY_GUARD_DELAY_MS);
            return;
        }

        if (!hasEnoughStorage()) {
            Logger.printDebug(() -> "SmartDownloadWorker: low storage (<500MB), skipping download");
            scheduleNext(RETRY_GUARD_DELAY_MS);
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
        return caps != null
                && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
    }

    private static boolean isLowBatteryAndNotCharging() {
        Context context = Utils.getContext();
        if (context == null) return false;
        Intent battery = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery == null) return false;
        int status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL;
        if (charging) return false;
        int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        if (level < 0 || scale <= 0) return false;
        int pct = (int) ((level * 100f) / scale);
        return pct < MIN_BATTERY_PCT;
    }

    private static boolean hasEnoughStorage() {
        Context context = Utils.getContext();
        if (context == null) return true;
        try {
            StatFs stat = new StatFs(context.getFilesDir().getAbsolutePath());
            return stat.getAvailableBytes() >= MIN_FREE_STORAGE_BYTES;
        } catch (Exception ex) {
            return true;
        }
    }
}
