package com.ylmac.adapt.module.player;

import com.ylmac.adapt.event.bus.EventHandler;
import com.ylmac.adapt.event.impl.PlayerTickEvent;
import com.ylmac.adapt.module.Category;
import com.ylmac.adapt.module.Module;
import com.ylmac.adapt.module.settings.impl.BoolSetting;
import com.ylmac.adapt.module.settings.impl.IntSetting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.item.component.SwingAnimation;

/**
 * AntiSuffocate 防窒息。
 * <p>
 * {@code thePlayer.isEntityInsideOpaqueBlock}（1.8.9 的 Entity 方法），
 * 若头部在遮挡方块内则向服务器发送 C07PacketPlayerDigging
 * (START_DESTROY_BLOCK, 玩家脚下方块, UP)，并可选挥臂（swingItem）。
 * <p>
 * - 26.2 中等价检测 = {@code player.isInWall}（Entity.isInWall 判断
 * getBlockState(getEyeHeight 处的方块).isSuffocating，即"头部卡进可窒息方块"）；
 * 同时直接检查头部所在方块（eyePosition 处）是否可窒息，双保险；
 * - 触发后向服务器发送 ServerboundPlayerActionPacket
 * (START_DESTROY_BLOCK, 玩家脚下方块, UP)—与原版 C07 语义一致，
 * 提示服务端释放该方块；可选挥臂 LocalPlayer.swing(MAIN_HAND)；
 * - 额外"手动移出"策略：若开关启用，直接把玩家向上挪 0.2 格并清空垂直速度，
 * 从窒息方块中脱出（1.21.9+ 移除了 suffocation 相关的客户端自动推挤，需自行处理）。
 */
public class AntiSuffocate extends Module {

 /** 是否发送破坏包 */
    private final BoolSetting sendBreakPacket;

 /** 是否挥臂 */
    private final BoolSetting swing;

    /** 是否直接移动玩家脱出（额外策略） */
    private final BoolSetting pushOut;

    /** 触发冷却（tick），避免每 tick 反复发包 */
    private final IntSetting cooldownTicks;

    private int cooldown;

    public AntiSuffocate() {
        super("antisuffocate", "AntiSuffocate", Category.PLAYER);
        setDefaultEnabled(false);
        setDefaultHidden(false);
        sendBreakPacket = boolSetting("发送破坏包", true);
        swing = boolSetting("挥臂", true, () -> sendBreakPacket.getValue());
        pushOut = boolSetting("直接移出", true);
        cooldownTicks = intSetting("冷却(tick)", 5, 0, 40, 1);
    }

    @EventHandler
    private void onTick(PlayerTickEvent.Post event) {
        if (mc.player == null || mc.level == null) return;
        if (mc.player.isSpectator()) return;

        if (cooldown > 0) {
            cooldown--;
            return;
        }

        // 头部是否卡进可窒息方块：玩家眼睛高度处方块 isSuffocating，或整实体 isInWall
        BlockPos headPos = BlockPos.containing(mc.player.getEyePosition());
        BlockState headState = mc.level.getBlockState(headPos);
        boolean suffocating = mc.player.isInWall() || headState.isSuffocating(mc.level, headPos);

        if (!suffocating) return;

        if (sendBreakPacket.getValue() && mc.getConnection() != null) {
            BlockPos below = mc.player.blockPosition().below();
            mc.getConnection().send(
                    new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, below, Direction.UP));
        }
        if (swing.getValue()) {
            mc.player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, true);
        }
        if (pushOut.getValue()) {
            // 向上移出窒息方块，并清除垂直速度防回弹
            mc.player.setPos(mc.player.getX(), mc.player.getY() + 0.2, mc.player.getZ());
            mc.player.setDeltaMovement(mc.player.getDeltaMovement().x(), Math.min(mc.player.getDeltaMovement().y(), 0), mc.player.getDeltaMovement().z());
        }
        cooldown = cooldownTicks.getValue();
    }

}
