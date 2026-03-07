package net.querz.nbt.io;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.querz.io.ExceptionBiFunction;
import net.querz.io.MaxDepthIO;
import net.querz.nbt.tag.ByteArrayTag;
import net.querz.nbt.tag.ByteTag;
import net.querz.nbt.tag.CompoundTag;
import net.querz.nbt.tag.DoubleTag;
import net.querz.nbt.tag.EndTag;
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
public class LittleEndianNBTInputStream implements DataInput, NBTInput, MaxDepthIO, Closeable {
  private static Map<Byte, ExceptionBiFunction<LittleEndianNBTInputStream, Integer, ? extends Tag<?>, IOException>> readers = new HashMap<>(); private final DataInputStream input;
  private static Map<Byte, Class<?>> idClassMapping = new HashMap<>();
  
  private static void put(byte id, ExceptionBiFunction<LittleEndianNBTInputStream, Integer, ? extends Tag<?>, IOException> reader, Class<?> clazz) {
    readers.put(Byte.valueOf(id), reader);
    idClassMapping.put(Byte.valueOf(id), clazz);
  }
  
  public LittleEndianNBTInputStream(InputStream in) {
    this.input = new DataInputStream(in);
  }
  
  public LittleEndianNBTInputStream(DataInputStream in) {
    this.input = in;
  }
  
  public NamedTag readTag(int maxDepth) throws IOException {
    byte id = readByte();
    try {
      return new NamedTag(readUTF(), readTag(id, maxDepth));
    } catch (Exception e) {
      throw new RuntimeException(e);
    } 
  }
  
  public Tag<?> readRawTag(int maxDepth) throws IOException {
    byte id = readByte();
    try {
      return readTag(id, maxDepth);
    } catch (Exception e) {
      throw new RuntimeException(e);
    } 
  }
  
  private Tag<?> readTag(byte type, int maxDepth) throws Exception {
    ExceptionBiFunction f;
    if ((f = readers.get(Byte.valueOf(type))) == null) {
      throw new IOException("invalid tag id \"" + type + "\"");
    }
    return (Tag)f.accept(this, Integer.valueOf(maxDepth));
  }

  
  private static ByteTag readByte(LittleEndianNBTInputStream in) throws IOException {
    return new ByteTag(in.readByte());
  }
  
  private static ShortTag readShort(LittleEndianNBTInputStream in) throws IOException {
    return new ShortTag(in.readShort());
  }
  
  private static IntTag readInt(LittleEndianNBTInputStream in) throws IOException {
    return new IntTag(in.readInt());
  }
  
  private static LongTag readLong(LittleEndianNBTInputStream in) throws IOException {
    return new LongTag(in.readLong());
  }
  
  private static FloatTag readFloat(LittleEndianNBTInputStream in) throws IOException {
    return new FloatTag(in.readFloat());
  }
  
  private static DoubleTag readDouble(LittleEndianNBTInputStream in) throws IOException {
    return new DoubleTag(in.readDouble());
  }
  
  private static StringTag readString(LittleEndianNBTInputStream in) throws IOException {
    return new StringTag(in.readUTF());
  }
  
  private static ByteArrayTag readByteArray(LittleEndianNBTInputStream in) throws IOException {
    ByteArrayTag bat = new ByteArrayTag(new byte[in.readInt()]);
    in.readFully((byte[])bat.getValue());
    return bat;
  }
  
  private static IntArrayTag readIntArray(LittleEndianNBTInputStream in) throws IOException {
    int l = in.readInt();
    int[] data = new int[l];
    IntArrayTag iat = new IntArrayTag(data);
    
    for (int i = 0; i < l; i++) {
      data[i] = in.readInt();
    }
    
    return iat;
  }
  
  private static LongArrayTag readLongArray(LittleEndianNBTInputStream in) throws IOException {
    int l = in.readInt();
    long[] data = new long[l];
    LongArrayTag iat = new LongArrayTag(data);
    
    for (int i = 0; i < l; i++) {
      data[i] = in.readLong();
    }
    
    return iat;
  }
  
