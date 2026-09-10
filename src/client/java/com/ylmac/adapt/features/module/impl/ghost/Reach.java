package com.ylmac.adapt.features.module.impl.ghost;

import com.ylmac.adapt.features.autoclick.RandomUtil;
import com.ylmac.adapt.features.event.bus.EventHandler;
import com.ylmac.adapt.features.event.impl.AttackEntityEvent;
import com.ylmac.adapt.features.event.impl.ClientTickEvent;
import com.ylmac.adapt.features.module.Category;
import com.ylmac.adapt.features.module.Module;
import com.ylmac.adapt.features.module.settings.impl.BoolSetting;
import com.ylmac.adapt.features.module.settings.impl.DoubleSetting;
import com.ylmac.adapt.features.module.settings.impl.IntSetting;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Reach —— 攻击/交互距离增强（移植自 Rise 6.9.5 ghost/Reach，适配 Fabric 26.2）。
 * <p>
 * Rise 原版核心逻辑（逐项对应）：
 * 1. onMouseOver（MouseOverEvent）：每次瞄准射线（pick）把射线距离改成
 *    {@code range(随机 min~max)}，并先用玩家当前实际视角做一次实体射线，命中
 *    实体则把准星命中改成该实体 —— 即"扩大瞄准判定"：准星能"够到"更远的实体。
 * 2. onGetMouseOver（RightClickEvent）：每次右键强制用 4.5 距离重算瞄准射线
 *    并覆盖 objectMouseOver —— 右键交互（开容器/交互实体）距离上限拉到 4.5。
 * 3. onPreMotionEvent：攻击 tick 计数（attackTicks++），供 Buffer Abuse 判定。
 * 4. onAttackEvent（AttackEvent）：Buffer Abuse 开启时，若本次攻击命中点超出
 *    原版 3.0 距离（自身射线在 3.0 内没命中实体），则把连击 combo+1；combo 达到
 *    maxBuffer 后取消本次攻击；正常射程内攻击则把 combo 按 bufferDecrease 衰减。
 *    （原理：客户端把攻击距离拉到 maxBuffer，服务端仍按 3.0 校验 —— 先白嫖几次
 *    远距离攻击，被服务端判定"未命中"后再回到射程内攻击，避免服务端怀疑。）
 * <p>
 * 26.2 移植（API 全部经 javap minecraft-merged-deobf-26.2.jar 确证，见下）：
 * - 攻击瞄准判定：{@code Minecraft.pick(tickDelta)} 内部走
 *   {@code LocalPlayer.raycastHitResult → AttackRange.getClosesetHit}，射线长度
 *   来自 {@code AttackRange.effectiveMaxRange(entity)}（手持物品无 ATTACK_RANGE
 *   组件时按 {@code Attributes.ENTITY_INTERACTION_RANGE} 默认 3.0/创造 6.0）。
 *   因此 AttackRangeMixin 把 effectiveMaxRange 加上 getEntityRange()，准星就能
 *   命中更远的实体（mc.hitResult 为远距离 EntityHitResult）。
 * - 攻击最终距离闸门：{@code Minecraft.startAttack} 实体分支（已反汇编确证）：
 *   hitResult 类型为 ENTITY 时，若主手物品带 ATTACK_RANGE 组件则调
 *   {@code AttackRange.isInRange(player, hitResult.getLocation())} 校验命中点距离
 *   （effectiveMaxRange ± hitboxMargin），失败直接 return false。AttackRangeMixin
 *   用 @ModifyReceiver 把 isInRange 的接收者换成 maxReach 放大后的副本，远处
 *   攻击得以通过原版校验（与 LB MixinMinecraft.startAttack 手法一致）。
 * - 实体交互距离：{@code Player.entityInteractionRange()}（属性
 *   ENTITY_INTERACTION_RANGE，默认 3.0）——{@code startUseItem} 实体分支用
 *   {@code isWithinEntityInteractionRange(entity, 0)} 校验（已反汇编确证）；
 *   PlayerReachMixin 放大该返回值 → 右键能远距离交互实体/开船等。
 * - 方块交互/挖掘距离：{@code Player.blockInteractionRange()}（属性
 *   BLOCK_INTERACTION_RANGE，默认 4.5）——PlayerReachMixin 放大该返回值 →
 *   远距离挖方块/放方块。
 * - Buffer Abuse 判定射线：Rise 用自研 RayCastUtil（
 *   {@code RayCastUtil.c(RotationComponent.fk, 3.0)}）沿当前实际视角做实体射线。
 *   26.2 等价：{@code level.clip(ClipContext)} 求方块交点 + 遍历
 *   {@code level.entitiesForRendering()} 用包围盒（含 getPickRadius）做射线求交
 *   （与 GhostHand.rayTraceEntities 同款做法），取最近实体。
 * - 攻击事件：AttackEntityEventMixin 在 {@code Minecraft.startAttack} HEAD 发
 *   AttackEntityEvent（可取消，cancel → cir.cancel() 跳过本次攻击，已确证）。
 *   监听该事件实现 Rise onAttackEvent 的取消逻辑；attackTicks 用
 *   ClientTickEvent.Pre 每 tick 递增（Rise 用 PreMotionEvent，26.2 无对等事件）。
 * <p>
 * 依赖：AttackRangeMixin + PlayerReachMixin（均已随本模块实现并注册）。
 */
