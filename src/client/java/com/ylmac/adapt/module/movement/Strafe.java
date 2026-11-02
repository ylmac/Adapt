package com.ylmac.adapt.module.movement;

import com.ylmac.adapt.event.bus.EventHandler;
import com.ylmac.adapt.event.impl.StrafeEvent;
import com.ylmac.adapt.module.Category;
import com.ylmac.adapt.module.Module;
import com.ylmac.adapt.module.settings.impl.DoubleSetting;
import net.minecraft.util.Mth;

/**
 * Strafe 空中平滑转向。
 * <p>
 * 原理：
 * - 每 tick（KeyboardInputMixin 在 KeyboardInput.tick 尾部触发 StrafeEvent，时机 =
 * 输入刷新后、applyInput/travel 前）把当前水平速度朝"移动合成方向"部分转向：
 * 1. direction = 按 getYRot 与前进/平移输入合成的期望移动方向（弧度，
 * 输入取 moveVector 的有符号值：x=左+1/右-1，y=前+1/后-1，26.2 字节码已验证）；
 * 2. 期望速度向量 = -sin(dir), cos(dir) × 当前速度大小（speed = hypot(motionX, motionZ)）；
 * 3. 结果 = 旧速度 + (期望速度 - 旧速度) × strength%，即按强度百分比向目标方向插值。
 * - 作用：空中无法自然转向时（1.8.9 原版空中转向极弱）实现平滑/强力的
 * 转向控制，且不掉速（速度大小始终守恒，只改方向）。
 * 叠加；本实现提供"仅空中"开关，默认开，语义更贴合"空中平滑转向"）。
 * <p>
 * DeltaTracker.Timer），本模块不修改 timer，无需恢复。
 */
public class Strafe extends Module {

 /** 转向强度 */
    private final DoubleSetting strength;
    /** 仅在空中时转向（默认开，关闭则地面也转向） */
    private final com.ylmac.adapt.module.settings.impl.BoolSetting onlyAir;

    public Strafe() {
        super("strafe", "Strafe", Category.MOVEMENT);
        setDefaultEnabled(false);
        setDefaultHidden(false);
        strength = doubleSetting("强度", 100.0, 1.0, 100.0, 1.0);
        onlyAir = boolSetting("仅空中", true);
    }

    @EventHandler
    private void onStrafe(StrafeEvent event) {
        if (mc.player == null || mc.level == null) {
            return;
        }
        if (onlyAir.getValue() && mc.player.onGround()) {
            return;
        }
        partialStrafePercent(strength.getValue(), event.getForward(), event.getStrafe());
    }

    /**
 * 速度朝移动方向部分转向。
 * strength 0-100 → 0-1 插值系数：0 = 完全不转向，100 = 完全转向。
 * 防止残留速度/击退被重定向到视角方向）。
 */
    private void partialStrafePercent(double percent, float forward, float strafe) {
        percent = Math.min(1.0, Math.max(0.0, percent / 100.0));

        double oldX = mc.player.getDeltaMovement().x;
        double oldZ = mc.player.getDeltaMovement().z;

 // 无移动输入时不转向
        if (forward == 0.0F && strafe == 0.0F) {
            return;
        }

 // 期望方向：direction
        double dir = direction(forward, strafe);

 // 速度大小守恒
        double speed = Math.hypot(oldX, oldZ);
        double targetX = -Math.sin(dir) * speed;
        double targetZ = Math.cos(dir) * speed;

        // 新旧插值：朝目标方向移动 percent 比例（垂直分量不动）
        mc.player.setDeltaMovement(
                oldX + (targetX - oldX) * percent,
                mc.player.getDeltaMovement().y,
                oldZ + (targetZ - oldZ) * percent
        );
    }

    /**
 * 按视角偏航 + 前进/平移输入合成期望移动角度：
 * 反键（forward<0）→ 偏航 +180°；平移输入按 f1 修正 ±90°。
 * <p>
 * 输入约定（26.2 字节码已验证）：KeyboardInput.tick 里
 * moveVector = Vec2(calculateImpulse(left,right), calculateImpulse(forward,backward))
 * 即 x=+1 左/-1 右、y=+1 前/-1 后（与 1.8.9 moveStrafing/moveForward 同号），
 */
    private double direction(float forward, float strafe) {
        float yaw = mc.player.getYRot();
        if (forward < 0.0F) {
            yaw += 180.0F;
        }
        float f1 = 1.0F;
        if (forward < 0.0F) {
            f1 = -0.5F;
        } else if (forward > 0.0F) {
            f1 = 0.5F;
        }
        if (strafe > 0.0F) {
            yaw -= 90.0F * f1;
        }
        if (strafe < 0.0F) {
            yaw += 90.0F * f1;
        }
        return Math.toRadians(Mth.wrapDegrees(yaw));
    }
}
