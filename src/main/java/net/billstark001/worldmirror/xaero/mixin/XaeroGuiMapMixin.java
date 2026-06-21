package net.billstark001.worldmirror.xaero.mixin;

import net.billstark001.worldmirror.xaero.XaeroWorldMapOverlayRenderer;
import net.billstark001.worldmirror.xaero.XaeroWorldMapOverlayStatus;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Optional integration point for Xaero's World Map.
 *
 * <p>The overlay must render after Xaero has drawn the map texture but before
 * Xaero draws waypoints, player markers, menus, tooltips, and other map UI.
 * Injecting at {@code TAIL} is therefore only a fallback: it keeps the overlay
 * usable on unverified Xaero builds, but it can draw above Xaero UI elements.
 *
 * <p>Keep all non-injection helpers at the top of this class, then the
 * {@code HEAD}/{@code TAIL} pass-management injections, then one exact
 * injection method per verified Xaero World Map version/signature. Different
 * Minecraft branches may legitimately use very different exact anchors because
 * Xaero changes its internal renderer signatures between Minecraft and Xaero
 * releases. Do not copy an exact injection point from one Minecraft branch into
 * another unless that target jar has been disassembled and verified.
 *
 * <p>When adding support for another Xaero World Map version in this same
 * Minecraft branch:
 * <ol>
 *   <li>Run {@code scripts/Get-LatestXaerosWorldMap.ps1 -XaeroVersion <version> -Disassemble}
 *       and inspect {@code build/tmp/xaero-inspect/GuiMap.javap.txt}.</li>
 *   <li>Add a new exact {@code @Inject(require = 0)} method at the bottom of this
 *       class for the verified anchor.</li>
 *   <li>Add the version to {@link XaeroWorldMapOverlayStatus#isExactVersionSupported(String)}.</li>
 * </ol>
 *
 * <p>All exact injection methods must call {@link #worldmirror$renderExactOnce}
 * instead of rendering directly. This one-pass guard prevents duplicate draws
 * when multiple exact anchors happen to match the same target method. The
 * {@code TAIL} fallback uses the same guard and only renders when no exact
 * anchor fired during the current {@code extractRenderState} pass.
 */
@Mixin(targets = "xaero.map.gui.GuiMap", remap = false)
public abstract class XaeroGuiMapMixin {
    @Unique
    private void worldmirror$renderExactOnce(GuiGraphicsExtractor ctx) {
        if (XaeroWorldMapOverlayStatus.wasExactRenderedThisPass()) {
            return;
        }
        Screen screen = (Screen) (Object) this;
        XaeroWorldMapOverlayStatus.markExactRenderedThisPass();
        XaeroWorldMapOverlayRenderer.extractRenderState(screen, ctx, screen.width, screen.height);
    }

    @Unique
    private void worldmirror$renderTailFallback(GuiGraphicsExtractor ctx) {
        Screen screen = (Screen) (Object) this;
        XaeroWorldMapOverlayStatus.markTailFallbackRendered();
        XaeroWorldMapOverlayRenderer.extractRenderState(screen, ctx, screen.width, screen.height);
    }

    @Inject(
            method = "extractRenderState",
            at = @At("HEAD"),
            remap = false,
            require = 0
    )
    private void worldmirror$beginWorldMapOverlayPass(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        XaeroWorldMapOverlayStatus.resetRenderPass();
    }

    @Inject(
            method = "extractRenderState",
            at = @At("TAIL"),
            remap = false,
            require = 0
    )
    private void worldmirror$extractWorldMapOverlayTailFallback(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (XaeroWorldMapOverlayStatus.wasExactRenderedThisPass()) {
            return;
        }
        if (!XaeroWorldMapOverlayStatus.isTailFallbackRenderEnabled()) {
            XaeroWorldMapOverlayStatus.markExactUnavailableThisPass();
            return;
        }
        worldmirror$renderTailFallback(ctx);
    }

    @Inject(
            method = "extractRenderState",
            at = @At(
                    value = "INVOKE",
                    target = "Lxaero/map/element/MapElementRenderHandler;render(Lxaero/map/gui/GuiMap;Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;Lxaero/map/graphics/renderer/multitexture/MultiTextureRenderTypeRendererProvider;DDIIDDDDDFZLxaero/map/element/HoveredMapElementHolder;Lnet/minecraft/client/Minecraft;F)Lxaero/map/element/HoveredMapElementHolder;",
                    shift = At.Shift.BEFORE
            ),
            remap = false,
            require = 0,
            expect = 1
    )
    private void worldmirror$extractWorldMapOverlay_1_41_1(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (!XaeroWorldMapOverlayStatus.isLoadedXaeroVersion("1.41.1")) {
            return;
        }
        worldmirror$renderExactOnce(ctx);
    }
}
