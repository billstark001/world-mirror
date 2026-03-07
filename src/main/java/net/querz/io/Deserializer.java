package net.querz.io;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.URL;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
public interface Deserializer<T> {
  default T fromFile(File file) throws Exception {
    Object var4;
    BufferedInputStream bis = new BufferedInputStream(new FileInputStream(file));
    Throwable var3 = null;

    
    try {
      var4 = fromStream(bis);
    } catch (Throwable var13) {
      var3 = var13;
      throw var13;
    } finally {
      if (bis != null) {
        if (var3 != null) {
          try {
            bis.close();
          } catch (Throwable var12) {
            var3.addSuppressed(var12);
          } 
        } else {
          bis.close();
        } 
      }
    } 

    
    return (T)var4;
  }
  T fromStream(InputStream paramInputStream) throws Exception;
  default T fromBytes(byte[] data) throws Exception {
    ByteArrayInputStream stream = new ByteArrayInputStream(data);
    return fromStream(stream);
  }
  default T fromResource(Class<?> clazz, String path) throws Exception {
    Object var5;
    InputStream stream = clazz.getClassLoader().getResourceAsStream(path);
    Throwable var4 = null;

    
    try {
      if (stream == null) {
        throw new IOException("resource \"" + path + "\" not found");
      }
      
      var5 = fromStream(stream);
    } catch (Throwable var14) {
      var4 = var14;
      throw var14;
    } finally {
      if (stream != null) {
        if (var4 != null) {
          try {
            stream.close();
          } catch (Throwable var13) {
            var4.addSuppressed(var13);
          } 
        } else {
          stream.close();
        } 
      }
    } 

    
    return (T)var5;
  }
  default T fromURL(URL url) throws Exception {
    Object var4;
    InputStream stream = url.openStream();
    Throwable var3 = null;

    
    try {
      var4 = fromStream(stream);
    } catch (Throwable var13) {
      var3 = var13;
      throw var13;
    } finally {
      if (stream != null) {
        if (var3 != null) {
          try {
            stream.close();
          } catch (Throwable var12) {
            var3.addSuppressed(var12);
          } 
        } else {
          stream.close();
        } 
      }
    } 

    
    return (T)var4;
  }
}
