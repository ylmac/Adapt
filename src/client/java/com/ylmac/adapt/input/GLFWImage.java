package com.ylmac.adapt.input;

import java.nio.ByteBuffer;

/**
 * 最小 stub：原版 26.3 已无 GLFW。CustomCursor 仍构造此对象，但
 * {@link GLFW#glfwCreateCursor} 返回 0（光标功能在 SDL 路径补齐前禁用）。
 */
public final class GLFWImage implements AutoCloseable {
    private int width;
    private int height;
    private ByteBuffer pixels;

    private GLFWImage() {}

    public static GLFWImage malloc() {
        return new GLFWImage();
    }

    public GLFWImage set(int width, int height, ByteBuffer pixels) {
        this.width = width;
        this.height = height;
        this.pixels = pixels;
        return this;
    }

    public int width() { return width; }
    public int height() { return height; }
    public ByteBuffer pixels() { return pixels; }

    @Override
    public void close() {}
}
