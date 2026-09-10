package com.ylmac.adapt.features.module.impl.combat;

import com.ylmac.adapt.features.event.bus.EventHandler;
import com.ylmac.adapt.features.event.impl.AttackEntityEvent;
import com.ylmac.adapt.features.event.impl.ClientTickEvent;
import com.ylmac.adapt.features.event.impl.KeyboardInputEvent;
import com.ylmac.adapt.features.event.impl.PacketEvent;
import com.ylmac.adapt.features.module.Category;
import com.ylmac.adapt.features.module.Module;
import com.ylmac.adapt.features.module.settings.impl.BoolSetting;
import com.ylmac.adapt.features.module.settings.impl.ChoiceSetting;
import com.ylmac.adapt.features.module.settings.impl.DoubleSetting;
import com.ylmac.adapt.features.module.settings.impl.IntSetting;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.entity.LivingEntity;

import java.util.List;

/**
 * Criticals 暴击 —— 自动让每次攻击都打出暴击（移植自 Rise 6.9.5 Criticals，1.8.9 → Fabric 26.2）。
 * <p>
 * 26.2 暴击机制（javap minecraft-merged-deobf-26.2.jar 反汇编确证）：
 * - 暴击判定在 {@code Player.attack(Entity)} 内部的私有方法
 *   {@code canCriticalAttack(Entity)}（1.8.9 的 getCriticalFactor 等价物）：
 *   要求 fallDistance > 0 && !onGround() && !onClimbable() && !isInWater()
 *   && !isMobilityRestricted() && !isPassenger() && 目标 instanceof LivingEntity
 *   && !isSprinting()，满足时伤害 ×1.5。
 * - 1.8.9 的 {@code EntityPlayer.onCriticalHit(entity)} 在 26.2 已退化为
 *   {@code Player.crit(Entity)} 且是空实现（javap 确认 return）——纯客户端粒子/音效，
 *   暴击本身完全由服务端判定。因此模块的核心 = 「让攻击发生时玩家处于正确的
 *   下落状态」+ 可选补发位置欺骗包（服务端看到的也是腾空）。
 * - 攻击链（与 KillAura/AutoClicker 共用）：Minecraft.startAttack → AttackEntityEvent
 *   （本模块监听，此时尚未发包/结算）→ MultiPlayerGameMode.attack → Player.attack。
 * <p>
 * 模式（对应 Rise 6.9.5 各 SubMode）：
 * - Jump（跳跃）：对应 Rise 的 Jump/Watchdog 思路 + LB CriticalsJump —— 攻击前
 *   （AttackEntityEvent 里）真实小跳：26.2 输入链 KeyboardInput.tick 会消费
 *   KeyboardInputEvent.jump（TAIL 发布、LocalPlayer.aiStep 的 Input.jump() 分支
 *   消费并调用 jumpFromGround），因此只需在事件里把 jump 置 true 即可本地真实跳起，
 *   下一 tick 玩家腾空（fallDistance>0）后攻击自然暴击，KillAura 照常点击。
 * - Packet（数据包）：对应 Rise PacketCriticals —— 攻击时补发两个
 *   ServerboundMovePlayerPacket.Pos 位置包（+0.42/+0.0，onGround=false），
 *   让服务端认为玩家先升空再落地，本次攻击按暴击结算（对服务器生效，本地不动）。
 * - NoGround（无地面）：对应 Rise NoGroundCriticals + LB CriticalsNoGround ——
 *   把所有 ServerboundMovePlayerPacket 的 onGround 改 false（LB 是字段可写，
 *   26.2 的包是 record 字段 final，只能发送新包替换，见下）。
 * - Verus（Verus 专精）：对应 Rise VerusCriticals —— 小偏移 0.0625/0.0，
 *   onGround=false 双位置包（不跳起 0.42，极小位移难被检测）。
 * - Edit（编辑）：对应 Rise EditCriticals —— 攻击后两个 tick 微调位置
 *   （0.0005/0.0001），改动极小、隐蔽。
 * <p>
 * 26.2 移植注意：
 * - 26.2 的 ServerboundMovePlayerPacket 子类（StatusOnly/Pos/Rot/PosRot）字段全部
 *   final 只读（javap 确证，旧版 1.8.9 的 C04PacketPlayerPosition 可改字段），
 *   且 LB 的 "packet.onGround = false" 写法在 26.2 编译不过。因此 NoGround 改为
 *   在 PacketEvent.Send 里拦截并把 onGround=false 的新 StatusOnly 包替换掉原包
 *   （连接层 ConnectionEventMixin 已对 ServerboundMovePlayerPacket 派发 Send 事件，
 *   替换后服务器收到等价位置 + onGround=false）。
 * - Packet/Verus/Edit 的补发位置包：直接 mc.player.connection.send（KillAura 同款），
 *   其中 Edit 的"改 PreMotion"在 26.2 没有对应事件（SendPositionEvent 未接线），
 *   改为在攻击后的两个客户端 tick 里补发微位移包 + 改玩家本地 Y（视觉一致）。
 * - 与 KillAura 协作：KillAura 的点击（KeyMapping.click → handleKeybinds →
 *   startAttack）同样触发 AttackEntityEvent，本模块对任意来源的攻击统一补暴击。
 */
