package com.ylmac.adapt.command.commands;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.ylmac.adapt.command.Command;
import com.ylmac.adapt.command.ChatUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;

import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Locale;

/**
 * 反隐藏昵称命令。
 * <p>
 * .denick &lt;名字&gt; — 从 PlayerInfo 的 GameProfile textures 属性（base64 皮肤 JSON）
 * 解码出真实 UUID/名称并复制到剪贴板。26.2 用 getOnlinePlayers 遍历代替
 * 1.8.9 的 getPlayerInfo(name)（26.2 getPlayerInfo(name) 只能查 UUID）。
 */
public class DenickCommand extends Command {
    public DenickCommand() {
        super(new ArrayList<>(Collections.singletonList("denick")));
    }

    @Override
    public void runCommand(ArrayList<String> args) {
        if (args.size() < 2) {
            ChatUtil.sendFormatted(String.format("%sUsage: .%s <&oname&r>&r", ChatUtil.CLIENT_NAME, args.get(0).toLowerCase(Locale.ROOT)));
            return;
        }
        var connection = Minecraft.getInstance().getConnection();
        if (connection == null) {
            ChatUtil.sendFormatted(String.format("%sNot connected&r", ChatUtil.CLIENT_NAME));
            return;
        }
        // 26.2 getPlayerInfo(String) 按 UUID 查找，按名字需遍历在线玩家
        String target = ChatUtil.parseColor(args.get(1));
        PlayerInfo playerInfo = null;
        for (PlayerInfo info : connection.getOnlinePlayers()) {
            if (info.getProfile() != null && target.equalsIgnoreCase(info.getProfile().name())) {
                playerInfo = info;
                break;
            }
        }
        if (playerInfo == null) {
            ChatUtil.sendFormatted(String.format("%sNo entity with name &o%s&r", ChatUtil.CLIENT_NAME, args.get(1)));
            return;
        }
        GameProfile gameProfile = playerInfo.getProfile();
        Property property = null;
        if (gameProfile.properties() != null) {
            var textures = gameProfile.properties().get("textures");
            if (textures != null) property = textures.stream().findFirst().orElse(null);
        }
        if (property == null) {
            ChatUtil.sendFormatted(String.format("%sNo textures for entity with name &o%s&r", ChatUtil.CLIENT_NAME, args.get(1)));
            return;
        }
        String code = new String(Base64.getDecoder().decode(property.value().getBytes(StandardCharsets.UTF_8)));
        String name = code.contains("profileName\" : \"") ? code.split("profileName\" : \"")[1].split("\"")[0] : "?";
        String uuid = code.contains("profileId\" : \"") ? code.split("profileId\" : \"")[1].split("\"")[0] : "?";
        ChatUtil.sendRaw(String.format("%s%s&r -> %s (&o%s&r)&r", ChatUtil.CLIENT_NAME,
                gameProfile.name().replace("§", "&"), name, uuid));
        if (!uuid.isEmpty() && !uuid.equals("?")) {
            try {
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(uuid), null);
            } catch (Exception ignored) {}
        }
    }
}
