package net.querz.mca;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Arrays;
import java.util.Iterator;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.querz.nbt.io.NBTDeserializer;
import net.querz.nbt.io.NBTSerializer;
import net.querz.nbt.io.NamedTag;
import net.querz.nbt.tag.CompoundTag;
import net.querz.nbt.tag.ListTag;
import net.querz.nbt.tag.Tag;

@Environment(EnvType.CLIENT)
public class Chunk
{
  public static final int DEFAULT_DATA_VERSION = 2567;
  private boolean partial;
  private boolean raw;
  private int lastMCAUpdate;
  private CompoundTag data;
  private int dataVersion;
  private long lastUpdate;
  private long inhabitedTime;
  private int[] biomes;
  private CompoundTag heightMaps;
  private CompoundTag carvingMasks;
  private Section[] sections = new Section[16];
  private ListTag<CompoundTag> entities;
  private ListTag<CompoundTag> tileEntities;
  private ListTag<CompoundTag> tileTicks;
  private ListTag<CompoundTag> liquidTicks;
  private ListTag<ListTag<?>> lights;
  private ListTag<ListTag<?>> liquidsToBeTicked;
  private ListTag<ListTag<?>> toBeTicked;
  private ListTag<ListTag<?>> postProcessing;
  private String status;
  private CompoundTag structures;
  
  Chunk(int lastMCAUpdate) {
    this.lastMCAUpdate = lastMCAUpdate;
  }
  
  public Chunk(CompoundTag data) {
    this.data = data;
    initReferences(-1L);
  }
  
  private void initReferences(long loadFlags) {
    if (this.data == null)
      throw new NullPointerException("data cannot be null"); 
    if (loadFlags != -1L && (loadFlags & 0x10000L) != 0L) {
      this.raw = true;
    
    }
    else {

      
      this.dataVersion = this.data.getInt("DataVersion");
      this.inhabitedTime = this.data.getLong("InhabitedTime");
      this.lastUpdate = this.data.getLong("LastUpdate");
      if ((loadFlags & 0x1L) != 0L) {
        this.biomes = this.data.getIntArray("Biomes");
      }
      
      if ((loadFlags & 0x2L) != 0L) {
        this.heightMaps = this.data.getCompoundTag("Heightmaps");
      }
      
      if ((loadFlags & 0x4L) != 0L) {
        this.carvingMasks = this.data.getCompoundTag("CarvingMasks");
      }
      
      if ((loadFlags & 0x8L) != 0L) {
        this.entities = this.data.containsKey("Entities") ? this.data.getListTag("Entities").asCompoundTagList() : null;
      }
      
      if ((loadFlags & 0x10L) != 0L) {
        this.tileEntities = this.data.containsKey("TileEntities") ? this.data.getListTag("TileEntities").asCompoundTagList() : null;
      }
      
      if ((loadFlags & 0x40L) != 0L) {
        this.tileTicks = this.data.containsKey("TileTicks") ? this.data.getListTag("TileTicks").asCompoundTagList() : null;
      }
      
      if ((loadFlags & 0x80L) != 0L) {
        this.liquidTicks = this.data.containsKey("LiquidTicks") ? this.data.getListTag("LiquidTicks").asCompoundTagList() : null;
      }
      
      if ((loadFlags & 0x4000L) != 0L) {
        this.lights = this.data.containsKey("Lights") ? this.data.getListTag("Lights").asListTagList() : null;
      }
      
      if ((loadFlags & 0x8000L) != 0L) {
        this.liquidsToBeTicked = this.data.containsKey("LiquidsToBeTicked") ? this.data.getListTag("LiquidsToBeTicked").asListTagList() : null;
      }
      
      if ((loadFlags & 0x100L) != 0L) {
        this.toBeTicked = this.data.containsKey("ToBeTicked") ? this.data.getListTag("ToBeTicked").asListTagList() : null;
      }
      
      if ((loadFlags & 0x200L) != 0L) {
        this.postProcessing = this.data.containsKey("PostProcessing") ? this.data.getListTag("PostProcessing").asListTagList() : null;
      }
      
      this.status = this.data.getString("Status");
      if ((loadFlags & 0x400L) != 0L) {
        this.structures = this.data.getCompoundTag("Structures");
      }
      
      if ((loadFlags & 0x3800L) != 0L && this.data.containsKey("Sections")) {
        Iterator<CompoundTag> var4 = this.data.getListTag("Sections").asCompoundTagList().iterator();
        
        while (var4.hasNext()) {
          CompoundTag section = var4.next();
          int sectionIndex = section.getByte("Y");
          if (sectionIndex <= 15 && sectionIndex >= 0) {
            Section newSection = new Section(section, this.dataVersion, loadFlags);
            if (!newSection.isEmpty()) {
              this.sections[sectionIndex] = newSection;
            }
          } 
        } 
      } 
      
      if (loadFlags != -1L) {
        this.data = null;
        this.partial = true;
      } 
    } 
  }


  
  public int serialize(RandomAccessFile raf, int xPos, int zPos) throws IOException {
    if (this.partial) {
      throw new UnsupportedOperationException("Partially loaded chunks cannot be serialized");
    }
    ByteArrayOutputStream baos = new ByteArrayOutputStream(4096);
    BufferedOutputStream nbtOut = new BufferedOutputStream(CompressionType.ZLIB.compress(baos));
    Throwable var6 = null;
    
    try {
      (new NBTSerializer(false)).toStream(new NamedTag((String)null, (Tag)updateHandle(xPos, zPos)), nbtOut);
    } catch (Throwable var15) {
      var6 = var15;
      throw var15;
    } finally {
      if (nbtOut != null) {
        if (var6 != null) {
          try {
            nbtOut.close();
          } catch (Throwable var14) {
            var6.addSuppressed(var14);
          } 
        } else {
          nbtOut.close();
        } 
      }
    } 

    
    byte[] rawData = baos.toByteArray();
    raf.writeInt(rawData.length + 1);
    raf.writeByte(CompressionType.ZLIB.getID());
    raf.write(rawData);
    return rawData.length + 5;
  }

  
  public void deserialize(RandomAccessFile raf) throws Exception {
    deserialize(raf, -1L);
  }
  
