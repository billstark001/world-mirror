package net.querz.mca;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.querz.nbt.tag.ByteArrayTag;
import net.querz.nbt.tag.CompoundTag;
import net.querz.nbt.tag.ListTag;
import net.querz.nbt.tag.LongArrayTag;
import net.querz.nbt.tag.Tag;

@Environment(EnvType.CLIENT)
public class Section
{
  private CompoundTag data;
  private Map<String, List<PaletteIndex>> valueIndexedPalette;
  private ListTag<CompoundTag> palette;
  private byte[] blockLight;
  private long[] blockStates;
  private byte[] skyLight;
  int dataVersion;
  
  public Section(CompoundTag sectionRoot, int dataVersion) {
    this(sectionRoot, dataVersion, -1L);
  }
  
  public Section(CompoundTag sectionRoot, int dataVersion, long loadFlags) {
    this.valueIndexedPalette = new HashMap<>();
    this.data = sectionRoot;
    this.dataVersion = dataVersion;
    ListTag<?> rawPalette = sectionRoot.getListTag("Palette");
    if (rawPalette != null) {
      this.palette = rawPalette.asCompoundTagList();
      
      for (int i = 0; i < this.palette.size(); i++) {
        CompoundTag data = (CompoundTag)this.palette.get(i);
        putValueIndexedPalette(data, i);
      } 
      
      ByteArrayTag blockLight = sectionRoot.getByteArrayTag("BlockLight");
      LongArrayTag blockStates = sectionRoot.getLongArrayTag("BlockStates");
      ByteArrayTag skyLight = sectionRoot.getByteArrayTag("SkyLight");
      if ((loadFlags & 0x800L) != 0L) {
        this.blockLight = (blockLight != null) ? (byte[])blockLight.getValue() : null;
      }
      
      if ((loadFlags & 0x1000L) != 0L) {
        this.blockStates = (blockStates != null) ? (long[])blockStates.getValue() : null;
      }
      
      if ((loadFlags & 0x2000L) != 0L) {
        this.skyLight = (skyLight != null) ? (byte[])skyLight.getValue() : null;
      }
    } 
  }

  
  Section() {
    this.valueIndexedPalette = new HashMap<>();
  }
  
  void putValueIndexedPalette(CompoundTag data, int index) {
    PaletteIndex leaf = new PaletteIndex(data, index);
    String name = data.getString("Name");
    List<PaletteIndex> leaves = this.valueIndexedPalette.get(name);
    if (leaves == null) {
      leaves = new ArrayList<>(1);
      leaves.add(leaf);
      this.valueIndexedPalette.put(name, leaves);
    } else {
      Iterator<PaletteIndex> var6 = leaves.iterator();
      
      while (var6.hasNext()) {
        PaletteIndex pal = var6.next();
        if (pal.data.equals(data)) {
          return;
        }
      } 
      
      leaves.add(leaf);
    } 
  }
  
  PaletteIndex getValueIndexedPalette(CompoundTag data) {
    PaletteIndex leaf;
    List<PaletteIndex> leaves = this.valueIndexedPalette.get(data.getString("Name"));
    if (leaves == null) {
      return null;
    }
    Iterator<PaletteIndex> var3 = leaves.iterator();

    
    do {
      if (!var3.hasNext()) {
        return null;
      }
      
      leaf = var3.next();
    } while (!leaf.data.equals(data));
    
    return leaf;
  }

  
  public boolean isEmpty() {
    return (this.data == null);
  }
  
  public CompoundTag getBlockStateAt(int blockX, int blockY, int blockZ) {
    int index = getBlockIndex(blockX, blockY, blockZ);
    int paletteIndex = getPaletteIndex(index);
    return (CompoundTag)this.palette.get(paletteIndex);
  }
  
