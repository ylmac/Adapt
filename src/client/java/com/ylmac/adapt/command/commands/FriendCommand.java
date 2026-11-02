package com.ylmac.adapt.command.commands;

import com.ylmac.adapt.command.Command;
import com.ylmac.adapt.command.FriendManager;
import com.ylmac.adapt.command.ChatUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;

/**
 * 好友名单命令。
 * <p>
 * .friend &lt;add|remove|list|clear&gt; [名字...]；不带子命令时按名字自动添加/移除。
 */
public class FriendCommand extends Command {
    public FriendCommand() {
        super(new ArrayList<>(Arrays.asList("friend", "f")));
    }

    @Override
    public void runCommand(ArrayList<String> args) {
        if (args.size() >= 2) {
            String subCommand = args.get(1).toLowerCase(Locale.ROOT);
            switch (subCommand) {
                case "a":
                case "add":
                    if (args.size() < 3) {
                        ChatUtil.sendFormatted(
                                String.format("%sUsage: .%s add <&oname&r> [&oname&r] ...&r", ChatUtil.CLIENT_NAME, args.get(0).toLowerCase(Locale.ROOT))
                        );
                        return;
                    }
                    for (String name : args.subList(2, args.size())) {
                        String added = FriendManager.get().add(name);
                        if (added == null) {
                            ChatUtil.sendFormatted(String.format("%s&o%s&r is already in your friend list&r", ChatUtil.CLIENT_NAME, name));
                        } else {
                            ChatUtil.sendFormatted(String.format("%sAdded &o%s&r to your friend list&r", ChatUtil.CLIENT_NAME, added));
                        }
                    }
                    return;
                case "r":
                case "remove":
                    if (args.size() < 3) {
                        ChatUtil.sendFormatted(
                                String.format("%sUsage: .%s remove <&oname&r> [&oname&r] ...&r", ChatUtil.CLIENT_NAME, args.get(0).toLowerCase(Locale.ROOT))
                        );
                        return;
                    }
                    for (String name : args.subList(2, args.size())) {
                        String removed = FriendManager.get().remove(name);
                        if (removed == null) {
                            ChatUtil.sendFormatted(String.format("%s&o%s&r is not in your friend list&r", ChatUtil.CLIENT_NAME, name));
                        } else {
                            ChatUtil.sendFormatted(String.format("%sRemoved &o%s&r from your friend list&r", ChatUtil.CLIENT_NAME, removed));
                        }
                    }
                    return;
                case "l":
                case "list":
                    ArrayList<String> list = FriendManager.get().getPlayers();
                    if (list.isEmpty()) {
                        ChatUtil.sendFormatted(String.format("%sNo friends&r", ChatUtil.CLIENT_NAME));
                        return;
                    }
                    ChatUtil.sendFormatted(String.format("%sFriends:&r", ChatUtil.CLIENT_NAME));
                    for (String friend : list) {
                        ChatUtil.sendRaw(String.format("   &o%s&r", friend));
                    }
                    return;
                case "c":
                case "clear":
                    FriendManager.get().clear();
                    ChatUtil.sendFormatted(String.format("%sCleared your friend list&r", ChatUtil.CLIENT_NAME));
                    return;
                default:
                    if (args.size() == 2) {
                        if (FriendManager.get().isFriend(args.get(1))) {
                            runCommand(new ArrayList<>(Arrays.asList(args.get(0), "remove", args.get(1))));
                        } else {
                            runCommand(new ArrayList<>(Arrays.asList(args.get(0), "add", args.get(1))));
                        }
                        return;
                    }
            }
        }
        ChatUtil.sendFormatted(
                String.format("%sUsage: .%s <&oa(dd)&r/&or(emove)&r/&ol(ist)&r/&oc(lear)&r>&r", ChatUtil.CLIENT_NAME, args.get(0).toLowerCase(Locale.ROOT))
        );
    }
}
