package com.ylmac.adapt.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.vulkan.VulkanGpuTexture;
import io.github.humbleui.skija.BackendRenderTarget;
import io.github.humbleui.skija.DirectContext;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.ImageFilter;
import io.github.humbleui.skija.ImageInfo;
import io.github.humbleui.skija.FilterTileMode;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.SamplingMode;
import io.github.humbleui.skija.Surface;
import io.github.humbleui.skija.SurfaceOrigin;
import io.github.humbleui.skija.ColorType;
import io.github.humbleui.types.Rect;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import com.ylmac.adapt.QplayerModClient;

/**
 * GPU-only 帧缓冲截取 + 模糊（无 CPU 回读）。
 *
 * 流程：
 * 1. 从 Minecraft RenderTarget 获取颜色纹理（GPU）
 * 2. CommandEncoder.copyTextureToTexture → GPU 纹理间复制（不经过 CPU）
 * 3. 包装为 Skija Surface（wrapBackendRenderTarget）
 * 4. makeImageSnapshot → GPU Image
 * 5. 降采样到 1/4 分辨率 + ImageFilter.makeBlur → GPU 高斯模糊
 *
 * 模糊在 1/4 分辨率执行：填充量约为全分辨率的 1/16，
 * 毛玻璃视觉上无差别（这也是 iOS/主流实现的做法）。
 */
public final class BackdropBlurCapture {

    /** 模糊执行分辨率的降采样倍数（4 = 1/4 分辨率） */
    private static final int BLUR_DOWNSCALE = 4;

    /**
     * 每帧缓存：同一帧内所有 HUD 元素（聊天/积分榜/音乐/时钟）共用一份模糊结果。
     * <p>
     * 每次 {@link #getBlurredImage} 都要新建一个包装 captureTexture 的 Vulkan
     * Surface 再 snapshot（重资源操作）+ 重画一遍模糊。之前 4 个 HUD 元素各自调用
     * → 每帧 4~5 次重建 = 明显掉帧（用户："模糊照片非常卡，迷你播放器都不会卡"——
     * 迷你播放器只在界面打开时每帧调一次）。
     * <p>
     * 约定：返回的 Image **由本类持有，调用方不要 close**。
     */
    private static int frameStamp = 0;
    /** 本帧的模糊图（按半径分槽，同一帧内不同元素可各取所需，帧边界统一释放） */
    private static final java.util.HashMap<Integer, Image> blurCache = new java.util.HashMap<>();
    /** blurCache 属于哪一帧；与 frameStamp 不等时说明缓存过期 */
    private static int blurCacheStamp = -1;

    private static GpuTexture captureTexture;
    private static int captureW = 0, captureH = 0;
    /** 复用的小尺寸模糊 Surface（1/4 分辨率） */
    private static Surface blurSurface;

    private BackdropBlurCapture() {}

    /**
     * 截取 Minecraft 帧缓冲到 GPU 纹理（无 CPU 回读）。
     * 在 QML 渲染前调用。
     */
    public static void captureFrame() {
        frameStamp++;          // 新的一帧 → 缓存的模糊作废
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.gameRenderer == null) return;
            RenderTarget mainRT = mc.gameRenderer.mainRenderTarget();
            if (mainRT == null || mainRT.width <= 0 || mainRT.height <= 0) return;

            GpuTexture srcTexture = mainRT.getColorTexture();
            if (srcTexture == null) return;

            int w = mainRT.width;
            int h = mainRT.height;

            // 日志：打印帧缓冲分辨率
            if (w != captureW || h != captureH) {
                QplayerModClient.LOGGER.info("[QPlayer] BackdropBlurCapture: framebuffer size {}x{}", w, h);
            }

            // 创建/复用目标纹理
            if (captureTexture == null || captureW != w || captureH != h) {
                if (captureTexture != null) { captureTexture.close(); captureTexture = null; }

                int usage = GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_SRC;
                captureTexture = RenderSystem.getDevice().createTexture(
                        "qplayer-backdrop", usage, GpuFormat.RGBA8_UNORM, w, h, 1, 1);
                captureW = w;
                captureH = h;
            }

            // GPU 纹理间复制（不经过 CPU）
            // copyTextureToTexture(source, dest, mipLevel, destX, destY, sourceX, sourceY, width, height)
            var enc = RenderSystem.getDevice().createCommandEncoder();
            enc.copyTextureToTexture(srcTexture, captureTexture, 0, 0, 0, 0, 0, w, h);
            enc.submit();