  public void setBlockStateAt(int blockX, int blockY, int blockZ, CompoundTag state, boolean cleanup) {
    int paletteSizeBefore = this.palette.size();
    int paletteIndex = addToPalette(state);
    if (paletteSizeBefore != this.palette.size() && (paletteIndex & paletteIndex - 1) == 0) {
      adjustBlockStateBits((Map<Integer, Integer>)null, this.blockStates);
      cleanup = true;
    } 
    
    setPaletteIndex(getBlockIndex(blockX, blockY, blockZ), paletteIndex, this.blockStates);
    if (cleanup) {
      cleanupPaletteAndBlockStates();
    }
  }

  
  public int getPaletteIndex(int blockStateIndex) {
    int bits = this.blockStates.length >> 6;
    
    if (this.dataVersion < 2527) {
      double d = blockStateIndex / 4096.0D / this.blockStates.length;
      int i = (int)d;
      int startBit = (int)((d - Math.floor(d)) * 64.0D);
      if (startBit + bits > 64) {
        long prev = bitRange(this.blockStates[i], startBit, 64);
        long next = bitRange(this.blockStates[i + 1], 0, startBit + bits - 64);
        return (int)((next << 64 - startBit) + prev);
      } 
      return (int)bitRange(this.blockStates[i], startBit, startBit + bits);
    } 
    
    int indicesPerLong = (int)(64.0D / bits);
    int blockStatesIndex = blockStateIndex / indicesPerLong;
    int longIndex = blockStateIndex % indicesPerLong * bits;
    return (int)bitRange(this.blockStates[blockStatesIndex], longIndex, longIndex + bits);
  }

  
  public void setPaletteIndex(int blockIndex, int paletteIndex, long[] blockStates) {
    int bits = blockStates.length >> 6;
    
    if (this.dataVersion < 2527) {
      double blockStatesIndex = blockIndex / 4096.0D / blockStates.length;
      int longIndex = (int)blockStatesIndex;
      int startBit = (int)((blockStatesIndex - Math.floor(longIndex)) * 64.0D);
      if (startBit + bits > 64) {
        blockStates[longIndex] = updateBits(blockStates[longIndex], paletteIndex, startBit, 64);
        blockStates[longIndex + 1] = updateBits(blockStates[longIndex + 1], paletteIndex, startBit - 64, startBit + bits - 64);
      } else {
        blockStates[longIndex] = updateBits(blockStates[longIndex], paletteIndex, startBit, startBit + bits);
      } 
    } else {
      int indicesPerLong = (int)(64.0D / bits);
      int blockStatesIndex = blockIndex / indicesPerLong;
      int longIndex = blockIndex % indicesPerLong * bits;
      blockStates[blockStatesIndex] = updateBits(blockStates[blockStatesIndex], paletteIndex, longIndex, longIndex + bits);
    } 
  }

  
  public ListTag<CompoundTag> getPalette() {
    return this.palette;
  }
  
  int addToPalette(CompoundTag data) {
    PaletteIndex index;
    if ((index = getValueIndexedPalette(data)) != null) {
      return index.index;
    }
    this.palette.add((Tag)data);
    putValueIndexedPalette(data, this.palette.size() - 1);
    return this.palette.size() - 1;
  }

  
  int getBlockIndex(int blockX, int blockY, int blockZ) {
    return (blockY & 0xF) * 256 + (blockZ & 0xF) * 16 + (blockX & 0xF);
  }
  
  static long updateBits(long n, long m, int i, int j) {
    long mShifted = (i > 0) ? ((m & (1L << j - i) - 1L) << i) : ((m & (1L << j - i) - 1L) >>> -i);
    return n & (((j > 63) ? 0L : (-1L << j)) | ((i < 0) ? 0L : ((1L << i) - 1L))) | mShifted;
  }
  
  static long bitRange(long value, int from, int to) {
    int waste = 64 - to;
    return value << waste >>> waste + from;
  }
  
  public void cleanupPaletteAndBlockStates() {
    Map<Integer, Integer> oldToNewMapping = cleanupPalette();
    adjustBlockStateBits(oldToNewMapping, this.blockStates);
  }
  
