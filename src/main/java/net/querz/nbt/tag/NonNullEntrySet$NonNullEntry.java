package net.querz.nbt.tag;

import java.util.Map;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;


























































@Environment(EnvType.CLIENT)
class NonNullEntry
  implements Map.Entry<K, V>
{
  private Map.Entry<K, V> entry;
  
  NonNullEntry(NonNullEntrySet this$0, Map.Entry<K, V> entry) {
    this.entry = entry;
  }
  
  public K getKey() {
    return this.entry.getKey();
  }
  
  public V getValue() {
    return this.entry.getValue();
  }
  
  public V setValue(V value) {
    if (value == null) {
      throw new NullPointerException(getClass().getSimpleName() + " does not allow setting null");
    }
    return this.entry.setValue(value);
  }

  
  public boolean equals(Object o) {
    return this.entry.equals(o);
  }
  
  public int hashCode() {
    return this.entry.hashCode();
  }
}
