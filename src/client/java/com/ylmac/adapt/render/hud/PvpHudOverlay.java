package com.ylmac.adapt.render.hud;

import com.ylmac.adapt.module.render.PvpHud;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/** 战斗数据小组件的绘制。计数和开关留在 {@link PvpHud}。 */
public final class PvpHudOverlay {

    private PvpHudOverlay() {}

    public static void registerAll() {
        for (PvpHud.LineHud hud : PvpHud.all()) {
            HudElementRegistry.addLast(Identifier.parse("adaptclient:" + hud.hudId()), new Painter(hud));
            HudEditor.get().register(hud);
        }
    }

    private static final class Painter implements HudElement {
        private final PvpHud.LineHud hud;

        private Painter(PvpHud.LineHud hud) {
            this.hud = hud;
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor g, net.minecraft.client.DeltaTracker dt) {
            if (hud instanceof PvpHud.Keystrokes) paintKeys(g, hud);
            else paintLines(g, hud);
        }
    }

    private static void paintLines(GuiGraphicsExtractor g, PvpHud.LineHud hud) {
        if (!hud.canDrawText()) return;
        List<String> rows = new ArrayList<>();
        List<String> content = hud.lines();
        if (content != null) {
            for (String line : content) {
                if (line != null && !line.isEmpty()) rows.add(line);
            }
        }
        if (rows.isEmpty()) {
            if (!HudEditor.get().isActive()) return;
            rows.add(hud.getName());
        }
        Font font = hud.hudFont();
        int stride = Math.max(10, font.lineHeight);
        hud.setHudSize(widthOf(font, rows), stride * rows.size());
        for (int i = 0; i < rows.size(); i++) {
            text(g, font, rows.get(i), hud.hudX(), hud.hudY() + i * stride, hud.colorOf(i, rows.get(i)));
        }
    }

    private static void paintKeys(GuiGraphicsExtractor g, PvpHud.LineHud hud) {
        if (!hud.canDrawKeys()) return;
        int x = Math.round(hud.hudX());
        int y = Math.round(hud.hudY());
        Font font = hud.hudFont();
        boolean[] down = hud.keyState();
        key(g, font, "W", down[0], x + 20, y, 18, 18);
        key(g, font, "A", down[1], x, y + 20, 18, 18);
        key(g, font, "S", down[2], x + 20, y + 20, 18, 18);
        key(g, font, "D", down[3], x + 40, y + 20, 18, 18);
        key(g, font, "LMB", down[4], x, y + 40, 28, 16);
        key(g, font, "RMB", down[5], x + 32, y + 40, 28, 16);
        key(g, font, "—", down[6], x, y + 58, 60, 14);
    }

    private static void key(GuiGraphicsExtractor g, Font font, String label, boolean down,
                            int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, down ? 0xCC4793FD : 0x66000000);
        int tx = x + (w - font.width(label)) / 2;
        int ty = y + (h - font.lineHeight) / 2 + 1;
        g.text(font, label, tx, ty, 0xFFFFFFFF, false);
    }

    private static void text(GuiGraphicsExtractor g, Font font, String line, float x, float y, int color) {
        g.text(font, line, Math.round(x), Math.round(y), color, true);
    }

    private static int widthOf(Font font, List<String> lines) {
        int width = 36;
        for (String line : lines) width = Math.max(width, font.width(line));
        return width + 4;
    }
}
