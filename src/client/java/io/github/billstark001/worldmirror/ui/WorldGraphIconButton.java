package io.github.billstark001.worldmirror.ui;

#if MC_VERSION >= 260102
import net.minecraft.client.gui.GuiGraphicsExtractor;
#else
import net.minecraft.client.gui.GuiGraphics;
#endif
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

/** Native button chrome and narration with small pixel glyphs for graph commands. */
final class WorldGraphIconButton extends Button {
    static final int SIZE = 18;

    enum Icon {
        FIT("###...###", "#.......#", "#.......#", ".........", "....#....", ".........", "#.......#",
                "#.......#", "###...###"),
        DETAILS("######...", "#....##..", "#.....##.", "#.###..#.", "#......#.", "#.####.#.", "#......#.",
                "#.####.#.", "########."),
        SETTINGS("...###...", ".#.###.#.", ".#######.", "###...###", "###...###", "###...###", ".#######.",
                ".#.###.#.", "...###..."),
        IMPORT("....#....", "....#....", "..#.#.#..", "...###...", "....#....", ".........", "#.......#",
                "#.......#", "#########");

        private final String[] rows;

        Icon(String... rows) {
            this.rows = rows;
        }
    }

    private final Icon icon;

    WorldGraphIconButton(int x, int y, Component label, Icon icon, Runnable action) {
        super(x, y, SIZE, SIZE, label, button -> action.run(), DEFAULT_NARRATION);
        this.icon = icon;
        setTooltip(Tooltip.create(label));
    }

    @Override
#if MC_VERSION >= 260102
    protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        extractDefaultSprite(graphics);
#else
    protected void renderContents(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        renderDefaultSprite(graphics);
#endif
        int x = getX() + 5;
        int y = getY() + 5;
        int color = active ? 0xFFFFFFFF : 0xFF909090;
        for (int row = 0; row < icon.rows.length; row++) {
            for (int column = 0; column < icon.rows[row].length(); column++) {
                if (icon.rows[row].charAt(column) == '#')
                    graphics.fill(x + column, y + row, x + column + 1, y + row + 1, color);
            }
        }
    }
}