public class Reach extends Module {

    /** 单例引用（AttackRangeMixin / PlayerReachMixin 经此读取设置） */
    private static Reach instance;

    public static Reach get() {
        return instance;
    }

    /** 攻击/瞄准距离（Rise: BoundsNumberValue range，3~6 随机 [min, max]，默认 [3, 4]） */
    private DoubleSetting rangeMin;
    private DoubleSetting rangeMax;
    /** 方块交互/挖掘距离加成（Rise 无此选项，LB Reach 提供 blockRangeIncrease，默认 0.5） */
    private DoubleSetting blockRange;
    /** Buffer Abuse 连击保护（Rise: BooleanValue bufferAbuse，默认关） */
    private BoolSetting bufferAbuse;
    /** 连击衰减速度（Rise: NumberValue bufferDecrease，每次正常攻击减 1，默认 1） */
    private DoubleSetting bufferDecrease;
    /** 连击上限（Rise: NumberValue maxBuffer，默认 5） */
    private IntSetting maxBuffer;

    // 内部状态（Rise 同款）
    private int lastId = -1;
    private int attackTicks;
    private double combo;

    public Reach() {
        super("reach", "Reach", Category.COMBAT);
        instance = this;
        setDefaultEnabled(false);
        setDefaultHidden(false);
        // 设置（对应 Rise 的 BoundsNumberValue / NumberValue / BooleanValue）
        rangeMin = doubleSetting("Range 最小", 3.0, 3.0, 6.0, 0.01);
        rangeMax = doubleSetting("Range 最大", 4.0, 3.0, 6.0, 0.01);
        blockRange = doubleSetting("方块距离加成", 0.5, 0.0, 6.0, 0.1);
        bufferAbuse = boolSetting("Buffer Abuse", false);
        bufferDecrease = doubleSetting("衰减速度", 1.0, 0.1, 10.0, 0.1,
                () -> bufferAbuse.getValue());
        maxBuffer = intSetting("连击上限", 5, 1, 200, 1,
                () -> bufferAbuse.getValue());
    }

    @Override
    protected void onEnable() {
        lastId = -1;
        attackTicks = 0;
        combo = 0.0;
    }

    @Override
    protected void onDisable() {
        combo = 0.0;
        attackTicks = 0;
        lastId = -1;
    }

    /** 模块启用且未开界面时生效（供 mixin 判断，与 HitBox.isActive 一致） */
    public boolean isActive() {
        return this.isEnabled() && mc.gui.screen() == null;
    }

    /** 本次瞄准使用的随机攻击距离（Rise: MathUtil.l(range 随机 min~max)） */
    public float getEntityRange() {
        double min = rangeMin.getValue();
        double max = rangeMax.getValue();
        if (max <= min) {
            return (float) max;
        }
        return (float) RandomUtil.nextDouble(min, max);
    }

    /** 方块交互/挖掘距离加成（PlayerReachMixin 用） */
    public double getBlockRange() {
        return blockRange.getValue();
    }

