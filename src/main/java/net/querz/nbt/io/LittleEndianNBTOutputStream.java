package net.querz.nbt.io;
import java.io.Closeable;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.querz.io.ExceptionTriConsumer;
import net.querz.io.MaxDepthIO;
import net.querz.nbt.tag.ByteArrayTag;
import net.querz.nbt.tag.ByteTag;
import net.querz.nbt.tag.CompoundTag;
import net.querz.nbt.tag.DoubleTag;
import net.querz.nbt.tag.FloatTag;
import net.querz.nbt.tag.IntArrayTag;
import net.querz.nbt.tag.IntTag;
import net.querz.nbt.tag.ListTag;
import net.querz.nbt.tag.LongArrayTag;
import net.querz.nbt.tag.LongTag;
import net.querz.nbt.tag.ShortTag;
import net.querz.nbt.tag.StringTag;
import net.querz.nbt.tag.Tag;

@Environment(EnvType.CLIENT)
public class LittleEndianNBTOutputStream implements DataOutput, NBTOutput, MaxDepthIO, Closeable {
  private static Map<Byte, ExceptionTriConsumer<LittleEndianNBTOutputStream, Tag<?>, Integer, IOException>> writers = new HashMap<>(); private final DataOutputStream output;
  private static Map<Class<?>, Byte> classIdMapping = new HashMap<>();
  
  private static void put(byte id, ExceptionTriConsumer<LittleEndianNBTOutputStream, Tag<?>, Integer, IOException> f, Class<?> clazz) {
    writers.put(Byte.valueOf(id), f);
    classIdMapping.put(clazz, Byte.valueOf(id));
  }
  
  public LittleEndianNBTOutputStream(OutputStream out) {
    this.output = new DataOutputStream(out);
  }
  
  public LittleEndianNBTOutputStream(DataOutputStream out) {
    this.output = out;
  }
  
  public void writeTag(NamedTag tag, int maxDepth) throws IOException {
    writeByte(tag.getTag().getID());
    if (tag.getTag().getID() != 0) {
      writeUTF((tag.getName() == null) ? "" : tag.getName());
    }
    
    try {
      writeRawTag(tag.getTag(), maxDepth);
    } catch (Exception e) {
      throw new RuntimeException(e);
    } 
  }
  
  public void writeTag(Tag<?> tag, int maxDepth) throws IOException {
    writeByte(tag.getID());
    if (tag.getID() != 0) {
      writeUTF("");
    }
    
    try {
      writeRawTag(tag, maxDepth);
    } catch (Exception e) {
      throw new RuntimeException(e);
    } 
  }
  
  public void writeRawTag(Tag<?> tag, int maxDepth) throws Exception {
    ExceptionTriConsumer f;
    if ((f = writers.get(Byte.valueOf(tag.getID()))) == null) {
      throw new IOException("invalid tag \"" + tag.getID() + "\"");
    }
    f.accept(this, tag, Integer.valueOf(maxDepth));
  }

  
  static byte idFromClass(Class<?> clazz) {
    Byte id = classIdMapping.get(clazz);
    if (id == null) {
      throw new IllegalArgumentException("unknown Tag class " + clazz.getName());
    }
    return id.byteValue();
  }

  
  private static void writeByte(LittleEndianNBTOutputStream out, Tag<?> tag) throws IOException {
    out.writeByte(((ByteTag)tag).asByte());
  }
  
  private static void writeShort(LittleEndianNBTOutputStream out, Tag<?> tag) throws IOException {
    out.writeShort(((ShortTag)tag).asShort());
  }
  
  private static void writeInt(LittleEndianNBTOutputStream out, Tag<?> tag) throws IOException {
    out.writeInt(((IntTag)tag).asInt());
  }
  
  private static void writeLong(LittleEndianNBTOutputStream out, Tag<?> tag) throws IOException {
    out.writeLong(((LongTag)tag).asLong());
  }
  
  private static void writeFloat(LittleEndianNBTOutputStream out, Tag<?> tag) throws IOException {
    out.writeFloat(((FloatTag)tag).asFloat());
  }
  
  private static void writeDouble(LittleEndianNBTOutputStream out, Tag<?> tag) throws IOException {
    out.writeDouble(((DoubleTag)tag).asDouble());
  }
  
  private static void writeString(LittleEndianNBTOutputStream out, Tag<?> tag) throws IOException {
    out.writeUTF(((StringTag)tag).getValue());
  }
  
  private static void writeByteArray(LittleEndianNBTOutputStream out, Tag<?> tag) throws IOException {
    out.writeInt(((ByteArrayTag)tag).length());
    out.write((byte[])((ByteArrayTag)tag).getValue());
  }
  
