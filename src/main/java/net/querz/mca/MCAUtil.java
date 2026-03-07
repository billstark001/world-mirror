package net.querz.mca;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
public final class MCAUtil {
  private static final Pattern mcaFilePattern = Pattern.compile("^.*r\\.(?<regionX>-?\\d+)\\.(?<regionZ>-?\\d+)\\.mca$");



  
  public static MCAFile read(String file) throws Exception {
    return read(new File(file), -1L);
  }
  
  public static MCAFile read(File file) throws Exception {
    return read(file, -1L);
  }
  
  public static MCAFile read(String file, long loadFlags) throws Exception {
    return read(new File(file), loadFlags);
  }
  
  public static MCAFile read(File file, long loadFlags) throws Exception {
    MCAFile var6, mcaFile = newMCAFile(file);
    RandomAccessFile raf = new RandomAccessFile(file, "r");
    Throwable var5 = null;

    
    try {
      mcaFile.deserialize(raf, loadFlags);
      var6 = mcaFile;
    } catch (Throwable var15) {
      var5 = var15;
      throw var15;
    } finally {
      if (raf != null) {
        if (var5 != null) {
          try {
            raf.close();
          } catch (Throwable var14) {
            var5.addSuppressed(var14);
          } 
        } else {
          raf.close();
        } 
      }
    } 

    
    return var6;
  }
  
  public static int write(MCAFile mcaFile, String file) throws IOException {
    return write(mcaFile, new File(file), false);
  }
  
  public static int write(MCAFile mcaFile, File file) throws IOException {
    return write(mcaFile, file, false);
  }
  
  public static int write(MCAFile mcaFile, String file, boolean changeLastUpdate) throws IOException {
    return write(mcaFile, new File(file), changeLastUpdate);
  }
  public static int write(MCAFile mcaFile, File file, boolean changeLastUpdate) throws IOException {
    int chunks;
    File to = file;
    if (file.exists()) {
      to = File.createTempFile(to.getName(), (String)null);
    }
    
    RandomAccessFile raf = new RandomAccessFile(to, "rw");
    Throwable var6 = null;

    
    try {
      chunks = mcaFile.serialize(raf, changeLastUpdate);
    } catch (Throwable var15) {
      var6 = var15;
      throw var15;
    } finally {
      if (raf != null) {
        if (var6 != null) {
          try {
            raf.close();
          } catch (Throwable var14) {
            var6.addSuppressed(var14);
          } 
        } else {
          raf.close();
        } 
      }
    } 

    
    if (chunks > 0 && to != file) {
      Files.move(to.toPath(), file.toPath(), new CopyOption[] { StandardCopyOption.REPLACE_EXISTING });
    }
    
    return chunks;
  }
  
  public static String createNameFromChunkLocation(int chunkX, int chunkZ) {
    return createNameFromRegionLocation(chunkToRegion(chunkX), chunkToRegion(chunkZ));
  }
  
  public static String createNameFromBlockLocation(int blockX, int blockZ) {
    return createNameFromRegionLocation(blockToRegion(blockX), blockToRegion(blockZ));
  }
  
  public static String createNameFromRegionLocation(int regionX, int regionZ) {
    return "r." + regionX + "." + regionZ + ".mca";
  }
  
  public static int blockToChunk(int block) {
    return block >> 4;
  }
  
  public static int blockToRegion(int block) {
    return block >> 9;
  }
  
  public static int chunkToRegion(int chunk) {
    return chunk >> 5;
  }
  
  public static int regionToChunk(int region) {
    return region << 5;
  }
  
  public static int regionToBlock(int region) {
    return region << 9;
  }
  
  public static int chunkToBlock(int chunk) {
    return chunk << 4;
  }
  
  public static MCAFile newMCAFile(File file) {
    Matcher m = mcaFilePattern.matcher(file.getName());
    if (m.find()) {
      return new MCAFile(Integer.parseInt(m.group("regionX")), Integer.parseInt(m.group("regionZ")));
    }
    throw new IllegalArgumentException("invalid mca file name: " + file.getName());
  }
}
