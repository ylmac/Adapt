package com.ylmac.adapt.render;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * 世界坐标 → 屏幕坐标投影。
 * <p>
 * 实现对齐 skija-mod 的 QmlWorldOverlay（该实现已验证能在 26.2 正确把 QML
 * 标签贴在苦力怕头顶），关键点：
 * <p>
 * 1. 相机取 {@code mc.gameRenderer.mainCamera()}（Camera 本体），而不是
 *    {@code gameRenderState().levelRenderState.cameraRenderState} —— 后者只在
 *    extract 阶段被填充，在别处读可能是过期的/未 initialized 的。
 * 2. 用 {@code Camera.getViewRotationProjectionMatrix()} 拿到**一个**组合好的
 *    视图-投影矩阵，一次性变换；不要拆成 viewRotationMatrix + projectionMatrix
 *    两步（两个 Matrix4f 的语义/平移分量容易踩坑）。
 * 3. 剔除条件看 **w**（= 到相机的深度），不是 NDC 的 z。w<=0 表示点在相机后方。
 *
 * <p>返回值：x/y 为 GUI 缩放坐标下的屏幕像素；z 为 w（到相机的距离），
 * 调用方可据此做透视缩放（如 skija-mod 的 {@code 5f / w}）。
 */
public final class WorldToScreen {

    private WorldToScreen() {
    }

    /** 将世界坐标投影到屏幕坐标（GUI 缩放坐标）。相机后方/太近返回 null。 */
    public static Vector3f calcWorld2Screen(Vec3 pos) {
        Minecraft mc = Minecraft.getInstance();
        Camera cam = mc.gameRenderer.mainCamera();
        if (cam == null) return null;

        Vec3 cp = cam.position();
        Matrix4f vp = cam.getViewRotationProjectionMatrix(new Matrix4f());
        int scrW = mc.getWindow().getGuiScaledWidth();
        int scrH = mc.getWindow().getGuiScaledHeight();

        Vector4f cl = vp.transform(new Vector4f(
                (float) (pos.x - cp.x),
                (float) (pos.y - cp.y),
                (float) (pos.z - cp.z),
                1f));
        // w<=0 → 点在相机后方；w 太小 → 贴在镜头上，都跳过
        if (cl.w <= 0.1f) return null;

        float ndcX = cl.x / cl.w;
        float ndcY = cl.y / cl.w;
        return new Vector3f(
                (ndcX * 0.5f + 0.5f) * scrW,
                (0.5f - ndcY * 0.5f) * scrH,
                cl.w
        );
    }
}
