package net.querz.mca;
import java.io.IOException;
import java.io.RandomAccessFile;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.querz.nbt.tag.CompoundTag;

@Environment(EnvType.CLIENT)
public class MCAFile {
  public static final int DEFAULT_DATA_VERSION = 1628;
  private int regionX;
  
  public MCAFile(int regionX, int regionZ) {
    this.regionX = regionX;
    this.regionZ = regionZ;
  }
  private int regionZ; private Chunk[] chunks;
  public void deserialize(RandomAccessFile raf) throws Exception {
    deserialize(raf, -1L);
  }
  
  public void deserialize(RandomAccessFile raf, long loadFlags) throws Exception {
    this.chunks = new Chunk[1024];
    
    for (int i = 0; i < 1024; i++) {
      raf.seek((i * 4));
      int offset = raf.read() << 16;
      offset |= (raf.read() & 0xFF) << 8;
      offset |= raf.read() & 0xFF;
      if (raf.readByte() != 0) {
        raf.seek((4096 + i * 4));
        int timestamp = raf.readInt();
        Chunk chunk = new Chunk(timestamp);
        raf.seek((4096 * offset + 4));
        chunk.deserialize(raf, loadFlags);
        this.chunks[i] = chunk;
      } 
    } 
  }

  
  public int serialize(RandomAccessFile raf) throws IOException {
    return serialize(raf, false);
  }
  
  public int serialize(RandomAccessFile raf, boolean changeLastUpdate) throws IOException {
    int globalOffset = 2;
    int lastWritten = 0;
    int timestamp = (int)(System.currentTimeMillis() / 1000L);
    int chunksWritten = 0;
    int chunkXOffset = MCAUtil.regionToChunk(this.regionX);
    int chunkZOffset = MCAUtil.regionToChunk(this.regionZ);
    if (this.chunks == null) {
      return 0;
    }
    for (int cx = 0; cx < 32; cx++) {
      for (int cz = 0; cz < 32; cz++) {
        int index = getChunkIndex(cx, cz);
        Chunk chunk = this.chunks[index];
        if (chunk != null) {
          raf.seek((4096 * globalOffset));
          lastWritten = chunk.serialize(raf, chunkXOffset + cx, chunkZOffset + cz);
          if (lastWritten != 0) {
            chunksWritten++;
            int sectors = (lastWritten >> 12) + ((lastWritten % 4096 == 0) ? 0 : 1);
            raf.seek((index * 4));
            raf.writeByte(globalOffset >>> 16);
            raf.writeByte(globalOffset >> 8 & 0xFF);
            raf.writeByte(globalOffset & 0xFF);
            raf.writeByte(sectors);
            raf.seek((index * 4 + 4096));
            raf.writeInt(changeLastUpdate ? timestamp : chunk.getLastMCAUpdate());
            globalOffset += sectors;
          } 
        } 
      } 
    } 
    
    if (lastWritten % 4096 != 0) {
      raf.seek((globalOffset * 4096 - 1));
      raf.write(0);
    } 
    
    return chunksWritten;
  }

  
  public void setChunk(int index, Chunk chunk) {
    checkIndex(index);
    if (this.chunks == null) {
      this.chunks = new Chunk[1024];
    }
    
    this.chunks[index] = chunk;
  }
  
  public void setChunk(int chunkX, int chunkZ, Chunk chunk) {
    setChunk(getChunkIndex(chunkX, chunkZ), chunk);
  }
  
  public Chunk getChunk(int index) {
    checkIndex(index);
    return (this.chunks == null) ? null : this.chunks[index];
  }
  
  public Chunk getChunk(int chunkX, int chunkZ) {
    return getChunk(getChunkIndex(chunkX, chunkZ));
  }
  
  public static int getChunkIndex(int chunkX, int chunkZ) {
    return (chunkX & 0x1F) + (chunkZ & 0x1F) * 32;
  }
  
  private int checkIndex(int index) {
    if (index >= 0 && index <= 1023) {
      return index;
    }
    throw new IndexOutOfBoundsException();
  }

  
  private Chunk createChunkIfMissing(int blockX, int blockZ) {
    int chunkX = MCAUtil.blockToChunk(blockX);
    int chunkZ = MCAUtil.blockToChunk(blockZ);
    Chunk chunk = getChunk(chunkX, chunkZ);
    if (chunk == null) {
      chunk = Chunk.newChunk();
      setChunk(getChunkIndex(chunkX, chunkZ), chunk);
    } 
    
    return chunk;
  }

  
  @Deprecated
  public void setBiomeAt(int blockX, int blockZ, int biomeID) {
    createChunkIfMissing(blockX, blockZ).setBiomeAt(blockX, blockZ, biomeID);
  }
  
  public void setBiomeAt(int blockX, int blockY, int blockZ, int biomeID) {
    createChunkIfMissing(blockX, blockZ).setBiomeAt(blockX, blockY, blockZ, biomeID);
  }

  
  @Deprecated
  public int getBiomeAt(int blockX, int blockZ) {
    int chunkX = MCAUtil.blockToChunk(blockX);
    int chunkZ = MCAUtil.blockToChunk(blockZ);
    Chunk chunk = getChunk(getChunkIndex(chunkX, chunkZ));
    return (chunk == null) ? -1 : chunk.getBiomeAt(blockX, blockZ);
  }
  
  public int getBiomeAt(int blockX, int blockY, int blockZ) {
    int chunkX = MCAUtil.blockToChunk(blockX);
    int chunkZ = MCAUtil.blockToChunk(blockZ);
    Chunk chunk = getChunk(getChunkIndex(chunkX, chunkZ));
    return (chunk == null) ? -1 : chunk.getBiomeAt(blockX, blockY, blockZ);
  }
  
  public void setBlockStateAt(int blockX, int blockY, int blockZ, CompoundTag state, boolean cleanup) {
    createChunkIfMissing(blockX, blockZ).setBlockStateAt(blockX, blockY, blockZ, state, cleanup);
  }
  
  public CompoundTag getBlockStateAt(int blockX, int blockY, int blockZ) {
    int chunkX = MCAUtil.blockToChunk(blockX);
    int chunkZ = MCAUtil.blockToChunk(blockZ);
    Chunk chunk = getChunk(chunkX, chunkZ);
    return (chunk == null) ? null : chunk.getBlockStateAt(blockX, blockY, blockZ);
  }
  
  public void cleanupPalettesAndBlockStates() {
    Chunk[] var1 = this.chunks;
    int var2 = var1.length;
    
    for (int var3 = 0; var3 < var2; var3++) {
      Chunk chunk = var1[var3];
      if (chunk != null)
        chunk.cleanupPalettesAndBlockStates(); 
    } 
  }
}
