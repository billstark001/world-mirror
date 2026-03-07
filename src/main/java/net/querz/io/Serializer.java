package net.querz.io;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

@Environment(EnvType.CLIENT)
public interface Serializer<T> {
  default void toFile(T object, File file) throws IOException {
    BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(file));
    Throwable var4 = null;
    
    try {
      toStream(object, bos);
    } catch (Throwable var13) {
      var4 = var13;
      throw var13;
    } finally {
      if (bos != null) {
        if (var4 != null) {
          try {
            bos.close();
          } catch (Throwable var12) {
            var4.addSuppressed(var12);
          } 
        } else {
          bos.close();
        } 
      }
    } 
  }
  
  void toStream(T paramT, OutputStream paramOutputStream) throws IOException;
  
  default byte[] toBytes(T object) throws IOException {
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    toStream(object, bos);
    bos.close();
    return bos.toByteArray();
  }
}
