package net.querz.mca;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@FunctionalInterface
@Environment(EnvType.CLIENT)
public interface ExceptionFunction<T, R, E extends Exception> {
  R accept(T paramT) throws E;
}
