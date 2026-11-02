package com.ylmac.adapt.command.commands;

import com.ylmac.adapt.command.Command;
import com.ylmac.adapt.command.TargetManager;
import com.ylmac.adapt.command.ChatUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;

/**
 * 敌人/目标名单命令。
 * <p>
 * .target &lt;add|remove|list|clear&gt; [名字]。
 */
public class TargetCommand extends Command {
    public TargetCommand() {
        super(new ArrayList<>(Arrays.asList("enemy", "e", "target")));
    }

    @Override
    public void runCommand(ArrayList<String> args) {
        if (args.size() >= 2) {
            String subCommand = args.get(1).toLowerCase(Locale.ROOT);
            switch (subCommand) {
                case "add":
                    if (args.size() < 3) {
                        ChatUtil.sendFormatted(
                                String.format("%sUsage: .%s add <&oname&r>&r", ChatUtil.CLIENT_NAME, args.get(0).toLowerCase(Locale.ROOT))
                        );
                        return;
                    }
                    String added = TargetManager.get().add(args.get(2));
                    if (added == null) {
                        ChatUtil.sendFormatted(String.format("%s&o%s&r is already in your enemy list&r", ChatUtil.CLIENT_NAME, args.get(2)));
                        return;
                    }
                    ChatUtil.sendFormatted(String.format("%sAdded &o%s&r to your enemy list&r", ChatUtil.CLIENT_NAME, added));
                    return;
                case "remove":
                    if (args.size() < 3) {
                        ChatUtil.sendFormatted(
                                String.format("%sUsage: .%s remove <&oname&r>&r", ChatUtil.CLIENT_NAME, args.get(0).toLowerCase(Locale.ROOT))
                        );
                        return;
                    }
                    String removed = TargetManager.get().remove(args.get(2));
                    if (removed == null) {
                        ChatUtil.sendFormatted(String.format("%s&o%s&r is not in your enemy list&r", ChatUtil.CLIENT_NAME, args.get(2)));
                        return;
                    }
                    ChatUtil.sendFormatted(String.format("%sRemoved &o%s&r from your enemy list&r", ChatUtil.CLIENT_NAME, removed));
                    return;
                case "list":
                    ArrayList<String> list = TargetManager.get().getPlayers();
                    if (list.isEmpty()) {
                        ChatUtil.sendFormatted(String.format("%sNo enemies&r", ChatUtil.CLIENT_NAME));
                        return;
                    }
                    ChatUtil.sendFormatted(String.format("%sEnemies:&r", ChatUtil.CLIENT_NAME));
                    for (String player : list) {
                        ChatUtil.sendRaw(String.format("   &o%s&r", player));
                    }
                    return;
                case "clear":
                    TargetManager.get().clear();
                    ChatUtil.sendFormatted(String.format("%sCleared your enemy list&r", ChatUtil.CLIENT_NAME));
                    return;
            }
        }
        ChatUtil.sendFormatted(
                String.format("%sUsage: .%s <&oadd&r/&oremove&r/&olist&r/&oclear&r>&r", ChatUtil.CLIENT_NAME, args.get(0).toLowerCase(Locale.ROOT))
        );
    }
}
