package net.billstark001.worldmirror.mixin;

import net.billstark001.worldmirror.download.DownloadManager;
import net.billstark001.worldmirror.core.ChunkListener;
import net.billstark001.worldmirror.io.ChunkSerializer;
import net.billstark001.worldmirror.util.WMLogger;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.world.chunk.WorldChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Environment(EnvType.CLIENT)
@Mixin({ClientPlayNetworkHandler.class})
public abstract class ChunkDataMixin {

    @Shadow
    public abstract ClientWorld getWorld();

    @Inject(method = {"onChunkData"}, at = {@At("TAIL")})
    private void onChunkData(ChunkDataS2CPacket packet, CallbackInfo ci) {
        if (!DownloadManager.isActive()) return;

        ClientWorld world = this.getWorld();
        if (world == null) {
            WMLogger.warn("Client world is null during chunk data processing.");
            return;
        }

        int x = packet.getChunkX();
        int z = packet.getChunkZ();
        ChunkPos pos = new ChunkPos(x, z);
        WorldChunk worldChunk = world.getChunk(x, z);

        if (worldChunk != null) {
            try {
                if (ChunkSerializer.isChunkEmpty(worldChunk)) {
                    WMLogger.debug("Skipping empty chunk at " + pos);
                    return;
                }
                NbtCompound chunkNbt = ChunkSerializer.serialize(world, worldChunk);
                // Pass the dimension key so ChunkListener can store chunks per dimension
                ChunkListener.addChunkNbt(world.getRegistryKey(), pos, chunkNbt);
            } catch (Exception e) {
                WMLogger.warn("Failed to capture chunk NBT for " + pos + ": " + e.getMessage());
            }
        } else {
            WMLogger.debug("Chunk at " + pos + " not fully loaded when onChunkData fired.");
        }
    }
}