  private static ListTag<?> readListTag(LittleEndianNBTInputStream in, int maxDepth) throws Exception {
    byte listType = in.readByte();
    ListTag<?> list = ListTag.createUnchecked(idClassMapping.get(Byte.valueOf(listType)));
    int length = in.readInt();
    if (length < 0) {
      length = 0;
    }
    
    for (int i = 0; i < length; i++) {
      list.addUnchecked(in.readTag(listType, in.decrementMaxDepth(maxDepth)));
    }
    
    return list;
  }
  
  private static CompoundTag readCompound(LittleEndianNBTInputStream in, int maxDepth) throws Exception {
    CompoundTag comp = new CompoundTag();
    
    for (int id = in.readByte() & 0xFF; id != 0; id = in.readByte() & 0xFF) {
      String key = in.readUTF();
      Tag<?> element = in.readTag((byte)id, in.decrementMaxDepth(maxDepth));
      comp.put(key, element);
    } 
    
    return comp;
  }
  
  public void readFully(byte[] b) throws IOException {
    this.input.readFully(b);
  }
  
  public void readFully(byte[] b, int off, int len) throws IOException {
    this.input.readFully(b, off, len);
  }
  
  public int skipBytes(int n) throws IOException {
    return this.input.skipBytes(n);
  }
  
  public boolean readBoolean() throws IOException {
    return this.input.readBoolean();
  }
  
  public byte readByte() throws IOException {
    return this.input.readByte();
  }
  
  public int readUnsignedByte() throws IOException {
    return this.input.readUnsignedByte();
  }
  
  public short readShort() throws IOException {
    return Short.reverseBytes(this.input.readShort());
  }
  
  public int readUnsignedShort() throws IOException {
    return Short.toUnsignedInt(Short.reverseBytes(this.input.readShort()));
  }
  
  public char readChar() throws IOException {
    return Character.reverseBytes(this.input.readChar());
  }
  
  public int readInt() throws IOException {
    return Integer.reverseBytes(this.input.readInt());
  }
  
  public long readLong() throws IOException {
    return Long.reverseBytes(this.input.readLong());
  }
  
  public float readFloat() throws IOException {
    return Float.intBitsToFloat(Integer.reverseBytes(this.input.readInt()));
  }
  
  public double readDouble() throws IOException {
    return Double.longBitsToDouble(Long.reverseBytes(this.input.readLong()));
  }

  
  @Deprecated
  public String readLine() throws IOException {
    return this.input.readLine();
  }
  
  public void close() throws IOException {
    this.input.close();
  }
  
  public String readUTF() throws IOException {
    byte[] bytes = new byte[readUnsignedShort()];
    readFully(bytes);
    return new String(bytes, StandardCharsets.UTF_8);
  }
  
  static {
    put((byte)0, (i, d) -> EndTag.INSTANCE, EndTag.class);

    
    put((byte)1, (i, d) -> readByte(i), ByteTag.class);

    
    put((byte)2, (i, d) -> readShort(i), ShortTag.class);

    
    put((byte)3, (i, d) -> readInt(i), IntTag.class);

    
    put((byte)4, (i, d) -> readLong(i), LongTag.class);

    
    put((byte)5, (i, d) -> readFloat(i), FloatTag.class);

    
    put((byte)6, (i, d) -> readDouble(i), DoubleTag.class);

    
    put((byte)7, (i, d) -> readByteArray(i), ByteArrayTag.class);

    
    put((byte)8, (i, d) -> readString(i), StringTag.class);

    
    put((byte)9, LittleEndianNBTInputStream::readListTag, ListTag.class);
    put((byte)10, LittleEndianNBTInputStream::readCompound, CompoundTag.class);
    put((byte)11, (i, d) -> readIntArray(i), IntArrayTag.class);

    
    put((byte)12, (i, d) -> readLongArray(i), LongArrayTag.class);
  }
}
