package net.billstark001.worlddownloader.mixin;

import net.fabricmc.api.EnvType;
import net.billstark001.worlddownloader.util.ChunkListener;
import net.billstark001.worlddownloader.util.ClientChunkSerializer;
import net.fabricmc.api.Environment;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.world.ClientWorld;
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
        ClientWorld world = this.getWorld();
        if (world == null) {
            System.err.println("❌ Client world is null during chunk data processing");

            return;
        }
        int x = packet.getChunkX();
        int z = packet.getChunkZ();
        ChunkPos pos = new ChunkPos(x, z);
        WorldChunk WorldChunk = world.getChunk(x, z);

        if (WorldChunk != null) {

            try {
                NbtCompound chunkNbt = ClientChunkSerializer.serialize(world, WorldChunk);
                ChunkListener.addChunkNbt(pos, chunkNbt);
                System.out.println("✅ Saved chunk NBT for: " + pos);
            } catch (Exception e) {
                System.err.println("❌ Failed to capture chunk NBT for " + pos + ": " + e.getMessage());
                e.printStackTrace();
            }
        } else {
            System.out.println("⚠ Chunk at " + pos + " not fully loaded when onChunkData processed");
        }
    }
}
