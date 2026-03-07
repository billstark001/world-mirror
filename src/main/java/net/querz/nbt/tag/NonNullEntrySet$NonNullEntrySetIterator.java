package net.querz.nbt.tag;

import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
























































































@Environment(EnvType.CLIENT)
class NonNullEntrySetIterator
  implements Iterator<Map.Entry<K, V>>
{
  private Iterator<Map.Entry<K, V>> iterator;
  
  NonNullEntrySetIterator(Iterator<Map.Entry<K, V>> iterator) {
    this.iterator = iterator;
  }
  
  public boolean hasNext() {
    return this.iterator.hasNext();
  }
  
  public Map.Entry<K, V> next() {
    Objects.requireNonNull(NonNullEntrySet.this); return new NonNullEntrySet.NonNullEntry(NonNullEntrySet.this, this.iterator.next());
  }
}