  public void deserialize(RandomAccessFile raf, long loadFlags) throws Exception {
    byte compressionTypeByte = raf.readByte();
    CompressionType compressionType = CompressionType.getFromID(compressionTypeByte);
    if (compressionType == null) {
      throw new IOException("invalid compression type " + compressionTypeByte);
    }
    BufferedInputStream dis = new BufferedInputStream(compressionType.decompress(new FileInputStream(raf.getFD())));
    NamedTag tag = (new NBTDeserializer(false)).fromStream(dis);
    if (tag != null && tag.getTag() instanceof CompoundTag) {
      this.data = (CompoundTag)tag.getTag();
      initReferences(loadFlags);
    } else {
      throw new IOException("invalid data tag: " + ((tag == null) ? "null" : tag.getClass().getName()));
    } 
  }


  
  @Deprecated
  public int getBiomeAt(int blockX, int blockZ) {
    if (this.dataVersion < 2202) {
      return (this.biomes != null && this.biomes.length == 256) ? this.biomes[getBlockIndex(blockX, blockZ)] : -1;
    }
    throw new IllegalStateException("cannot get biome using Chunk#getBiomeAt(int,int) from biome data with DataVersion of 2202 or higher, use Chunk#getBiomeAt(int,int,int) instead");
  }

  
  public int getBiomeAt(int blockX, int blockY, int blockZ) {
    if (this.dataVersion < 2202)
      return (this.biomes != null && this.biomes.length == 256) ? this.biomes[getBlockIndex(blockX, blockZ)] : -1; 
    if (this.biomes != null && this.biomes.length == 1024) {
      int biomeX = (blockX & 0xF) >> 2;
      int biomeY = (blockY & 0xF) >> 2;
      int biomeZ = (blockZ & 0xF) >> 2;
      return this.biomes[getBiomeIndex(biomeX, biomeY, biomeZ)];
    } 
    return -1;
  }


  
  @Deprecated
  public void setBiomeAt(int blockX, int blockZ, int biomeID) {
    checkRaw();
    if (this.dataVersion < 2202) {
      if (this.biomes == null || this.biomes.length != 256) {
        this.biomes = new int[256];
        Arrays.fill(this.biomes, -1);
      } 
      
      this.biomes[getBlockIndex(blockX, blockZ)] = biomeID;
    } else {
      if (this.biomes == null || this.biomes.length != 1024) {
        this.biomes = new int[1024];
        Arrays.fill(this.biomes, -1);
      } 
      
      int biomeX = (blockX & 0xF) >> 2;
      int biomeZ = (blockZ & 0xF) >> 2;
      
      for (int y = 0; y < 64; y++) {
        this.biomes[getBiomeIndex(biomeX, y, biomeZ)] = biomeID;
      }
    } 
  }

  
  public void setBiomeAt(int blockX, int blockY, int blockZ, int biomeID) {
    checkRaw();
    if (this.dataVersion < 2202) {
      if (this.biomes == null || this.biomes.length != 256) {
        this.biomes = new int[256];
        Arrays.fill(this.biomes, -1);
      } 
      
      this.biomes[getBlockIndex(blockX, blockZ)] = biomeID;
    } else {
      if (this.biomes == null || this.biomes.length != 1024) {
        this.biomes = new int[1024];
        Arrays.fill(this.biomes, -1);
      } 
      
      int biomeX = (blockX & 0xF) >> 2;
      int biomeZ = (blockZ & 0xF) >> 2;
      this.biomes[getBiomeIndex(biomeX, blockY, biomeZ)] = biomeID;
    } 
  }

  
  int getBiomeIndex(int biomeX, int biomeY, int biomeZ) {
    return biomeY * 16 + biomeZ * 4 + biomeX;
  }
  
