package io.github.billstark001.worldmirror.ui;

import com.mojang.blaze3d.platform.InputConstants;
#if MC_VERSION >= 260102
import net.minecraft.client.gui.GuiGraphicsExtractor;
#else
import net.minecraft.client.gui.GuiGraphics;
#endif
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.tabs.GridLayoutTab;
#if MC_VERSION >= 260200
import net.minecraft.client.gui.components.tabs.MenuTabBar;
#endif
import net.minecraft.client.gui.components.tabs.Tab;
import net.minecraft.client.gui.components.tabs.TabManager;
import net.minecraft.client.gui.components.tabs.TabNavigationBar;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/** Uses the same native navigation, background and footer as the Create World menu. */
abstract class WorldMirrorTabbedScreen extends StatusScreenApi {
    private static final int POPUP_ROW_HEIGHT = 18;
    private static final int POPUP_ROW_STEP = 19;
    private TabNavigationBar tabs;
    private boolean selectingInitialTab;
    private boolean tabsEnabled;
    private int tabContentTop;
    private int selectedTab;
    private Runnable pendingTabChange;
    private SelectionPopup selectionPopup;
    private final int footerHeight = HeaderAndFooterLayout.DEFAULT_HEADER_AND_FOOTER_HEIGHT;

    protected record SelectionOption(Component label, boolean selected, Runnable action) {}

    protected static Component selectionLabel(Component label) {
        return label.copy().append("  ▼");
    }

    protected static Component selectionValue(String key, Component current) {
        return Component.translatable(key).append(": ").append(current);
    }

    private final class SelectionPopup {
        final Button opener;
        final List<Button> rows = new ArrayList<>();
        final List<SelectionOption> options;
        final boolean restoreTabs = tabsEnabled;
        final int x, y, popupWidth, visibleRows;
        int first;
        Button focus;

        SelectionPopup(Button opener, List<SelectionOption> options) {
            this.opener = opener;
            this.options = List.copyOf(options);
            visibleRows = Math.min(
                    options.size(), Math.max(1, (footerTop() - 1 - tabContentTop()) / POPUP_ROW_STEP));
            int popupHeight = visibleRows * POPUP_ROW_STEP - 1;
            int below = opener.getY() + opener.getHeight();
            int above = opener.getY() - popupHeight;
            y = Math.clamp(below + popupHeight <= footerTop() - 2 ? below : above, tabContentTop(),
                    Math.max(tabContentTop(), footerTop() - 2 - popupHeight));
            popupWidth = Math.min(opener.getWidth(), 220);
            x = opener.getX() + opener.getWidth() - popupWidth;
        }

        void show(int start, int focused) {
            first = Math.clamp(start, 0, options.size() - visibleRows);
            rows.forEach(WorldMirrorTabbedScreen.this::removeWidget);
            rows.clear();
            for (int row = 0; row < visibleRows; row++) {
                var option = options.get(first + row);
                Component text =
                        option.selected() ? Component.literal("✓ ").append(option.label()) : option.label();
                rows.add(addWidget(
                        Button.builder(text, ignored -> {
                                  closeSelectionPopup();
                                  option.action().run();
                              }).bounds(x, y + row * POPUP_ROW_STEP, popupWidth, POPUP_ROW_HEIGHT).build()));
            }
            focus(rows.get(Math.clamp(focused - first, 0, visibleRows - 1)));
        }

        void moveFocus(int step) {
            int focused =
                    Math.floorMod(first + Math.max(0, rows.indexOf(getFocused())) + step, options.size());
            if (focused < first)
                show(focused, focused);
            else if (focused >= first + visibleRows)
                show(focused - visibleRows + 1, focused);
            else
                focus(rows.get(focused - first));
        }

        void focus(Button button) {
            focus = button;
            setFocused(button);
        }
    }

    protected WorldMirrorTabbedScreen(Component title) {
        super(title);
    }

    protected final void initTabs(
            List<Component> labels, int selected, boolean enabled, IntConsumer onSelect) {
        selectionPopup = null;
        List<Tab> pages = labels.stream().<Tab>map(GridLayoutTab::new).toList();
        var manager = new TabManager(this::addRenderableWidget, this::removeWidget, current -> {
            if (!selectingInitialTab && tabsEnabled) {
                int index = pages.indexOf(current);
                pendingTabChange = () -> onSelect.accept(index);
            }
        }, previous -> {});
        Tab[] nativeTabs = pages.toArray(Tab[] ::new);
#if MC_VERSION >= 260200
        var navigation = MenuTabBar.builder(manager, width).addTabs(nativeTabs).build();
        navigation.arrangeElements(width);
#else
        var navigation = TabNavigationBar.builder(manager, width).addTabs(nativeTabs).build();
        navigation.arrangeElements();
#endif
        tabs = addRenderableWidget(navigation);
        tabContentTop = tabs.getRectangle().bottom();
        selectedTab = selected;
        selectingInitialTab = true;
        try {
            tabs.selectTab(selected, false);
        } finally {
            selectingInitialTab = false;
        }
        setTabsEnabled(enabled);
    }

    protected final void setTabsEnabled(boolean enabled) {
        tabsEnabled = enabled;
        for (int index = 0; index < tabs.getTabs().size(); index++) tabs.setTabActiveState(index, enabled);
    }

