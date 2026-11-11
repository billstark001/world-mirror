package io.github.billstark001.worldmirror.xaero;

import io.github.billstark001.worldmirror.ui.ChunkStatusCache;
import io.github.billstark001.worldmirror.ui.ChunkStatusCache.StatusTarget;
import io.github.billstark001.worldmirror.ui.ClientDialogs;
import io.github.billstark001.worldmirror.ui.MirrorPrompt;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** Screen-space mirror badge; platform adapters draw the label with Minecraft's font. */
public final class XaeroIndicator {
    private static volatile boolean toastShown;

    public interface Canvas {
        void fill(int x1, int y1, int x2, int y2, int color);
        void textCentered(Component value, int centerX, int boxY, int boxHeight, int color);
    }

    private XaeroIndicator() { }

    public static boolean isXaeroMap(Object screen) {
        for (Class<?> type = screen.getClass(); type != null; type = type.getSuperclass()) {
            if ("xaero.map.gui.GuiMap".equals(type.getName())) return true;
        }
        return false;
    }

    public static void render(Minecraft client, Canvas canvas, int width) {
        StatusTarget target = ChunkStatusCache.targetFor(client);
        if (target == null || !target.currentWorldIsMirror()) {
            toastShown = false;
            return;
        }

        int x = Math.min(36, Math.max(4, width - 50));
        int y = 5;
        canvas.fill(x, y, x + 46, y + 18, 0xD0000000);
        canvas.fill(x, y, x + 46, y + 2, 0xFFFFC000);
        canvas.fill(x, y + 16, x + 46, y + 18, 0xFFFFC000);
        canvas.fill(x, y, x + 2, y + 18, 0xFFFFC000);
        canvas.fill(x + 44, y, x + 46, y + 18, 0xFFFFC000);
        canvas.textCentered(Component.translatable("overlay.worldmirror.mirrorIndicator"),
                x + 23, y, 18, 0xFFFFE066);

        if (!toastShown) {
            toastShown = true;
            client.execute(() -> ClientDialogs.toast(client,
                    new MirrorPrompt.Text("toast.worldmirror.mirrorIndicator.title"),
                    new MirrorPrompt.Text("toast.worldmirror.mirrorIndicator.body")));
        }
    }
}
