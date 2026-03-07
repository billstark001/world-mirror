package net.billstark001.worlddownloader.mixin;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.billstark001.worlddownloader.core.ContainerTracker;
import net.minecraft.storage.WriteView;
import net.minecraft.util.math.BlockPos;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.block.entity.ChestBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;


@Environment(EnvType.CLIENT)
@Mixin({ChestBlockEntity.class})
public class ChestBlockEntityMixin {

    @Inject(method = {"writeData"}, at = {@At("RETURN")})
    private void onWriteNbt(WriteView view, CallbackInfo ci) {
        ChestBlockEntity chest = (ChestBlockEntity) (Object) this;
        BlockPos pos = chest.getPos();
        CallbackInfoReturnable<NbtCompound> cir = new CallbackInfoReturnable<>(ci.getId(), false);


        if (ContainerTracker.hasContainerData(pos)) {
            NbtCompound enhancedNbt = ContainerTracker.enhanceBlockEntityWithContainerData(chest, cir.getReturnValue());
            cir.setReturnValue(enhancedNbt);
        }
    }
}
