package com.ylmac.adapt.command.commands;

import com.ylmac.adapt.command.Command;
import com.ylmac.adapt.command.CommandManager;
import com.ylmac.adapt.command.ChatUtil;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * 列出所有命令。
 */
public class HelpCommand extends Command {
    public HelpCommand() {
        super(new ArrayList<>(Arrays.asList("help", "commands")));
    }

    @Override
    public void runCommand(ArrayList<String> args) {
        if (!CommandManager.get().commands.isEmpty()) {
            ChatUtil.sendFormatted(String.format("%sCommands:&r", ChatUtil.CLIENT_NAME));
            for (Command command : CommandManager.get().commands) {
 // 跳过 ModuleCommand
                if (command instanceof ModuleCommand) continue;
                ChatUtil.sendFormatted(String.format("&7»&r .%s&r", String.join(" &7/&r .", command.names)));
            }
        }
    }
}
