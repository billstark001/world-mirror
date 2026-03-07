package net.querz.nbt.io;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.querz.io.MaxDepthIO;
import net.querz.nbt.tag.ArrayTag;
import net.querz.nbt.tag.ByteArrayTag;
import net.querz.nbt.tag.ByteTag;
import net.querz.nbt.tag.CompoundTag;
import net.querz.nbt.tag.DoubleTag;
import net.querz.nbt.tag.EndTag;
import net.querz.nbt.tag.IntArrayTag;
import net.querz.nbt.tag.ListTag;
import net.querz.nbt.tag.LongArrayTag;
import net.querz.nbt.tag.LongTag;
import net.querz.nbt.tag.ShortTag;
import net.querz.nbt.tag.StringTag;
import net.querz.nbt.tag.Tag;

@Environment(EnvType.CLIENT)
public final class SNBTParser implements MaxDepthIO {
  private static final Pattern FLOAT_LITERAL_PATTERN = Pattern.compile("^[-+]?(?:\\d+\\.?|\\d*\\.\\d+)(?:e[-+]?\\d+)?f$", 2);
  private static final Pattern DOUBLE_LITERAL_PATTERN = Pattern.compile("^[-+]?(?:\\d+\\.?|\\d*\\.\\d+)(?:e[-+]?\\d+)?d$", 2);
  private static final Pattern DOUBLE_LITERAL_NO_SUFFIX_PATTERN = Pattern.compile("^[-+]?(?:\\d+\\.|\\d*\\.\\d+)(?:e[-+]?\\d+)?$", 2);
  private static final Pattern BYTE_LITERAL_PATTERN = Pattern.compile("^[-+]?\\d+b$", 2);
  private static final Pattern SHORT_LITERAL_PATTERN = Pattern.compile("^[-+]?\\d+s$", 2);
  private static final Pattern INT_LITERAL_PATTERN = Pattern.compile("^[-+]?\\d+$", 2);
  private static final Pattern LONG_LITERAL_PATTERN = Pattern.compile("^[-+]?\\d+l$", 2);
  private static final Pattern NUMBER_PATTERN = Pattern.compile("^[-+]?\\d+$");
  private StringPointer ptr;
  
  public SNBTParser(String string) {
    this.ptr = new StringPointer(string);
  }
  
  public Tag<?> parse(int maxDepth, boolean lenient) throws ParseException {
    Tag<?> tag = parseAnything(maxDepth);
    if (!lenient) {
      this.ptr.skipWhitespace();
      if (this.ptr.hasNext()) {
        throw this.ptr.parseException("invalid characters after end of snbt");
      }
    } 
    
    return tag;
  }
  
  public Tag<?> parse(int maxDepth) throws ParseException {
    return parse(maxDepth, false);
  }
  
  public Tag<?> parse() throws ParseException {
    return parse(512, false);
  }
  
  public int getReadChars() {
    return this.ptr.getIndex() + 1;
  }
  
  private Tag<?> parseAnything(int maxDepth) throws ParseException {
    this.ptr.skipWhitespace();
    switch (this.ptr.currentChar()) {
      case '[':
        if (this.ptr.hasCharsLeft(2) && this.ptr.lookAhead(1) != '"' && this.ptr.lookAhead(2) == ';') {
          return (Tag<?>)parseNumArray();
        }
        
        return (Tag<?>)parseListTag(maxDepth);
      case '{':
        return (Tag<?>)parseCompoundTag(maxDepth);
    } 
    return parseStringOrLiteral();
  }

  
  private Tag<?> parseStringOrLiteral() throws ParseException {
    this.ptr.skipWhitespace();
    if (this.ptr.currentChar() == '"') {
      return (Tag<?>)new StringTag(this.ptr.parseQuotedString());
    }
    String s = this.ptr.parseSimpleString();
    if (s.isEmpty())
      throw new ParseException("expected non empty value"); 
    if (FLOAT_LITERAL_PATTERN.matcher(s).matches())
      return (Tag<?>)new FloatTag(Float.parseFloat(s.substring(0, s.length() - 1))); 
    if (BYTE_LITERAL_PATTERN.matcher(s).matches())
      try {
        return (Tag<?>)new ByteTag(Byte.parseByte(s.substring(0, s.length() - 1)));
      } catch (NumberFormatException var3) {
        throw this.ptr.parseException("byte not in range: \"" + s.substring(0, s.length() - 1) + "\"");
      }  
    if (SHORT_LITERAL_PATTERN.matcher(s).matches())
      try {
        return (Tag<?>)new ShortTag(Short.parseShort(s.substring(0, s.length() - 1)));
      } catch (NumberFormatException var4) {
        throw this.ptr.parseException("short not in range: \"" + s.substring(0, s.length() - 1) + "\"");
      }  
    if (LONG_LITERAL_PATTERN.matcher(s).matches())
      try {
        return (Tag<?>)new LongTag(Long.parseLong(s.substring(0, s.length() - 1)));
      } catch (NumberFormatException var5) {
        throw this.ptr.parseException("long not in range: \"" + s.substring(0, s.length() - 1) + "\"");
      }  
    if (INT_LITERAL_PATTERN.matcher(s).matches())
      try {
        return (Tag<?>)new IntTag(Integer.parseInt(s));
      } catch (NumberFormatException var6) {
        throw this.ptr.parseException("int not in range: \"" + s.substring(0, s.length() - 1) + "\"");
      }  
    if (DOUBLE_LITERAL_PATTERN.matcher(s).matches())
      return (Tag<?>)new DoubleTag(Double.parseDouble(s.substring(0, s.length() - 1))); 
    if (DOUBLE_LITERAL_NO_SUFFIX_PATTERN.matcher(s).matches())
      return (Tag<?>)new DoubleTag(Double.parseDouble(s)); 
    if ("true".equalsIgnoreCase(s)) {
      return (Tag<?>)new ByteTag(true);
    }
    return "false".equalsIgnoreCase(s) ? (Tag<?>)new ByteTag(false) : (Tag<?>)new StringTag(s);
  }


  
  private CompoundTag parseCompoundTag(int maxDepth) throws ParseException {
    this.ptr.expectChar('{');
    CompoundTag compoundTag = new CompoundTag();
    this.ptr.skipWhitespace();
    
    while (this.ptr.hasNext() && this.ptr.currentChar() != '}') {
      this.ptr.skipWhitespace();
      String key = (this.ptr.currentChar() == '"') ? this.ptr.parseQuotedString() : this.ptr.parseSimpleString();
      if (key.isEmpty()) {
        throw new ParseException("empty keys are not allowed");
      }
      
      this.ptr.expectChar(':');
      compoundTag.put(key, parseAnything(decrementMaxDepth(maxDepth)));
      if (!this.ptr.nextArrayElement()) {
        break;
      }
    } 
    
    this.ptr.expectChar('}');
    return compoundTag;
  }
  
