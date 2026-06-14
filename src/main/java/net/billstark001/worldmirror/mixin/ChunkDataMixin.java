package net.billstark001.worldmirror.mixin;

import net.billstark001.worldmirror.download.DownloadManager;
import net.billstark001.worldmirror.core.ChunkListener;
import net.billstark001.worldmirror.io.ChunkSerializer;
import net.billstark001.worldmirror.util.WMLogger;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Environment(EnvType.CLIENT)
@Mixin({ClientPacketListener.class})
public abstract class ChunkDataMixin {

    @Shadow
    public abstract ClientLevel getLevel();

    @Inject(method = {"handleLevelChunkWithLight"}, at = {@At("TAIL")})
    private void onChunkData(ClientboundLevelChunkWithLightPacket packet, CallbackInfo ci) {
        if (!DownloadManager.isActive()) return;

        ClientLevel world = this.getLevel();
        if (world == null) {
            WMLogger.warn("Client world is null during chunk data processing.");
            return;
        }

        int x = packet.getX();
        int z = packet.getZ();
        ChunkPos pos = new ChunkPos(x, z);
        LevelChunk worldChunk = world.getChunk(x, z);

        if (worldChunk != null) {
            try {
                if (ChunkSerializer.isChunkEmpty(worldChunk)) {
                    WMLogger.debug("Skipping empty chunk at " + pos);
                    return;
                }
                CompoundTag chunkNbt = ChunkSerializer.serialize(world, worldChunk);
                // Pass the dimension key so ChunkListener can store chunks per dimension
                ChunkListener.addChunkNbt(world.dimension(), pos, chunkNbt);
            } catch (Exception e) {
                WMLogger.warn("Failed to capture chunk NBT for " + pos + ": " + e.getMessage());
            }
        } else {
            WMLogger.debug("Chunk at " + pos + " not fully loaded when onChunkData fired.");
        }
    }
}

