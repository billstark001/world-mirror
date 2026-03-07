package net.querz.nbt.io;
import net.fabricmc.api.EnvType;
import net.querz.nbt.tag.Tag;

@Environment(EnvType.CLIENT)
public class NamedTag {
  private String name;
  
  public NamedTag(String name, Tag<?> tag) {
    this.name = name;
    this.tag = tag;
  }
  private Tag<?> tag;
  public void setName(String name) {
    this.name = name;
  }
  
  public void setTag(Tag<?> tag) {
    this.tag = tag;
  }
  
  public String getName() {
    return this.name;
  }
  
  public Tag<?> getTag() {
    return this.tag;
  }
}
