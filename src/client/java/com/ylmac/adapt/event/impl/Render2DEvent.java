package com.ylmac.adapt.event.impl;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/** 2D 渲染事件（Level=世界层，HUD=界面层） */
public class Render2DEvent {

    private final GuiGraphicsExtractor guiGraphics;

    protected Render2DEvent(GuiGraphicsExtractor guiGraphics) {
        this.guiGraphics = guiGraphics;
    }

    public GuiGraphicsExtractor getGuiGraphics() {
        return guiGraphics;
    }

    public static final class Level extends Render2DEvent {
        public Level(GuiGraphicsExtractor guiGraphics) {
            super(guiGraphics);
        }
    }

    public static final class HUD extends Render2DEvent {
        public HUD(GuiGraphicsExtractor guiGraphics) {
            super(guiGraphics);
        }
    }

}