public class Criticals extends Module {

    /** 暴击模式（对应 Rise 6.9.5 的 ModeValue 各 SubMode） */
    private final ChoiceSetting mode;

    /** 模式内参数 */
    private final IntSetting delay;          // 攻击间延迟（Rise delay：0~1000ms，默认 500）
    private final BoolSetting autoJump;      // Jump 模式：跳起后自动落地攻击（Rise 攻击触发式）
    private final BoolSetting checkKillAura; // Jump 模式：仅 KillAura 攻击时跳（LB CheckKillaura）
    private final DoubleSetting jumpHeight;  // Jump 模式：跳跃高度（LB Height 0.1~0.42）

    // 内部状态
    private long lastAttackTime = 0L;        // 上次触发暴击时间（delay 冷却）
    private int jumpTicks = 0;               // Jump 模式：跳起后经过的 tick（落地前锁定）
    private int editTicks = 0;               // Edit 模式：攻击后微调 tick 计数
    private boolean editPending = false;     // Edit 模式：等待微调
    private boolean jumped = false;          // Jump 模式：本次跳起标志
    private double editY = 0.0;              // Edit 模式：攻击时玩家 Y（微调基准）

    public Criticals() {
        super("criticals", "Criticals", Category.COMBAT);
        setDefaultEnabled(false);
        setDefaultHidden(false);
        mode = choiceSetting("Mode", "Packet", List.of("Jump", "Packet", "NoGround", "Verus", "Edit"));
        delay = intSetting("Delay", 500, 0, 1000, 50,
                () -> !"Jump".equals(mode.getValue()));
        autoJump = boolSetting("自动落地攻击", true,
                () -> "Jump".equals(mode.getValue()));
        checkKillAura = boolSetting("仅KillAura触发", false,
                () -> "Jump".equals(mode.getValue()));
        jumpHeight = doubleSetting("跳跃高度", 0.42, 0.1, 0.42, 0.01,
                () -> "Jump".equals(mode.getValue()));
    }

    @Override
    protected void onEnable() {
        lastAttackTime = 0L;
        jumpTicks = 0;
        editTicks = 0;
        editPending = false;
        jumped = false;
    }

    /** 攻击事件：在攻击结算（MultiPlayerGameMode.attack → Player.attack）前补暴击 */
    @EventHandler(priority = com.ylmac.adapt.features.event.bus.EventPriority.HIGHEST)
    private void onAttack(AttackEntityEvent event) {
        if (mc.player == null || mc.level == null) return;
        // 26.2 暴击判定要求目标是 LivingEntity（canCriticalAttack 里 instanceof 检查）
        if (!(event.getEntity() instanceof LivingEntity)) return;

        switch (mode.getValue()) {
            case "Jump" -> handleJumpAttack();
            case "Packet" -> handlePacketAttack(0.42, 0.0);
            case "Verus" -> handlePacketAttack(0.0625, 0.0);
            case "Edit" -> handleEditAttack();
            // NoGround 不需要在攻击时做任何事（onGround 包已被拦截）
            default -> {
            }
        }
    }

    /** Packet/Verus 模式：攻击前补发两个位置包（Rise PacketCriticals/VerusCriticals 同款） */
    private void handlePacketAttack(double offset1, double offset2) {
        if (System.currentTimeMillis() - lastAttackTime < delay.getValue()) return;
        // Rise 条件：cqL > 0（1.8.9 的攻击冷却 tick），26.2 等价 = 攻击强度已恢复
        if (mc.player.getAttackStrengthScale(0.5f) < 0.9f) return;
        if (mc.player.onGround()) {
            // 服务器视角：先升到 offset1（onGround=false）再回 offset2（onGround=false）
            sendPosPacket(offset1);
            sendPosPacket(offset2);
            lastAttackTime = System.currentTimeMillis();
        }
    }

