package io.github.billstark001.worldmirror.mixin;

import io.github.billstark001.worldmirror.download.DownloadCapture;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientLevel.class)
abstract class ClientBlockPredictionMixin {
    @Inject(method = "syncBlockState", at = @At("TAIL"))
    private void worldmirror$captureVerifiedBlockState(
            BlockPos pos, BlockState state, Vec3 playerPos, CallbackInfo result) {
        // A block-update packet can leave its verified state pending until acknowledgement.
        // The loaded chunk's identity stays the same when the prediction is resolved.
        DownloadCapture.queueBlockChange((ClientLevel) (Object) this,
                new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4), "block-prediction-ack");
    }
}
