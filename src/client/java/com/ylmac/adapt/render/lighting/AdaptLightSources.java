package com.ylmac.adapt.render.lighting;

import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * 每帧收集的光环局部光源列表，供 AdaptLightingUniform 转成视空间 UBO。
 * <p>
 * 这是 式自有渲染管线的光源数据通道—不触碰原版 LightEngine，
 * 不放置任何方块，不叠加原版实体轮廓；光照完全在自定义实体 shader 中计算。
 */
public final class AdaptLightSources {

    /**
 * 一个光环局部光源。world 坐标由 Halo3D 提交，view 坐标由
 * AdaptLightingUniform 每帧用相机旋转矩阵算出（std140 UBO 输入）。
 */
    public record HaloSource(
            double worldX, double worldY, double worldZ,
            float viewX, float viewY, float viewZ,
            float red, float green, float blue,
            float radius, float strength
    ) {
    }

    private static final List<HaloSource> BUILDING = new ArrayList<>();
    private static List<HaloSource> active = List.of();

    private AdaptLightSources() {
    }

    public static void beginFrame() {
        BUILDING.clear();
    }

    public static void submitSource(Vec3 position, float red, float green, float blue,
                                    float radius, float strength) {
        if (position == null || radius <= 0.0f || strength <= 0.0f) {
            return;
        }
        BUILDING.add(new HaloSource(
                position.x, position.y, position.z,
                0.0f, 0.0f, 0.0f,
                red, green, blue, radius, strength));
    }

    public static void endFrame() {
        active = BUILDING.isEmpty() ? List.of() : List.copyOf(BUILDING);
    }

    public static List<HaloSource> active() {
        return active;
    }

    public static void clear() {
        BUILDING.clear();
        active = List.of();
    }
}
