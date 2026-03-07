package net.billstark001.worlddownloader.mixin;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.Map;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.world.chunk.SerializedChunk;
import net.minecraft.world.gen.structure.Structure;
import net.minecraft.structure.StructureStart;
import net.minecraft.structure.StructureContext;
import org.spongepowered.asm.mixin.Mixin;

@Environment(EnvType.CLIENT)
@Mixin({SerializedChunk.class})
public interface SerializedChunkInvoker {
  @Invoker("writeStructures")
  static NbtCompound invokeWriteStructures(StructureContext context, ChunkPos pos, Map<Structure, StructureStart> starts, Map<Structure, LongSet> references) {
    throw new AssertionError();
  }
}
