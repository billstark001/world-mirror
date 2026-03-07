package net.billstark001.worlddownloader.mixin;
import net.fabricmc.api.EnvType;
import net.billstark001.worlddownloader.util.ChunkListener;
import net.billstark001.worlddownloader.util.ClientChunkSerializer;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.world.ClientWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Environment(EnvType.CLIENT)
@Mixin({ClientPlayNetworkHandler.class})
public abstract class ChunkDataMixin {
  @Inject(method = {"onChunkData"}, at = {@At("TAIL")})
  private void onChunkData(ChunkDataS2CPacket packet, CallbackInfo ci) {
    ClientPlayNetworkHandler handler = (ClientPlayNetworkHandler)this;
    ClientWorld world = handler.getWorld();
    if (world == null) {
      System.err.println("❌ Client world is null during chunk data processing");
      
      return;
    } 
    int x = packet.getChunkX();
    int z = packet.getChunkZ();
    ChunkPos pos = new ChunkPos(x, z);
    WorldChunk WorldChunk = world.method_8497(x, z);
    
    if (WorldChunk != null) {
      
      try {
        NbtCompound chunkNbt = ClientChunkSerializer.serialize(world, (Chunk)WorldChunk);
        ChunkListener.addChunkNbt(pos, chunkNbt);
        System.out.println("✅ Saved chunk NBT for: " + String.valueOf(pos));
      }
      catch (Exception e) {
        System.err.println("❌ Failed to capture chunk NBT for " + String.valueOf(pos) + ": " + e.getMessage());
        e.printStackTrace();
      } 
    } else {
      System.out.println("⚠ Chunk at " + String.valueOf(pos) + " not fully loaded when onChunkData processed");
    } 
  }
}
