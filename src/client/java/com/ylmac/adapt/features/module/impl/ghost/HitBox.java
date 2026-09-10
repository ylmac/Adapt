package com.ylmac.adapt.features.module.impl.ghost;

import com.ylmac.adapt.features.module.Category;
import com.ylmac.adapt.features.module.Module;
import com.ylmac.adapt.features.module.settings.impl.BoolSetting;
import com.ylmac.adapt.features.module.settings.impl.DoubleSetting;

/**
 * HitBox 扩大实体判定框（移植 Rise 6.9.5 ghost/HitBox，适配 Fabric 26.2）。
 * <p>
 * 26.2 瞄准/攻击判定链路（javap 确证）：
 * - 瞄准：ProjectileUtil.getEntityHitResult 对每个候选实体用
 *   boundingBox.inflate(entity.getPickRadius()) 做射线相交 —— EntityMarginMixin
 *   放大 getPickRadius 返回值即扩大判定框。
 * - 攻击校验：Minecraft.startAttack 的 AttackRange.isInRange 用
 *   effectiveMaxRange ± hitboxMargin 判定距离 —— AttackRangeMixin 放大
 *   hitboxMargin 让放大后的判定框通过校验。
 * <p>
 * 与原版 Rise 对照：Rise 用 MouseOverEvent.setExpand 扩大射线判定；26.2 无此
 * 事件，改 getPickRadius + hitboxMargin 两处，效果一致。
 */
public class HitBox extends Module {

    /** 单例引用（EntityMarginMixin / AttackRangeMixin 经此读取设置） */
    private static HitBox instance;

    public static HitBox get() {
        return instance;
    }

    /** 判定框扩大量（每个方向，格） */
    private DoubleSetting expand;
    /** 是否同时扩大攻击距离（false = 只扩大判定框，不增加射程，Rise effectRange） */
    private BoolSetting effectRange;

    public HitBox() {
        super("hitbox", "HitBox", Category.COMBAT);
        instance = this;
        setDefaultEnabled(false);
        setDefaultHidden(false);
        expand = doubleSetting("扩大量", 0.2, 0.0, 6.0, 0.01);
        effectRange = boolSetting("影响射程", true);
    }

    /** 当前判定框扩大量 */
    public float getExpand() {
        return expand.getValue().floatValue();
    }

    /** 是否同时影响射程（Rise effectRange） */
    public boolean shouldEffectRange() {
        return effectRange.getValue();
    }

    /** 模块启用且未开界面时生效（供 mixin 判断） */
    public boolean isActive() {
        return this.isEnabled() && mc.gui.screen() == null;
    }

    /** tag：显示当前扩大量（HUD 附加信息） */
    @Override
    public String getInfo() {
        return String.format("%.1f", this.expand.getValue());
    }
}
