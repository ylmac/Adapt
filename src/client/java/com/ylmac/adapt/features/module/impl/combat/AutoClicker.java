package com.ylmac.adapt.features.module.impl.combat;

import com.ylmac.adapt.features.autoclick.ItemUtil;
import com.ylmac.adapt.features.autoclick.KeyBindUtil;
import com.ylmac.adapt.features.autoclick.RandomUtil;
import com.ylmac.adapt.features.autoclick.RotationUtil;
import com.ylmac.adapt.features.event.bus.EventHandler;
import com.ylmac.adapt.features.event.impl.ClientTickEvent;
import com.ylmac.adapt.features.module.Category;
import com.ylmac.adapt.features.module.Module;
import com.ylmac.adapt.features.module.ModuleRegistry;
import com.ylmac.adapt.AdaptClient;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.HitResult;

/**
 * AutoClicker 自动点击（移植自 myau AutoClicker，1.8.9 → Fabric 26.2）。
 * <p>
 * 逻辑与 myau 原版一一对应（不做任何增强）：
 * - 延迟 = 1000 / rand(minCPS, maxCPS)，ClientTickEvent 驱动（50ms 粒度）
 * - while 循环补偿：一次 tick 内可补发多次点击
 * - 手动点击延迟补偿：真实攻击（Minecraft.startAttack）时追加一次延迟
 * - blockHit：持剑时定时补一次右键（按住右键才能格挡）
 * - weaponsOnly：仅持剑/工具（或耐久 III）才连点
 * - breakBlocks：aiming 方块且无有效玩家目标时，仅在非生存/创造模式点击
 * - range + hitBox 放大包围盒射线判定有效目标
 * <p>
 * 点击模拟（26.2 已 javap 确证）：
 * KeyMapping.click(InputConstants.Key) = 注入 clickCount++，由 Minecraft.handleKeybinds()
 * 的 while (keyAttack.consumeClick()) startAttack() 消费 —— 等价 1.8.9 KeyBinding.onTick。
 * KeyMapping.set(key, bool) = 按键状态（setDown），等价的 setKeyBindState；
 * KeyMapping.isDown 只由输入事件更新，故 pending 释放后需 updateKeyState 从 GLFW 恢复。
 * <p>
 * 开关：ClickGUI / .toggle / .bind 都操作 ModuleRegistry 条目（见 ClickGuiBridge、
 * ToggleCommand），本模块以其为准（启动时 ModuleManager.syncHud 已拉齐状态）。
 */
public class AutoClicker extends Module {

    /** 单例引用（MinecraftStartAttackMixin 经此调用 onManualClick） */
    private static AutoClicker instance;

    public static AutoClicker get() {
        return instance;
    }

    public AutoClicker() {
        super("autoclicker", "AutoClicker", Category.COMBAT);
        instance = this;
        setDefaultEnabled(false);
        setDefaultHidden(false);
        // 设置（对应 myau 的 IntProperty/FloatProperty/BooleanProperty）
        minCps = intSetting("min-cps", 8, 1, 20, 1);
        maxCps = intSetting("max-cps", 12, 1, 20, 1);
        blockHit = boolSetting("block-hit", false, () -> true);
        blockHitTicks = doubleSetting("block-hit-ticks", 1.5, 1.0, 20.0, 0.5,
                () -> blockHit.getValue());
        weaponsOnly = boolSetting("weapons-only", true, () -> true);
        allowTools = boolSetting("allow-tools", false,
                () -> weaponsOnly.getValue());
        breakBlocks = boolSetting("break-blocks", true, () -> true);
        range = doubleSetting("range", 3.0, 3.0, 8.0, 0.1,
                () -> breakBlocks.getValue());
        hitBoxVertical = doubleSetting("hit-box-vertical", 0.1, 0.0, 1.0, 0.05,
                () -> breakBlocks.getValue());
        hitBoxHorizontal = doubleSetting("hit-box-horizontal", 0.2, 0.0, 1.0, 0.05,
                () -> breakBlocks.getValue());
    }

    // 内部状态（myau 同款）
    private boolean clickPending = false;
    private long clickDelay = 0L;
    private boolean blockHitPending = false;
    private long blockHitDelay = 0L;

    // 设置引用（SettingHost 快捷方法创建）
    private com.ylmac.adapt.features.module.settings.impl.IntSetting minCps;
    private com.ylmac.adapt.features.module.settings.impl.IntSetting maxCps;
    private com.ylmac.adapt.features.module.settings.impl.BoolSetting blockHit;
    private com.ylmac.adapt.features.module.settings.impl.DoubleSetting blockHitTicks;
    private com.ylmac.adapt.features.module.settings.impl.BoolSetting weaponsOnly;
    private com.ylmac.adapt.features.module.settings.impl.BoolSetting allowTools;
    private com.ylmac.adapt.features.module.settings.impl.BoolSetting breakBlocks;
    private com.ylmac.adapt.features.module.settings.impl.DoubleSetting range;
    private com.ylmac.adapt.features.module.settings.impl.DoubleSetting hitBoxVertical;
    private com.ylmac.adapt.features.module.settings.impl.DoubleSetting hitBoxHorizontal;

