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
            schedule
