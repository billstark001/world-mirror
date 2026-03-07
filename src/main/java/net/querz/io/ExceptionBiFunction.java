package net.querz.io;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@FunctionalInterface
@Environment(EnvType.CLIENT)
public interface ExceptionBiFunction<T, U, R, E extends Exception> {
  R accept(T paramT, U paramU) throws Exception;
}
