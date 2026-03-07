package net.querz.nbt.io;

import java.io.IOException;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.querz.nbt.tag.Tag;

@Environment(EnvType.CLIENT)
public class SNBTUtil {
  public static String toSNBT(Tag<?> tag) throws IOException {
    return (new SNBTSerializer()).toString(tag);
  }
  
  public static Tag<?> fromSNBT(String string) throws IOException {
    return (Tag)(new SNBTDeserializer()).fromString(string);
  }
  
  public static Tag<?> fromSNBT(String string, boolean lenient) throws IOException {
    return (new SNBTParser(string)).parse(512, lenient);
  }
}
