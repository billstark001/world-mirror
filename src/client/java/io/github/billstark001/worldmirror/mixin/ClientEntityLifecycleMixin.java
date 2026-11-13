package io.github.billstark001.worldmirror.mixin;

import io.github.billstark001.worldmirror.core.EntityTracker;
import io.github.billstark001.worldmirror.download.DownloadManager;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Environment(EnvType.CLIENT)
@Mixin(ClientLevel.class)
public abstract class ClientEntityLifecycleMixin {
    @Inject(method = "onChunkLoaded", at = @At("TAIL"))
    private void worldmirror$onChunkLoaded(ChunkPos pos, CallbackInfo ci) {
        ClientLevel world = (ClientLevel) (Object) this;
        if (DownloadManager.canCapture(world))
            EntityTracker.onChunkLoaded(world, pos);
    }

    @Inject(method = "unload", at = @At("HEAD"))
    private void worldmirror$onChunkUnloaded(LevelChunk chunk, CallbackInfo ci) {
        ClientLevel world = (ClientLevel) (Object) this;
        if (DownloadManager.canCapture(world))
            EntityTracker.onChunkUnloaded(world, chunk.getPos());
    }
}
