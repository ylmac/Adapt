package com.ylmac.adapt.features.command.commands;

import com.ylmac.adapt.features.command.Command;
import com.ylmac.adapt.render.clickgui.ClickGuiBridge;
import com.ylmac.adapt.render.clickgui.ClickGuiScreen;
import com.ylmac.adapt.util.ChatUtil;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;

/**
 * ClickGUI 命令（移植自 myau ClickGuiCommand）。
 * <p>
 * .clickgui open|save|scale &lt;f&gt;|corner &lt;f&gt;
 * - open：打开/关闭 ClickGuiScreen（等价 myau 的 ClickGUIModule.toggle）
 * - save：保存模块状态到默认配置（等价 myau 的 saveGuiState=true）
 * - scale：设置窗口缩放（ClickGuiBridge.windowScale，QML 可绑定）
 * - corner：设置圆角半径（ClickGuiBridge.cornerRadius，QML 可绑定）
 */
public class ClickGuiCommand extends Command {
    public ClickGuiCommand() {
        super(new ArrayList<>(Arrays.asList("clickgui", "gui")));
    }

    @Override
    public void runCommand(ArrayList<String> args) {
        if (args.size() < 2) {
            ChatUtil.sendFormatted(
                    String.format("%sUsage: .%s <&oopen&r/&osave&r/&oscale&r/&ocorner&r>&r", ChatUtil.CLIENT_NAME, args.get(0).toLowerCase(Locale.ROOT))
            );
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        String subCommand = args.get(1).toLowerCase(Locale.ROOT);
        switch (subCommand) {
            case "open":
                if (mc.gui.screen() instanceof ClickGuiScreen) {
                    mc.gui.setScreen(null);
                    ChatUtil.sendFormatted(String.format("%sClickGUI &cclosed&r", ChatUtil.CLIENT_NAME));
                } else {
                    mc.gui.setScreen(new ClickGuiScreen());
                    ChatUtil.sendFormatted(String.format("%sClickGUI &aopened&r", ChatUtil.CLIENT_NAME));
                }
                break;
            case "save":
                // 保存模块状态到默认配置（等效 myau saveGuiState；配置写入见 ConfigCommand）
                new ConfigCommand().saveDefault();
                ChatUtil.sendFormatted(String.format("%sClickGUI state saving &aenabled&r", ChatUtil.CLIENT_NAME));
                break;
            case "scale":
                if (args.size() < 3) {
                    ChatUtil.sendFormatted(String.format("%sUsage: .%s scale <&oscale_factor&r>&r", ChatUtil.CLIENT_NAME, args.get(0).toLowerCase(Locale.ROOT)));
                    return;
                }
                try {
                    float scale = Float.parseFloat(args.get(2));
                    if (scale <= 0) scale = 1.0f;
                    ClickGuiBridge.windowScale = scale;
                    ChatUtil.sendFormatted(String.format("%sClickGUI scale set to &o%s&r", ChatUtil.CLIENT_NAME, scale));
                } catch (NumberFormatException e) {
                    ChatUtil.sendFormatted(String.format("%sInvalid scale value (&o%s&r)&r", ChatUtil.CLIENT_NAME, args.get(2)));
                }
                break;
            case "corner":
                if (args.size() < 3) {
                    ChatUtil.sendFormatted(String.format("%sUsage: .%s corner <&oradius&r>&r", ChatUtil.CLIENT_NAME, args.get(0).toLowerCase(Locale.ROOT)));
                    return;
                }
                try {
                    float radius = Float.parseFloat(args.get(2));
                    ClickGuiBridge.cornerRadius = radius;
                    ChatUtil.sendFormatted(String.format("%sClickGUI corner radius set to &o%s&r", ChatUtil.CLIENT_NAME, radius));
                } catch (NumberFormatException e) {
                    ChatUtil.sendFormatted(String.format("%sInvalid corner radius value (&o%s&r)&r", ChatUtil.CLIENT_NAME, args.get(2)));
                }
                break;
            default:
                ChatUtil.sendFormatted(String.format("%sInvalid argument (&o%s&r)&r", ChatUtil.CLIENT_NAME, args.get(1)));
                break;
        }
    }
}