  private ListTag<?> parseListTag(int maxDepth) throws ParseException {
    this.ptr.expectChar('[');
    this.ptr.skipWhitespace();
    ListTag<?> list = ListTag.createUnchecked(EndTag.class);
    
    while (this.ptr.currentChar() != ']') {
      Tag<?> element = parseAnything(decrementMaxDepth(maxDepth));
      
      try {
        list.addUnchecked(element);
      } catch (IllegalArgumentException var5) {
        IllegalArgumentException ex = var5;
        throw this.ptr.parseException(ex.getMessage());
      } 
      
      if (!this.ptr.nextArrayElement()) {
        break;
      }
    } 
    
    this.ptr.expectChar(']');
    return list;
  }
  
  private ArrayTag<?> parseNumArray() throws ParseException {
    this.ptr.expectChar('[');
    char arrayType = this.ptr.next();
    this.ptr.expectChar(';');
    this.ptr.skipWhitespace();
    switch (arrayType) {
      case 'B':
        return (ArrayTag<?>)parseByteArrayTag();
      case 'I':
        return (ArrayTag<?>)parseIntArrayTag();
      case 'L':
        return (ArrayTag<?>)parseLongArrayTag();
    } 
    throw new ParseException("invalid array type '" + arrayType + "'");
  }

  
  private ByteArrayTag parseByteArrayTag() throws ParseException {
    List<Byte> byteList = new ArrayList<>();

    
    while (this.ptr.currentChar() != ']') {
      String s = this.ptr.parseSimpleString();
      this.ptr.skipWhitespace();
      if (!NUMBER_PATTERN.matcher(s).matches()) {
        throw this.ptr.parseException("invalid byte in ByteArrayTag: \"" + s + "\"");
      }
      
      try {
        byteList.add(Byte.valueOf(Byte.parseByte(s)));
      } catch (NumberFormatException var4) {
        throw this.ptr.parseException("byte not in range: \"" + s + "\"");
      } 
      
      if (this.ptr.nextArrayElement());
    } 


    
    this.ptr.expectChar(']');
    byte[] bytes = new byte[byteList.size()];
    
    for (int i = 0; i < byteList.size(); i++) {
      bytes[i] = ((Byte)byteList.get(i)).byteValue();
    }
    
    return new ByteArrayTag(bytes);
  }

  
  private IntArrayTag parseIntArrayTag() throws ParseException {
    List<Integer> intList = new ArrayList<>();

    
    while (this.ptr.currentChar() != ']') {
      String s = this.ptr.parseSimpleString();
      this.ptr.skipWhitespace();
      if (!NUMBER_PATTERN.matcher(s).matches()) {
        throw this.ptr.parseException("invalid int in IntArrayTag: \"" + s + "\"");
      }
      
      try {
        intList.add(Integer.valueOf(Integer.parseInt(s)));
      } catch (NumberFormatException var4) {
        throw this.ptr.parseException("int not in range: \"" + s + "\"");
      } 
      
      if (this.ptr.nextArrayElement());
    } 


    
    this.ptr.expectChar(']');
    return new IntArrayTag(intList.stream().mapToInt(i -> i.intValue())
        
        .toArray());
  }

  
  private LongArrayTag parseLongArrayTag() throws ParseException {
    List<Long> longList = new ArrayList<>();

    
    while (this.ptr.currentChar() != ']') {
      String s = this.ptr.parseSimpleString();
      this.ptr.skipWhitespace();
      if (!NUMBER_PATTERN.matcher(s).matches()) {
        throw this.ptr.parseException("invalid long in LongArrayTag: \"" + s + "\"");
      }
      
      try {
        longList.add(Long.valueOf(Long.parseLong(s)));
      } catch (NumberFormatException var4) {
        throw this.ptr.parseException("long not in range: \"" + s + "\"");
      } 
      
      if (this.ptr.nextArrayElement());
    } 


    
    this.ptr.expectChar(']');
    return new LongArrayTag(longList.stream().mapToLong(l -> l.longValue())
        
        .toArray());
  }
}
