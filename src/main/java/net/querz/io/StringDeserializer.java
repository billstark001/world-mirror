package net.querz.io;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;

@Environment(EnvType.CLIENT)
public interface StringDeserializer<T> extends Deserializer<T> {
  default T fromString(String s) throws IOException {
    return fromReader(new StringReader(s));
  } T fromReader(Reader paramReader) throws IOException;
  default T fromStream(InputStream stream) throws IOException {
    Object var4;
    Reader reader = new InputStreamReader(stream);
    Throwable var3 = null;

    
    try {
      var4 = fromReader(reader);
    } catch (Throwable var13) {
      var3 = var13;
      throw var13;
    } finally {
      if (reader != null) {
        if (var3 != null) {
          try {
            reader.close();
          } catch (Throwable var12) {
            var3.addSuppressed(var12);
          } 
        } else {
          reader.close();
        } 
      }
    } 

    
    return (T)var4;
  }
  default T fromFile(File file) throws IOException {
    Object var4;
    Reader reader = new FileReader(file);
    Throwable var3 = null;

    
    try {
      var4 = fromReader(reader);
    } catch (Throwable var13) {
      var3 = var13;
      throw var13;
    } finally {
      if (reader != null) {
        if (var3 != null) {
          try {
            reader.close();
          } catch (Throwable var12) {
            var3.addSuppressed(var12);
          } 
        } else {
          reader.close();
        } 
      }
    } 

    
    return (T)var4;
  }
  
  default T fromBytes(byte[] data) throws IOException {
    return fromReader(new StringReader(new String(data)));
  }
}
