package app.morphe.extension.music.patches.smartdownloads;

import android.widget.Toast;

import androidx.annotation.Nullable;

import com.google.protobuf.MessageLite;

import app.morphe.extension.music.patches.components.SongMenuFilter;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.innertube.Menu.MenuCommand;
import app.morphe.extension.shared.innertube.Menu.MenuIcon;
import app.morphe.extension.shared.innertube.Menu.MenuItem;
import app.morphe.extension.shared.innertube.Menu.MenuNavigationItemRenderer;
import app.morphe.extension.shared.innertube.Menu.MenuRenderer;
import app.morphe.extension.shared.innertube.Menu.MenuText;
import app.morphe.extension.shared.innertube.Menu.MenuTextRun;

/**
 * Adds "Pin to Smart Cache" / "Unpin from Smart Cache" to YouTube Music's 3-dot song menu.
 */
@SuppressWarnings("unused")
public final class SmartCacheMenuItem {

    private static final int ICON_TYPE_OFFLINE_DOWNLOAD = 147;
    private static final String LABEL_PIN = "Pin to Smart Cache";
    private static final String LABEL_UNPIN = "Unpin from Smart Cache";
    private static final byte[][] NO_ITEMS = new byte[0][];

    private static volatile String lastMenuVideoId = "";

    private SmartCacheMenuItem() {
    }

    public static byte[][] getSmartMenuItems(@Nullable Object menu) {
        try {
            if (!SmartDownloadsManager.isEnabled() || !(menu instanceof MessageLite message)) {
                return NO_ITEMS;
            }
            MenuRenderer renderer = MenuRenderer.parseFrom(message.toByteArray());
            String videoId = extractVideoId(renderer);
            if (videoId == null || videoId.isEmpty()) {
                videoId = SongMenuFilter.getMenuVideoId();
            }
            if (videoId == null || videoId.isEmpty()) {
                return NO_ITEMS;
            }
            lastMenuVideoId = videoId;

            boolean pinned = SmartDownloadsManager.getInstance().isPinned(videoId);
            String label = pinned ? LABEL_UNPIN : LABEL_PIN;

            byte[] item = MenuItem.newBuilder()
                    .setMenuNavigationItemRenderer(
                            MenuNavigationItemRenderer.newBuilder()
                                    .setText(MenuText.newBuilder()
                                            .addRuns(MenuTextRun.newBuilder().setText(label)))
                                    .setIcon(MenuIcon.newBuilder().setIconType(ICON_TYPE_OFFLINE_DOWNLOAD)))
                    .build()
                    .toByteArray();
            return new byte[][]{item};
        } catch (Exception ex) {
            Logger.printException(() -> "SmartCacheMenuItem: getSmartMenuItems failure", ex);
            return NO_ITEMS;
        }
    }

    public static void onMenuItemClick(@Nullable Object item) {
        try {
            if (!(item instanceof MessageLite message)) return;
            MenuNavigationItemRenderer renderer = MenuItem.parseFrom(message.toByteArray())
                    .getMenuNavigationItemRenderer();
            if (renderer.getText().getRunsCount() == 0) return;

            String text = renderer.getText().getRuns(0).getText();
            if (!LABEL_PIN.equals(text) && !LABEL_UNPIN.equals(text)) return;

            String videoId = lastMenuVideoId;
            if (videoId == null || videoId.isEmpty()) return;

            boolean nowPinned = SmartDownloadsManager.getInstance().togglePin(videoId, null, null);
            String toastMsg = nowPinned ? "Pinned to Smart Cache" : "Unpinned from Smart Cache";
            if (Utils.getContext() != null) {
                Toast.makeText(Utils.getContext(), toastMsg, Toast.LENGTH_SHORT).show();
            }
        } catch (Exception ex) {
            Logger.printException(() -> "SmartCacheMenuItem: onMenuItemClick failure", ex);
        }
    }

    @Nullable
    private static String extractVideoId(MenuRenderer menu) {
        String queueVideoId = null;
        for (MenuItem item : menu.getItemsList()) {
            MenuCommand command = item.getMenuNavigationItemRenderer().getNavigationEndpoint();
            if (command.hasOfflineVideoEndpoint()) {
                String id = command.getOfflineVideoEndpoint().getVideoId();
                if (!id.isEmpty()) return id;
            }
            if (queueVideoId == null && command.hasQueueAddEndpoint()) {
                String id = command.getQueueAddEndpoint().getQueueTarget().getVideoId();
                if (!id.isEmpty()) queueVideoId = id;
            }
        }
        return queueVideoId;
    }
}
