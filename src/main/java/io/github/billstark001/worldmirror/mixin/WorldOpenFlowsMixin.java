package io.github.billstark001.worldmirror.mixin;

import io.github.billstark001.worldmirror.download.WorldOpenMigrationController;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Defers only outdated World Mirror saves before vanilla obtains the save lock. */
@Mixin(WorldOpenFlows.class)
abstract class WorldOpenFlowsMixin {
    @Inject(method = "openWorld", at = @At("HEAD"), cancellable = true)
    private void worldmirror$upgradeOutdatedMirrorBeforeOpen(String levelId, Runnable reloadCallback,
                                                              CallbackInfo ci) {
        if (WorldOpenMigrationController.intercept((WorldOpenFlows) (Object) this,
                levelId, reloadCallback)) {
            ci.cancel();
        }
    }
}
