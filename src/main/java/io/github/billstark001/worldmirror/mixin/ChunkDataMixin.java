package io.github.billstark001.worldmirror.mixin;

import io.github.billstark001.worldmirror.download.DownloadManager;
import io.github.billstark001.worldmirror.core.ChunkListener;
import io.github.billstark001.worldmirror.core.LightingUpdate;
import io.github.billstark001.worldmirror.io.ChunkSerializer;
import io.github.billstark001.worldmirror.util.WMLogger;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
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

    /**
     * Vanilla queues both initial and light-only packet data before applying it.
     * Hooking this method, rather than the packet handler tail, therefore sees
     * the exact masks after the client light engine has accepted them.
     */
    @Inject(method = "applyLightData", at = @At("TAIL"))
    private void onLightDataApplied(
            int chunkX,
            int chunkZ,
            ClientboundLightUpdatePacketData lightData,
            boolean markDirty,
            CallbackInfo ci) {
        if (!DownloadManager.isActive()) return;

        ClientLevel world = this.getLevel();
        if (world == null) return;

        ChunkPos pos = new ChunkPos(chunkX, chunkZ);
        try {
            var lightEngine = world.getChunkSource().getLightEngine();
            boolean applied = ChunkListener.applyLightUpdate(
                    world.dimension(), pos, new LightingUpdate(
                            lightEngine.getMinLightSection(), lightEngine.getLightSectionCount(),
                            lightData.getBlockYMask(), lightData.getEmptyBlockYMask(),
                            lightData.getBlockUpdates(),
                            lightData.getSkyYMask(), lightData.getEmptySkyYMask(),
                            lightData.getSkyUpdates()));
            if (applied) {
                DownloadManager.markLightUpdateDirty(world, pos);
            } else {
                DownloadManager.queueLightUpdateCapture(world, pos);
            }
        } catch (Exception e) {
            WMLogger.warn("Failed to capture applied light update for " + pos + ": " + e.getMessage());
        }
    }
}

