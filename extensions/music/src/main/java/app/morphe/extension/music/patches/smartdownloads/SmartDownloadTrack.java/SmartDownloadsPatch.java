package app.morphe.extension.music.patches.smartdownloads;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.protobuf.ExtensionRegistryLite;
import com.google.protobuf.MessageLite;

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
public final class SmartDownloadsPatch {

    public interface CommandDispatcher {
        void patch_resolveCommand(MessageLite command);
    }

    private static volatile WeakReference<CommandDispatcher> dispatcherRef = new WeakReference<>(null);
    @Nullable
    private static volatile MessageLite commandDefaultInstance;

    private SmartDownloadsPatch() {
    }

    /** Injection point: called when CommandResolver is constructed or resolves a command. */
    public static void setCommandDispatcher(@NonNull CommandDispatcher dispatcher,
                                            @NonNull MessageLite defaultInstance) {
        dispatcherRef = new WeakReference<>(dispatcher);
        commandDefaultInstance = defaultInstance;
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
        OfflineCommand cmd = BaseInAppDownloadsPatch.saveVideoCommand(videoId, List.of());
        return dispatchBytes(cmd.toByteArray());
    }

    /**
     * Dispatches a native OfflineCommand to remove an evicted song from offline storage.
     */
    public static boolean dispatchOfflineRemove(@NonNull String videoId) {
        OfflineCommand cmd = OfflineCommand.newBuilder()
                .setOfflineVideoEndpoint(
                        OfflineVideoEndpoint.newBuilder()
                                .setVideoId(videoId)
                                .setAction(OfflineVideoAction.OFFLINE_VIDEO_ACTION_REMOVE))
                .build();
        return dispatchBytes(cmd.toByteArray());
    }

    private static boolean dispatchBytes(byte[] commandBytes) {
        CommandDispatcher dispatcher = dispatcherRef.get();
        MessageLite defaultInstance = commandDefaultInstance;
        if (dispatcher == null || defaultInstance == null) {
            return false;
        }
        try {
            MessageLite parsed = defaultInstance.getParserForType()
                    .parseFrom(commandBytes, ExtensionRegistryLite.getGeneratedRegistry());
            Utils.runOnMainThreadNowOrLater(() -> dispatcher.patch_resolveCommand(parsed));
            return true;
        } catch (Exception ex) {
            Logger.printException(() -> "SmartDownloads: dispatchBytes failure", ex);
            return false;
        }
    }
}