    /** 模块开关状态：以真实 Module 状态为准（ClickGUI/.toggle 经 ModuleRegistry 联动到这里） */
    private boolean isModuleEnabled() {
        return this.isEnabled();
    }

    @EventHandler
    private void onTick(ClientTickEvent.Pre event) {
        if (this.clickDelay > 0L) {
            this.clickDelay -= 50L;
        }
        if (this.blockHitDelay > 0L) {
            this.blockHitDelay -= 50L;
        }
        if (mc.gui.screen() != null) {
            this.clickPending = false;
            this.blockHitPending = false;
            return;
        }
        if (this.clickPending) {
            this.clickPending = false;
            KeyBindUtil.updateKeyState(mc.options.keyAttack);
        }
        if (this.blockHitPending) {
            this.blockHitPending = false;
            KeyBindUtil.updateKeyState(mc.options.keyUse);
        }
        if (this.isModuleEnabled() && this.canClick() && mc.options.keyAttack.isDown()) {
            if (mc.player != null && !mc.player.isUsingItem()) {
                while (this.clickDelay <= 0L) {
                    this.clickPending = true;
                    this.clickDelay = this.clickDelay + this.getNextClickDelay();
                    KeyBindUtil.setKeyBindState(mc.options.keyAttack, false);
                    KeyBindUtil.pressKeyOnce(mc.options.keyAttack);
                }
            }
            if (this.blockHit.getValue()
                    && this.blockHitDelay <= 0L
                    && mc.options.keyUse.isDown()
                    && ItemUtil.isHoldingSword()) {
                this.blockHitPending = true;
                KeyBindUtil.setKeyBindState(mc.options.keyUse, false);
                if (mc.player != null && !mc.player.isUsingItem()) {
                    this.blockHitDelay = this.blockHitDelay + this.getBlockHitDelay();
                    KeyBindUtil.pressKeyOnce(mc.options.keyUse);
                }
            }
        }
    }

    /** 手动左键点击补偿（myau onCLick LeftClickMouseEvent → Minecraft.startAttack mixin） */
    public void onManualClick() {
        if (this.isModuleEnabled() && !this.clickPending) {
            this.clickDelay = this.clickDelay + this.getNextClickDelay();
        }
    }

    @Override
    protected void onEnable() {
        this.clickDelay = 0L;
        this.blockHitDelay = 0L;
    }

    @Override
    public String getInfo() {
        return minCps.getValue() == maxCps.getValue()
                ? String.valueOf(minCps.getValue())
                : String.format("%d-%d", minCps.getValue(), maxCps.getValue());
    }

    private long getNextClickDelay() {
        return 1000L / RandomUtil.nextLong(this.minCps.getValue(), this.maxCps.getValue());
    }

    private long getBlockHitDelay() {
        return (long) (50.0F * this.blockHitTicks.getValue());
    }

    /** 是否正对方块（myau isBreakingBlock：objectMouseOver 是 BLOCK） */
    private boolean isBreakingBlock() {
        HitResult hit = mc.hitResult;
        return hit != null && hit.getType() == HitResult.Type.BLOCK;
    }

    private boolean canClick() {
        if (!this.weaponsOnly.getValue()
                || ItemUtil.hasRawUnbreakingEnchant()
                || this.allowTools.getValue() && ItemUtil.isHoldingTool()) {
            if (this.breakBlocks.getValue() && this.isBreakingBlock() && !this.hasValidTarget()) {
                GameType gameType = mc.gameMode.getPlayerMode();
                return gameType != GameType.SURVIVAL && gameType != GameType.CREATIVE;
            } else {
                return true;
            }
        } else {
            return false;
        }
    }

    /** 玩家是否在准星范围内（myau isValidTarget：非自己/骑乘/观察实体，未死亡，射线判定） */
    private boolean isValidTarget(Player entityPlayer) {
        if (entityPlayer != mc.player && entityPlayer != mc.player.getVehicle()) {
            if (entityPlayer == mc.getCameraEntity() || entityPlayer == mc.getCameraEntity().getVehicle()) {
                return false;
            } else if (entityPlayer.isDeadOrDying()) {
                return false;
            } else {
                float borderSize = entityPlayer.getPickRadius();
                return RotationUtil.rayTrace(entityPlayer.getBoundingBox().inflate(
                        borderSize + this.hitBoxHorizontal.getValue(),
                        borderSize + this.hitBoxVertical.getValue(),
                        borderSize + this.hitBoxHorizontal.getValue()
                ), mc.player.getYRot(), mc.player.getXRot(), this.range.getValue()) != null;
            }
        } else {
            return false;
        }
    }

    /** 世界内是否有有效玩家目标（myau hasValidTarget：遍历 loadedEntityList 玩家） */
    private boolean hasValidTarget() {
        if (mc.level == null) return false;
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity instanceof Player player && this.isValidTarget(player)) {
                return true;
            }
        }
        return false;
    }
}