    /** 是否影响攻击/瞄准距离（与 HitBox.shouldEffectRange 对应，本模块恒真） */
    public boolean shouldAffectAttackRange() {
        return true;
    }

    /** Buffer Abuse 开关（事件监听判断用） */
    private boolean isBufferAbuseEnabled() {
        return bufferAbuse.getValue() && this.isEnabled();
    }

    /** 每 tick 递增攻击 tick 计数（Rise: PreMotionEvent attackTicks++） */
    @EventHandler
    private void onTick(ClientTickEvent.Pre event) {
        this.attackTicks++;
    }

    /**
     * 攻击事件（Rise: AttackEvent onAttackEvent）。
     * <p>
     * Buffer Abuse 逻辑（与 Rise 一一对应）：
     * - 本次攻击命中点在原版 3.0 内没命中实体（沿当前视角 3.0 射线无实体）：
     *   - 距上次攻击 >9 tick 或目标换了（新实体 id）且 combo 未达上限 → combo+1；
     *   - 否则取消本次攻击（服务端 3.0 校验会把超距攻击吞掉，客户端先白嫖几次
     *     远距离攻击，再退回正常攻击，降低服务端怀疑）。
     * - 正常射程内攻击 → combo 按 bufferDecrease 衰减。
     * 无论是否取消都记录本次目标 id 并清零 attackTicks（Rise 同款）。
     */
    @EventHandler
    private void onAttack(AttackEntityEvent event) {
        if (!this.isBufferAbuseEnabled()) {
            this.combo = 0.0;
            this.lastId = event.getEntity().getId();
            this.attackTicks = 0;
            return;
        }

        if (!this.isWithinVanillaRange(event.getEntity())) {
            // 超距攻击（Rise: RayCastUtil.c(RotationComponent.fk, 3.0) 无实体）
            if ((this.attackTicks > 9 || event.getEntity().getId() != this.lastId)
                    && this.combo < this.maxBuffer.getValue()) {
                this.combo++;
            } else {
                event.cancel();
            }
        } else {
            this.combo = Math.max(0.0, this.combo - this.bufferDecrease.getValue());
        }

        this.lastId = event.getEntity().getId();
        this.attackTicks = 0;
    }

    /**
     * 目标是否在原版 3.0 攻击距离内（Rise: RayCastUtil.c(rotation, 3.0).typeOfHit == ENTITY）。
     * 26.2 实现：从眼睛位置沿当前实际视角做 3.0 长实体射线（方块 clip + 实体
     * 包围盒求交），命中且命中的就是该目标 → 在射程内。
     */
    private boolean isWithinVanillaRange(Entity target) {
        if (mc.player == null || mc.level == null) {
            return true;
        }
        EntityHitResult hit = this.rayTraceEntities(mc.player.getEyePosition(),
                mc.player.getEyePosition().add(mc.player.getLookAngle().scale(3.0)));
        return hit != null && hit.getEntity() == target;
    }

    /** 沿视线 3.0 内实体射线（26.2 无 1.8.9 getEntitiesInAABBexcluding，手动 clip） */
    private EntityHitResult rayTraceEntities(Vec3 from, Vec3 to) {
        EntityHitResult best = null;
        double bestDistSqr = 3.0 * 3.0;
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity == null || entity == mc.player) continue;
            if (!entity.isAlive() || !entity.isPickable()) continue;
            float border = entity.getPickRadius();
            // 扩大包围盒参与求交（Rise: collisionBorderSize，getPickRadius 同源）
            net.minecraft.world.phys.AABB aabb = entity.getBoundingBox()
                    .inflate(border, border, border);
            java.util.Optional<Vec3> clip = aabb.clip(from, to);
            if (clip.isEmpty() && !aabb.contains(from)) continue;
            Vec3 hitVec = clip.orElse(from);
            double distSqr = from.distanceToSqr(hitVec);
            if (distSqr <= bestDistSqr) {
                bestDistSqr = distSqr;
                best = new EntityHitResult(entity, hitVec);
            }
        }
        return best;
    }

    /** tag：显示当前随机距离区间（HUD 附加信息） */
    @Override
    public String getInfo() {
        return String.format("%.2f-%.2f", rangeMin.getValue(), rangeMax.getValue());
    }
}
