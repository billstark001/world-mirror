package io.github.billstark001.worldmirror.mixin;

import io.github.billstark001.worldmirror.download.WorldOpenMigrationController;
import com.mojang.serialization.Dynamic;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Defers only outdated World Mirror saves after vanilla's version migration. */
@Mixin(WorldOpenFlows.class)
abstract class WorldOpenFlowsMixin {
    @Inject(method = "openWorldLoadLevelStem", at = @At("HEAD"), cancellable = true)
    private void worldmirror$upgradeOutdatedMirrorAfterVanillaUpgrade(
            LevelStorageSource.LevelStorageAccess worldAccess, Dynamic<?> levelData,
            boolean safeMode, Runnable reloadCallback, CallbackInfo ci) {
        if (WorldOpenMigrationController.intercept((WorldOpenFlows) (Object) this,
                worldAccess.getLevelId(), reloadCallback, worldAccess::safeClose)) {
            ci.cancel();
        }
    }
}
