package io.github.billstark001.worldmirror.mixin;

import io.github.billstark001.worldmirror.ui.map.ChunkWorldOverlayRenderApi;
import net.minecraft.client.renderer.LevelRenderer;
#if MC_VERSION >= 260102
import net.minecraft.client.renderer.state.level.LevelRenderState;
#else
import net.minecraft.client.renderer.state.LevelRenderState;
#endif
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

#if MC_VERSION >= 260200
    @Inject(method = "render", at = @At(value = "INVOKE",
#else
    @Inject(method = "renderLevel", at = @At(value = "INVOKE",
#endif
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
