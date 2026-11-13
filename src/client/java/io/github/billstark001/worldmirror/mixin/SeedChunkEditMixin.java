package io.github.billstark001.worldmirror.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.billstark001.worldmirror.io.SeedChunkTracking;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelChunk.class)
abstract class SeedChunkEditMixin {
    @WrapMethod(method = "postProcessGeneration")
    private void worldmirror$recordPostprocessedTerrain(ServerLevel level, Operation<Void> postprocess) {
        var chunk = (LevelChunk) (Object) this;
        boolean fresh = SeedChunkTracking.beforePostprocess(level, chunk);
        boolean completed = false;
        try {
            postprocess.call(level);
            completed = true;
        } finally {
            if (fresh)
                SeedChunkTracking.afterPostprocess(level, chunk, completed);
        }
    }

    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void worldmirror$promoteChangedTerrain(
            BlockPos pos, BlockState state, int flags, CallbackInfoReturnable<BlockState> result) {
        if (result.getReturnValue() != null && result.getReturnValue() != state)
            SeedChunkTracking.changed((LevelChunk) (Object) this);
    }
}
