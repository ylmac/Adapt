package com.ylmac.adapt.module.ghost;

import com.ylmac.adapt.module.Category;
import com.ylmac.adapt.module.Module;
import com.ylmac.adapt.module.settings.impl.BoolSetting;
import com.ylmac.adapt.module.settings.impl.DoubleSetting;

/**
 * <p>
 * - HitSlowDownEvent 处理：
 * 1. onlyInAir 且在地上 → 不处理（return）
 * 2. bufferAbuse：combo < maxBuffer → combo+；否则 combo 递减（bufferDecrease）
 * 并 return（跳过本次减速设置）；combo 归零后 resetting=false
 * 3. hurtTime > 0 → slowDown = slowDownVelocity、sprint = sprintSlowDownVelocity
 * 否则 slowDown = slowDownNormal、sprint = sprintSlowDownNormal
 * - 26.2 无 HitSlowDownEvent → KeepSprintMixin 在 Player.causeExtraKnockback
 * （攻击命中后）回调里等价实现：@ModifyConstant 换减速因子（按 hurtTime 两因子），
 * @Inject setSprinting 换 sprint 决策（Buffer Abuse + 两 sprint 设置）。
 */
public class KeepSprint extends Module {

    /** 单例引用（KeepSprintMixin 经此读取设置） */
    private static KeepSprint instance;

    public static KeepSprint get() {
        return instance;
    }

 /** 受击减速因子 */
    private DoubleSetting slowDownVelocity;
 /** 平时减速因子 */
    private DoubleSetting slowDownNormal;
 /** 受击时强制疾跑 */
    private BoolSetting sprintSlowDownVelocity;
 /** 平时强制疾跑 */
    private BoolSetting sprintSlowDownNormal;
 /** 连击缓冲 */
    private BoolSetting bufferAbuse;
 /** 缓冲扣减 */
    private DoubleSetting bufferDecrease;
 /** 缓冲上限 */
    private DoubleSetting maxBuffer;
 /** 仅在空中生效 */
    private BoolSetting onlyInAir;

 /** Buffer Abuse 状态 */
    private double combo;
    private boolean resetting;

    public KeepSprint() {
        super("keepsprint", "KeepSprint", Category.COMBAT);
        instance = this;
        setDefaultEnabled(false);
        setDefaultHidden(false);
        slowDownVelocity = doubleSetting("受击减速", 0.6, 0.0, 1.0, 0.05);
        slowDownNormal = doubleSetting("普通减速", 0.6, 0.0, 1.0, 0.05);
        sprintSlowDownVelocity = boolSetting("受击保持疾跑", false);
        sprintSlowDownNormal = boolSetting("普通保持疾跑", false);
        bufferAbuse = boolSetting("缓冲滥用", false);
        bufferDecrease = doubleSetting("缓冲扣减", 1.0, 0.1, 10.0, 0.1, () -> bufferAbuse.getValue());
        maxBuffer = doubleSetting("缓冲上限", 5.0, 1.0, 10.0, 1.0, () -> bufferAbuse.getValue());
        onlyInAir = boolSetting("仅在空中", false);
    }

    @Override
    protected void onEnable() {
        combo = 0.0;
        resetting = false;
    }

    @Override
    protected void onDisable() {
        combo = 0.0;
        resetting = false;
    }

    /**
 * 由 KeepSprintMixin 在 causeExtraKnockback 的 setSprinting 调用点调用。
 *
 * @param onGround 玩家是否在地面
 * @param hurtTime 玩家受击时间
 * @return true = 应处理（替换减速/疾跑）；false = 跳过
 */
    public boolean onHitSlowDown(boolean onGround, int hurtTime) {
        if (onGround && onlyInAir.getValue()) {
            return false;
        }
        if (bufferAbuse.getValue()) {
            if (combo < maxBuffer.getValue().intValue() && !resetting) {
                combo++;
            } else {
                if (combo > 0.0) {
                    combo = Math.max(0.0, combo - bufferDecrease.getValue());
                    resetting = true;
                    return false;
                }
                resetting = false;
            }
        } else {
            combo = 0.0;
        }
        return true;
    }

    /** 减速因子（mixin @ModifyConstant 调用，按 hurtTime 两因子） */
    public double getSlowDownFactor(boolean velocity) {
        return velocity ? slowDownVelocity.getValue() : slowDownNormal.getValue();
    }

    /** 受击时（hurtTime > 0）是否强制疾跑 */
    public boolean shouldSprintVelocity() {
        return sprintSlowDownVelocity.getValue();
    }

    /** 平时是否强制疾跑 */
    public boolean shouldSprintNormal() {
        return sprintSlowDownNormal.getValue();
    }

    /** Buffer Abuse 当前 combo（调试/HUD） */
    public double getCombo() {
        return combo;
    }

 /** 模块信息 tag */
    @Override
    public String getInfo() {
        return String.format("%.2f", slowDownNormal.getValue());
    }
}
