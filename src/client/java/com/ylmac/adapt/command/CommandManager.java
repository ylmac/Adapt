package com.ylmac.adapt.command;

import com.ylmac.adapt.command.commands.*;
import com.ylmac.adapt.module.ModuleRegistry;
import com.ylmac.adapt.command.ChatUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 命令管理器。
 * <p>
 * 拦截机制：26.2 已无 1.8.9 的 C01PacketChatMessage，改为 mixin 注入
 * {@code ClientPacketListener.sendChat(String)}（见 ClientPacketListenerMixin），
 * 检测 "." 前缀的本地命令后 cancel 并交由此处执行。
 */
public class CommandManager {

    private static volatile CommandManager INSTANCE;

    public ArrayList<Command> commands;

    public CommandManager() {
        this.commands = new ArrayList<>();
    }

    /** 全局单例（AdaptClient.onInitializeClient 首次调用） */
    public static CommandManager get() {
        if (INSTANCE == null) {
            synchronized (CommandManager.class) {
                if (INSTANCE == null) INSTANCE = new CommandManager();
            }
        }
        return INSTANCE;
    }

    /** 注册全部命令（onInitializeClient 调用一次） */
    public void init() {
        // 提前初始化 ClickGuiBridge：其静态块注册演示模块（杀戮光环/飞行/速度...），
        // 保证 .toggle/.list 等命令在启动后立即可操作模块表，而非等首次打开 ClickGUI
        com.ylmac.adapt.render.clickgui.ClickGuiBridge.INSTANCE.toString();

        commands.add(new ToggleCommand());
        commands.add(new BindCommand());
        commands.add(new ListCommand());
        commands.add(new HideCommand());
        commands.add(new ShowCommand());
        commands.add(new VclipCommand());
        commands.add(new ConfigCommand());
        commands.add(new FriendCommand());
        commands.add(new TargetCommand());
        commands.add(new HelpCommand());
        commands.add(new IgnCommand());
        commands.add(new ItemCommand());
        commands.add(new PlayerCommand());
        commands.add(new DenickCommand());
        commands.add(new ClickGuiCommand());
        commands.add(new ComposeCommand());
        commands.add(new ModuleCommand());
        commands.add(new InfoCommand());
        commands.add(new SetCommand());
    }

    /** 解析并执行 ".xxx ..." 命令字符串（不含前缀判断，由拦截点保证） */
    public void handleCommand(String string) {
        List<String> params = Arrays.asList(string.substring(1).trim().split("\\s+"));
        ArrayList<String> arrayList = new ArrayList<>(params);
        if (params.get(0).isEmpty()) {
            ChatUtil.sendFormatted(String.format("%sUnknown command&r", ChatUtil.CLIENT_NAME).replace("&", "§"));
        } else {
            for (Command command : commands) {
                for (String name : command.names) {
                    if (params.get(0).equalsIgnoreCase(name)) {
                        command.runCommand(arrayList);
                        return;
                    }
                }
                // ModuleCommand 别名 = 运行期注册的模块名，动态匹配（模块注册晚于命令注册）
                if (command instanceof ModuleCommand && ModuleRegistry.get(params.get(0)) != null) {
                    command.runCommand(arrayList);
                    return;
                }
            }
            ChatUtil.sendFormatted(String.format("%sUnknown command (&o%s&r)&r", ChatUtil.CLIENT_NAME, params.get(0)).replace("&", "§"));
        }
    }

    /** 是否为本地命令：以 "." 开头且后面紧跟非空格（单点 "." 也拦截，本地报 Unknown） */
    public boolean isTypingCommand(String string) {
        if (string == null || string.isEmpty() || string.charAt(0) != '.') {
            return false;
        }
        // "." 后必须是字母/数字/直接结束（单点也拦截）；"." + 空格视为普通聊天不拦
        if (string.length() == 1) {
            return true;
        }
        char c = string.charAt(1);
        return Character.isLetterOrDigit(c);
    }
}
