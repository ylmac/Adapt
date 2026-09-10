package com.ylmac.adapt.features.command.commands;

import com.ylmac.adapt.features.command.Command;
import com.ylmac.adapt.util.ChatUtil;
import net.minecraft.client.Minecraft;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;

/**
 * 垂直传送命令（移植自 myau VclipCommand）。
 * <p>
 * .vclip &lt;距离&gt; —— 玩家沿 Y 轴瞬移（mc.player.setPos）。
 */
public class VclipCommand extends Command {
    private static final DecimalFormat df = new DecimalFormat("#.##", new DecimalFormatSymbols(Locale.US));

    public VclipCommand() {
        super(new ArrayList<>(Collections.singletonList("vclip")));
    }

    @Override
    public void runCommand(ArrayList<String> args) {
        if (args.size() >= 2) {
            double distance = 0.0;
            try {
                distance = Double.parseDouble(args.get(1));
            } catch (NumberFormatException ignored) {
            }
            var player = Minecraft.getInstance().player;
            if (player != null) {
                player.setPos(player.getX(), player.getY() + distance, player.getZ());
                ChatUtil.sendFormatted(String.format("%sClipped (%s blocks)", ChatUtil.CLIENT_NAME, df.format(distance)));
            }
            return;
        }
        ChatUtil.sendFormatted(
                String.format("%sUsage: .%s <&odistance&r>&r", ChatUtil.CLIENT_NAME, args.get(0).toLowerCase(Locale.ROOT))
        );
    }
}
