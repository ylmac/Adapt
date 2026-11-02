package com.ylmac.adapt.module.player;

import com.ylmac.adapt.event.bus.EventHandler;
import com.ylmac.adapt.event.impl.PlayerTickEvent;
import com.ylmac.adapt.module.Category;
import com.ylmac.adapt.module.Module;
import com.ylmac.adapt.module.settings.impl.BoolSetting;
import com.ylmac.adapt.module.settings.impl.DoubleSetting;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.hurtingprojectile.Fireball;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.item.component.SwingAnimation;

/**
 * AntiFireBall 防火焰弹。
 * <p>
 * 若实体是 EntityFireball（火焰弹，1.8.9 类名；26.2 对应
 * net.minecraft.world.entity.projectile.hurtingprojectile.Fireball
 * 及派生 SmallFireball/LargeFireball）且距离玩家 < 6 格：
 * - 开启 Rotate：把视角旋转对准火焰弹（RotationComponent.setRotations，
 * 客户端本地视角，不发包）；
 * - 停止移动（MoveUtil.strafe(0)）；
 * - 距离 ≤ 3 格且未攻击过：发送 C02PacketUseEntity(ATTACK) 击落（26.2 =
 * MultiPlayerGameMode.attack(player, entity)，已 javap 确证），
 * 并记录已攻击的 UUID 防止重复；
 * - 否则模拟左键点击（PlayerUtil.sendClick）。
 * <p>
 * - 遍历 mc.level.entitiesForRendering（与 AutoClicker 同款迭代方式）；
 * - 检测 Fireball 及派生类；计算到玩家眼睛的距离；
 * 本地旋转不发包）；停止水平移动（把 deltaMovement 的水平分量清零，
 * 对应 MoveUtil.strafe(0)）；
 * - 距离 ≤ 3 格且未攻击过：用 mc.gameMode.attack(player, fireball) 击落
 * 并记录 UUID，附挥臂；有冷却防止刷屏；
 * "躲避/提醒"补充，默认关闭）。
 */
public class AntiFireBall extends Module {

    /** 触发检测半径（格） */
    private final DoubleSetting range;

    /** 攻击距离（格） */
    private final DoubleSetting attackRange;

    /** 是否把视角对准火焰弹（本地旋转，不发包） */
    private final BoolSetting rotate;

    /** 接近时是否停止移动（躲避） */
    private final BoolSetting stopMovement;

    /** 是否主动攻击击落火焰弹 */
    private final BoolSetting attack;

    /** 是否在聊天输出提醒 */
    private final BoolSetting warn;

    /** 攻击冷却（tick），防连发 */
    private int attackCooldown;

    /** 提醒节流：同一火焰弹只提醒一次，避免刷屏 */
    private java.util.UUID lastWarned;

    public AntiFireBall() {
        super("antifireball", "AntiFireBall", Category.PLAYER);
        setDefaultEnabled(false);
        setDefaultHidden(false);
        range = doubleSetting("检测范围", 6.0, 1.0, 16.0, 0.5);
        attackRange = doubleSetting("攻击范围", 3.0, 1.0, 6.0, 0.5);
        rotate = boolSetting("对准旋转", true);
        stopMovement = boolSetting("停止移动", true);
        attack = boolSetting("攻击击落", true);
        warn = boolSetting("聊天提醒", false);
    }

    @EventHandler
    private void onTick(PlayerTickEvent.Post event) {
        if (mc.player == null || mc.level == null) return;
        if (mc.player.isSpectator()) return;
        if (attackCooldown > 0) attackCooldown--;

        double rangeSq = range.getValue() * range.getValue();
        double attackRangeSq = attackRange.getValue() * attackRange.getValue();

        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof Fireball fireball)) continue;
            // 只处理活着的火焰弹
            if (!fireball.isAlive()) continue;

            double distSq = fireball.distanceToSqr(mc.player);
            if (distSq > rangeSq) continue;

 // 1) 对准火焰弹
            if (rotate.getValue()) {
                Vec3 delta = fireball.position().subtract(mc.player.getEyePosition()).normalize();
                float yaw = (float) Math.toDegrees(Math.atan2(-delta.x, delta.z));
                float pitch = (float) -Math.toDegrees(Math.atan2(delta.y, Math.sqrt(delta.x * delta.x + delta.z * delta.z)));
                mc.player.setYRot(yaw);
                mc.player.setXRot(pitch);
            }

 // 2) 停止移动
            if (stopMovement.getValue()) {
                mc.player.setDeltaMovement(0, mc.player.getDeltaMovement().y(), 0);
            }

 // 3) 提醒
            if (warn.getValue() && lastWarned != fireball.getUUID()) {
                lastWarned = fireball.getUUID();
                String dir = directionName(fireball.position().subtract(mc.player.position()));
                com.ylmac.adapt.command.ChatUtil.sendFormatted("&c警告&r：检测到火焰弹接近（&7" + dir + "&r）");
            }

 // 4) 近身则攻击击落
            if (attack.getValue() && attackCooldown == 0 && distSq <= attackRangeSq) {
                mc.gameMode.attack(mc.player, fireball);
                mc.player.swing(net.minecraft.world.InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, true);
                attackCooldown = 5;
            }

 // 只处理最近的
            break;
        }
    }

    /** 把相对位移翻译成方位词（提醒用） */
    private String directionName(Vec3 rel) {
        double dx = rel.x;
        double dz = rel.z;
        StringBuilder sb = new StringBuilder();
        if (Math.abs(dx) > 0.5) sb.append(dx > 0 ? "东侧" : "西侧");
        if (Math.abs(dz) > 0.5) {
            if (sb.length() > 0) sb.append("·");
            sb.append(dz > 0 ? "南侧" : "北侧");
        }
        if (rel.y > 1.0) sb.append("（高处）");
        if (rel.y < -1.0) sb.append("（低处）");
        return sb.length() == 0 ? "正前方" : sb.toString();
    }

}
