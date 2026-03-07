package net.querz.nbt.io;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.util.zip.GZIPInputStream;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.querz.nbt.tag.Tag;

@Environment(EnvType.CLIENT)
public final class NBTUtil {
  public static void write(NamedTag tag, File file, boolean compressed) throws IOException {
    FileOutputStream fos = new FileOutputStream(file);
    Throwable var4 = null;
    
    try {
      (new NBTSerializer(compressed)).toStream(tag, fos);
    } catch (Throwable var13) {
      var4 = var13;
      throw var13;
    } finally {
      if (fos != null) {
        if (var4 != null) {
          try {
            fos.close();
          } catch (Throwable var12) {
            var4.addSuppressed(var12);
          } 
        } else {
          fos.close();
        } 
      }
    } 
  }


  
  public static void write(NamedTag tag, String file, boolean compressed) throws IOException {
    write(tag, new File(file), compressed);
  }
  
  public static void write(NamedTag tag, File file) throws IOException {
    write(tag, file, true);
  }
  
  public static void write(NamedTag tag, String file) throws IOException {
    write(tag, new File(file), true);
  }
  
  public static void write(Tag<?> tag, File file, boolean compressed) throws IOException {
    write(new NamedTag((String)null, tag), file, compressed);
  }
  
  public static void write(Tag<?> tag, String file, boolean compressed) throws IOException {
    write(new NamedTag((String)null, tag), new File(file), compressed);
  }
  
  public static void write(Tag<?> tag, File file) throws IOException {
    write(new NamedTag((String)null, tag), file, true);
  }
  
  public static void write(Tag<?> tag, String file) throws IOException {
    write(new NamedTag((String)null, tag), new File(file), true);
  }
  
  public static void writeLE(NamedTag tag, File file, boolean compressed) throws IOException {
    FileOutputStream fos = new FileOutputStream(file);
    Throwable var4 = null;
    
    try {
      (new NBTSerializer(compressed, true)).toStream(tag, fos);
    } catch (Throwable var13) {
      var4 = var13;
      throw var13;
    } finally {
      if (fos != null) {
        if (var4 != null) {
          try {
            fos.close();
          } catch (Throwable var12) {
            var4.addSuppressed(var12);
          } 
        } else {
          fos.close();
        } 
      }
    } 
  }


  
  public static void writeLE(NamedTag tag, String file, boolean compressed) throws IOException {
    writeLE(tag, new File(file), compressed);
  }
  
  public static void writeLE(NamedTag tag, File file) throws IOException {
    writeLE(tag, file, true);
  }
  
  public static void writeLE(NamedTag tag, String file) throws IOException {
    writeLE(tag, new File(file), true);
  }
  
  public static void writeLE(Tag<?> tag, File file, boolean compressed) throws IOException {
    writeLE(new NamedTag((String)null, tag), file, compressed);
  }
  
  public static void writeLE(Tag<?> tag, String file, boolean compressed) throws IOException {
    writeLE(new NamedTag((String)null, tag), new File(file), compressed);
  }
  
  public static void writeLE(Tag<?> tag, File file) throws IOException {
    writeLE(new NamedTag((String)null, tag), file, true);
  }
  
  public static void writeLE(Tag<?> tag, String file) throws IOException {
    writeLE(new NamedTag((String)null, tag), new File(file), true);
  }
  public static NamedTag read(File file, boolean compressed) throws Exception {
    NamedTag var4;
    FileInputStream fis = new FileInputStream(file);
    Throwable var3 = null;

    
    try {
      var4 = (new NBTDeserializer(compressed)).fromStream(fis);
    } catch (Throwable var13) {
      var3 = var13;
      throw var13;
    } finally {
      if (fis != null) {
        if (var3 != null) {
          try {
            fis.close();
          } catch (Throwable var12) {
            var3.addSuppressed(var12);
          } 
        } else {
          fis.close();
        } 
      }
    } 

    
    return var4;
  }
  
  public static NamedTag read(String file, boolean compressed) throws Exception {
    return read(new File(file), compressed);
  }
  public static NamedTag read(File file) throws Exception {
    NamedTag var3;
    FileInputStream fis = new FileInputStream(file);
    Throwable var2 = null;

    
    try {
      var3 = (new NBTDeserializer(false)).fromStream(detectDecompression(fis));
    } catch (Throwable var12) {
      var2 = var12;
      throw var12;
    } finally {
      if (fis != null) {
        if (var2 != null) {
          try {
            fis.close();
          } catch (Throwable var11) {
            var2.addSuppressed(var11);
          } 
        } else {
          fis.close();
        } 
      }
    } 

    
    return var3;
  }
  
  public static NamedTag read(String file) throws Exception {
    return read(new File(file));
  }
  public static NamedTag readLE(File file, boolean compressed) throws Exception {
    NamedTag var4;
    FileInputStream fis = new FileInputStream(file);
    Throwable var3 = null;

    
    try {
      var4 = (new NBTDeserializer(compressed, true)).fromStream(fis);
    } catch (Throwable var13) {
      var3 = var13;
      throw var13;
    } finally {
      if (fis != null) {
        if (var3 != null) {
          try {
            fis.close();
          } catch (Throwable var12) {
            var3.addSuppressed(var12);
          } 
        } else {
          fis.close();
        } 
      }
    } 

    
    return var4;
  }
  
  public static NamedTag readLE(String file, boolean compressed) throws Exception {
    return readLE(new File(file), compressed);
  }
  public static NamedTag readLE(File file) throws Exception {
    NamedTag var3;
    FileInputStream fis = new FileInputStream(file);
    Throwable var2 = null;

    
    try {
      var3 = (new NBTDeserializer(false, true)).fromStream(detectDecompression(fis));
    } catch (Throwable var12) {
      var2 = var12;
      throw var12;
    } finally {
      if (fis != null) {
        if (var2 != null) {
          try {
            fis.close();
          } catch (Throwable var11) {
            var2.addSuppressed(var11);
          } 
        } else {
          fis.close();
        } 
      }
    } 

    
    return var3;
  }
  
  public static NamedTag readLE(String file) throws Exception {
    return readLE(new File(file));
  }
  
  private static InputStream detectDecompression(InputStream is) throws IOException {
    PushbackInputStream pbis = new PushbackInputStream(is, 2);
    int signature = (pbis.read() & 0xFF) + (pbis.read() << 8);
    pbis.unread(signature >> 8);
    pbis.unread(signature & 0xFF);
    return (signature == 35615) ? new GZIPInputStream(pbis) : pbis;
  }
}
