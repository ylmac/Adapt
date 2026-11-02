package com.ylmac.adapt;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.KeyMapping;
import com.ylmac.adapt.input.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.ylmac.adapt.render.surface.SkijaNativeFix;
import com.ylmac.adapt.render.screen.AdaptRuntime;
import com.ylmac.adapt.render.surface.GpuContextHelper;
import com.ylmac.adapt.render.color.WorldColorSampler;
import com.ylmac.adapt.render.hud.WorldLyricOverlay;
import com.ylmac.adapt.render.hud.ModuleListOverlay;
import com.ylmac.adapt.event.bus.EventBus;
import com.ylmac.adapt.module.ModuleManager;
import com.ylmac.adapt.module.ModuleRegistrar;

import java.lang.invoke.MethodHandles;

public class AdaptClient implements ClientModInitializer {
    public static final String MOD_ID = "adaptclient";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static int rightShiftWasDown = 0; // 0=up, 1=down, 2=consumed

    private static int preloadTick = 0;
    private static boolean gpuPreloaded = false;
    private static boolean runtimeWarmed = false;
    private static volatile Thread warmupThread;

    /** 打开界面前等待后台预热完成（通常早已完成，立即返回） */
    public static void joinWarmup() {
        Thread t = warmupThread;
        if (t != null && t.isAlive() && t != Thread.currentThread()) {
            try {
                t.join(8000);
            } catch (InterruptedException ignored) {
            }
        }
    }

    @Override
    public void onInitializeClient() {
        LOGGER.info("[Adapt] Mod loading...");

        SkijaNativeFix.preload();

        // 命令系统：注册全部命令（.toggle/.bind/.config 等，拦截见 ClientPacketListenerMixin）
        com.ylmac.adapt.command.CommandManager.get().init();
        LOGGER.info("[Adapt] Command system initialized ({} commands)", 
                com.ylmac.adapt.command.CommandManager.get().commands.size());

        // 事件系统 + 模块系统初始化
        EventBus.INSTANCE.registerLambdaFactory("com.ylmac.adapt",
                (lookupInMethod, klass) -> (MethodHandles.Lookup) lookupInMethod.invoke(null, klass, MethodHandles.lookup()));
        ModuleRegistrar.registerAll();
        LOGGER.info("[Adapt] EventBus + ModuleManager initialized ({} modules)",
                ModuleManager.INSTANCE.getModules().size());

        // 客户端退出：在渲染线程显式释放 Skija GPU 资源。
        // Skija 未 close 的 GPU 对象由 JVM Cleaner 线程析构 — 那会在非渲染线程上
        // 触碰 Skia 的 GrResourceCache（非线程安全）→ SIGTRAP 崩溃
        // （实测崩溃报告：Thread 40 "Java: Cleaner-0" ← GrVkTexture::onRelease）。
        try {
            net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.CLIENT_STOPPING
                    .register(client -> {
                        try {
                            com.ylmac.adapt.render.hud.BackdropBlurCapture.dispose();
                        } catch (Throwable t) {
                            LOGGER.warn("[Adapt] GPU dispose failed: {}", t.toString());
                        }
                    });
        } catch (Throwable t) {
            LOGGER.warn("[Adapt] ClientStopping hook registration failed: {}", t.toString());
        }

        // 注册按键绑定到 Fabric API（确保 consumeClick 正常工作）
        try {
            Class<?> helper = Class.forName("net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper");
            java.lang.reflect.Method reg = helper.getMethod("registerKeyBinding", KeyMapping.class);
            reg.invoke(null, new KeyMapping("key.adaptclient.open_gui", GLFW.GLFW_KEY_RIGHT_SHIFT, KeyMapping.Category.MISC));
            LOGGER.info("[Adapt] Key binding registered via Fabric API");
        } catch (Throwable t) {
            LOGGER.warn("[Adapt] Fabric key binding registration failed (will use GLFW direct): {}", t.toString());
        }

        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            if (warmupThread == null) {
                warmupThread = new Thread(AdaptRuntime::warmup, "adapt-runtime-warmup");
                warmupThread.setDaemon(true);
                warmupThread.start();
            }
            if (gpuPreloaded) {
                // VkImage 必须创建于渲染线程：GPU 就绪后主线程预创建 surface
                if (!runtimeWarmed) {
                    runtimeWarmed = true;
                    AdaptRuntime.precreateGpuSurface();
                }
                return;
            }
            preloadTick++;
            if (preloadTick >= 40) {
                gpuPreloaded = true;
                LOGGER.info("[Adapt] Background GPU preload starting...");
                try {
                    GpuContextHelper.getOrCreateContext();
                    LOGGER.info("[Adapt] GPU preload complete: {} ({})",
                        GpuContextHelper.deviceName, GpuContextHelper.backendName);
                } catch (Throwable t) {
                    LOGGER.warn("[Adapt] GPU preload failed (will retry on open): {}", t.toString());
                }
                // Compose 字体 / Vulkan scene 预热：避免首次打开 UI 卡顿
                try {
                    AdaptRuntime.ensureFonts();
                    Class<?> fonts = Class.forName("com.ylmac.adapt.compose.theme.AdaptFonts");
                    fonts.getMethod("warm").invoke(null);
                } catch (Throwable t) {
                    LOGGER.warn("[Adapt] AdaptFonts warm failed: {}", t.toString());
                }
                try {
                    Class<?> skia = Class.forName("com.ylmac.adapt.compose.skia.ComposeSkiaContext");
                    skia.getMethod("tryInit").invoke(null);
                } catch (Throwable t) {
                    LOGGER.warn("[Adapt] ComposeSkiaContext warm failed: {}", t.toString());
                }
                // Compose ClickGUI / Music session 预热：建 Vulkan RT + 空 scene 首帧
                try {
                    Class<?> sess = Class.forName("com.ylmac.adapt.compose.host.AdaptComposeSession");
                    sess.getMethod("prewarm", String.class, int.class, int.class, float.class)
                            .invoke(null, "clickgui", 960, 540, 1.0f);
                    sess.getMethod("prewarm", String.class, int.class, int.class, float.class)
                            .invoke(null, "adapt_main", 960, 540, 1.0f);
                } catch (Throwable t) {
                    LOGGER.warn("[Adapt] AdaptComposeSession prewarm failed: {}", t.toString());
                }
            }
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // 检测 Right Shift 按键（多种方式兼容）
            long win = 0;
            try { win = client.getWindow().handle(); } catch (Exception ignored) {}
            boolean shiftDown = false;
            if (win != 0) {
                shiftDown = GLFW.glfwGetKey(win, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;
            }
            if (shiftDown && rightShiftWasDown == 0) {
                rightShiftWasDown = 1; // just pressed
            } else if (!shiftDown) {
                rightShiftWasDown = 0; // released
            }
            if (rightShiftWasDown == 1) {
                rightShiftWasDown = 2; // consumed
                LOGGER.info("[Adapt] Right Shift pressed! screen={}", client.gui.screen());
                if (client.gui.screen() == null) {
                    try {
                        net.minecraft.client.gui.screens.Screen s =
                                com.ylmac.adapt.module.render.InterfaceModule.createClickGui();
                        client.gui.setScreen(s);
                        LOGGER.info("[Adapt] ClickGuiScreen opened ({})", s.getClass().getSimpleName());
                    } catch (Throwable t) {
                        LOGGER.error("[Adapt] Failed to open ClickGuiScreen", t);
                    }
                }
            }

            // 模块按键绑定已统一由 ModuleManager 事件分发处理（KeyPressEvent/MousePressEvent，
            // 见 ModuleManager.dispatchKeyBind）。此处的 tick 轮询与 .bind 同步到
            // realModule.keyBind 会构成双分发：按一次键被 toggle 两次（净效果=没按）。
            // 本循环已删除，绑定只走事件驱动一条路径（单数据源：module.getKeyBind）。

            // 世界画面颜色采样 → 莫奈取色主题（内部按 settings.monetEnabled + 3s 节流自行判断）
            WorldColorSampler.get().onTick(client);
        });