            // 刷新 Skija（共享 Vulkan/GL 队列，确保复制完成）。
            // 用非阻塞 flush：copy 指令与 Skia 读取在同一队列上按提交顺序执行，
            // present() 时才真正读 captureTexture，此时 copy 必然已完成。
            DirectContext ctx = GpuContextHelper.getOrCreateContext();
            if (ctx != null) {
                ctx.flushAndSubmit(false);
            }
        } catch (Throwable t) {
            QplayerModClient.LOGGER.error("[QPlayer] BackdropBlurCapture.captureFrame failed: {}", t.toString());
        }
    }

    /**
     * 获取模糊后的帧缓冲图像（GPU Image，无 CPU 回读）。在渲染线程调用。
     * <p>
     * 返回的 Image 由本类持有，**调用方不要 close**；帧边界（下一次
     * {@link #captureFrame()}）统一在渲染线程释放。
     * <p>
     * 按半径分槽缓存：同一帧里积分榜/聊天用 24、QML 界面用 8、模块列表用 2×
     * 各自设置值 —— 单槽缓存会让后一个请求把前一个还在被绘制的 Image close 掉。
     */
    public static Image getBlurredImage(float blurRadius) {
        if (captureTexture == null || captureW <= 0 || captureH <= 0) return null;
        // 帧变了 → 上一帧的缓存全部失效（此刻渲染线程安全，显式 close 而不是留给 GC：
        // Skija 的 Cleaner 线程析构 GPU Image 时走 GrVkTexture::onRelease，
        // 和渲染线程的 flushAndSubmit 并发访问 GrResourceCache 就是 SIGTRAP）
        if (blurCacheStamp != frameStamp) {
            closeBlurCache();
            blurCacheStamp = frameStamp;
        }
        int key = Math.round(blurRadius * 100f);
        Image cached = blurCache.get(key);
        if (cached != null) return cached;
        Image result = buildBlurredImage(blurRadius);
        if (result != null) {
            dumpOnce(result);
            blurCache.put(key, result);
        }
        return result;
    }

    /** 释放本帧全部模糊图（渲染线程）。 */
    private static void closeBlurCache() {
        for (Image img : blurCache.values()) {
            try { img.close(); } catch (Throwable ignored) { }
        }
        blurCache.clear();
    }

    /** 一次性诊断：把模糊结果写盘（看模糊到底有没有内容、内容对不对）。 */
    private static boolean dumped;

    private static void dumpOnce(Image blurred) {
        if (dumped || blurred == null) return;
        dumped = true;
        try {
            java.nio.file.Path dir = java.nio.file.Path.of(System.getProperty("user.home"), ".adapt-blur");
            java.nio.file.Files.createDirectories(dir);
            var d = blurred.encodeToData(io.github.humbleui.skija.EncodedImageFormat.PNG, 100);
            if (d != null) {
                java.nio.file.Files.write(dir.resolve("blurred.png"), d.getBytes());
                d.close();
                QplayerModClient.LOGGER.info("[QPlayer] blur debug: wrote {} ({}x{})",
                        dir.resolve("blurred.png"), blurred.getWidth(), blurred.getHeight());
            }
        } catch (Throwable t) {
            QplayerModClient.LOGGER.warn("[QPlayer] blur debug dump failed: {}", t.toString());
        }
    }

    private static Image buildBlurredImage(float blurRadius) {

        try {
            DirectContext ctx = GpuContextHelper.getOrCreateContext();
            if (ctx == null) return null;

            Image snapshot = null;
            if (captureTexture instanceof VulkanGpuTexture) {
                // Vulkan 路径：包装 GPU 纹理，零拷贝快照
                long vkImage = ((VulkanGpuTexture) captureTexture).vkImage();
                // ⚠ layout 必须是 **GENERAL(1)**，不能是 UNDEFINED(0)：
                //   MC 的 VulkanCommandEncoder.copyTextureToTexture 直接 vkCmdCopyImage、
                //   **不做 layout 过渡**，纹理停在 MC 跟踪的布局里。声明 UNDEFINED 会让
                //   Skia 做 UNDEFINED→其它 的 barrier —— 按 Vulkan 语义**内容作废**，
                //   于是模糊图全黑/空白（实测：诊断转储 ~/.adapt-blur 根本没生成）。
                BackendRenderTarget brt = BackendRenderTarget.makeVulkan(
                        captureW, captureH, vkImage,
                        0, 1, 37, 0x10 | 0x04 | 0x02 | 0x01, 1, 1);
                Surface freshSurface = Surface.wrapBackendRenderTarget(
                        ctx, brt, SurfaceOrigin.BOTTOM_LEFT, ColorType.RGBA_8888, null);
                if (freshSurface == null) { brt.close(); return null; }
                snapshot = freshSurface.makeImageSnapshot();
                freshSurface.close();
                brt.close();
            } else if (GpuContextHelper.isGL()) {
                // OpenGL 路径：通过反射获取 GL 纹理 ID，包装为 GPU Image
                snapshot = getGlSnapshot(ctx);
            } else {
                return null;
            }
            if (snapshot == null) return null;

            // ⚠ GPU Image 必须显式 close，绝不能留给 GC：Skija 的 Cleaner 线程析构
            //   SkImage_Ganesh 时会走 GrVkTexture::onRelease →
            //   GrResourceCache::notifyARefCntReachedZero，而 Skia 的 GPU 资源缓存不是
            //   线程安全的，和渲染线程的 flushAndSubmit 并发就是 SIGTRAP 崩溃
            //   （崩溃报告：Thread 40 "Java: Cleaner-0" ← GrVkTexture::onRelease）。
            //   drawImageRect 已经把图记录进 op 并持有自己的引用，此处 close 只是
            //   释放 Java 侧句柄引用，GPU 资源存活到 op 被 flush。
            try {
                // 降采样到 1/4 分辨率再做模糊：填充量降到全分辨率模糊的 1/16
                int blurW = Math.max(16, captureW / BLUR_DOWNSCALE);
                int blurH = Math.max(16, captureH / BLUR_DOWNSCALE);
                float sigma = blurRadius / 2.0f / BLUR_DOWNSCALE;

                // 复用模糊 Surface
                if (blurSurface == null || blurW != blurSurface.getWidth() || blurH != blurSurface.getHeight()) {
                    if (blurSurface != null) blurSurface.close();
                    ImageInfo info = ImageInfo.makeN32Premul(blurW, blurH);
                    blurSurface = Surface.makeRenderTarget(ctx, false, info);
                    if (blurSurface == null) {
                        blurSurface = null;
                        return null;
                    }
                }

                // 降采样 + 高斯模糊一步完成
                ImageFilter blurFilter = ImageFilter.makeBlur(sigma, sigma, FilterTileMode.CLAMP);
                Paint paint = new Paint();
                paint.setImageFilter(blurFilter);
                blurSurface.getCanvas().drawImageRect(
                    snapshot,
                    Rect.makeXYWH(0, 0, captureW, captureH),
                    Rect.makeXYWH(0, 0, blurW, blurH),
                    SamplingMode.LINEAR, paint, false);
                blurFilter.close();
                paint.close();

                return blurSurface.makeImageSnapshot();
            } finally {
                snapshot.close();
            }
        } catch (Throwable t) {
            QplayerModClient.LOGGER.error("[QPlayer] BackdropBlurCapture.getBlurredImage failed: {}", t.toString());
            return null;
        }
    }

    /** OpenGL 回读路径：读回帧缓冲到 CPU Image，避免 GPU 纹理包装导致的递归崩溃 */
    private static Image getGlSnapshot(DirectContext ctx) {
        try {
            // 通过反射获取 GL 纹理 ID
            int glTexId = 0;
            Class<?> cls = captureTexture.getClass();
            for (String name : new String[]{"glTextureId", "textureId", "id", "glId", "texture"}) {
                try {
                    java.lang.reflect.Field f = cls.getDeclaredField(name);
                    f.setAccessible(true);
                    Object val = f.get(captureTexture);
                    if (val instanceof Number) { glTexId = ((Number) val).intValue(); break; }
                } catch (Exception ignored) {}
            }
            if (glTexId == 0) {
                for (String name : new String[]{"glTextureId", "getId", "textureId", "id"}) {
                    try {
                        java.lang.reflect.Method m = cls.getMethod(name);
                        Object val = m.invoke(captureTexture);
                        if (val instanceof Number) { glTexId = ((Number) val).intValue(); break; }
                    } catch (Exception ignored) {}
                }
            }
            if (glTexId == 0) return null;

            // 创建临时 FBO 读回像素到 CPU
            int[] fboId = new int[1];
            GL30.glGenFramebuffers(fboId);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, fboId[0]);
            GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                GL11.GL_TEXTURE_2D, glTexId, 0);
            int status = GL30.glCheckFramebufferStatus(GL30.GL_READ_FRAMEBUFFER);
            if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
                GL30.glDeleteFramebuffers(fboId[0]);
                return null;
            }

            // 读回像素到 CPU ByteBuffer
            java.nio.ByteBuffer pixels = java.nio.ByteBuffer.allocateDirect(captureW * captureH * 4);
            GL11.glReadPixels(0, 0, captureW, captureH, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
            GL30.glDeleteFramebuffers(fboId[0]);

            // 从 CPU 像素创建 Skia Image
            ImageInfo info = ImageInfo.makeN32Premul(captureW, captureH);
            long pixelsPtr = org.lwjgl.system.MemoryUtil.memAddress(pixels);
            Surface cpuSurface = Surface.makeRasterDirect(info, pixelsPtr, captureW * 4);
            if (cpuSurface == null) {
                cpuSurface = Surface.makeRasterN32Premul(captureW, captureH);
                if (cpuSurface == null) return null;
            }
            Image snap = cpuSurface.makeImageSnapshot();
            cpuSurface.close();
            return snap;
        } catch (Throwable t) {
            QplayerModClient.LOGGER.warn("[QPlayer] BackdropBlurCapture GL snapshot failed: {}", t.toString());
            return null;
        }
    }

    public static void dispose() {
        closeBlurCache();
        blurCacheStamp = -1;
        if (captureTexture != null) { captureTexture.close(); captureTexture = null; }
        if (blurSurface != null) { blurSurface.close(); blurSurface = null; }
        captureW = 0;
        captureH = 0;
    }
}
