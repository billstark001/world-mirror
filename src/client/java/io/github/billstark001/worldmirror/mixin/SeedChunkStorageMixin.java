package io.github.billstark001.worldmirror.mixin;

import io.github.billstark001.worldmirror.io.SeedChunkTracking;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.file.Path;

@Mixin(RegionFileStorage.class)
abstract class SeedChunkStorageMixin {
    @Shadow @Final private RegionStorageInfo info;
    @Shadow @Final private Path folder;
    @Unique private SeedChunkTracking.IndexWrites worldmirror$seedWrites;

    @Inject(method = "read", at = @At("RETURN"))
    private void worldmirror$observeReadChunk(ChunkPos pos, CallbackInfoReturnable<CompoundTag> result) {
        if (result.getReturnValue() != null)
            worldmirror$observeChunk(pos, result.getReturnValue());
    }

    @Inject(method = "write", at = @At("RETURN"))
    private void worldmirror$observeWrittenChunk(ChunkPos pos, CompoundTag tag, CallbackInfo result) {
        worldmirror$observeChunk(pos, tag);
    }

    @Unique
    private void worldmirror$observeChunk(ChunkPos pos, CompoundTag tag) {
        if (!"chunk".equals(info.type()))
            return;
        if (worldmirror$seedWrites == null)
            worldmirror$seedWrites = new SeedChunkTracking.IndexWrites(folder);
        worldmirror$seedWrites.observe(info.dimension(), pos, tag);
    }

    @Inject(method = {"flush", "close"}, at = @At("RETURN"))
    private void worldmirror$publishDurableChunks(CallbackInfo result) {
        if (worldmirror$seedWrites != null)
            worldmirror$seedWrites.flushed(info.dimension());
    }
}
