package io.github.billstark001.worldmirror.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.billstark001.worldmirror.io.SeedChunkTracking;
import io.github.billstark001.worldmirror.legacy.LegacyBlockEntityRepair;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

@Mixin(SerializableChunkData.class)
abstract class MirrorBlockEntityLoadMixin {
    @WrapOperation(method = "read",
            at = @At(value = "INVOKE",
                    target =
                            "Lnet/minecraft/world/level/chunk/storage/SerializableChunkData;postLoadChunk(Lnet/minecraft/server/level/ServerLevel;Ljava/util/List;Ljava/util/List;)Lnet/minecraft/world/level/chunk/LevelChunk$PostLoadProcessor;"))
    private LevelChunk.PostLoadProcessor
    worldmirror$repairLegacyBlockEntities(ServerLevel level, List<CompoundTag> entities,
            List<CompoundTag> blockEntities, Operation<LevelChunk.PostLoadProcessor> vanilla) {
        var loader = vanilla.call(level, entities, blockEntities);
        if (blockEntities.isEmpty() || !SeedChunkTracking.tracks(level))
            return loader;
        return chunk -> {
            if (!SeedChunkTracking.canCleanSavedBlockEntities(level, chunk)) {
                loader.run(chunk);
                return;
            }
            var retained = LegacyBlockEntityRepair.filter(chunk, blockEntities);
            if (retained == blockEntities) {
                loader.run(chunk);
                return;
            }
            var repaired = vanilla.call(level, entities, retained);
            if (repaired != null)
                repaired.run(chunk);
            chunk.markUnsaved();
        };
    }
}
