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

@SuppressWarnings("unused")
public final class SmartDownloadsPatch extends BaseInAppDownloadsPatch {

    public interface CommandDispatcher {
        void patch_resolveCommand(byte[] commandBytes);
    }

    public interface OfflineHandler {
        void patch_handleOffline(byte[] commandBytes, byte[] endpointBytes);
    }

    private static volatile WeakReference<CommandDispatcher> dispatcherRef = new WeakReference<>(null);
    private static volatile WeakReference<OfflineHandler> offlineHandlerRef = new WeakReference<>(null);

    private SmartDownloadsPatch() {
    }

    public static void setCommandDispatcher(@NonNull CommandDispatcher dispatcher) {
        dispatcherRef = new WeakReference<>(dispatcher);
    }

    public static void setOfflineHandler(@NonNull OfflineHandler handler) {
        offlineHandlerRef = new WeakReference<>(handler);
    }

    public static void onVideoTimeUpdate(long currentPlaybackTimeMs) {
        try {
            SmartDownloadsManager.getInstance().onPlaybackTick(currentPlaybackTimeMs);
        } catch (Exception ex) {
            Logger.printException(() -> "SmartDownloads: onVideoTimeUpdate failure", ex);
        }
    }

    public static boolean dispatchOfflineAdd(@NonNull String videoId) {
        OfflineCommand cmd = saveVideoCommand(videoId, List.of());
        return dispatch(cmd.toByteArray(), cmd.getOfflineVideoEndpoint().toByteArray());
    }

    public static boolean dispatchOfflineRemove(@NonNull String videoId) {
        OfflineVideoAction removeAction = findRemoveAction();
        if (removeAction == null) return false;
        OfflineVideoEndpoint endpoint = OfflineVideoEndpoint.newBuilder()
                .setVideoId(videoId)
                .setAction(removeAction)
                .build();
        OfflineCommand cmd = OfflineCommand.newBuilder()
                .setOfflineVideoEndpoint(endpoint)
                .build();
        return dispatch(cmd.toByteArray(), endpoint.toByteArray());
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

    private static boolean dispatch(byte[] commandBytes, byte[] endpointBytes) {
        OfflineHandler offlineHandler = offlineHandlerRef.get();
        CommandDispatcher dispatcher = dispatcherRef.get();
        if (offlineHandler == null && dispatcher == null) {
            return false;
        }
        try {
            Utils.runOnMainThreadNowOrLater(() -> {
                try {
                    if (offlineHandler != null) {
                        offlineHandler.patch_handleOffline(commandBytes, endpointBytes);
                    } else {
                        dispatcher.patch_resolveCommand(commandBytes);
                    }
                } catch (Throwable ex) {
                    Logger.printException(() -> "SmartDownloads: dispatch error", ex);
                    if (Utils.getContext() != null) {
                        Toast.makeText(Utils.getContext(),
                                "SmartDL Err: " + ex.getClass().getSimpleName() + ": " + ex.getMessage(),
                                Toast.LENGTH_LONG).show();
                    }
                }
            });
            return true;
        } catch (Exception ex) {
            Logger.printException(() -> "SmartDownloads: dispatch failure", ex);
            return false;
        }
    }
}
