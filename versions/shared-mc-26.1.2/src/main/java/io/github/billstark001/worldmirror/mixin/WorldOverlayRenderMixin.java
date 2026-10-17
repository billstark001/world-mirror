package io.github.billstark001.worldmirror.mixin;

import io.github.billstark001.worldmirror.ui.ChunkWorldOverlayRenderApi;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Draw after the entire frame graph, while the world projection/view are still active. */
@Mixin(LevelRenderer.class)
public abstract class WorldOverlayRenderMixin {
    @Shadow @Final private LevelRenderState levelRenderState;

    @Inject(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelTargetBundle;clear()V"))
    private void worldmirror$drawOverlay(CallbackInfo ci) {
        var camera = levelRenderState.cameraRenderState;
        if (camera != null && camera.pos != null) {
            ChunkWorldOverlayRenderApi.renderLate(camera.pos);
        }
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void worldmirror$closeOverlay(CallbackInfo ci) {
        ChunkWorldOverlayRenderApi.close();
    }
}
