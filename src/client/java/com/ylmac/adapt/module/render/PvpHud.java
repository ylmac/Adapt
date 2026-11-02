package com.ylmac.adapt.module.render;

import com.ylmac.adapt.event.bus.EventHandler;
import com.ylmac.adapt.event.impl.AttackEntityEvent;
import com.ylmac.adapt.event.impl.MousePressEvent;
import com.ylmac.adapt.event.impl.PlayerTickEvent;
import com.ylmac.adapt.module.Category;
import com.ylmac.adapt.module.Module;
import com.ylmac.adapt.render.hud.HudEditor;
import com.ylmac.adapt.render.hud.MovableHud;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.EntityHitResult;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * 战斗和状态 HUD。每个都是独立模块，能在编辑器里单独拖。
 * 数字来自客户端自己看到的点击、挥击和受击，不读服务器回放。
 */
public final class PvpHud {

    public static final Cps CPS = new Cps();
    public static final Sps SPS = new Sps();
    public static final Combo COMBO = new Combo();
    public static final TapCounter TAPS = new TapCounter();
    public static final Reach REACH = new Reach();
    public static final Keystrokes KEYS = new Keystrokes();
    public static final ArmorDurability ARMOR = new ArmorDurability();
    public static final PotionHud POTIONS = new PotionHud();
    public static final CooldownHud COOLDOWNS = new CooldownHud();
    public static final SaturationHud SATURATION = new SaturationHud();

    private PvpHud() {}

    public static List<LineHud> all() {
        return List.of(CPS, SPS, COMBO, TAPS, REACH, KEYS, ARMOR, POTIONS, COOLDOWNS, SATURATION);
    }

    /** 最近一秒钟里发生过的时刻。 */
    static final class Times {
        private final ArrayDeque<Long> stamps = new ArrayDeque<>();

        void add(long now) {
            stamps.addLast(now);
            trim(now);
        }

        int count(long now) {
            trim(now);
            return stamps.size();
        }

        private void trim(long now) {
            while (!stamps.isEmpty() && now - stamps.peekFirst() > 1000L) stamps.removeFirst();
        }
    }

    public abstract static class LineHud extends Module implements MovableHud {

        private final String id;
        private float x;
        private float y;
        private float w = 48f;
        private float h = 12f;

        protected LineHud(String id, String name, Category category, float x, float y) {
            super(id, name, category);
            this.id = id;
            this.x = x;
            this.y = y;
            setDefaultEnabled(false);
            setDefaultHidden(false);
        }

        /** 要画的行。空列表表示这一帧没有内容。 */
        public abstract List<String> lines();

        public int colorOf(int index, String line) {
            return 0xFFFFFFFF;
        }

        public void setHudSize(float w, float h) {
            this.w = w;
            this.h = h;
        }

        public boolean canDrawText() {
            return isEnabled() && mc.player != null && mc.font != null;
        }

        public boolean canDrawKeys() {
            return canDrawText() && mc.options != null;
        }

        public net.minecraft.client.gui.Font hudFont() {
            return mc.font;
        }

        /** W A S D LMB RMB 跳跃。 */
        public boolean[] keyState() {
            var options = mc.options;
            return new boolean[]{
                    options.keyUp.isDown(),
                    options.keyLeft.isDown(),
                    options.keyDown.isDown(),
                    options.keyRight.isDown(),
                    options.keyAttack.isDown(),
                    options.keyUse.isDown(),
                    options.keyJump.isDown()
            };
        }

        @Override public String hudId() { return id; }
        @Override public String hudLabel() { return getName(); }
        @Override public float hudX() { return x; }
        @Override public float hudY() { return y; }
        @Override public float hudW() { return w; }
        @Override public float hudH() { return h; }
        @Override public boolean hudVisible() { return isEnabled() && mc.player != null; }
        @Override public void setHudPos(float x, float y) { this.x = x; this.y = y; }
    }

    public static final class Cps extends LineHud {
        private final Times clicks = new Times();

        public Cps() { super("cps", "CPS", Category.COMBAT, 4f, 28f); }

