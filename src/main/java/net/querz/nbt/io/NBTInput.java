package net.querz.nbt.io;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.querz.nbt.tag.Tag;

@Environment(EnvType.CLIENT)
public interface NBTInput {
  NamedTag readTag(int paramInt) throws Exception;
  
  Tag<?> readRawTag(int paramInt) throws Exception;
}
