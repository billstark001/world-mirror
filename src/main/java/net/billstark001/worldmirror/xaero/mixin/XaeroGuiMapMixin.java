package net.billstark001.worldmirror.xaero.mixin;

import net.billstark001.worldmirror.xaero.XaeroWorldMapOverlayRenderer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "xaero.map.gui.GuiMap", remap = false)
public abstract class XaeroGuiMapMixin {
    @Inject(
            method = "render",
            require = 0,
            at = @At(
                    value = "INVOKE",
                    target = "Lxaero/map/element/MapElementRenderHandler;render(Lxaero/map/gui/GuiMap;Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;Lxaero/map/graphics/renderer/multitexture/MultiTextureRenderTypeRendererProvider;DDIIDDDDDFZLxaero/map/element/HoveredMapElementHolder;Lnet/minecraft/client/Minecraft;F)Lxaero/map/element/HoveredMapElementHolder;",
                    shift = At.Shift.BEFORE
            ),
            remap = false
    )
    private void worldmirror$extractWorldMapOverlay(GuiGraphics ctx, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        Screen screen = (Screen) (Object) this;
        XaeroWorldMapOverlayRenderer.render(screen, ctx, screen.width, screen.height);
    }
}
