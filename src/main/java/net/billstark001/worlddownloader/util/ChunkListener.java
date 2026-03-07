package net.billstark001.worlddownloader.util;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.nbt.NbtCompound;


@Environment(EnvType.CLIENT)
public class ChunkListener
{
  public static final Map<ChunkPos, NbtCompound> downloadedChunkNbt = new ConcurrentHashMap<>();
  
  public static void addChunkNbt(ChunkPos pos, NbtCompound chunkNbt) {
    downloadedChunkNbt.put(pos, chunkNbt);
    System.out.println("[WorldDownloader] Captured raw NBT chunk: " + String.valueOf(pos));
  }
  
  public static Map<ChunkPos, NbtCompound> getAll() {
    return downloadedChunkNbt;
  }
  
  public static void clear() {
    downloadedChunkNbt.clear();
  }
}
