package com.ylmac.adapt.features.module.impl.combat;

import com.ylmac.adapt.features.input.ClientInputAddition;
import com.ylmac.adapt.features.module.Category;
import com.ylmac.adapt.features.module.Module;
import com.ylmac.adapt.features.module.settings.impl.DoubleSetting;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

/**
 * Fences —— 相扑防掉落（移植自 Rise 6.9.5 Fences）。
 * <p>
 * Rise 原版：监听 SafeWalkEvent，置 safeWalk 标志（防边缘滑落）并拉低
 * SafeWalk 灵敏度（-5.0），本质是"走到边缘前自动停下/退回"。
 * <p>
 * 26.2 完整移植（API 经 javap /Users/ylmac/Documents/26.2 确证）：
 * - 移动输入链：KeyboardInput.tick 计算 moveVector（Vec2，x=左右/1、y=前后/1，
 *   符号语义见 {@code Entity.getInputVector}：x'=x·cos−z·sin，z'=z·cos+x·sin，
 *   正 yaw 时 x 为右、y 为前）→ LocalPlayer 消费 → moveRelative 转世界位移。
 *   本模块在 KeyboardInputMixin 注入点（tick TAIL）读原始输入（MixinClientInput
 *   记录的 adapt_originalMoveVector），检测到"即将走出边缘"时把 moveVector 反向
 *   （前进↔后退、左↔右），实现"检测边缘 → 反向移动防掉落"。
 * - 边缘检测：取玩家移动方向的射线采样点，检查该点脚下 1 格（含下落容差
 *   0.5 格）是否悬空（level.noCollision(player, box)）；悬空即边缘。
 *   同时检测身后安全点（反向 0.3 格）确保有退路。
 * - 附加 safeWalk 兜底：player.isStayingOnGroundSurface() 由 PlayerMixin
 *   在边缘时返回 true，防止站在边缘时 vanilla 位移继续滑出（与 Rise 的
 *   crd=true 同效；Rise 的 -5.0 灵敏度参数用"提前触发距离"distance 代替）。
 * <p>
 * 实现文件：KeyboardInputMixin / MixinClientInput / ClientInputAddition / PlayerMixin
 * （已注册进 adapt.mixins.json）。
 */
public class Fences extends Module {

    /** 单例引用（KeyboardInputMixin 经此调用 onInputTick） */
    private static Fences instance;

    public static Fences get() {
        return instance;
    }

    /** 边缘检测距离（Rise 灵敏度 -5.0 的移植：检测多远外的悬空点） */
    private final DoubleSetting distance;

    /** 当前是否处于边缘（供 PlayerMixin 的 safeWalk 兜底读取） */
    private boolean onEdge;

    public Fences() {
        super("fences", "Fences", Category.COMBAT);
        setDefaultEnabled(false);
        setDefaultHidden(false);
        instance = this;
        distance = doubleSetting("检测距离", 0.35, 0.1, 1.0, 0.05);
    }

    /**
     * 由 KeyboardInputMixin 每 tick 调用（tick 末尾，moveVector 已算出）：
     * 玩家在地面且正在移动时，若移动方向前方是边缘 → 把 moveVector 反向，
     * 让玩家向回走（相扑防掉落）。
     */
    public void onInputTick(ClientInputAddition input) {
        if (!isEnabled()) return;
        if (mc.player == null || mc.level == null) return;
        if (mc.gui.screen() != null) return;

        // 仅地面（悬空时反向移动没意义，且不影响跳跃）
        if (!mc.player.onGround()) {
            onEdge = false;
            return;
        }

        Vec2 move = input.adapt_getOriginalMoveVector();
        boolean moving = Math.abs(move.x) > 1.0E-4F || Math.abs(move.y) > 1.0E-4F;
        if (!moving) {
            onEdge = false;
            return;
        }

        // 计算移动方向（相对玩家朝向）
        Vec3 dir = directionFromInput(move);
        Vec3 pos = mc.player.position();
        double d = distance.getValue();

        // 前方采样点脚下是否悬空 → 即将走出边缘
        Vec3 ahead = pos.add(dir.scale(d));
        boolean edgeAhead = isHanging(ahead);
        // 反向（身后）是否有安全落点（防止被反向后仍悬空）
        Vec3 back = pos.subtract(dir.scale(0.3));
        boolean safeBack = !isHanging(back);

        if (edgeAhead && safeBack) {
            onEdge = true;
            // 反向移动：前进↔后退、左↔右（Rise 语义：边缘时往安全方向拉回）
            input.adapt_setMovementInput(new Vec2(-move.x, -move.y));
        } else {
            onEdge = false;
        }
    }

    /** 玩家是否处于边缘（PlayerMixin.safeWalk 兜底读取） */
    public boolean isOnEdge() {
        return onEdge;
    }

    /** 根据移动输入向量（x=左右、y=前后，正 yaw 约定）计算世界方向 */
    private Vec3 directionFromInput(Vec2 move) {
        float yaw = mc.player.getYRot();
        double cos = Math.cos(Math.toRadians(yaw));
        double sin = Math.sin(Math.toRadians(yaw));
        // getInputVector：x' = x·cos − z·sin；z' = z·cos + x·sin（z=move.y 前后）
        double x = move.x * cos - move.y * sin;
        double z = move.y * cos + move.x * sin;
        double len = Math.sqrt(x * x + z * z);
        if (len < 1.0E-6) return Vec3.ZERO;
        return new Vec3(x / len, 0.0, z / len);
    }

    /** 点脚下是否悬空：脚下方 1 格（含 0.5 下落容差）无碰撞 → 会掉落 */
    private boolean isHanging(Vec3 pos) {
        AABB box = mc.player.getBoundingBox().move(pos.x() - mc.player.getX(),
                0.0, pos.z() - mc.player.getZ());
        // 采样脚下方 0~0.5 格（步高容差），任何碰撞都算有支撑
        AABB below = new AABB(box.minX, box.minY - 0.5, box.minZ, box.maxX, box.minY, box.maxZ);
        return mc.level.noCollision(mc.player, below);
    }

    @Override
    protected void onEnable() {
        onEdge = false;
    }

    @Override
    protected void onDisable() {
        onEdge = false;
    }
}
