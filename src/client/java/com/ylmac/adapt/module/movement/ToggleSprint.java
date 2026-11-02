package com.ylmac.adapt.module.movement;

import com.mojang.blaze3d.platform.InputConstants;
import com.ylmac.adapt.module.combat.KeyBindUtil;
import com.ylmac.adapt.event.bus.EventHandler;
import com.ylmac.adapt.event.impl.KeyPressEvent;
import com.ylmac.adapt.event.impl.PlayerTickEvent;
import com.ylmac.adapt.module.Category;
import com.ylmac.adapt.module.Module;
import com.ylmac.adapt.module.settings.impl.BoolSetting;
import com.ylmac.adapt.mixins.input.KeyMappingAccessor;
import com.ylmac.adapt.render.hud.HudEditor;
import com.ylmac.adapt.render.hud.MovableHud;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

/**
 * 按一下疾跑键就保持疾跑，按一下潜行键就保持潜行，并在屏幕上写出现在的状态。
 * 原来的 Sprint 还是自动前进疾跑，这个模块管的是切换。
 */
public class ToggleSprint extends Module implements MovableHud,
        net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement {

    public static ToggleSprint INSTANCE;

    private final BoolSetting toggleSprint = boolSetting("切换疾跑", true);
    private final BoolSetting toggleSneak = boolSetting("切换潜行", false);

    private boolean sprintLatched;
    private boolean sneakLatched;
    private boolean sneakWasLatched;
    private String status = "";
    private float x = 4f;
    private float y = 98f;
    private float w = 72f;
    private boolean registered;

    public ToggleSprint() {
        super("togglesprint", "切换疾跑", Category.MOVEMENT);
        INSTANCE = this;
        setDefaultEnabled(false);
        setDefaultHidden(false);
    }

    public void registerHud() {
        if (registered) return;
        registered = true;
        net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.addLast(
                Identifier.parse("adaptclient:togglesprint"), this);
        HudEditor.get().register(this);
    }

    @EventHandler
    private void onKey(KeyPressEvent event) {
        if (event.getAction() != 1 || mc.player == null || mc.options == null) return;
        int key = event.getKey();
        if (toggleSprint.getValue() && bound(mc.options.keySprint, key)) sprintLatched = !sprintLatched;
        if (toggleSneak.getValue() && bound(mc.options.keyShift, key)) sneakLatched = !sneakLatched;
    }

    @EventHandler
    private void onTick(PlayerTickEvent.Pre event) {
        if (nullCheck() || mc.options == null) return;
        var player = mc.player;
        if (sneakLatched) {
            setShift(true);
            sneakWasLatched = true;
        } else if (sneakWasLatched) {
            setShift(false);
            sneakWasLatched = false;
        }

        if (player.getAbilities().flying || player.isFallFlying()) {
            status = "飞行";
        } else if (player.isPassenger()) {
            status = "骑乘";
        } else if (sneakLatched) {
            status = "潜行（切换）";
        } else if (player.isShiftKeyDown()) {
            status = "潜行（按住）";
        } else if (sprintLatched) {
            player.setSprinting(true);
            status = "疾跑（切换）";
        } else if (mc.options.keySprint.isDown() && player.isSprinting()) {
            status = "疾跑（按住）";
        } else if (player.isSprinting()) {
            status = "疾跑（原版）";
        } else {
            status = "";
        }
    }

    @Override
    protected void onDisable() {
        sprintLatched = false;
        if (sneakLatched || sneakWasLatched) setShift(false);
        sneakLatched = false;
        sneakWasLatched = false;
        status = "";
    }

    @Override
    public String getInfo() {
        return status.isEmpty() ? null : status;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, DeltaTracker dt) {
        if (!isEnabled() || mc.player == null || mc.font == null) return;
        String line = status;
        if (line.isEmpty()) {
            if (!HudEditor.get().isActive()) return;
            line = "切换疾跑";
        }
        g.text(mc.font, line, Math.round(x), Math.round(y), 0xFFFFFFFF, true);
        w = mc.font.width(line) + 4f;
    }

    private void setShift(boolean pressed) {
        if (mc.options == null) return;
        if (pressed) {
            InputConstants.Key key = ((KeyMappingAccessor) mc.options.keyShift).adapt_getKey();
            KeyMapping.set(key, true);
        } else {
            KeyBindUtil.updateKeyState(mc.options.keyShift);
        }
    }

    private static boolean bound(KeyMapping mapping, int key) {
        return ((KeyMappingAccessor) mapping).adapt_getKey().getValue() == key;
    }

    @Override public String hudId() { return "togglesprint"; }
    @Override public String hudLabel() { return "切换疾跑"; }
    @Override public float hudX() { return x; }
    @Override public float hudY() { return y; }
    @Override public float hudW() { return w; }
    @Override public float hudH() { return 12f; }
    @Override public boolean hudVisible() { return isEnabled() && mc.player != null; }
    @Override public void setHudPos(float x, float y) { this.x = x; this.y = y; }
}
