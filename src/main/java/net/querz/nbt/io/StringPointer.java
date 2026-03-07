package net.querz.nbt.io;

@Environment(EnvType.CLIENT)
public class StringPointer {
  private String value;
  
  public StringPointer(String value) {
    this.value = value;
  }
  private int index;
  public int getIndex() {
    return this.index;
  }
  
  public int size() {
    return this.value.length();
  }
  
  public String parseSimpleString() {
    int oldIndex;
    for (oldIndex = this.index; hasNext() && isSimpleChar(currentChar()); this.index++);

    
    return this.value.substring(oldIndex, this.index);
  }
  
  public String parseQuotedString() throws ParseException {
    int oldIndex = ++this.index;
    StringBuilder sb = null;
    boolean escape = false;

    
    while (hasNext()) {
      char c = next();
      if (escape) {
        if (c != '\\' && c != '"') {
          throw parseException("invalid escape of '" + c + "'");
        }
        
        escape = false;
      } else {
        if (c == '\\') {
          escape = true;
          if (sb == null) {
            sb = new StringBuilder(this.value.substring(oldIndex, this.index - 1));
          }
          
          continue;
        } 
        if (c == '"') {
          return (sb == null) ? this.value.substring(oldIndex, this.index - 1) : sb.toString();
        }
      } 
      
      if (sb != null) {
        sb.append(c);
      }
    } 
    
    throw parseException("missing end quote");
  }

  
  public boolean nextArrayElement() {
    skipWhitespace();
    if (hasNext() && currentChar() == ',') {
      this.index++;
      skipWhitespace();
      return true;
    } 
    return false;
  }

  
  public void expectChar(char c) throws ParseException {
    skipWhitespace();
    boolean hasNext = hasNext();
    if (hasNext && currentChar() == c) {
      this.index++;
    } else {
      throw parseException("expected '" + c + "' but got " + (hasNext ? ("'" + currentChar() + "'") : "EOF"));
    } 
  }
  
  public void skipWhitespace() {
    while (hasNext() && Character.isWhitespace(currentChar())) {
      this.index++;
    }
  }

  
  public boolean hasNext() {
    return (this.index < this.value.length());
  }
  
  public boolean hasCharsLeft(int num) {
    return (this.index + num < this.value.length());
  }
  
  public char currentChar() {
    return this.value.charAt(this.index);
  }
  
  public char next() {
    return this.value.charAt(this.index++);
  }
  
  public void skip(int offset) {
    this.index += offset;
  }
  
  public char lookAhead(int offset) {
    return this.value.charAt(this.index + offset);
  }
  
  private static boolean isSimpleChar(char c) {
    return ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == '_');
  }
  
  public ParseException parseException(String msg) {
    return new ParseException(msg, this.value, this.index);
  }
}
