package com.ylmac.adapt.command.commands;

import com.ylmac.adapt.compose.host.ComposeEntry;
import com.ylmac.adapt.command.Command;
import com.ylmac.adapt.command.ChatUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;

/**
 * Compose 壳验收：.compose [open]
 */
public class ComposeCommand extends Command {
    public ComposeCommand() {
        super(new ArrayList<>(Arrays.asList("compose", "cmp")));
    }

    @Override
    public void runCommand(ArrayList<String> args) {
        if (args.size() >= 2) {
            String sub = args.get(1).toLowerCase(Locale.ROOT);
            if ("help".equals(sub)) {
                ChatUtil.sendFormatted(String.format(
                        "%sUsage: .%s [&oopen&r|&obench&r]&r — smoke / idle FPS bench",
                        ChatUtil.CLIENT_NAME, args.get(0).toLowerCase(Locale.ROOT)));
                return;
            }
            if ("bench".equals(sub)) {
                ComposeEntry.runIdleBench();
                ChatUtil.sendFormatted(String.format(
                        "%sCompose idle bench &arunning&r — see log [ComposeIdleBench]",
                        ChatUtil.CLIENT_NAME));
                return;
            }
        }
        // 聊天提交后 ChatScreen 会 setScreen(null)；ComposeEntry 已 defer 打开。
        // 当前屏几乎总是 ChatScreen，不能用 isSmokeOpen 判断「已开」。
        boolean closing = ComposeEntry.isSmokeOpen();
        if (closing) {
            ComposeEntry.closeSmoke();
            ChatUtil.sendFormatted(String.format("%sCompose smoke &cclosed&r", ChatUtil.CLIENT_NAME));
        } else {
            ComposeEntry.openSmoke();
            ChatUtil.sendFormatted(String.format(
                    "%sCompose smoke &aopening&r (dark panel + Click me) — ESC to close",
                    ChatUtil.CLIENT_NAME));
        }
    }
}
