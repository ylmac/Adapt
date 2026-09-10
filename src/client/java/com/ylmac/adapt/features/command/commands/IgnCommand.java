package com.ylmac.adapt.features.command.commands;

import com.ylmac.adapt.features.command.Command;
import com.ylmac.adapt.util.ChatUtil;
import net.minecraft.client.Minecraft;

import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * 复制自己的游戏名到剪贴板（移植自 myau IgnCommand，26.2 用 Minecraft.getUser().getName()）。
 */
public class IgnCommand extends Command {
    public IgnCommand() {
        super(new ArrayList<String>(Arrays.asList("username", "name", "ign")));
    }

    @Override
    public void runCommand(ArrayList<String> args) {
        try {
            String username = Minecraft.getInstance().getUser().getName();
            if (username != null && !username.isEmpty()) {
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(username), null);
                ChatUtil.sendFormatted(String.format("%sYour username has been copied to the clipboard (&o%s&r)&r", ChatUtil.CLIENT_NAME, username));
            }
        } catch (Exception e) {
            ChatUtil.sendFormatted(String.format("%sFailed to copy&r", ChatUtil.CLIENT_NAME));
        }
    }
}
