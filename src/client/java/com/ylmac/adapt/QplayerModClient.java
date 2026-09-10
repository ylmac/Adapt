package com.ylmac.adapt;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.ylmac.adapt.render.SkijaNativeFix;
import com.ylmac.adapt.render.QplayerScreen;
import com.ylmac.adapt.render.clickgui.ClickGuiScreen;
import com.ylmac.adapt.render.GpuContextHelper;
import com.ylmac.adapt.render.WorldColorSampler;
import com.ylmac.adapt.render.WorldLyricOverlay;
import com.ylmac.adapt.render.ModuleListOverlay;
import com.ylmac.adapt.ui.QmlCacheManager;
import com.ylmac.adapt.features.event.bus.EventBus;
import com.ylmac.adapt.features.module.ModuleManager;
import com.ylmac.adapt.features.module.impl.player.NoFall;
import com.ylmac.adapt.features.module.impl.movement.Sprint;

import java.lang.invoke.MethodHandles;

public class QplayerModClient implements ClientModInitializer {
    public static final String MOD_ID = "adaptclient";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static int rightShiftWasDown = 0; // 0=up, 1=down, 2=consumed

    /** 模块按键绑定边沿检测：client -> (键码 -> 上 tick 是否按下) */
    private static final java.util.Map<Minecraft, java.util.Map<Integer, Boolean>> bindWasDown = new java.util.HashMap<>();

    private static int preloadTick = 0;
    private static boolean gpuPreloaded = false;
    private static boolean qmlPrecompiled = false;
    private static volatile Thread qmlPrecompileThread;

    /** 打开界面前等待后台预编译完成（通常早已完成，立即返回） */
    public static void joinQmlPrecompile() {
        Thread t = qmlPrecompileThread;
        if (t != null && t.isAlive() && t != Thread.currentThread()) {
            try {
                t.join(8000);
            } catch (InterruptedException ignored) {
            }
        }
    }

