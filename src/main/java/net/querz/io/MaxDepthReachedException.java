package net.querz.io;
@Environment(EnvType.CLIENT)
public class MaxDepthReachedException extends RuntimeException {
  public MaxDepthReachedException(String msg) {
    super(msg);
  }
}
