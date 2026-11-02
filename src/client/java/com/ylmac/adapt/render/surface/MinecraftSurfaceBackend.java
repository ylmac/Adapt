package com.ylmac.adapt.render.surface;

import com.mojang.blaze3d.platform.NativeImage;
import io.github.humbleui.skija.*;
import io.github.humbleui.types.RRect;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;
import com.ylmac.adapt.AdaptClient;

public final class MinecraftSurfaceBackend {
    private static final AtomicLong COUNTER = new AtomicLong();

    private static final int VK_FORMAT_R8G8B8A8_UNORM = 37;
    private static final int VK_IMAGE_TILING_OPTIMAL = 0;
    private static final int VK_IMAGE_LAYOUT_UNDEFINED = 0;
    private static final int VK_IMAGE_USAGE_FLAGS = 0x10 | 0x04 | 0x02 | 0x01;

    private final String name;
    private int width, height;
    private float guiScale;

    private DirectContext gpuContext;
    private BackendRenderTarget backendRT;
    private Surface gpuSurface;
    private GpuSharedTexture gpuSharedTexture;

    /** 设备像素尺寸（两条后端路径共用；ensureDeviceScale 判重用） */
    private int devWidth, devHeight;

    // OpenGL 渲染对象
    private int glTextureId;
    private int glFboId;

    private Surface cpuSurface;
    private NativeImage nativeImage;
    private DynamicTexture mcTexture;

    private Identifier textureId;
    private ByteBuffer pixelBuffer;
    private Pixmap uploadPixmap;
    private int saveCount = 0;
    private boolean useGpu = false;
    private boolean isGL = false;
    private int clearColor = 0x00000000;
    /** scale 之后、把画布交给调用方之前的钩子（流体背景等，逻辑坐标）。 */
    private java.util.function.Consumer<Canvas> afterAcquire;

    public MinecraftSurfaceBackend(String name, int logicalWidth, int logicalHeight, float guiScale) {
        this.name = name;
        this.width = logicalWidth;
        this.height = logicalHeight;
        this.guiScale = guiScale;

        init((int)(logicalWidth * guiScale), (int)(logicalHeight * guiScale));
    }

    public void init(int displayWidth, int displayHeight) {
        disposeInternal();
        devWidth = displayWidth;
        devHeight = displayHeight;

        try {
            gpuContext = GpuContextHelper.getOrCreateContext();
            if (gpuContext == null) {
                throw new RuntimeException("DirectContext creation failed: " + GpuContextHelper.gpuStatus);
            }

            if (GpuContextHelper.isGL()) {
                // OpenGL 路径：GL 纹理 + FBO，Skia 渲染到 FBO，present 时回读到 NativeImage
                isGL = true;
                initGL(displayWidth, displayHeight);
            } else {
                // Vulkan 路径：GpuSharedTexture 零拷贝
                isGL = false;
                gpuSharedTexture = new GpuSharedTexture(displayWidth, displayHeight);
                long vkImage = gpuSharedTexture.getVkImage();

                backendRT = BackendRenderTarget.makeVulkan(
                    displayWidth, displayHeight,
                    vkImage,
                    VK_IMAGE_TILING_OPTIMAL,
                    VK_IMAGE_LAYOUT_UNDEFINED,
                    VK_FORMAT_R8G8B8A8_UNORM,
                    VK_IMAGE_USAGE_FLAGS,
                    1, 1
                );

                gpuSurface = Surface.wrapBackendRenderTarget(
                    gpuContext, backendRT,
                    SurfaceOrigin.TOP_LEFT,
                    ColorType.RGBA_8888,
                    null
                );

                if (gpuSurface == null) {
                    throw new RuntimeException("Surface.wrapBackendRenderTarget returned null");
                }

                textureId = Identifier.parse("adaptclient:" + name + "_" + COUNTER.incrementAndGet());
                Minecraft.getInstance().getTextureManager().register(textureId, gpuSharedTexture);
            }

            useGpu = true;
            AdaptClient.LOGGER.info("[MinecraftSurfaceBackend] GPU {} active", isGL ? "OpenGL" : "Vulkan");
        } catch (Throwable e) {
            AdaptClient.LOGGER.warn("[MinecraftSurfaceBackend] GPU failed, fallback to CPU: {}", e.toString());
            cleanupGpu();
            initCpu(displayWidth, displayHeight);
        }
    }