  private Map<Integer, Integer> cleanupPalette() {
    Map<Integer, Integer> allIndices = new HashMap<>();
    
    int index;
    
    for (index = 0; index < 4096; index++) {
      int j = getPaletteIndex(index);
      allIndices.put(Integer.valueOf(j), Integer.valueOf(j));
    } 
    
    index = 1;
    this.valueIndexedPalette = new HashMap<>(this.valueIndexedPalette.size());
    putValueIndexedPalette((CompoundTag)this.palette.get(0), 0);
    
    for (int i = 1; i < this.palette.size(); i++) {
      if (!allIndices.containsKey(Integer.valueOf(index))) {
        this.palette.remove(i);
        i--;
      } else {
        putValueIndexedPalette((CompoundTag)this.palette.get(i), i);
        allIndices.put(Integer.valueOf(index), Integer.valueOf(i));
      } 
      
      index++;
    } 
    
    return allIndices;
  }
  void adjustBlockStateBits(Map<Integer, Integer> oldToNewMapping, long[] blockStates) {
    long[] newBlockStates;
    int newBits = 32 - Integer.numberOfLeadingZeros(this.palette.size() - 1);
    newBits = Math.max(newBits, 4);

    
    if (this.dataVersion < 2527) {
      newBlockStates = (newBits == blockStates.length / 64) ? blockStates : new long[newBits * 64];
    } else {
      int i = (int)Math.ceil(4096.0D / Math.floor(64.0D / newBits));
      newBlockStates = (newBits == blockStates.length / 64) ? blockStates : new long[i];
    } 
    
    if (oldToNewMapping != null) {
      for (int i = 0; i < 4096; i++) {
        setPaletteIndex(i, ((Integer)oldToNewMapping.get(Integer.valueOf(getPaletteIndex(i)))).intValue(), newBlockStates);
      }
    } else {
      for (int i = 0; i < 4096; i++) {
        setPaletteIndex(i, getPaletteIndex(i), newBlockStates);
      }
    } 
    
    this.blockStates = newBlockStates;
  }
  
  public byte[] getBlockLight() {
    return this.blockLight;
  }
  
  public void setBlockLight(byte[] blockLight) {
    if (blockLight != null && blockLight.length != 2048) {
      throw new IllegalArgumentException("BlockLight array must have a length of 2048");
    }
    this.blockLight = blockLight;
  }

  
  public long[] getBlockStates() {
    return this.blockStates;
  }
  
  public void setBlockStates(long[] blockStates) {
    if (blockStates == null)
      throw new NullPointerException("BlockStates cannot be null"); 
    if (blockStates.length % 64 == 0 && blockStates.length >= 256 && blockStates.length <= 4096) {
      this.blockStates = blockStates;
    } else {
      throw new IllegalArgumentException("BlockStates must have a length > 255 and < 4097 and must be divisible by 64");
    } 
  }
  
  public byte[] getSkyLight() {
    return this.skyLight;
  }
  
  public void setSkyLight(byte[] skyLight) {
    if (skyLight != null && skyLight.length != 2048) {
      throw new IllegalArgumentException("SkyLight array must have a length of 2048");
    }
    this.skyLight = skyLight;
  }

  
  public static Section newSection() {
    Section s = new Section();
    s.blockStates = new long[256];
    s.palette = new ListTag(CompoundTag.class);
    CompoundTag air = new CompoundTag();
    air.putString("Name", "minecraft:air");
    s.palette.add((Tag)air);
    s.data = new CompoundTag();
    return s;
  }
  
  public CompoundTag updateHandle(int y) {
    this.data.putByte("Y", (byte)y);
    if (this.palette != null) {
      this.data.put("Palette", (Tag)this.palette);
    }
    
    if (this.blockLight != null) {
      this.data.putByteArray("BlockLight", this.blockLight);
    }
    
    if (this.blockStates != null) {
      this.data.putLongArray("BlockStates", this.blockStates);
    }
    
    if (this.skyLight != null) {
      this.data.putByteArray("SkyLight", this.skyLight);
    }
    
    return this.data;
  }
  
  @Environment(EnvType.CLIENT)
  private static class PaletteIndex { CompoundTag data;
    int index;
    
    PaletteIndex(CompoundTag data, int index) {
      this.data = data;
      this.index = index;
    } }

}