  private static void writeIntArray(LittleEndianNBTOutputStream out, Tag<?> tag) throws IOException {
    out.writeInt(((IntArrayTag)tag).length());
    int[] var2 = (int[])((IntArrayTag)tag).getValue();
    int var3 = var2.length;
    
    for (int var4 = 0; var4 < var3; var4++) {
      int i = var2[var4];
      out.writeInt(i);
    } 
  }

  
  private static void writeLongArray(LittleEndianNBTOutputStream out, Tag<?> tag) throws IOException {
    out.writeInt(((LongArrayTag)tag).length());
    long[] var2 = (long[])((LongArrayTag)tag).getValue();
    int var3 = var2.length;
    
    for (int var4 = 0; var4 < var3; var4++) {
      long l = var2[var4];
      out.writeLong(l);
    } 
  }

  
  private static void writeList(LittleEndianNBTOutputStream out, Tag<?> tag, int maxDepth) throws Exception {
    out.writeByte(idFromClass(((ListTag)tag).getTypeClass()));
    out.writeInt(((ListTag)tag).size());
    Iterator<Tag> var3 = ((ListTag)tag).iterator();
    
    while (var3.hasNext()) {
      Tag<?> t = var3.next();
      out.writeRawTag(t, out.decrementMaxDepth(maxDepth));
    } 
  }

  
  private static void writeCompound(LittleEndianNBTOutputStream out, Tag<?> tag, int maxDepth) throws Exception {
    Iterator<Map.Entry<String, Tag<?>>> var3 = ((CompoundTag)tag).iterator();
    
    while (var3.hasNext()) {
      Map.Entry<String, Tag<?>> entry = var3.next();
      if (((Tag)entry.getValue()).getID() == 0) {
        throw new IOException("end tag not allowed");
      }
      
      out.writeByte(((Tag)entry.getValue()).getID());
      out.writeUTF(entry.getKey());
      out.writeRawTag(entry.getValue(), out.decrementMaxDepth(maxDepth));
    } 
    
    out.writeByte(0);
  }
  
  public void close() throws IOException {
    this.output.close();
  }
  
  public void flush() throws IOException {
    this.output.flush();
  }
  
  public void write(int b) throws IOException {
    this.output.write(b);
  }
  
  public void write(byte[] b) throws IOException {
    this.output.write(b);
  }
  
  public void write(byte[] b, int off, int len) throws IOException {
    this.output.write(b, off, len);
  }
  
  public void writeBoolean(boolean v) throws IOException {
    this.output.writeBoolean(v);
  }
  
  public void writeByte(int v) throws IOException {
    this.output.writeByte(v);
  }
  
  public void writeShort(int v) throws IOException {
    this.output.writeShort(Short.reverseBytes((short)v));
  }
  
  public void writeChar(int v) throws IOException {
    this.output.writeChar(Character.reverseBytes((char)v));
  }
  
  public void writeInt(int v) throws IOException {
    this.output.writeInt(Integer.reverseBytes(v));
  }
  
  public void writeLong(long v) throws IOException {
    this.output.writeLong(Long.reverseBytes(v));
  }
  
  public void writeFloat(float v) throws IOException {
    this.output.writeInt(Integer.reverseBytes(Float.floatToIntBits(v)));
  }
  
  public void writeDouble(double v) throws IOException {
    this.output.writeLong(Long.reverseBytes(Double.doubleToLongBits(v)));
  }
  
  public void writeBytes(String s) throws IOException {
    this.output.writeBytes(s);
  }
  
  public void writeChars(String s) throws IOException {
    this.output.writeChars(s);
  }
  
  public void writeUTF(String s) throws IOException {
    byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
    writeShort(bytes.length);
    write(bytes);
  }
  
  static {
    put((byte)0, (o, t, d) -> {  }EndTag.class);
    
    put((byte)1, (o, t, d) -> writeByte(o, t), ByteTag.class);

    
    put((byte)2, (o, t, d) -> writeShort(o, t), ShortTag.class);

    
    put((byte)3, (o, t, d) -> writeInt(o, t), IntTag.class);

    
    put((byte)4, (o, t, d) -> writeLong(o, t), LongTag.class);

    
    put((byte)5, (o, t, d) -> writeFloat(o, t), FloatTag.class);

    
    put((byte)6, (o, t, d) -> writeDouble(o, t), DoubleTag.class);

    
    put((byte)7, (o, t, d) -> writeByteArray(o, t), ByteArrayTag.class);

    
    put((byte)8, (o, t, d) -> writeString(o, t), StringTag.class);

    
    put((byte)9, LittleEndianNBTOutputStream::writeList, ListTag.class);
    put((byte)10, LittleEndianNBTOutputStream::writeCompound, CompoundTag.class);
    put((byte)11, (o, t, d) -> writeIntArray(o, t), IntArrayTag.class);

    
    put((byte)12, (o, t, d) -> writeLongArray(o, t), LongArrayTag.class);
  }
}
