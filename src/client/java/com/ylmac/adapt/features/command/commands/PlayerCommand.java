package com.ylmac.adapt.features.command.commands;

import com.ylmac.adapt.features.command.Command;
import com.ylmac.adapt.util.ChatUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * 列出在线玩家命令（移植自 myau PlayerCommand）。
 * <p>
 * .playerlist —— 26.2 用 connection.getOnlinePlayers() 遍历 PlayerInfo。
 */
public class PlayerCommand extends Command {
    public PlayerCommand() {
        super(new ArrayList<>(Arrays.asList("playerlist", "players")));
    }

    @Override
    public void runCommand(ArrayList<String> args) {
        var connection = Minecraft.getInstance().getConnection();
        if (connection == null) {
            ChatUtil.sendFormatted(String.format("%sNo players&r", ChatUtil.CLIENT_NAME));
            return;
        }
        ArrayList<String> players = new ArrayList<>();
        for (PlayerInfo playerInfo : connection.getOnlinePlayers()) {
            if (playerInfo.getProfile() != null) {
                players.add(playerInfo.getProfile().name().replace("§", "&"));
            }
        }
        if (players.isEmpty()) {
            ChatUtil.sendFormatted(String.format("%sNo players&r", ChatUtil.CLIENT_NAME));
        } else {
            ChatUtil.sendRaw(String.format("%sPlayers:&r %s", ChatUtil.CLIENT_NAME, String.join(", ", players)));
        }
    }
}
