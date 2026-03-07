package net.billstark001.worlddownloader.util;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import net.fabricmc.api.EnvType;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtElement;
import net.querz.mca.Chunk;
import net.querz.mca.MCAFile;
import net.querz.mca.MCAUtil;
import net.querz.nbt.io.NamedTag;
import net.querz.nbt.tag.CompoundTag;

@Environment(EnvType.CLIENT)
public class Exporter {
  public static void exportChunks() throws Exception {
    Path regionDir = Paths.get("downloaded_world", new String[] { "region" });

    
    if (!regionDir.toFile().exists() && !regionDir.toFile().mkdirs()) {
      throw new IOException("Failed to create region directory: " + String.valueOf(regionDir));
    }
    
    Map<ChunkPos, NbtCompound> allChunks = ChunkListener.getAll();
    if (allChunks.isEmpty()) {
      System.out.println("⚠ No chunks to export!");
      
      return;
    } 
    
    Map<String, MCAFile> regionFiles = new HashMap<>();
    
    for (Map.Entry<ChunkPos, NbtCompound> entry : allChunks.entrySet()) {
      ChunkPos chunkPos = entry.getKey();
      NbtCompound chunkNbt = entry.getValue();
      
      int chunkX = chunkPos.x;
      int chunkZ = chunkPos.z;
      int regionX = chunkX >> 5;
      int regionZ = chunkZ >> 5;
      int localChunkX = chunkX & 0x1F;
      int localChunkZ = chunkZ & 0x1F;
      
      String regionKey = "" + regionX + "," + regionX;
      Path regionFile = regionDir.resolve(String.format("r.%d.%d.mca", new Object[] { Integer.valueOf(regionX), Integer.valueOf(regionZ) }));

      
      MCAFile mcaFile = regionFiles.get(regionKey);
      if (mcaFile == null) {
        if (regionFile.toFile().exists()) {
          try {
            mcaFile = MCAUtil.read(regionFile.toFile());
          } catch (Exception e) {
            System.err.println("Failed to read existing region file " + String.valueOf(regionFile) + ", creating new one: " + e.getMessage());
            mcaFile = new MCAFile(regionX, regionZ);
          } 
        } else {
          mcaFile = new MCAFile(regionX, regionZ);
        } 
        regionFiles.put(regionKey, mcaFile);
      } 

      
      try {
        List<NbtCompound> entities = EntityTracker.getEntitiesForChunk(chunkPos);
        if (!entities.isEmpty()) {
          NbtList entitiesList = new NbtList();
          for (NbtCompound entity : entities) {
            entitiesList.add(entity);
          }


          
          chunkNbt.put("entities", (NbtElement)entitiesList);
          System.out.println("✅ Added " + entities.size() + " entities to chunk " + String.valueOf(chunkPos));
        } 

        
        CompoundTag querzChunk = convertToQuerz(chunkNbt);
        Chunk chunk = new Chunk(querzChunk);
        mcaFile.setChunk(localChunkX, localChunkZ, chunk);
        
        System.out.println(String.format("✅ Prepared chunk %d,%d with %d entities", new Object[] {
                Integer.valueOf(chunkX), Integer.valueOf(chunkZ), Integer.valueOf(entities.size())
              }));
      } catch (Exception e) {
        System.err.println("❌ Failed to process chunk " + String.valueOf(chunkPos) + ": " + e.getMessage());
        e.printStackTrace();
      } 
    } 

    
    for (Map.Entry<String, MCAFile> entry : regionFiles.entrySet()) {
      String regionKey = entry.getKey();
      MCAFile mcaFile = entry.getValue();
      String[] coords = regionKey.split(",");
      int regionX = Integer.parseInt(coords[0]);
      int regionZ = Integer.parseInt(coords[1]);
      Path regionFile = regionDir.resolve(String.format("r.%d.%d.mca", new Object[] { Integer.valueOf(regionX), Integer.valueOf(regionZ) }));
      
      try {
        MCAUtil.write(mcaFile, regionFile.toFile());
        System.out.println("✅ Successfully wrote region file: " + String.valueOf(regionFile.getFileName()));
      } catch (Exception e) {
        System.err.println("❌ Failed to write region file " + String.valueOf(regionFile) + ": " + e.getMessage());
        e.printStackTrace();
      } 
    } 
    
    int totalEntities = EntityTracker.getTotalTrackedEntities();
    int totalContainers = ContainerTracker.getTotalSavedContainers();
    System.out.println("✅ Exported " + allChunks.size() + " chunks with " + totalEntities + " entities and " + totalContainers + " containers to " + regionFiles.size() + " region files.");
  }

  
  public static CompoundTag convertToQuerz(NbtCompound mcNbt) {
    try {
      ByteArrayOutputStream bos = new ByteArrayOutputStream();
      DataOutputStream dos = new DataOutputStream(bos);
      NbtIo.write((NbtElement)mcNbt, dos);
      dos.close();
      byte[] nbtData = bos.toByteArray();

      
      ByteArrayInputStream bis = new ByteArrayInputStream(nbtData);
      DataInputStream dis = new DataInputStream(bis);
      NamedTag namedTag = (new NBTDeserializer(false)).fromStream(dis);
      dis.close();
      
      return (CompoundTag)namedTag.getTag();
    } catch (Exception e) {
      throw new RuntimeException("Failed to convert NbtCompound to Querz CompoundTag", e);
    } 
  }
}
