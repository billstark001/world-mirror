package io.github.billstark001.worldmirror.mixin;

import io.github.billstark001.worldmirror.download.WorldOpenMigrationController;
import com.mojang.serialization.Dynamic;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Defers only outdated World Mirror saves after vanilla's version migration. */
@Mixin(WorldOpenFlows.class)
abstract class WorldOpenFlowsMixin {
    @Unique private LevelStorageSource.LevelStorageAccess worldmirror$resuming;

    @Invoker("openWorldLoadLevelStem")
    protected abstract void worldmirror$resumeStem(LevelStorageSource.LevelStorageAccess worldAccess,
            Dynamic<?> levelData, boolean safeMode, Runnable reloadCallback);

    @Inject(method = "openWorldLoadLevelStem", at = @At("HEAD"), cancellable = true)
    private void worldmirror$upgradeOutdatedMirrorAfterVanillaUpgrade(
            LevelStorageSource.LevelStorageAccess worldAccess, Dynamic<?> levelData, boolean safeMode,
            Runnable reloadCallback, CallbackInfo ci) {
        // Vanilla's safe mode retries an attempt that already passed our disk checks.
        if (safeMode || worldmirror$resuming == worldAccess)
            return;
        Runnable resume = () -> {
            worldmirror$resuming = worldAccess;
            try {
                worldmirror$resumeStem(worldAccess, levelData, safeMode, reloadCallback);
            } finally {
                worldmirror$resuming = null;
            }
        };
        if (WorldOpenMigrationController.intercept((WorldOpenFlows) (Object) this,
                    worldAccess.getLevelPath(net.minecraft.world.level.storage.LevelResource.ROOT),
                    worldAccess.getLevelId(), reloadCallback, worldAccess::safeClose, resume)) {
            ci.cancel();
        }
    }
}
