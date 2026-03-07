package net.querz.nbt.io;
import java.io.IOException;
import java.io.Writer;
import java.lang.reflect.Array;
import java.util.Iterator;
import java.util.Map;
import java.util.regex.Pattern;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.querz.io.MaxDepthIO;
import net.querz.nbt.tag.ByteArrayTag;
import net.querz.nbt.tag.ByteTag;
import net.querz.nbt.tag.CompoundTag;
import net.querz.nbt.tag.IntArrayTag;
import net.querz.nbt.tag.IntTag;
import net.querz.nbt.tag.ListTag;
import net.querz.nbt.tag.LongArrayTag;
import net.querz.nbt.tag.LongTag;
import net.querz.nbt.tag.ShortTag;
import net.querz.nbt.tag.StringTag;
import net.querz.nbt.tag.Tag;

@Environment(EnvType.CLIENT)
public final class SNBTWriter implements MaxDepthIO {
  private static final Pattern NON_QUOTE_PATTERN = Pattern.compile("[a-zA-Z_.+\\-]+");
  private Writer writer;
  
  private SNBTWriter(Writer writer) {
    this.writer = writer;
  }
  
  public static void write(Tag<?> tag, Writer writer, int maxDepth) throws IOException {
    (new SNBTWriter(writer)).writeAnything(tag, maxDepth);
  }
  
  public static void write(Tag<?> tag, Writer writer) throws IOException {
    write(tag, writer, 512); } private void writeAnything(Tag<?> tag, int maxDepth) throws IOException {
    int i;
    boolean first;
    Iterator<Map.Entry<String, Tag<?>>> var4;
    switch (tag.getID()) {
      case 0:
        return;
      case 1:
        this.writer.append(Byte.toString(((ByteTag)tag).asByte())).write(98);
      
      case 2:
        this.writer.append(Short.toString(((ShortTag)tag).asShort())).write(115);
      
      case 3:
        this.writer.write(Integer.toString(((IntTag)tag).asInt()));
      
      case 4:
        this.writer.append(Long.toString(((LongTag)tag).asLong())).write(108);
      
      case 5:
        this.writer.append(Float.toString(((FloatTag)tag).asFloat())).write(102);
      
      case 6:
        this.writer.append(Double.toString(((DoubleTag)tag).asDouble())).write(100);
      
      case 7:
        writeArray(((ByteArrayTag)tag).getValue(), ((ByteArrayTag)tag).length(), "B");
      
      case 8:
        this.writer.write(escapeString(((StringTag)tag).getValue()));
      
      case 9:
        this.writer.write(91);
        
        for (i = 0; i < ((ListTag)tag).size(); i++) {
          this.writer.write((i == 0) ? "" : ",");
          writeAnything(((ListTag)tag).get(i), decrementMaxDepth(maxDepth));
        } 
        
        this.writer.write(93);
      
      case 10:
        this.writer.write(123);
        first = true;
        
        for (var4 = ((CompoundTag)tag).iterator(); var4.hasNext(); first = false) {
          Map.Entry<String, Tag<?>> entry = var4.next();
          this.writer.write(first ? "" : ",");
          this.writer.append(escapeString(entry.getKey())).write(58);
          writeAnything(entry.getValue(), decrementMaxDepth(maxDepth));
        } 
        
        this.writer.write(125);
      
      case 11:
        writeArray(((IntArrayTag)tag).getValue(), ((IntArrayTag)tag).length(), "I");
      
      case 12:
        writeArray(((LongArrayTag)tag).getValue(), ((LongArrayTag)tag).length(), "L");
    } 
    
    throw new IOException("unknown tag with id \"" + tag.getID() + "\"");
  }


  
  private void writeArray(Object array, int length, String prefix) throws IOException {
    this.writer.append('[').append(prefix).write(59);
    
    for (int i = 0; i < length; i++) {
      this.writer.append((i == 0) ? "" : ",").write(Array.get(array, i).toString());
    }
    
    this.writer.write(93);
  }
  
  public static String escapeString(String s) {
    if (NON_QUOTE_PATTERN.matcher(s).matches()) {
      return s;
    }
    StringBuilder sb = new StringBuilder();
    sb.append('"');
    
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '\\' || c == '"') {
        sb.append('\\');
      }
      
      sb.append(c);
    } 
    
    sb.append('"');
    return sb.toString();
  }
}
