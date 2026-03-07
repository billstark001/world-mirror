package net.querz.io;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@FunctionalInterface
@Environment(EnvType.CLIENT)
public interface ExceptionTriConsumer<T, U, V, E extends Exception> {
  void accept(T paramT, U paramU, V paramV) throws Exception;
}
