package app.morphe.extension.music.patches.smartdownloads;

import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.List;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.innertube.Offline.OfflineCommand;
import app.morphe.extension.shared.innertube.Offline.OfflineVideoAction;
import app.morphe.extension.shared.innertube.Offline.OfflineVideoEndpoint;
import app.morphe.extension.shared.patches.BaseInAppDownloadsPatch;

/**
 * Extension bridge for Smart Downloads hooks and native CommandResolver dispatch.
 */
@SuppressWarnings("unused")
public final class SmartDownloadsPatch extends BaseInAppDownloadsPatch {

    public interface CommandDispatcher {
        void patch_resolveCommand(byte[] commandBytes);
    }

    private static volatile WeakReference<CommandDispatcher> dispatcherRef = new WeakReference<>(null);

    private SmartDownloadsPatch() {
    }

    /** Injection point: called when CommandResolver resolves a command. */
    public static void setCommandDispatcher(@NonNull CommandDispatcher dispatcher) {
        dispatcherRef = new WeakReference<>(dispatcher);
    }

    /** Injection point: hooked into musicVideoTimeHook (~1000ms). */
    public static void onVideoTimeUpdate(long currentPlaybackTimeMs) {
        try {
            SmartDownloadsManager.getInstance().onPlaybackTick(currentPlaybackTimeMs);
        } catch (Exception ex) {
            Logger.printException(() -> "SmartDownloads: onVideoTimeUpdate failure", ex);
        }
    }

    /**
     * Dispatches a native in-app OfflineCommand to add a song to YouTube Music's offline library.
     */
    public static boolean dispatchOfflineAdd(@NonNull String videoId) {
        OfflineCommand cmd = saveVideoCommand(videoId, List.of());
        return dispatchBytes(cmd.toByteArray());
    }

    /**
     * Dispatches a native OfflineCommand to remove an evicted song from offline storage.
     */
    public static boolean dispatchOfflineRemove(@NonNull String videoId) {
        OfflineVideoAction removeAction = findRemoveAction();
        if (removeAction == null) {
            return false;
        }
        OfflineCommand cmd = OfflineCommand.newBuilder()
                .setOfflineVideoEndpoint(
                        OfflineVideoEndpoint.newBuilder()
                                .setVideoId(videoId)
                                .setAction(removeAction))
                .build();
        return dispatchBytes(cmd.toByteArray());
    }

    @Nullable
    private static OfflineVideoAction findRemoveAction() {
        for (OfflineVideoAction action : OfflineVideoAction.values()) {
            String name = action.name();
            if (name.contains("DELETE") || name.contains("REMOVE")) {
                return action;
            }
        }
        return OfflineVideoAction.forNumber(2);
    }

    private static boolean dispatchBytes(byte[] commandBytes) {
        CommandDispatcher dispatcher = dispatcherRef.get();
        if (dispatcher == null) {
            return false;
        }
        try {
            Utils.runOnMainThreadNowOrLater(() -> {
                try {
                    dispatcher.patch_resolveCommand(commandBytes);
                } catch (Throwable ex) {
                    Logger.printException(() -> "SmartDownloads: patch_resolveCommand error", ex);
                    if (Utils.getContext() != null) {
                        Toast.makeText(Utils.getContext(),
                                "SmartDL Err: " + ex.getClass().getSimpleName() + ": " + ex.getMessage(),
                                Toast.LENGTH_LONG).show();
                    }
                }
            });
            return true;
        } catch (Exception ex) {
            Logger.printException(() -> "SmartDownloads: dispatchBytes failure", ex);
            return false;
        }
    }
}
