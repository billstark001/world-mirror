package net.billstark001.worldmirror.mixin;

import net.billstark001.worldmirror.io.ChunkSerializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Placeholder mixin for ChestBlockEntity.
 * Container item data is merged into block-entity NBT by
 * {@link ChunkSerializer} via
 * {@link net.billstark001.worldmirror.core.ContainerTracker#enhanceBlockEntityWithContainerData},
 * so no injection into {@code writeData} is needed here.
 */
@Environment(EnvType.CLIENT)
@Mixin({ChestBlockEntity.class})
public class ChestBlockEntityMixin {
    // intentionally empty — see class Javadoc
}
