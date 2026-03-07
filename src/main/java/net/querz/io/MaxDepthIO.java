package net.querz.io;
@Environment(EnvType.CLIENT)
public interface MaxDepthIO {
  default int decrementMaxDepth(int maxDepth) {
    if (maxDepth < 0)
      throw new IllegalArgumentException("negative maximum depth is not allowed"); 
    if (maxDepth == 0) {
      throw new MaxDepthReachedException("reached maximum depth of NBT structure");
    }
    maxDepth--;
    return maxDepth;
  }
}