        @EventHandler
        private void onMouse(MousePressEvent event) {
            if (event.getAction() == 1 && event.getButton() == 0) clicks.add(System.currentTimeMillis());
        }

        @Override
        public List<String> lines() {
            return List.of(clicks.count(System.currentTimeMillis()) + " CPS");
        }
    }

    public static final class Sps extends LineHud {
        private final Times swings = new Times();
        private final Times uses = new Times();

        public Sps() { super("sps", "SPS", Category.COMBAT, 4f, 42f); }

        @EventHandler
        private void onMouse(MousePressEvent event) {
            if (event.getAction() != 1) return;
            long now = System.currentTimeMillis();
            if (event.getButton() == 0) swings.add(now);
            else if (event.getButton() == 1) uses.add(now);
        }

        @Override
        public List<String> lines() {
            long now = System.currentTimeMillis();
            int rate = Math.max(swings.count(now), uses.count(now));
            return List.of(rate + " SPS");
        }
    }

    public static final class Combo extends LineHud {
        private int targetId = -1;
        private int seenHurt;
        private int seenSelf;
        private final ArrayDeque<Long> hits = new ArrayDeque<>();

        public Combo() { super("combo", "连击", Category.COMBAT, 4f, 56f); }

        @EventHandler
        private void onAttack(AttackEntityEvent event) {
            if (event.getEntity() != null) targetId = event.getEntity().getId();
        }

        @EventHandler
        private void onTick(PlayerTickEvent.Post event) {
            if (nullCheck()) return;
            long now = System.currentTimeMillis();
            if (mc.player.hurtTime >= 9 && mc.player.hurtTime > seenSelf) hits.clear();
            seenSelf = mc.player.hurtTime;
            if (mc.level.getEntity(targetId) instanceof LivingEntity living) {
                if (living.hurtTime >= 9 && living.hurtTime > seenHurt) hits.addLast(now);
                seenHurt = living.hurtTime;
            } else {
                seenHurt = 0;
            }
            while (!hits.isEmpty() && now - hits.peekFirst() > 2000L) hits.removeFirst();
        }

        @Override
        public List<String> lines() {
            if (hits.isEmpty() && !HudEditor.get().isActive()) return List.of();
            return List.of(hits.size() + " 连击");
        }
    }

    /** 疾跑时松开再按下 W 或 S，两秒内的次数。显示较多的那一边。 */
    public static final class TapCounter extends LineHud {
        private boolean wasForward;
        private boolean wasBack;
        private long forwardReleased;
        private long backReleased;
        private final ArrayDeque<Long> forward = new ArrayDeque<>();
        private final ArrayDeque<Long> back = new ArrayDeque<>();

        public TapCounter() { super("taps", "回击", Category.COMBAT, 4f, 70f); }

        @EventHandler
        private void onTick(PlayerTickEvent.Post event) {
            if (nullCheck()) return;
            long now = System.currentTimeMillis();
            boolean forwardDown = mc.options.keyUp.isDown();
            boolean backDown = mc.options.keyDown.isDown();
            if (mc.player.isSprinting()) {
                if (wasForward && !forwardDown) forwardReleased = now;
                if (!wasForward && forwardDown && forwardReleased != 0L && now - forwardReleased < 400L) {
                    forward.addLast(now);
                }
                if (wasBack && !backDown) backReleased = now;
                if (!wasBack && backDown && backReleased != 0L && now - backReleased < 400L) {
                    back.addLast(now);
                }
            }
            wasForward = forwardDown;
            wasBack = backDown;
            trim(forward, now);
            trim(back, now);
        }

        private static void trim(ArrayDeque<Long> stamps, long now) {
            while (!stamps.isEmpty() && now - stamps.peekFirst() > 2000L) stamps.removeFirst();
        }

        @Override
        public List<String> lines() {
            if (forward.isEmpty() && back.isEmpty() && !HudEditor.get().isActive()) return List.of();
            if (back.size() > forward.size()) return List.of(back.size() + " S-Tap");
            return List.of(forward.size() + " W-Tap");
        }
    }

