package com.ylmac.adapt.features.module.impl.combat;

import com.ylmac.adapt.features.module.Category;
import com.ylmac.adapt.features.module.Module;
import com.ylmac.adapt.features.module.settings.impl.DoubleSetting;

/**
 * Piercing 穿墙打击（移植自 Rise 6.9.5 Piercing，1.8.9 → Fabric 26.2）。
 * <p>
 * Rise 原版：点击事件（ClickEvent）时若准星不是实体，用 RayCastUtil 做无视
 * 方块的射线（只检测实体包围盒），命中则改写 objectMouseOver/pointedEntity，
 * 让后续攻击打到墙后实体。
 * <p>
 * 26.2 攻击链路已 javap 确证：Minecraft.startAttack() 以 mc.hitResult 为准
 * （MISS→missTime、BLOCK→破坏方块、ENTITY→MultiPlayerGameMode.attack）。
 * 因此完整实现 = MinecraftPiercingMixin 注入 startAttack HEAD：
 * 当原始 hitResult 不是实体（空挥/打墙）时，用无视方块的射线（eye 起 + 视向
 * 方向，命中点取实体包围盒表面最近点）重算最近可攻击实体，命中则把
 * mc.hitResult / mc.crosshairPickEntity 替换为实体命中结果 —— 原版攻击逻辑
 * 随即走 ENTITY 分支真实攻击墙后目标（攻击包/挥动/服务器判定全部正常）。
 * <p>
 * 判定范围取玩家实际攻击距离：手持武器的 ATTACK_RANGE 组件或 3 格默认值
 * （与原版 startAttack 的实体分支判定一致）。
 */
public class Piercing extends Module {

    /** 单例引用（MinecraftPiercingMixin 经此调用 onStartAttack） */
    private static Piercing instance;

    public static Piercing get() {
        return instance;
    }

    /** 最大穿墙打击距离（默认 4.5 = Rise 的 getReachRange 上限，3.0 原版范围） */
    private final DoubleSetting range;

    public Piercing() {
        super("piercing", "Piercing", Category.COMBAT);
        instance = this;
        setDefaultEnabled(false);
        setDefaultHidden(false);
        range = doubleSetting("距离", 4.5, 1.0, 8.0, 0.1);
    }

    public double getRange() {
        return range.getValue();
    }

    @Override
    public String getInfo() {
        return range.getValue() + "格";
    }
}
