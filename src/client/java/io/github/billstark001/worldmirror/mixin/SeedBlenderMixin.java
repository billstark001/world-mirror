package io.github.billstark001.worldmirror.mixin;

import io.github.billstark001.worldmirror.io.SeedChunkTracking;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
#if MC_VERSION < 260300
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ProtoChunk;
#endif

@Mixin(Blender.class)
abstract class SeedBlenderMixin {
    @Inject(method = "of", at = @At("HEAD"), cancellable = true)
    private static void worldmirror$respectBlendSetting(
            WorldGenRegion region, CallbackInfoReturnable<Blender> result) {
        if (region != null && SeedChunkTracking.disablesTerrainBlending(region.getLevel()))
            result.setReturnValue(Blender.empty());
    }

    @Inject(method = "generateBorderTicks", at = @At("HEAD"), cancellable = true)
    private static void worldmirror$skipNativeBlendingBorderTicks(
            WorldGenRegion region, ChunkAccess center, CallbackInfo callback) {
        if (SeedChunkTracking.disablesTerrainBlending(region.getLevel()))
            callback.cancel();
    }

#if MC_VERSION < 260300
    @Inject(method = "addAroundOldChunksCarvingMaskFilter", at = @At("HEAD"), cancellable = true)
    private static void worldmirror$skipNativeBlendingCarvingMask(
            WorldGenLevel level, ProtoChunk center, CallbackInfo callback) {
        if (SeedChunkTracking.disablesTerrainBlending(level.getLevel()))
            callback.cancel();
    }
#endif
}
