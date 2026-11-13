package io.github.billstark001.worldmirror.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import io.github.billstark001.worldmirror.io.SeedChunkTracking;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@Mixin(ChunkMap.class)
abstract class SeedChunkLoadMixin {
    @Shadow @Final private ServerLevel level;

    @WrapOperation(method = "scheduleChunkLoad",
            at = @At(value = "INVOKE",
                    target =
                            "Lnet/minecraft/server/level/ChunkMap;readChunk(Lnet/minecraft/world/level/ChunkPos;)Ljava/util/concurrent/CompletableFuture;"))
    private CompletableFuture<Optional<CompoundTag>>
    worldmirror$observeRawChunkLoad(ChunkMap storage, ChunkPos pos,
            Operation<CompletableFuture<Optional<CompoundTag>>> load,
            @Share("rawLoad") LocalRef<CompletableFuture<Optional<CompoundTag>>> rawLoad) {
        var read = load.call(storage, pos);
        // The saved-data read returns absent before parsing. The result of scheduleChunkLoad can also be
        // a ProtoChunk after a corrupt or unsupported save, so observing the final result alone is unsafe.
        if (SeedChunkTracking.recordsFreshGeneration(level))
            rawLoad.set(read);
        return read;
    }

    @Inject(method = "scheduleChunkLoad", at = @At("RETURN"), cancellable = true)
    private void worldmirror$associateAbsence(ChunkPos pos,
            CallbackInfoReturnable<CompletableFuture<ChunkAccess>> result,
            @Share("rawLoad") LocalRef<CompletableFuture<Optional<CompoundTag>>> rawLoad) {
        var read = rawLoad.get();
        if (read != null) {
            result.setReturnValue(result.getReturnValue().thenApply(chunk -> {
                if (!read.isCompletedExceptionally() && read.join().isEmpty() && chunk instanceof ProtoChunk)
                    SeedChunkTracking.markAbsent(chunk);
                return chunk;
            }));
        }
    }
}
