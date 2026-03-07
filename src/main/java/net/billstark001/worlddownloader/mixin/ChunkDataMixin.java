package net.billstark001.worlddownloader.mixin;

import net.billstark001.worlddownloader.download.DownloadManager;
import net.billstark001.worlddownloader.util.ChunkListener;
import net.billstark001.worlddownloader.util.ClientChunkSerializer;
import net.billstark001.worlddownloader.util.WDLogger;
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
            WDLogger.warn("Client world is null during chunk data processing.");
            return;
        }

        int x = packet.getChunkX();
        int z = packet.getChunkZ();
        ChunkPos pos = new ChunkPos(x, z);
        WorldChunk worldChunk = world.getChunk(x, z);

        if (worldChunk != null) {
            try {
                NbtCompound chunkNbt = ClientChunkSerializer.serialize(world, worldChunk);
                ChunkListener.addChunkNbt(pos, chunkNbt);
            } catch (Exception e) {
                WDLogger.warn("Failed to capture chunk NBT for " + pos + ": " + e.getMessage());
            }
        } else {
            WDLogger.debug("Chunk at " + pos + " not fully loaded when onChunkData fired.");
        }
    }
}

