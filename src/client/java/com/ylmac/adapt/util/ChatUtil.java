package com.ylmac.adapt.util;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;

/**
 * 聊天输出工具（替代 myau 的 ChatUtil + ChatColors）。
 * <p>
 * myau 的 "&" 色码格式（&a 绿 / &c 红 / &o 斜体 / &r 重置）在 26.2 无原生支持，
 * 这里自行解析翻译成 ChatFormatting；输出统一走 ChatComponent（HUD 聊天区）。
 */
public final class ChatUtil {

    public static final String CLIENT_NAME = "&9Adapt&r ";

    private ChatUtil() {}

    /** 发送格式化消息（支持 & 色码），带客户端名前缀 */
    public static void sendFormatted(String message) {
        send(Component.literal(parseColor(message)));
    }

    /** 发送原始消息（无前缀，支持 & 色码） */
    public static void sendRaw(String message) {
        send(Component.literal(parseColor(message)));
    }

    /** 发送任意组件 */
    public static void send(Component message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            // 玩家侧显示：带 [Adapt] 前缀，红色客户端名
            mc.player.sendSystemMessage(Component.literal("§8[§9Adapt§8]§r ").append(message));
        } else {
            try {
                // 26.2：聊天 HUD 在 gui.hud.getChat()（Gui 本身无 getChat）
                mc.gui.hud.getChat().addClientSystemMessage(message);
            } catch (Throwable ignored) {}
        }
    }

    /** 把 "&" 色码翻译成 § 格式（myau ChatColors.formatColor 的 26.2 版） */
    public static String parseColor(String input) {
        if (input == null) return "";
        StringBuilder sb = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '&' && i + 1 < input.length()) {
                char code = input.charAt(i + 1);
                ChatFormatting fmt = ChatFormatting.getByCode(code);
                if (fmt != null) {
                    sb.append('§').append(code);
                    i++;
                    continue;
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /** 生成可点击的配置列表项（点击运行命令、悬停显示命令文本） */
    public static Component clickable(String text, String runCommand) {
        return Component.literal(parseColor(text))
                .withStyle(style -> style
                        .withClickEvent(new ClickEvent.RunCommand(runCommand))
                        .withHoverEvent(new HoverEvent.ShowText(Component.literal(runCommand))));
    }
}
