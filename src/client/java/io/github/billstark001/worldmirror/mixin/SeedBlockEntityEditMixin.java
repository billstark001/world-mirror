package io.github.billstark001.worldmirror.mixin;

import io.github.billstark001.worldmirror.io.SeedChunkTracking;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Level.class)
abstract class SeedBlockEntityEditMixin {
    @Inject(method = "blockEntityChanged", at = @At("TAIL"))
    private void worldmirror$promoteChangedBlockEntity(BlockPos pos, CallbackInfo result) {
        if ((Object) this instanceof ServerLevel level && SeedChunkTracking.tracks(level)) {
            var chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
            if (chunk != null)
                SeedChunkTracking.changed(chunk);
        }
    }
}
