package net.querz.io;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.StringWriter;
import java.io.Writer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
public interface StringSerializer<T> extends Serializer<T> {
  default String toString(T object) throws IOException {
    Writer writer = new StringWriter();
    toWriter(object, writer);
    writer.flush();
    return writer.toString();
  }
  void toWriter(T paramT, Writer paramWriter) throws IOException;
  default void toStream(T object, OutputStream stream) throws IOException {
    Writer writer = new OutputStreamWriter(stream);
    toWriter(object, writer);
    writer.flush();
  }
  
  default void toFile(T object, File file) throws IOException {
    Writer writer = new FileWriter(file);
    Throwable var4 = null;
    
    try {
      toWriter(object, writer);
    } catch (Throwable var13) {
      var4 = var13;
      throw var13;
    } finally {
      if (writer != null)
        if (var4 != null) {
          try {
            writer.close();
          } catch (Throwable var12) {
            var4.addSuppressed(var12);
          } 
        } else {
          writer.close();
        }  
    } 
  }
}
