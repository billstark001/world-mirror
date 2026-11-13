package io.github.billstark001.worldmirror.ui.input;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.input.MouseButtonEvent;

/** Mouse predicates for widgets handling Minecraft input events directly. */
public final class MouseInput {
    private MouseInput() {}

    public static boolean isPrimary(MouseButtonEvent event) {
        return event.button() == InputConstants.MOUSE_BUTTON_LEFT;
    }
}
