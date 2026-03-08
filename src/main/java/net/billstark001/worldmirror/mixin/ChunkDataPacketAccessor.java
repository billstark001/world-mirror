package net.billstark001.worldmirror.mixin;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Environment(EnvType.CLIENT)
@Mixin({ChunkDataS2CPacket.class})
public interface ChunkDataPacketAccessor {
    @Accessor("chunkX")
    int getChunkX();

    @Accessor("chunkZ")
    int getChunkZ();
}