    @Override
    public void onInitializeClient() {
        LOGGER.info("[QPlayer] Mod loading...");

        SkijaNativeFix.preload();

        // 命令系统：注册全部命令（.toggle/.bind/.config 等，拦截见 ClientPacketListenerMixin）
        com.ylmac.adapt.features.command.CommandManager.get().init();
        LOGGER.info("[QPlayer] Command system initialized ({} commands)", 
                com.ylmac.adapt.features.command.CommandManager.get().commands.size());

        // 事件系统 + 模块系统初始化
        EventBus.INSTANCE.registerLambdaFactory("com.ylmac.adapt",
                (lookupInMethod, klass) -> (MethodHandles.Lookup) lookupInMethod.invoke(null, klass, MethodHandles.lookup()));
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.features.module.impl.render.Fullbright());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.features.module.impl.render.ESP2D());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.features.module.impl.player.SoundFX());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.features.module.impl.player.NoFall());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.features.module.impl.movement.Sprint());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.features.module.impl.combat.AutoClicker());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.features.module.impl.misc.MusicPlayerModule());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.features.module.impl.render.InterfaceModule());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.features.module.impl.render.AppearanceModule());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.features.module.impl.render.ESP());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.features.module.impl.render.BedPlates());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.features.module.impl.render.Halo3D());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.features.module.impl.render.Shaders());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.features.module.impl.render.MusicWidgetModule());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.features.module.impl.render.ClockWidgetModule());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.features.module.impl.render.ScoreBoardModule());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.features.module.impl.render.ChatModule());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.features.module.impl.render.CustomCursor());
        // —— Rise 模块全量注册（Combat/Exploit/Ghost/Movement/Player）——
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.features.module.impl.combat.AntiBot());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.features.module.impl.combat.ComboOneHit());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.features.module.impl.combat.Criticals());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.features.module.impl.combat.Fences());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.features.module.impl.combat.KeepRange());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.features.module.impl.combat.KillAura());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.features.module.impl.combat.LagBreak());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.features.module.impl.combat.LegitReach());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.features.module.impl.combat.Mimic());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.features.module.impl.combat.Piercing());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.features.module.impl.combat.Regen());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.features.module.impl.combat.ThrowableAura());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.features.module.impl.combat.TickBase());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.features.module.impl.combat.Velocity());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.features.module.impl.exploit.BlockTracker());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.features.module.impl.exploit.ConsoleSpammer());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.features.module.impl.exploit.Crasher());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.features.module.impl.exploit.Disabler());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.features.module.impl.exploit.GhostHand());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.features.module.impl.exploit.GodMode());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.features.module.impl.exploit.LightningTracker());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.features.module.impl.exploit.NoRotate());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.features.module.impl.exploit.PingSpoof());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.features.module.impl.exploit.StaffDetector());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.features.module.impl.ghost.AimAssist());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.features.module.impl.ghost.AimBacktrack());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.features.module.impl.ghost.FastPlace());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.features.module.impl.ghost.GuiClicker());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.features.module.impl.ghost.HitBox());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.features.module.impl.ghost.KeepSprint());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.features.module.impl.ghost.LegitScaffold());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.features.module.impl.ghost.ManualKBDisplacement());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.features.module.impl.ghost.NoClickDelay());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.features.module.impl.ghost.Reach());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.features.module.impl.ghost.SafeWalk());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.features.module.impl.ghost.WTap());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.features.module.impl.movement.AutoMLG());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.features.module.impl.movement.AutoStuck());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.features.module.impl.movement.Clipper());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.features.module.impl.movement.Flight());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.features.module.impl.movement.InventoryMove());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.features.module.impl.movement.Jesus());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.features.module.impl.movement.LongJump());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.features.module.impl.movement.NoClip());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.features.module.impl.movement.NoJumpDelay());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.features.module.impl.movement.NoSlow());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.features.module.impl.movement.Phase());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.features.module.impl.movement.PotionExtender());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.features.module.impl.movement.ResourcePackSpoof());
        // 注：Rise 的 ScaffoldState 只是 AutoMLG/Scaffold 用的状态枚举（IDLE/ROTATING/
        // PLACED/PICKUP），不是功能模块 —— 之前被误做成模块注册，已删除（功能与
        // Scaffold 重叠、含死分支、还会和 Scaffold 抢服务器视角旋转包）。
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.features.module.impl.movement.SnapTap());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.features.module.impl.movement.Sneak());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.features.module.impl.movement.Speed());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.features.module.impl.movement.Step());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.features.module.impl.movement.Strafe());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.features.module.impl.movement.Stuck());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.features.module.impl.movement.TargetStrafe());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.features.module.impl.movement.Teleport());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.features.module.impl.movement.TerrainSpeed());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.features.module.impl.movement.WallClimb());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.features.module.impl.player.AntiFireBall());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.features.module.impl.player.AntiSuffocate());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.features.module.impl.player.AntiVoid());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.features.module.impl.player.AutoHead());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.features.module.impl.player.AutoPot());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.features.module.impl.player.AutoTool());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.features.module.impl.player.Blink());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.features.module.impl.player.Breaker());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.features.module.impl.player.Clutch());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.features.module.impl.player.FastBreak());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.features.module.impl.player.FastUse());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.features.module.impl.player.HealthBypass());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.features.module.impl.player.InventorySync());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.features.module.impl.player.Manager());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.features.module.impl.player.PolarDetector());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.features.module.impl.player.Scaffold());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.features.module.impl.player.Stealer());
        for (com.ylmac.adapt.features.module.Module m : ModuleManager.INSTANCE.getModules()) {
            ModuleManager.INSTANCE.syncHud(m); // 同步初始状态到 HUD
        }
        LOGGER.info("[QPlayer] EventBus + ModuleManager initialized ({} modules)",
                ModuleManager.INSTANCE.getModules().size());

        // 客户端退出：在渲染线程显式释放 Skija GPU 资源。
        // Skija 未 close 的 GPU 对象由 JVM Cleaner 线程析构 —— 那会在非渲染线程上
        // 触碰 Skia 的 GrResourceCache（非线程安全）→ SIGTRAP 崩溃
        // （实测崩溃报告：Thread 40 "Java: Cleaner-0" ← GrVkTexture::onRelease）。
        try {
            net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.CLIENT_STOPPING
                    .register(client -> {
                        try {
                            com.ylmac.adapt.render.BackdropBlurCapture.dispose();
                        } catch (Throwable t) {
                            LOGGER.warn("[QPlayer] GPU dispose failed: {}", t.toString());
                        }
                    });
        } catch (Throwable t) {
            LOGGER.warn("[QPlayer] ClientStopping hook registration failed: {}", t.toString());
        }

        // 注册按键绑定到 Fabric API（确保 consumeClick() 正常工作）
        try {
            Class<?> helper = Class.forName("net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper");
            java.lang.reflect.Method reg = helper.getMethod("registerKeyBinding", KeyMapping.class);
            reg.invoke(null, new KeyMapping("key.adaptclient.open_gui", GLFW.GLFW_KEY_RIGHT_SHIFT, KeyMapping.Category.MISC));
            LOGGER.info("[QPlayer] Key binding registered via Fabric API");
        } catch (Throwable t) {
            LOGGER.warn("[QPlayer] Fabric key binding registration failed (will use GLFW direct): {}", t.toString());
        }

        // 预加载 QML 字节码缓存（从 JAR 读取到内存，所有 QML 屏幕共享）
        try {
            QmlCacheManager qcm = new QmlCacheManager();
            qcm.preloadBuiltin(true);
            LOGGER.info("[QPlayer] QML cache preloaded");
        } catch (Throwable t) {
            LOGGER.warn("[QPlayer] QML cache preload failed (will load on demand): {}", t.toString());
        }

        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            // QML 编译（纯 CPU：ANTLR 解析 + ASM 字节码生成）放后台线程，启动不卡；
            // 编译结果落盘到字节码缓存，打开界面时命中缓存 load ~10ms
            if (qmlPrecompileThread == null) {
                qmlPrecompileThread = new Thread(
                        QplayerScreen::precompileQmlBackground, "qplayer-qml-precompile");
                qmlPrecompileThread.setDaemon(true);
                qmlPrecompileThread.start();
            }
            if (gpuPreloaded) {
                // VkImage 必须创建于渲染线程：GPU 就绪后主线程预创建 surface
                if (!qmlPrecompiled) {
                    qmlPrecompiled = true;
                    QplayerScreen.precreateGpuSurface();
                }
                return;
            }
            preloadTick++;
            if (preloadTick >= 40) {
                gpuPreloaded = true;
                LOGGER.info("[QPlayer] Background GPU preload starting...");
                try {
                    GpuContextHelper.getOrCreateContext();
                    LOGGER.info("[QPlayer] GPU preload complete: {} ({})",
                        GpuContextHelper.deviceName, GpuContextHelper.backendName);
                } catch (Throwable t) {
                    LOGGER.warn("[QPlayer] GPU preload failed (will retry on open): {}", t.toString());
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
                LOGGER.info("[QPlayer] Right Shift pressed! screen={}", client.gui.screen());
                if (client.gui.screen() == null) {
                    try {
                        // 样式由 Interface 模块的 "Click GUI" 设置决定（默认 ADAPT，
                        // 可在设置里切 Rise 6.9.5 风格）
                        boolean rise = com.ylmac.adapt.features.module.impl.render.InterfaceModule.isRiseClickGui();
                        net.minecraft.client.gui.screens.Screen s = rise
                                ? new com.ylmac.adapt.render.clickgui.RiseClickGuiScreen()
                                : new ClickGuiScreen();
                        client.gui.setScreen(s);
                        LOGGER.info("[QPlayer] ClickGuiScreen opened ({})", rise ? "Rise" : "Adapt");
                    } catch (Throwable t) {
                        LOGGER.error("[QPlayer] Failed to open ClickGuiScreen", t);
                    }
                }
            }

            // 模块按键绑定已统一由 ModuleManager 事件分发处理（KeyPressEvent/MousePressEvent，
            // 见 ModuleManager.dispatchKeyBind）。此处的 tick 轮询与 .bind 同步到
            // realModule.keyBind 会构成双分发：按一次键被 toggle 两次（净效果=没按）。
            // 本循环已删除，绑定只走事件驱动一条路径（单数据源：module.getKeyBind()）。

            // 世界画面颜色采样 → 莫奈取色主题（内部按 settings.monetEnabled + 3s 节流自行判断）
            WorldColorSampler.get().onTick(client);
        });

        // 世界内歌词（屏幕右下方 HUD 层，复用桌面歌词渲染）
        WorldLyricOverlay.get().register();

        // 模块列表 HUD（Rise 风格，屏幕右上角显示已启用模块）
        ModuleListOverlay.get().register();

        // 音乐小组件（PixelPlayer 小组件复刻）+ HUD 位置编辑器
        // 顺序：编辑器最后注册 → 它的边框画在元素之上；addLast 的顺序就是渲染顺序。
        com.ylmac.adapt.render.MusicWidgetOverlay.get().registerHud();
        com.ylmac.adapt.render.ClockWidgetOverlay.get().registerHud();
        com.ylmac.adapt.render.ScoreBoardOverlay.get().registerHud();
        com.ylmac.adapt.render.ChatOverlay.get().registerHud();
        com.ylmac.adapt.render.HudEditor.get().init();
        com.ylmac.adapt.render.HudEditor.get()
                .register(com.ylmac.adapt.render.MusicWidgetOverlay.get());
        com.ylmac.adapt.render.HudEditor.get()
                .register(com.ylmac.adapt.render.ClockWidgetOverlay.get());
        com.ylmac.adapt.render.HudEditor.get()
                .register(com.ylmac.adapt.render.ScoreBoardOverlay.get());
        com.ylmac.adapt.render.HudEditor.get()
                .register(com.ylmac.adapt.render.ChatOverlay.get());
        try {
            net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.addLast(
                    net.minecraft.resources.Identifier.parse("adaptclient:hudeditor"),
                    new net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement() {
                        @Override
                        public void extractRenderState(net.minecraft.client.gui.GuiGraphicsExtractor g,
                                                       net.minecraft.client.DeltaTracker dt) {
                            com.ylmac.adapt.render.HudEditor.get().renderFrames(g);
                        }
                    });
        } catch (Throwable t) {
            LOGGER.warn("[QPlayer] HUD editor registration failed: {}", t.toString());
        }

        // ESP / BedPlates 2D HUD（LB 移植模块的 2D 渲染：GuiGraphicsExtractor 直接绘制）
        try {
            net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.addLast(
                    net.minecraft.resources.Identifier.parse("adaptclient:esp"),
                    com.ylmac.adapt.features.module.impl.render.ESP.get());
            net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.addLast(
                    net.minecraft.resources.Identifier.parse("adaptclient:bedplates"),
                    com.ylmac.adapt.features.module.impl.render.BedPlates.get());
        } catch (Throwable t) {
            LOGGER.warn("[QPlayer] ESP/BedPlates HUD registration failed: {}", t.toString());
        }

        LOGGER.info("[QPlayer] Mod loaded. Press Right Shift to open ClickGUI.");
    }
}