    public static final class Reach extends LineHud {
        private double blocks;
        private long at;

        public Reach() { super("reachdisplay", "距离", Category.COMBAT, 4f, 84f); }

        @EventHandler
        private void onAttack(AttackEntityEvent event) {
            if (mc.player == null || !(mc.hitResult instanceof EntityHitResult hit)) return;
            blocks = mc.player.getEyePosition().distanceTo(hit.getLocation());
            at = System.currentTimeMillis();
        }

        @Override
        public List<String> lines() {
            if (at == 0L || System.currentTimeMillis() - at > 3000L) {
                return HudEditor.get().isActive() ? List.of("0.0 格") : List.of();
            }
            return List.of(String.format("%.1f 格", blocks));
        }
    }

    public static final class Keystrokes extends LineHud {
        public Keystrokes() { super("keystrokes", "按键", Category.RENDER, 4f, 150f); }

        @Override public float hudW() { return 60f; }
        @Override public float hudH() { return 72f; }
        @Override public List<String> lines() { return List.of(); }
    }

    public static final class ArmorDurability extends LineHud {
        private static final EquipmentSlot[] SLOTS = {
                EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
        private static final String[] NAMES = {"头", "胸", "腿", "靴"};
        private final int[] colors = new int[4];

        public ArmorDurability() { super("armor", "盔甲", Category.RENDER, 80f, 28f); }

        @Override
        public List<String> lines() {
            List<String> rows = new ArrayList<>();
            int index = 0;
            for (int i = 0; i < SLOTS.length; i++) {
                ItemStack stack = mc.player.getItemBySlot(SLOTS[i]);
                if (stack.isEmpty() || !stack.isDamageableItem() || stack.getMaxDamage() <= 0) continue;
                float left = 1f - stack.getDamageValue() / (float) stack.getMaxDamage();
                int red = Math.round((1f - left) * 220f);
                int green = Math.round(left * 210f);
                colors[index++] = 0xFF000000 | (red << 16) | (green << 8);
                rows.add(NAMES[i] + " " + Math.round(left * 100f) + "%");
            }
            return rows;
        }

        @Override
        public int colorOf(int index, String line) {
            return index < colors.length ? colors[index] : 0xFFFFFFFF;
        }
    }

    public static final class PotionHud extends LineHud {
        public PotionHud() { super("potions", "药水", Category.RENDER, 80f, 90f); }

        @Override
        public List<String> lines() {
            List<String> rows = new ArrayList<>();
            for (var effect : mc.player.getActiveEffects()) {
                String name = effect.getEffect().value().getDisplayName().getString();
                int seconds = effect.getDuration() / 20;
                String time = effect.getDuration() < 0 ? "∞" : (seconds / 60) + ":" + String.format("%02d", seconds % 60);
                rows.add(name + " " + time);
                if (rows.size() == 8) break;
            }
            return rows;
        }
    }

    public static final class CooldownHud extends LineHud {
        public CooldownHud() { super("cooldowns", "冷却", Category.RENDER, 80f, 180f); }

        @Override
        public List<String> lines() {
            List<String> rows = new ArrayList<>();
            var inventory = mc.player.getInventory();
            var cooldowns = mc.player.getCooldowns();
            for (int i = 0; i < inventory.getContainerSize() && rows.size() < 6; i++) {
                ItemStack stack = inventory.getItem(i);
                if (stack.isEmpty() || !cooldowns.isOnCooldown(stack)) continue;
                int left = Math.round(cooldowns.getCooldownPercent(stack, 0f) * 100f);
                rows.add(stack.getHoverName().getString() + " " + left + "%");
            }
            return rows;
        }
    }

    public static final class SaturationHud extends LineHud {
        public SaturationHud() { super("saturation", "饱和", Category.RENDER, 4f, 112f); }

        @Override
        public List<String> lines() {
            var food = mc.player.getFoodData();
            return List.of("饥饿 " + food.getFoodLevel() + "  饱和 " + String.format("%.1f", food.getSaturationLevel()));
        }
    }
}
