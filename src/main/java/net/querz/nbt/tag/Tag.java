package net.querz.nbt.tag;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
public abstract class Tag<T> implements Cloneable {
  public static final int DEFAULT_MAX_DEPTH = 512;
  private static final Map<String, String> ESCAPE_CHARACTERS;
  
  public Tag(T value) {
    setValue(value);
  }


  
  protected T getValue() {
    return this.value;
  }
  
  protected void setValue(T value) {
    this.value = checkValue(value);
  }
  
  protected T checkValue(T value) {
    return Objects.requireNonNull(value);
  }
  
  public final String toString() {
    return toString(512);
  }
  
  public String toString(int maxDepth) {
    return "{\"type\":\"" + getClass().getSimpleName() + "\",\"value\":" + valueToString(maxDepth) + "}";
  }
  
  public String valueToString() {
    return valueToString(512);
  }


  
  public boolean equals(Object other) {
    return (other != null && getClass() == other.getClass());
  }
  
  public int hashCode() {
    return this.value.hashCode();
  }


  
  protected static String escapeString(String s, boolean lenient) {
    StringBuffer sb = new StringBuffer();
    Matcher m = ESCAPE_PATTERN.matcher(s);
    
    while (m.find()) {
      m.appendReplacement(sb, ESCAPE_CHARACTERS.get(m.group()));
    }
    
    m.appendTail(sb);
    m = NON_QUOTE_PATTERN.matcher(s);
    if (!lenient || !m.matches()) {
      sb.insert(0, "\"").append("\"");
    }
    
    return sb.toString();
  }
  
  static {
    Map<String, String> temp = new HashMap<>();
    temp.put("\\", "\\\\\\\\");
    temp.put("\n", "\\\\n");
    temp.put("\t", "\\\\t");
    temp.put("\r", "\\\\r");
    temp.put("\"", "\\\\\"");
    ESCAPE_CHARACTERS = Collections.unmodifiableMap(temp);
  } private static final Pattern ESCAPE_PATTERN = Pattern.compile("[\\\\\n\t\r\"]");
  private static final Pattern NON_QUOTE_PATTERN = Pattern.compile("[a-zA-Z0-9_\\-+]+");
  private T value;
  
  public abstract byte getID();
  
  public abstract String valueToString(int paramInt);
  
  public abstract Tag<T> clone();
}