    /** Edit 模式：记录攻击，之后两个 tick 微调位置（Rise EditCriticals 同款 VALUES） */
    private void handleEditAttack() {
        if (System.currentTimeMillis() - lastAttackTime < delay.getValue()) return;
        if (mc.player.onGround() && !mc.player.onClimbable()) {
            editPending = true;
            editTicks = 0;
            editY = mc.player.getY();
            lastAttackTime = System.currentTimeMillis();
        }
    }

    /** Jump 模式：攻击瞬间真实小跳（26.2 KeyboardInputEvent.jump → jumpFromGround 消费） */
    private void handleJumpAttack() {
        if (mc.player.onGround() && !mc.player.onClimbable()
                && !mc.player.isInWater() && !mc.player.isPassenger()) {
            jumped = true;
        }
    }

    /** 输入事件（Jump 模式）：攻击时把 jump 置 true（等价 LB CriticalsJump 的 MovementInputEvent） */
    @EventHandler
    private void onKeyboardInput(KeyboardInputEvent event) {
        if (jumped) {
            jumped = false;           // 只强制跳一次
            jumpTicks = 0;            // 开始腾空计时
            event.setJump(true);      // 本 tick 消费为真实跳跃（LocalPlayer.aiStep 调 jumpFromGround）
        }
    }

    /** 每客户端 tick：Jump 模式落地恢复 + Edit 模式微调补发 */
    @EventHandler
    private void onTick(ClientTickEvent.Pre event) {
        if (mc.player == null) return;

        // Jump 模式：跳起后锁定，直到落地才允许下一次跳（防止连续跳）
        if (jumpTicks > 0) {
            jumpTicks++;
            if (mc.player.onGround()) {
                jumpTicks = 0;
            }
        }

        // Edit 模式：攻击后两个 tick 微调（Rise EditCriticals：tick1 +0.0005，tick2 +0.0001）
        if (editPending) {
            editTicks++;
            switch (editTicks) {
                case 1 -> {
                    mc.player.setPos(mc.player.getX(), editY + 0.0005, mc.player.getZ());
                    sendPosPacket(0.0005);
                }
                case 2 -> {
                    mc.player.setPos(mc.player.getX(), editY + 0.0001, mc.player.getZ());
                    sendPosPacket(0.0001);
                    editPending = false;
                }
            }
        }
    }

    /** 发包拦截（NoGround 模式）：把移动包替换为 onGround=false 的等价包 */
    @EventHandler
    private void onPacketSend(PacketEvent.Send event) {
        if (!"NoGround".equals(mode.getValue())) return;
        if (mc.player == null) return;
        if (event.getPacket() instanceof ServerboundMovePlayerPacket packet) {
            // 26.2 包字段 final 不可写 → 用**同类型**新包替换，只翻转 onGround。
            // ⚠ 之前用 StatusOnly(false) 替换是错的：StatusOnly 不携带位置/旋转
            //   （26.2 javap：Pos/PosRot/Rot 才有 x/y/z/yRot/xRot 字段，StatusOnly 没有），
            //   服务端会把玩家位置/朝向永久停在旧值 → 位置解同步、交互被判太远。
            boolean hc = packet.horizontalCollision();
            if (packet instanceof ServerboundMovePlayerPacket.Pos pos) {
                event.setPacket(new ServerboundMovePlayerPacket.Pos(
                        pos.getX(0), pos.getY(0), pos.getZ(0), false, hc));
            } else if (packet instanceof ServerboundMovePlayerPacket.PosRot pr) {
                event.setPacket(new ServerboundMovePlayerPacket.PosRot(
                        pr.getX(0), pr.getY(0), pr.getZ(0),
                        pr.getYRot(0), pr.getXRot(0), false, hc));
            } else if (packet instanceof ServerboundMovePlayerPacket.Rot rot) {
                event.setPacket(new ServerboundMovePlayerPacket.Rot(
                        rot.getYRot(0), rot.getXRot(0), false, hc));
            } else {
                // StatusOnly 本来就不带位置/旋转，直接翻转 onGround
                event.setPacket(new ServerboundMovePlayerPacket.StatusOnly(false, hc));
            }
        }
    }

    /** 补发位置包（Rise PacketUtil.send 同款：直接走连接，不经事件拦截） */
    private void sendPosPacket(double yOffset) {
        if (mc.player.connection == null) return;
        mc.player.connection.send(new ServerboundMovePlayerPacket.Pos(
                mc.player.getX(), mc.player.getY() + yOffset, mc.player.getZ(),
                false, mc.player.horizontalCollision));
    }

    /** 模块信息 tag（当前模式名，与 KillAura 一致显示在 HUD） */
    @Override
    public String getInfo() {
        return mode.getValue();
    }
}
