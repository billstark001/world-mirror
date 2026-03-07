package net.querz.nbt.tag;
@Environment(EnvType.CLIENT)
public abstract class NumberTag<T extends Number & Comparable<T>> extends Tag<T> {
  public NumberTag(T value) {
    super(value);
  }
  
  public byte asByte() {
    return ((Number)getValue()).byteValue();
  }
  
  public short asShort() {
    return ((Number)getValue()).shortValue();
  }
  
  public int asInt() {
    return ((Number)getValue()).intValue();
  }
  
  public long asLong() {
    return ((Number)getValue()).longValue();
  }
  
  public float asFloat() {
    return ((Number)getValue()).floatValue();
  }
  
  public double asDouble() {
    return ((Number)getValue()).doubleValue();
  }
  
  public String valueToString(int maxDepth) {
    return ((Number)getValue()).toString();
  }
}