        // 世界内歌词（屏幕右下方 HUD 层，复用桌面歌词渲染）
        WorldLyricOverlay.get().register();

 // 模块列表 HUD
        ModuleListOverlay.get().register();

 // 音乐小组件+ HUD 位置编辑器
        // 顺序：编辑器最后注册 → 它的边框画在元素之上；addLast 的顺序就是渲染顺序。
        com.ylmac.adapt.render.hud.MusicWidgetOverlay.get().registerHud();
        com.ylmac.adapt.render.hud.ClockWidgetOverlay.get().registerHud();
        com.ylmac.adapt.render.hud.ScoreBoardOverlay.get().registerHud();
        com.ylmac.adapt.render.hud.ChatOverlay.get().registerHud();
        com.ylmac.adapt.render.hud.HudEditor.get().init();
        com.ylmac.adapt.render.hud.HudEditor.get()
                .register(com.ylmac.adapt.render.hud.MusicWidgetOverlay.get());
        com.ylmac.adapt.render.hud.HudEditor.get()
                .register(com.ylmac.adapt.render.hud.ClockWidgetOverlay.get());
        com.ylmac.adapt.render.hud.HudEditor.get()
                .register(com.ylmac.adapt.render.hud.ScoreBoardOverlay.get());
        com.ylmac.adapt.render.hud.HudEditor.get()
                .register(com.ylmac.adapt.render.hud.ChatOverlay.get());
        com.ylmac.adapt.render.hud.PvpHudOverlay.registerAll();
        com.ylmac.adapt.module.movement.ToggleSprint.INSTANCE.registerHud();
        try {
            net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.addLast(
                    net.minecraft.resources.Identifier.parse("adaptclient:hudeditor"),
                    new net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement() {
                        @Override
                        public void extractRenderState(net.minecraft.client.gui.GuiGraphicsExtractor g,
                                                       net.minecraft.client.DeltaTracker dt) {
                            com.ylmac.adapt.render.hud.HudEditor.get().renderFrames(g);
                        }
                    });
        } catch (Throwable t) {
            LOGGER.warn("[Adapt] HUD editor registration failed: {}", t.toString());
        }

 // ESP / BedPlates 2D HUD
        try {
            net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.addLast(
                    net.minecraft.resources.Identifier.parse("adaptclient:esp"),
                    com.ylmac.adapt.module.render.ESP.get());
            net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.addLast(
                    net.minecraft.resources.Identifier.parse("adaptclient:bedplates"),
                    com.ylmac.adapt.module.render.BedPlates.get());
        } catch (Throwable t) {
            LOGGER.warn("[Adapt] ESP/BedPlates HUD registration failed: {}", t.toString());
        }

        LOGGER.info("[Adapt] Mod loaded. Press Right Shift to open ClickGUI.");
    }
}