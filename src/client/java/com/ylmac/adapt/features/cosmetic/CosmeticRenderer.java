package com.ylmac.adapt.features.cosmetic;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.ylmac.adapt.features.cosmetic.CosmeticLibrary.HatModel;
import com.ylmac.adapt.features.module.impl.render.CosmeticsModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * 翅膀走 CheatBreaker 平时的 {@code WingModel.render}，不走表情层那套 180° 翻转。
 * 帽子走 {@code BlockModelParser.renderOnHead}：头的朝向之后再下移、转 180°、缩到 0.625。
 */
public final class CosmeticRenderer {

    private static final float PIXEL = 1f / 16f;
    private static final float TEX = 256f;
    /**
     * {@code WingModel.render} 先 {@code glScaled(cosmeticScale)}，再转 15°、再平移到背上。
     * 缩放来自服务器，包里没有。翅膀旋转点 x=-12，玩家手臂旋转点 x=-5，
     * 用 5/12 时根部落在肩膀上，0.5 的背上偏移也一起缩小。
     */
    private static final float WING_SCALE = 5f / 12f;

    private CosmeticRenderer() {}

    public static void submit(LivingEntityRenderState state, PoseStack pose, SubmitNodeCollector collector) {
        if (!(state instanceof AvatarRenderState avatar) || state.isInvisible) return;
        CosmeticsModule module = CosmeticsModule.INSTANCE;
        if (module == null || !module.isEnabled()) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || avatar.id != minecraft.player.getId()) return;

        Identifier wings = CosmeticLibrary.wings(module.wings());
        if (wings != null) {
            int light = state.lightCoords;
            boolean crouch = avatar.isCrouching;
            RenderType type = RenderTypes.entityCutout(wings);
            collector.submitCustomGeometry(pose, type, (copied, buffer) -> drawWings(copied, buffer, light, crouch));
        }