  public CompoundTag getBlockStateAt(int blockX, int blockY, int blockZ) {
    Section section = this.sections[MCAUtil.blockToChunk(blockY)];
    return (section == null) ? null : section.getBlockStateAt(blockX, blockY, blockZ);
  }
  
  public void setBlockStateAt(int blockX, int blockY, int blockZ, CompoundTag state, boolean cleanup) {
    checkRaw();
    int sectionIndex = MCAUtil.blockToChunk(blockY);
    Section section = this.sections[sectionIndex];
    if (section == null) {
      section = this.sections[sectionIndex] = Section.newSection();
    }
    
    section.setBlockStateAt(blockX, blockY, blockZ, state, cleanup);
  }
  
  public int getDataVersion() {
    return this.dataVersion;
  }
  
  public void setDataVersion(int dataVersion) {
    checkRaw();
    this.dataVersion = dataVersion;
    Section[] var2 = this.sections;
    int var3 = var2.length;
    
    for (int var4 = 0; var4 < var3; var4++) {
      Section section = var2[var4];
      if (section != null) {
        section.dataVersion = dataVersion;
      }
    } 
  }

  
  public int getLastMCAUpdate() {
    return this.lastMCAUpdate;
  }
  
  public void setLastMCAUpdate(int lastMCAUpdate) {
    checkRaw();
    this.lastMCAUpdate = lastMCAUpdate;
  }
  
  public String getStatus() {
    return this.status;
  }
  
  public void setStatus(String status) {
    checkRaw();
    this.status = status;
  }
  
  public Section getSection(int sectionY) {
    return this.sections[sectionY];
  }
  
  public void setSection(int sectionY, Section section) {
    checkRaw();
    this.sections[sectionY] = section;
  }
  
  public long getLastUpdate() {
    return this.lastUpdate;
  }
  
  public void setLastUpdate(long lastUpdate) {
    checkRaw();
    this.lastUpdate = lastUpdate;
  }
  
  public long getInhabitedTime() {
    return this.inhabitedTime;
  }
  
  public void setInhabitedTime(long inhabitedTime) {
    checkRaw();
    this.inhabitedTime = inhabitedTime;
  }
  
  public int[] getBiomes() {
    return this.biomes;
  }
  
  public void setBiomes(int[] biomes) {
    checkRaw();
    if (biomes != null && ((this.dataVersion < 2202 && biomes.length != 256) || (this.dataVersion >= 2202 && biomes.length != 1024))) {
      throw new IllegalArgumentException("biomes array must have a length of " + ((this.dataVersion < 2202) ? "256" : "1024"));
    }
    this.biomes = biomes;
  }

  
  public CompoundTag getHeightMaps() {
    return this.heightMaps;
  }
  
  public void setHeightMaps(CompoundTag heightMaps) {
    checkRaw();
    this.heightMaps = heightMaps;
  }
  
  public CompoundTag getCarvingMasks() {
    return this.carvingMasks;
  }
  
  public void setCarvingMasks(CompoundTag carvingMasks) {
    checkRaw();
    this.carvingMasks = carvingMasks;
  }
  
  public ListTag<CompoundTag> getEntities() {
    return this.entities;
  }
  
  public void setEntities(ListTag<CompoundTag> entities) {
    checkRaw();
    this.entities = entities;
  }
  
  public ListTag<CompoundTag> getTileEntities() {
    return this.tileEntities;
  }
  
  public void setTileEntities(ListTag<CompoundTag> tileEntities) {
    checkRaw();
    this.tileEntities = tileEntities;
  }
  
  public ListTag<CompoundTag> getTileTicks() {
    return this.tileTicks;
  }
  
  public void setTileTicks(ListTag<CompoundTag> tileTicks) {
    checkRaw();
    this.tileTicks = tileTicks;
  }
  
  public ListTag<CompoundTag> getLiquidTicks() {
    return this.liquidTicks;
  }
  
  public void setLiquidTicks(ListTag<CompoundTag> liquidTicks) {
    checkRaw();
    this.liquidTicks = liquidTicks;
  }
  
  public ListTag<ListTag<?>> getLights() {
    return this.lights;
  }
  
