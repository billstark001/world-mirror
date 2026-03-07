package net.querz.nbt.io;

import java.io.IOException;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.querz.nbt.tag.Tag;

@Environment(EnvType.CLIENT)
public interface NBTOutput {
  void writeTag(NamedTag paramNamedTag, int paramInt) throws IOException;
  
  void writeTag(Tag<?> paramTag, int paramInt) throws IOException;
  
  void flush() throws IOException;
}
