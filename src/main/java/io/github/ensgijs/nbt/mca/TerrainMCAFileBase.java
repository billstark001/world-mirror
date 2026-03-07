//package net.rossquerz.mca;
//
//import CompoundTag;
//
/// **
// * Represents a TERRAIN data mca file (those that live in the /region folder).
// * TODO: Unused - was my intention to fraction out McaRegionFile terrain stuff to here and make McaRegionFile a version abstraction layer?And why si this called "base"?
// */
//public class TerrainMCAFileBase extends McaFileBase<TerrainChunk> implements Iterable<TerrainChunk> {
//	/**
//	 * The default chunk data version used when no custom version is supplied.
//	 * <p>Deprecated: use {@code DataVersion.latest().id()} instead.
//	 */
//	@Deprecated
//	public static final int DEFAULT_DATA_VERSION = DataVersion.latest().id();
//
//	/**
//	 * {@inheritDoc}
//	 */
//	public TerrainMCAFileBase(int regionX, int regionZ) {
//		super(regionX, regionZ);
//	}
//
//	/**
//	 * {@inheritDoc}
//	 */
//	public TerrainMCAFileBase(int regionX, int regionZ, int defaultDataVersion) {
//		super(regionX, regionZ, defaultDataVersion);
//	}
//
//	/**
//	 * {@inheritDoc}
//	 */
//	public TerrainMCAFileBase(int regionX, int regionZ, DataVersion defaultDataVersion) {
//		super(regionX, regionZ, defaultDataVersion);
//	}
//
//	/**
//	 * {@inheritDoc}
//	 */
//	@Override
//	public Class<TerrainChunk> chunkClass() {
//		return TerrainChunk.class;
//	}
//
//	/**
//	 * {@inheritDoc}
//	 */
//	@Override
//	public TerrainChunk createChunk() {
//		return TerrainChunk.newChunk(defaultDataVersion);
//	}
//
//	/**
//	 * @deprecated Use {@link #setBiomeAt(int, int, int, int)} instead
//	 */
//	@Deprecated
//	public void setBiomeAt(int blockX, int blockZ, int biomeID) {
//		createChunkIfMissing(blockX, blockZ).setBiomeAt(blockX, blockZ, biomeID);
//	}
//
//	public void setBiomeAt(int blockX, int blockY, int blockZ, int biomeID) {
//		createChunkIfMissing(blockX, blockZ).setBiomeAt(blockX, blockY, blockZ, biomeID);
//	}
//
//	/**
//	 * @deprecated Use {@link #getBiomeAt(int, int, int)} instead
//	 */
//	@Deprecated
//	public int getBiomeAt(int blockX, int blockZ) {
//		int chunkX = McaFileHelpers.blockToChunk(blockX), chunkZ = McaFileHelpers.blockToChunk(blockZ);
//		TerrainChunk chunk = getChunk(getChunkIndex(chunkX, chunkZ));
//		if (chunk == null) {
//			return -1;
//		}
//		return chunk.getBiomeAt(blockX, blockZ);
//	}
//
//	/**
//	 * Fetches the biome id at a specific block.
//	 * @param blockX The x-coordinate of the block.
//	 * @param blockY The y-coordinate of the block.
//	 * @param blockZ The z-coordinate of the block.
//	 * @return The biome id if the chunk exists and the chunk has biomes, otherwise -1.
//	 */
//	public int getBiomeAt(int blockX, int blockY, int blockZ) {
//		int chunkX = McaFileHelpers.blockToChunk(blockX), chunkZ = McaFileHelpers.blockToChunk(blockZ);
//		TerrainChunk chunk = getChunk(getChunkIndex(chunkX, chunkZ));
//		if (chunk == null) {
//			return -1;
//		}
//		return chunk.getBiomeAt(blockX,blockY, blockZ);
//	}
//
////	/**
////	 * Set a block state at a specific block location.
////	 * The block coordinates can be absolute coordinates or they can be relative to the region.
////	 * @param blockX The x-coordinate of the block.
////	 * @param blockY The y-coordinate of the block.
////	 * @param blockZ The z-coordinate of the block.
////	 * @param state The block state to be set.
////	 * @param cleanup Whether the Palette and the BLockStates should be recalculated after adding the block state.
////	 */
////	public void setBlockStateAt(int blockX, int blockY, int blockZ, CompoundTag state, boolean cleanup) {
////		createChunkIfMissing(blockX, blockZ).setBlockStateAt(blockX, blockY, blockZ, state, cleanup);
////    }
////
////	/**
////	 * Fetches a block state at a specific block location.
////	 * The block coordinates can be absolute coordinates or they can be relative to the region.
////	 * @param blockX The x-coordinate of the block.
////	 * @param blockY The y-coordinate of the block.
////	 * @param blockZ The z-coordinate of the block.
////	 * @return The block state or <code>null</code> if the chunk or the section do not exist.
////	 */
////	public CompoundTag getBlockStateAt(int blockX, int blockY, int blockZ) {
////		int chunkX = McaFileHelpers.blockToChunk(blockX), chunkZ = McaFileHelpers.blockToChunk(blockZ);
////		TerrainChunk chunk = getChunk(chunkX, chunkZ);
////		if (chunk == null) {
////			return null;
////        }
////		return chunk.getBlockStateAt(blockX, blockY, blockZ);
////    }
//
////	/**
////	 * Recalculates the Palette and the BlockStates of all chunks and sections of this region.
////	 */
////	public void cleanupPalettesAndBlockStates() {
////		for (TerrainChunk chunk : chunks) {
////			if (chunk != null) {
////				chunk.cleanupPalettesAndBlockStates();
////            }
////        }
////    }
//}
