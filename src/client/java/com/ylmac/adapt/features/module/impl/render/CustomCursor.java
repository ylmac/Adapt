package com.ylmac.adapt.features.module.impl.render;

import com.mojang.blaze3d.platform.Window;
import com.ylmac.adapt.features.event.bus.EventHandler;
import com.ylmac.adapt.features.event.impl.ClientTickEvent;
import com.ylmac.adapt.features.module.Category;
import com.ylmac.adapt.features.module.Module;
import com.ylmac.adapt.features.module.settings.impl.BoolSetting;
import com.ylmac.adapt.features.module.settings.impl.ChoiceSetting;
import com.ylmac.adapt.features.module.settings.impl.IntSetting;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWImage;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * CustomCursor —— 自定义 Minecraft 窗口光标。
 * <p>
 * GLFWImage 使用运行时生成的 RGBA8 位图创建 native cursor；WindowCursorMixin
 * 拦截 vanilla Window.selectCursor，避免 GUI hover 时 Arrow/Hand 把自定义光标覆盖。
 */
public final class CustomCursor extends Module {

    private static CustomCursor instance;

    public static CustomCursor get() {
        return instance;
    }

    private final ChoiceSetting style;
    private final BoolSetting overrideGuiCursor;
    private final IntSetting size;

    private long cursorHandle;
    private String currentKey = "";

    public CustomCursor() {
        super("customcursor", "CustomCursor", Category.RENDER);
        instance = this;
        setDefaultEnabled(false);
        setDefaultHidden(false);
        style = choiceSetting("Style", "Crosshair", List.of("Crosshair", "Dot"));
        overrideGuiCursor = boolSetting("Override GUI Cursor", true);
        size = intSetting("Size", 32, 16, 64, 8);
    }

    @Override
    protected void onEnable() {
        rebuildCursor();
    }

    @Override
    protected void onDisable() {
        destroyCursor();
        GLFW.glfwSetCursor(mc.getWindow().handle(), 0L);
    }

    @EventHandler
    private void onTick(ClientTickEvent.Post event) {
        if (!isEnabled() || mc.getWindow() == null) {
            return;
        }
        String key = style.getValue() + "@" + size.getValue();
        if (!key.equals(currentKey)) {
            rebuildCursor();
        }
    }

    /** WindowCursorMixin 在 vanilla 尝试选择 Arrow/Hand/IBeam 时调用。 */
    public boolean overrideWindowCursor(Window window) {
        if (!isEnabled() || !overrideGuiCursor.getValue() || cursorHandle == 0L) {
            return false;
        }
        GLFW.glfwSetCursor(window.handle(), cursorHandle);
        return true;
    }

    private void rebuildCursor() {
        destroyCursor();
        int px = size.getValue();
        byte[] rgba = generateCursorImage(style.getValue(), px);
        ByteBuffer pixels = MemoryUtil.memAlloc(rgba.length);
        pixels.put(rgba).flip();
        try (GLFWImage image = GLFWImage.malloc()) {
            image.set(px, px, pixels);
            int hotspot = px / 2;
            cursorHandle = GLFW.glfwCreateCursor(image, hotspot, hotspot);
        } finally {
            MemoryUtil.memFree(pixels);
        }
        // 26.3 无 GLFW cursor；shim 返回 0，功能暂禁用但不崩
        if (cursorHandle == 0L) {
            currentKey = style.getValue() + "@" + px;
            return;
        }
        currentKey = style.getValue() + "@" + px;
        GLFW.glfwSetCursor(mc.getWindow().handle(), cursorHandle);
    }

    private void destroyCursor() {
        if (cursorHandle != 0L) {
            GLFW.glfwDestroyCursor(cursorHandle);
            cursorHandle = 0L;
        }
        currentKey = "";
    }

    private static byte[] generateCursorImage(String style, int px) {
        byte[] rgba = new byte[px * px * 4];
        float center = (px - 1) * 0.5f;
        float unit = px / 32.0f;
        float lineHalf = Math.max(0.8f, unit * 1.15f);
        float ringRadius = px * 0.39f;
        float ringWidth = Math.max(0.9f, unit * 1.25f);
        float dotRadius = Math.max(2.0f, px * 0.07f);
        boolean crosshair = "Crosshair".equals(style);

        for (int y = 0; y < px; y++) {
            for (int x = 0; x < px; x++) {
                float dx = x - center;
                float dy = y - center;
                float distance = (float) Math.sqrt(dx * dx + dy * dy);
                boolean visible;
                if (crosshair) {
                    boolean cross = Math.abs(dx) <= lineHalf || Math.abs(dy) <= lineHalf;
                    boolean ring = Math.abs(distance - ringRadius) <= ringWidth;
                    boolean dot = distance <= dotRadius;
                    visible = cross || ring || dot;
                } else {
                    visible = distance <= Math.max(3.0f, px * 0.13f);
                }

                int index = (y * px + x) * 4;
                if (!visible) {
                    rgba[index + 3] = 0;
                    continue;
                }
                if (crosshair) {
                    boolean centerDot = distance <= dotRadius;
                    rgba[index] = (byte) 255;
                    rgba[index + 1] = (byte) (centerDot ? 230 : 196);
                    rgba[index + 2] = (byte) (centerDot ? 96 : 32);
                    rgba[index + 3] = (byte) (centerDot ? 255 : 236);
                } else {
                    rgba[index] = (byte) 255;
                    rgba[index + 1] = (byte) 110;
                    rgba[index + 2] = (byte) 200;
                    rgba[index + 3] = (byte) 255;
                }
            }
        }
        return rgba;
    }

    @Override
    public String getInfo() {
        return style.getValue();
    }
}