        HatModel hat = CosmeticLibrary.hat(module.hat());
        if (hat == null) return;
        pose.pushPose();
        // ModelPlayer 潜行时先把模型下移 0.2，头的旋转点再往下 1 像素。
        if (avatar.isCrouching) {
            pose.translate(0f, 0.2f, 0f);
            pose.translate(0f, PIXEL, 0f);
        }
        pose.rotate(Axis.YP, avatar.yRot * ((float) Math.PI / 180f));
        pose.rotate(Axis.XP, avatar.xRot * ((float) Math.PI / 180f));
        pose.translate(0f, -0.25f, 0f);
        pose.rotate(Axis.YP, (float) Math.PI);
        pose.scale(0.625f, -0.625f, -0.625f);
        pose.translate(hat.translation[0] / 16f, hat.translation[1] / 16f, hat.translation[2] / 16f);
        pose.rotateDegrees(Axis.YP, hat.rotation[1]);
        pose.rotateDegrees(Axis.XP, hat.rotation[0]);
        pose.rotateDegrees(Axis.ZP, hat.rotation[2]);
        pose.scale(hat.scale[0], hat.scale[1], hat.scale[2]);
        pose.translate(-0.5f, -0.5f, -0.5f);
        int light = state.lightCoords;
        for (HatModel.Mesh mesh : hat.meshes) {
            RenderType type = RenderTypes.entityCutout(mesh.texture);
            collector.submitCustomGeometry(pose, type, (copied, buffer) -> drawHat(copied, buffer, light, mesh));
        }
        pose.popPose();
    }

    /**
     * 顺序跟 {@code WingModel.render} 一致：先按肩宽缩放，再绕 X 转 15°，再平移到背上，
     * 然后才是骨头的旋转点。左右两边用 X 镜像，不在这里做表情用的 Z 轴 180°。
     */
    private static void drawWings(PoseStack.Pose entityPose, VertexConsumer buffer, int light, boolean crouch) {
        float phase = (System.currentTimeMillis() % 2000L) / 2000f * ((float) Math.PI * 2f);
        float wingX = -0.125f - (float) Math.cos(phase) * 0.2f;
        float wingY = 0.75f;
        float wingZ = ((float) Math.sin(phase) + 0.125f) * 0.8f;
        float tipZ = ((float) Math.sin(phase + 2f) + 0.5f) * 0.75f;
        for (int side = 0; side < 2; side++) {
            Matrix4f wing = new Matrix4f(entityPose.pose());
            if (crouch) wing.translate(0f, 0.2f, 0f);
            wing.scale(WING_SCALE, WING_SCALE, WING_SCALE);
            wing.rotate(Axis.XP.rotationDegrees(15f));
            wing.translate(0f, 0.5f, 0.25f);
            if (side == 1) wing.scale(-1f, 1f, 1f);
            wing.translate(-12f * PIXEL, 5f * PIXEL, 2f * PIXEL);
            wing.rotate(Axis.ZP.rotation(wingZ));
            wing.rotate(Axis.YP.rotation(wingY));
            wing.rotate(Axis.XP.rotation(wingX));
            bone(wing, buffer, light, -56f, -4f, -4f, 56, 8, 8, 112, 88);
            membrane(wing, buffer, light, -56f, 0f, 2f, 56, 56, 56, 88);
            Matrix4f tip = new Matrix4f(wing);
            tip.translate(-56f * PIXEL, 0f, 0f);
            tip.rotate(Axis.ZP.rotation(tipZ));
            bone(tip, buffer, light, -56f, -2f, -2f, 56, 4, 4, 112, 136);
            membrane(tip, buffer, light, -56f, 0f, 2f, 56, 56, 56, 144);
        }
    }

    private static void drawHat(PoseStack.Pose pose, VertexConsumer buffer, int light, HatModel.Mesh mesh) {
        Matrix4f matrix = pose.pose();
        for (float[] quad : mesh.quads) {
            quad(matrix, buffer, light,
                    quad[0], quad[1], quad[2], quad[3], quad[4],
                    quad[5], quad[6], quad[7], quad[8], quad[9],
                    quad[10], quad[11], quad[12], quad[13], quad[14],
                    quad[15], quad[16], quad[17], quad[18], quad[19],
                    quad[20], quad[21], quad[22],
                    true);
        }
    }

    /**
     * 1.8 {@code ModelBox} 的六个面。顶点顺序和 {@code TexturedQuad} 一样：
     * 第 0 个顶点拿 (u2, v1)，第 1 个拿 (u1, v1)。之前按顺序铺，骨头会采样到黑边。
     */
    private static void bone(Matrix4f matrix, VertexConsumer buffer, int light,
                             float x, float y, float z, int dx, int dy, int dz, int u, int v) {
        float x1 = x + dx;
        float y1 = y + dy;
        float z1 = z + dz;
        boxFace(matrix, buffer, light,
                x1, y, z1, x1, y, z, x1, y1, z, x1, y1, z1,
                u + dz + dx, v + dz, u + dz + dx + dz, v + dz + dy);
        boxFace(matrix, buffer, light,
                x, y, z, x, y, z1, x, y1, z1, x, y1, z,
                u, v + dz, u + dz, v + dz + dy);
        boxFace(matrix, buffer, light,
                x1, y, z1, x, y, z1, x, y, z, x1, y, z,
                u + dz, v, u + dz + dx, v + dz);
        boxFace(matrix, buffer, light,
                x1, y1, z, x, y1, z, x, y1, z1, x1, y1, z1,
                u + dz + dx, v + dz, u + dz + dx + dx, v);
        boxFace(matrix, buffer, light,
                x1, y, z, x, y, z, x, y1, z, x1, y1, z,
                u + dz, v + dz, u + dz + dx, v + dz + dy);
        boxFace(matrix, buffer, light,
                x, y, z1, x1, y, z1, x1, y1, z1, x, y1, z1,
                u + dz + dx + dz, v + dz, u + dz + dx + dz + dx, v + dz + dy);
    }

    private static void boxFace(Matrix4f matrix, VertexConsumer buffer, int light,
                                float x0, float y0, float z0,
                                float x1, float y1, float z1,
                                float x2, float y2, float z2,
                                float x3, float y3, float z3,
                                int u1, int v1, int u2, int v2) {
        face(matrix, buffer, light,
                x0, y0, z0, u2 / TEX, v1 / TEX,
                x1, y1, z1, u1 / TEX, v1 / TEX,
                x2, y2, z2, u1 / TEX, v2 / TEX,
                x3, y3, z3, u2 / TEX, v2 / TEX);
    }

    private static void membrane(Matrix4f matrix, VertexConsumer buffer, int light,
                                 float x, float y, float z, int width, int depth, int u, int v) {
        float x1 = x + width;
        float z1 = z + depth;
        face(matrix, buffer, light,
                x1, y, z, (u + width) / TEX, (v + depth) / TEX,
                x, y, z, u / TEX, (v + depth) / TEX,
                x, y, z1, u / TEX, v / TEX,
                x1, y, z1, (u + width) / TEX, v / TEX);
    }

    private static void face(Matrix4f matrix, VertexConsumer buffer, int light,
                             float x0, float y0, float z0, float u0, float v0,
                             float x1, float y1, float z1, float u1, float v1,
                             float x2, float y2, float z2, float u2, float v2,
                             float x3, float y3, float z3, float u3, float v3) {
        quad(matrix, buffer, light,
                x0 * PIXEL, y0 * PIXEL, z0 * PIXEL, u0, v0,
                x1 * PIXEL, y1 * PIXEL, z1 * PIXEL, u1, v1,
                x2 * PIXEL, y2 * PIXEL, z2 * PIXEL, u2, v2,
                x3 * PIXEL, y3 * PIXEL, z3 * PIXEL, u3, v3,
                0f, 0f, 0f,
                true);
    }

    private static void quad(Matrix4f matrix, VertexConsumer buffer, int light,
                             float x0, float y0, float z0, float u0, float v0,
                             float x1, float y1, float z1, float u1, float v1,
                             float x2, float y2, float z2, float u2, float v2,
                             float x3, float y3, float z3, float u3, float v3,
                             float nx, float ny, float nz,
                             boolean bothSides) {
        Vector4f p0 = matrix.transform(new Vector4f(x0, y0, z0, 1f));
        Vector4f p1 = matrix.transform(new Vector4f(x1, y1, z1, 1f));
        Vector4f p2 = matrix.transform(new Vector4f(x2, y2, z2, 1f));
        Vector4f p3 = matrix.transform(new Vector4f(x3, y3, z3, 1f));
        // 法线用变换后的顶点叉乘。模型矩阵带了负缩放，先算再 transformDirection 会把朝向翻进模型里面，白天就成黑条。
        float ex1 = p1.x - p0.x;
        float ey1 = p1.y - p0.y;
        float ez1 = p1.z - p0.z;
        float ex2 = p2.x - p0.x;
        float ey2 = p2.y - p0.y;
        float ez2 = p2.z - p0.z;
        nx = ey1 * ez2 - ez1 * ey2;
        ny = ez1 * ex2 - ex1 * ez2;
        nz = ex1 * ey2 - ey1 * ex2;
        if (nx == 0f && ny == 0f && nz == 0f) {
            nx = 0f;
            ny = 1f;
            nz = 0f;
        }
        put(buffer, light, p0, u0, v0, nx, ny, nz);
        put(buffer, light, p1, u1, v1, nx, ny, nz);
        put(buffer, light, p2, u2, v2, nx, ny, nz);
        put(buffer, light, p3, u3, v3, nx, ny, nz);
        if (!bothSides) return;
        // TexturedQuad.flipFace 把顶点倒过来，背面的贴图才不会拧。
        put(buffer, light, p3, u3, v3, -nx, -ny, -nz);
        put(buffer, light, p2, u2, v2, -nx, -ny, -nz);
        put(buffer, light, p1, u1, v1, -nx, -ny, -nz);
        put(buffer, light, p0, u0, v0, -nx, -ny, -nz);
    }

    private static void put(VertexConsumer buffer, int light, Vector4f position,
                            float u, float v, float nx, float ny, float nz) {
        Vector3f normal = new Vector3f(nx, ny, nz);
        if (normal.lengthSquared() < 1.0e-8f) normal.set(0f, 1f, 0f);
        else normal.normalize();
        buffer.addVertex(position.x, position.y, position.z)
                .setColor(255, 255, 255, 255)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setUv1(0, 0)
                .setLight(light)
                .setNormal(normal.x, normal.y, normal.z);
    }
}
