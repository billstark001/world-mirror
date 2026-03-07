package net.querz.mca;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
public final class LoadFlags {
  public static final long BIOMES = 1L;
  
  public static final long HEIGHTMAPS = 2L;
  
  public static final long CARVING_MASKS = 4L;
  
  public static final long ENTITIES = 8L;
  
  public static final long TILE_ENTITIES = 16L;
  
  public static final long TILE_TICKS = 64L;
  
  public static final long LIQUID_TICKS = 128L;
  
  public static final long TO_BE_TICKED = 256L;
  
  public static final long POST_PROCESSING = 512L;
  
  public static final long STRUCTURES = 1024L;
  
  public static final long BLOCK_LIGHTS = 2048L;
  
  public static final long BLOCK_STATES = 4096L;
  
  public static final long SKY_LIGHT = 8192L;
  
  public static final long LIGHTS = 16384L;
  
  public static final long LIQUIDS_TO_BE_TICKED = 32768L;
  
  public static final long RAW = 65536L;
  
  public static final long ALL_DATA = -1L;
}