    private void initGL(int displayWidth, int displayHeight) {
        // 创建 GL 纹理
        int[] texId = new int[1];
        GL11.glGenTextures(texId);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texId[0]);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8,
            displayWidth, displayHeight, 0,
            GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        // 线性采样：surface 2x 超采样缩小显示时平滑（WorldLyric 同款，任意 GUI scale 清晰）
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);

        // 创建 FBO
        int[] fboId = new int[1];
        GL30.glGenFramebuffers(fboId);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fboId[0]);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
            GL11.GL_TEXTURE_2D, texId[0], 0);

        glTextureId = texId[0];
        glFboId = fboId[0];

        backendRT = BackendRenderTarget.makeGL(displayWidth, displayHeight, 0, 8, fboId[0], GL11.GL_RGBA8);

        gpuSurface = Surface.wrapBackendRenderTarget(
            gpuContext, backendRT,
            SurfaceOrigin.TOP_LEFT,
            ColorType.RGBA_8888,
            null
        );

        if (gpuSurface == null) {
            throw new RuntimeException("GL Surface.wrapBackendRenderTarget returned null");
        }

        // 用 NativeImage + DynamicTexture 把渲染结果传给 Minecraft
        nativeImage = new NativeImage(displayWidth, displayHeight, true);
        mcTexture = new DynamicTexture(() -> "qplayer-surface", nativeImage);
        textureId = Identifier.parse("adaptclient:" + name + "_" + COUNTER.incrementAndGet());
        Minecraft.getInstance().getTextureManager().register(textureId, mcTexture);
    }

    private void initCpu(int displayWidth, int displayHeight) {
        useGpu = false;
        isGL = false;
        ImageInfo imageInfo = new ImageInfo(displayWidth, displayHeight,
            ColorType.RGBA_8888, ColorAlphaType.UNPREMUL);

        nativeImage = new NativeImage(displayWidth, displayHeight, true);
        mcTexture = new DynamicTexture(() -> "qplayer-surface", nativeImage);
        textureId = Identifier.parse("adaptclient:" + name + "_" + COUNTER.incrementAndGet());
        Minecraft.getInstance().getTextureManager().register(textureId, mcTexture);

        pixelBuffer = MemoryUtil.memByteBuffer(nativeImage.getPointer(), displayWidth * displayHeight * 4);
        uploadPixmap = Pixmap.make(imageInfo, pixelBuffer, displayWidth * 4);
        cpuSurface = Surface.makeRasterDirect(uploadPixmap);
        if (cpuSurface == null) {
            cpuSurface = Surface.makeRasterN32Premul(displayWidth, displayHeight);
        }
    }

    private void cleanupGpu() {
        if (gpuSurface != null) { try { gpuSurface.close(); } catch (Exception ignored) {} gpuSurface = null; }
        if (backendRT != null) { try { backendRT.close(); } catch (Exception ignored) {} backendRT = null; }
        if (gpuSharedTexture != null) { try { gpuSharedTexture.close(); } catch (Exception ignored) {} gpuSharedTexture = null; }
        // 清理 GL 对象
        if (glFboId != 0) {
            try { GL30.glDeleteFramebuffers(glFboId); } catch (Exception ignored) {}
            glFboId = 0;
        }
        if (glTextureId != 0) {
            try { GL11.glDeleteTextures(glTextureId); } catch (Exception ignored) {}
            glTextureId = 0;
        }
    }

    private void disposeInternal() {
        cleanupGpu();
        if (cpuSurface != null) { try { cpuSurface.close(); } catch (Exception ignored) {} cpuSurface = null; }
        if (uploadPixmap != null) { try { uploadPixmap.close(); } catch (Exception ignored) {} uploadPixmap = null; }
        pixelBuffer = null;
        if (mcTexture != null) {
            try { Minecraft.getInstance().getTextureManager().release(textureId); }
            catch (Exception e) { try { mcTexture.close(); } catch (Exception ignored) {} }
            mcTexture = null;
        }
        if (nativeImage != null) {
            try { nativeImage.close(); } catch (Exception ignored) {}
            nativeImage = null;
        }
        if (gpuSharedTexture != null) {
            try { Minecraft.getInstance().getTextureManager().release(textureId); }
            catch (Exception e) { try { gpuSharedTexture.close(); } catch (Exception ignored) {} }
            gpuSharedTexture = null;
        }
    }

    public void setClearColor(int argb) { this.clearColor = argb; }

    public void setAfterAcquire(java.util.function.Consumer<Canvas> hook) {
        this.afterAcquire = hook;
    }

    public Canvas acquireCanvas() {
        Surface surface = useGpu ? gpuSurface : cpuSurface;
        if (surface == null) return null;
        Canvas canvas = surface.getCanvas();
        // 强制回到根矩阵，避免上一帧 save/restore 不对称留下残留 scale。
        canvas.restoreToCount(1);
        saveCount = canvas.save();
        canvas.clear(clearColor);
        canvas.scale(guiScale, guiScale);
        if (afterAcquire != null) {
            try { afterAcquire.accept(canvas); }
            catch (Throwable t) {
                AdaptClient.LOGGER.warn("[MinecraftSurfaceBackend] afterAcquire: {}", t.toString());
            }
        }
        return canvas;
    }

    /** 当前表面的快照（诊断/截图用；调用方负责 close）。 */
    public io.github.humbleui.skija.Image snapshot() {
        io.github.humbleui.skija.Surface surface = useGpu ? gpuSurface : cpuSurface;
        if (surface == null) return null;
        return surface.makeImageSnapshot();
    }

    public Canvas getRawCanvas() {
        Surface surface = useGpu ? gpuSurface : cpuSurface;
        if (surface == null) return null;
        return surface.getCanvas();
    }

    public void present() {
        if (useGpu) {
            if (gpuSurface == null) return;
            gpuSurface.getCanvas().restoreToCount(saveCount);
            if (isGL) {
                // OpenGL：从 GPU 回读到 NativeImage，然后上传到 Minecraft
                readGlToNativeImage();
                mcTexture.upload();
            } else {
                // Vulkan：零拷贝，非阻塞提交（不等待 GPU 完成，避免渲染线程被拖住）
                gpuContext.flushAndSubmit(false);
            }
        } else {
            if (cpuSurface == null) return;
            cpuSurface.getCanvas().restoreToCount(saveCount);
            mcTexture.upload();
        }
    }

    private void readGlToNativeImage() {
        if (nativeImage == null || glFboId == 0) return;
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, glFboId);
        GL11.glReadPixels(0, 0, devWidth, devHeight,
            GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, nativeImage.getPointer());
    }

    public void resize(int logicalWidth, int logicalHeight) {
        this.width = logicalWidth;
        this.height = logicalHeight;
        dispose();
        init((int)(logicalWidth * guiScale), (int)(logicalHeight * guiScale));
    }

    public void setGuiScale(float gs) {
        this.guiScale = Math.max(1.0f, gs);
        resize(width, height);
    }

    /**
 * 按<b>原生设备像素</b>对齐 surface 尺寸：物理纹理 = 逻辑尺寸 × deviceScale，
 * 即它在屏幕上真实占用的设备像素数 — 1:1，既不超采样也不缩放重采样。
 * <p>
 * 这是 SkikoBackend（Mizulune）的做法：它的 surface 直接是窗口物理分辨率，
 * canvas 只 scale(guiScale)，画多少设备像素就是多少，所以抗锯齿边缘永远不会被
 * 二次重采样（也就不会有黑边/发虚）。我们原来的固定 2× 逻辑倍率在大窗口下
 * 其实只有屏幕分辨率的 0.6 倍（放大 → 发虚），在小窗口下又超采样（浪费）。
 * <p>
 * deviceScale 的算法：{@code (显示尺寸 GUI px / 逻辑尺寸) × 窗口 GUI scale}。
 * 只在有效尺寸变化时重建（每帧调用开销可忽略）。
 */
    public void ensureDeviceScale(float deviceScale) {
        float s = Math.max(0.05f, deviceScale);
        int dw = Math.max(1, Math.round(width * s));
        int dh = Math.max(1, Math.round(height * s));
        if (Math.abs(this.guiScale - s) < 1e-4f && devWidth == dw && devHeight == dh
                && (gpuSurface != null || cpuSurface != null)) {
            return;
        }
        this.guiScale = s;
        disposeInternal();
        init(dw, dh);
    }

    public void dispose() {
        disposeInternal();
    }

    public int width() { return width; }
    public int height() { return height; }
    /**
 * 贴这个表面该用的 GUI 管线。
 * <p>
 * Skia 的 GPU 表面**恒为预乘 alpha**（{@code wrapBackendRenderTarget} 没有 alpha 类型
 * 参数，Skia 固定 kPremul）。而 MC 的 {@code GUI_TEXTURED} 是直通 alpha 混合
 * （{@code BlendFunction.TRANSLUCENT} = SRC_ALPHA / ONE_MINUS_SRC_ALPHA）— 半透明边缘
 * 的 RGB 会被再乘一次 alpha，于是所有抗锯齿边缘发暗，就是用户看到的"奇怪的黑边"。
 * 26.2 为此专门提供了 {@code GUI_TEXTURED_PREMULTIPLIED_ALPHA}
 * （ONE / ONE_MINUS_SRC_ALPHA），预乘内容必须用它。
 * <p>
 * CPU 回退路径的位图是 UNPREMUL（见 {@link #initCpu}），仍走 {@code GUI_TEXTURED}。
 */
    public com.mojang.renderpearl.api.pipeline.RenderPipeline blitPipeline() {
        return useGpu
                ? net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED_PREMULTIPLIED_ALPHA
                : net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED;
    }

    public Identifier getTextureId() { return textureId; }
    /** 实际 GPU/CPU 纹理宽（ensureDeviceScale 用 Math.round，勿再 width*guiScale 截断）。 */
    public int getDisplayWidth() { return Math.max(1, devWidth); }
    public int getDisplayHeight() { return Math.max(1, devHeight); }
    public float getGuiScale() { return guiScale; }
    public boolean isGpuActive() { return useGpu; }

    public void clearSurface() {
        if (useGpu && gpuSurface != null) {
            gpuSurface.getCanvas().clear(0x00000000);
            gpuContext.flushAndSubmit(false);
        } else if (cpuSurface != null) {
            cpuSurface.getCanvas().clear(0x00000000);
        }
    }

    public void applyRoundedClip(Canvas canvas, float radius) {
        // radius in logical coordinates; convert to display coords via guiScale
        float w = width * guiScale, h = height * guiScale;
        float r = Math.min(radius * guiScale, Math.min(w, h) * 0.5f);
        RRect bounds = RRect.makeLTRB(0f, 0f, w, h, r, r);
        canvas.clipRRect(bounds, ClipMode.INTERSECT, true);
    }

    public DirectContext recordingContext() {
        if (useGpu && gpuContext != null) {
            return gpuContext;
        }
        return null;
    }
}