    protected final void initFooter(Button... buttons) {
        var layout = new HeaderAndFooterLayout(this, tabContentTop, footerHeight);
        var footer = layout.addToFooter(LinearLayout.horizontal().spacing(8));
        for (Button button : buttons) footer.addChild(button);
        layout.arrangeElements();
        layout.visitWidgets(this::addRenderableWidget);
    }

    protected final int tabContentTop() {
        return tabContentTop;
    }

    protected final int footerTop() {
        return height - footerHeight;
    }

    protected final boolean hasSelectionPopup() {
        return selectionPopup != null;
    }

    @Override
    protected final boolean hasModalOverlay() {
        // Covered buttons must not claim the pointer or queue a tooltip over the popup.
        return hasSelectionPopup();
    }

    protected final void openSelectionPopup(Button opener, List<SelectionOption> options) {
        if (selectionPopup != null) {
            closeSelectionPopup();
            return;
        }
        if (options.isEmpty())
            return;
        int selected = 0;
        for (int index = 0; index < options.size(); index++) {
            if (options.get(index).selected()) {
                selected = index;
                break;
            }
        }
        selectionPopup = new SelectionPopup(opener, options);
        setTabsEnabled(false);
        selectionPopup.show(selected - selectionPopup.visibleRows + 1, selected);
    }

    private void closeSelectionPopup() {
        SelectionPopup popup = selectionPopup;
        selectionPopup = null;
        popup.rows.forEach(this::removeWidget);
        setTabsEnabled(popup.restoreTabs);
        setFocused(popup.opener);
    }

    @Override
    public boolean keyPressed(KeyEvent input) {
        if (selectionPopup != null) {
            if (input.key() == InputConstants.KEY_ESCAPE) {
                closeSelectionPopup();
                return true;
            }
            int step = switch (input.key()) {
                case InputConstants.KEY_UP -> -1;
                case InputConstants.KEY_DOWN -> 1;
                case InputConstants.KEY_TAB -> input.hasShiftDown() ? -1 : 1;
                default -> 0;
            };
            if (step != 0) {
                selectionPopup.moveFocus(step);
                return true;
            }
            if (getFocused() != null)
                getFocused().keyPressed(input);
            return true;
        }
        return tabsEnabled && tabs != null && tabs.keyPressed(input) || super.keyPressed(input);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        if (selectionPopup != null) {
            for (Button row : selectionPopup.rows) {
                if (row.mouseClicked(click, doubled))
                    return true;
            }
            closeSelectionPopup();
            return true;
        }
        boolean handled = super.mouseClicked(click, doubled);
        if (selectionPopup != null && getFocused() == selectionPopup.opener) {
            // Screen assigns focus to the opener after its callback returns.
            setFocused(selectionPopup.focus);
        }
        return handled;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (selectionPopup == null)
            return super.mouseScrolled(x, y, horizontal, vertical);
        if (vertical != 0 && selectionPopup.visibleRows < selectionPopup.options.size()) {
            int start = selectionPopup.first + (vertical < 0 ? 1 : -1);
            int focused = selectionPopup.first + Math.max(0, selectionPopup.rows.indexOf(getFocused()));
            selectionPopup.show(start, focused);
        }
        return true;
    }

    @Override
#if MC_VERSION >= 260102
    protected void renderOverlay(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
#else
    protected void renderOverlay(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
#endif
        if (selectionPopup == null)
            return;
#if MC_VERSION >= 260102
        graphics.nextStratum();
#endif
        for (Button row : selectionPopup.rows) {
#if MC_VERSION >= 260102
            row.extractRenderState(graphics, mouseX, mouseY, delta);
#else
            row.render(graphics, mouseX, mouseY, delta);
#endif
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (pendingTabChange == null)
            return;
        var change = pendingTabChange;
        pendingTabChange = null;
        if (!tabsEnabled)
            return;
        // Vanilla finishes focus transfer after dispatching the click or key event.
        boolean restoreTabFocus = getFocused() == tabs;
        change.run();
        if (restoreTabFocus && tabsEnabled) {
            setFocused(tabs);
            tabs.selectTab(selectedTab, false);
        }
    }

    @Override
#if MC_VERSION >= 260102
    protected void extractMenuBackground(GuiGraphicsExtractor graphics) {
#else
    protected void renderMenuBackground(GuiGraphics graphics) {
#endif
        graphics.blit(RenderPipelines.GUI_TEXTURED, CreateWorldScreen.TAB_HEADER_BACKGROUND, 0, 0, 0, 0,
                width, tabContentTop(), 16, 16);
#if MC_VERSION >= 260102
        extractMenuBackground(graphics, 0, tabContentTop(), width, height);
#else
        renderMenuBackground(graphics, 0, tabContentTop(), width, height);
#endif
    }

    @Override
#if MC_VERSION >= 260102
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractBackground(graphics, mouseX, mouseY, delta);
#else
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        super.renderBackground(graphics, mouseX, mouseY, delta);
#endif
        var separator = minecraft.level == null ? FOOTER_SEPARATOR : INWORLD_FOOTER_SEPARATOR;
        graphics.blit(RenderPipelines.GUI_TEXTURED, separator, 0, footerTop() - 2, 0, 0, width, 2, 32, 2);
    }
}