  public void setLights(ListTag<ListTag<?>> lights) {
    checkRaw();
    this.lights = lights;
  }
  
  public ListTag<ListTag<?>> getLiquidsToBeTicked() {
    return this.liquidsToBeTicked;
  }
  
  public void setLiquidsToBeTicked(ListTag<ListTag<?>> liquidsToBeTicked) {
    checkRaw();
    this.liquidsToBeTicked = liquidsToBeTicked;
  }
  
  public ListTag<ListTag<?>> getToBeTicked() {
    return this.toBeTicked;
  }
  
  public void setToBeTicked(ListTag<ListTag<?>> toBeTicked) {
    checkRaw();
    this.toBeTicked = toBeTicked;
  }
  
  public ListTag<ListTag<?>> getPostProcessing() {
    return this.postProcessing;
  }
  
  public void setPostProcessing(ListTag<ListTag<?>> postProcessing) {
    checkRaw();
    this.postProcessing = postProcessing;
  }
  
  public CompoundTag getStructures() {
    return this.structures;
  }
  
  public void setStructures(CompoundTag structures) {
    checkRaw();
    this.structures = structures;
  }
  
  int getBlockIndex(int blockX, int blockZ) {
    return (blockZ & 0xF) * 16 + (blockX & 0xF);
  }
  
  public void cleanupPalettesAndBlockStates() {
    checkRaw();
    Section[] var1 = this.sections;
    int var2 = var1.length;
    
    for (int var3 = 0; var3 < var2; var3++) {
      Section section = var1[var3];
      if (section != null) {
        section.cleanupPaletteAndBlockStates();
      }
    } 
  }

  
  private void checkRaw() {
    if (this.raw) {
      throw new UnsupportedOperationException("cannot update field when working with raw data");
    }
  }
  
  public static Chunk newChunk() {
    return newChunk(2567);
  }
  
  public static Chunk newChunk(int dataVersion) {
    Chunk c = new Chunk(0);
    c.dataVersion = dataVersion;
    c.data = new CompoundTag();
    c.data.put("Level", (Tag)new CompoundTag());
    c.status = "mobs_spawned";
    return c;
  }
  
  public CompoundTag getHandle() {
    return this.data;
  }
  
  public CompoundTag updateHandle(int xPos, int zPos) {
    if (this.raw) {
      return this.data;
    }
    this.data.putInt("DataVersion", this.dataVersion);
    CompoundTag level = this.data.getCompoundTag("Level");
    this.data.putInt("xPos", xPos);
    this.data.putInt("zPos", zPos);
    this.data.putLong("LastUpdate", this.lastUpdate);
    this.data.putLong("InhabitedTime", this.inhabitedTime);
    if (this.dataVersion < 2202) {
      if (this.biomes != null && this.biomes.length == 256) {
        this.data.putIntArray("Biomes", this.biomes);
      }
    } else if (this.biomes != null && this.biomes.length == 1024) {
      this.data.putIntArray("Biomes", this.biomes);
    } 
    
    if (this.heightMaps != null) {
      this.data.put("Heightmaps", (Tag)this.heightMaps);
    }
    
    if (this.carvingMasks != null) {
      this.data.put("CarvingMasks", (Tag)this.carvingMasks);
    }
    
    if (this.entities != null) {
      this.data.put("Entities", (Tag)this.entities);
    }
    
    if (this.tileEntities != null) {
      this.data.put("TileEntities", (Tag)this.tileEntities);
    }
    
    if (this.tileTicks != null) {
      this.data.put("TileTicks", (Tag)this.tileTicks);
    }
    
    if (this.liquidTicks != null) {
      this.data.put("LiquidTicks", (Tag)this.liquidTicks);
    }
    
    if (this.lights != null) {
      this.data.put("Lights", (Tag)this.lights);
    }
    
    if (this.liquidsToBeTicked != null) {
      this.data.put("LiquidsToBeTicked", (Tag)this.liquidsToBeTicked);
    }
    
    if (this.toBeTicked != null) {
      this.data.put("ToBeTicked", (Tag)this.toBeTicked);
    }
    
    if (this.postProcessing != null) {
      this.data.put("PostProcessing", (Tag)this.postProcessing);
    }
    
    this.data.putString("Status", this.status);
    if (this.structures != null) {
      this.data.put("Structures", (Tag)this.structures);
    }
    
    ListTag<CompoundTag> sections = new ListTag(CompoundTag.class);
    
    for (int i = 0; i < this.sections.length; i++) {
      if (this.sections[i] != null) {
        sections.add((Tag)this.sections[i].updateHandle(i));
      }
    } 
    
    this.data.put("Sections", (Tag)sections);
    return this.data;
  }
}
