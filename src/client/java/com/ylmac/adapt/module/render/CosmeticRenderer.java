package com.ylmac.adapt.module.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.ylmac.adapt.module.render.CosmeticLibrary.HatModel;
import com.ylmac.adapt.render.adapt3d.AdaptMaterial;
import com.ylmac.adapt.render.adapt3d.AdaptMesh;
import com.ylmac.adapt.render.adapt3d.AdaptMeshes;
import com.ylmac.adapt.render.adapt3d.AdaptModel;
import com.ylmac.adapt.render.adapt3d.AdaptSkeleton;
import com.ylmac.adapt.render.adapt3d.AdaptSkeleton;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 帽子和翅膀都交给 Adapt3D。翅膀是五根骨头的刚体模型，帽子是缓存下来的四边形网格。
 * 帽子的头部位移仍在这里做：下移、转 180°、缩到 0.625，再套 Blockbench 的 display.head。
 */
public final class CosmeticRenderer {

    private static final float PIXEL = 1f / 16f;
    private static final float TEX = 256f;
    /**
     * 翅膀先按肩宽缩放再转 15°、再平移到背上。缩放来自原版包外的服务器值，
     * 翅膀旋转点 x=-12，玩家手臂旋转点 x=-5，用 5/12 时根部落在肩膀上。
     */
    private static final float WING_SCALE = 5f / 12f;

    private static final Map<Identifier, AdaptModel> WINGS = new ConcurrentHashMap<>();
    private static final Map<HatModel, AdaptModel> HATS = new IdentityHashMap<>();

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
            wings(wings).submit(collector, pose, locals -> writeWingPose(locals, crouch), light);
        }

        HatModel hat = CosmeticLibrary.hat(module.hat());
        if (hat == null) return;
        pose.pushPose();
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
        hat(hat).submit(collector, pose, null, state.lightCoords);
        pose.popPose();
    }

    private static AdaptModel wings(Identifier texture) {
        AdaptModel cached = WINGS.get(texture);
        if (cached != null) return cached;
        AdaptModel built = buildWings(texture);
        AdaptModel raced = WINGS.putIfAbsent(texture, built);
        return raced == null ? built : raced;
    }

    private static AdaptModel hat(HatModel model) {
        synchronized (HATS) {
            AdaptModel cached = HATS.get(model);
            if (cached != null) return cached;
            cached = buildHat(model);
            HATS.put(model, cached);
            return cached;
        }
    }

    private static AdaptModel buildWings(Identifier texture) {
        AdaptMesh upper = AdaptMesh.builder()
                .box(-56f, -4f, -4f, 56, 8, 8, 112, 88, PIXEL, TEX)
                .build();
        AdaptMesh upperMembrane = AdaptMesh.builder()
                .membrane(-56f, 0f, 2f, 56, 56, 56, 88, PIXEL, TEX)
                .build();
        AdaptMesh tip = AdaptMesh.builder()
                .box(-56f, -2f, -2f, 56, 4, 4, 112, 136, PIXEL, TEX)
                .build();
        AdaptMesh tipMembrane = AdaptMesh.builder()
                .membrane(-56f, 0f, 2f, 56, 56, 56, 144, PIXEL, TEX)
                .build();
        AdaptMaterial material = new AdaptMaterial(RenderTypes.entityCutout(texture), true);
        AdaptSkeleton skeleton = AdaptSkeleton.builder()
                .bone("root", -1)
                .bone("left", 0)
                .bone("leftTip", 1)
                .bone("right", 0)
                .bone("rightTip", 3)
                .build();
        AdaptModel.Builder builder = AdaptModel.builder().skeleton(skeleton);
        int upperId = builder.mesh(upper);
        int upperMembraneId = builder.mesh(upperMembrane);
        int tipId = builder.mesh(tip);
        int tipMembraneId = builder.mesh(tipMembrane);
        int materialId = builder.material(material);
        builder.part("left", -1, upperId, materialId, 1, null);
        builder.part("leftMembrane", -1, upperMembraneId, materialId, 1, null);
        builder.part("leftTip", -1, tipId, materialId, 2, null);
        builder.part("leftTipMembrane", -1, tipMembraneId, materialId, 2, null);
        builder.part("right", -1, upperId, materialId, 3, null);
        builder.part("rightMembrane", -1, upperMembraneId, materialId, 3, null);
        builder.part("rightTip", -1, tipId, materialId, 4, null);
        builder.part("rightTipMembrane", -1, tipMembraneId, materialId, 4, null);
        return builder.build();
    }

    private static AdaptModel buildHat(HatModel hat) {
        AdaptModel.Builder builder = AdaptModel.builder();
        for (HatModel.Mesh mesh : hat.meshes) {
            int meshId = builder.mesh(AdaptMeshes.fromQuads(mesh.quads));
            int materialId = builder.material(new AdaptMaterial(RenderTypes.entityCutout(mesh.texture), true));
            builder.part(mesh.texture.toString(), -1, meshId, materialId, -1, null);
        }
        return builder.build();
    }

    /**
     * 0 根，1 左上臂，2 左尖，3 右上臂，4 右尖。
     * 右臂在局部矩阵上做 X 镜像，不在网格里做 Z 轴 180°。
     */
    private static void writeWingPose(Matrix4f[] locals, boolean crouch) {
        float phase = (System.currentTimeMillis() % 2000L) / 2000f * ((float) Math.PI * 2f);
        float wingX = -0.125f - (float) Math.cos(phase) * 0.2f;
        float wingY = 0.75f;
        float wingZ = ((float) Math.sin(phase) + 0.125f) * 0.8f;
        float tipZ = ((float) Math.sin(phase + 2f) + 0.5f) * 0.75f;

        Matrix4f root = locals[0].identity();
        if (crouch) root.translate(0f, 0.2f, 0f);
        root.scale(WING_SCALE, WING_SCALE, WING_SCALE);
        root.rotate(Axis.XP.rotationDegrees(15f));
        root.translate(0f, 0.5f, 0.25f);

        Matrix4f upper = locals[1].identity();
        upper.translate(-12f * PIXEL, 5f * PIXEL, 2f * PIXEL);
        upper.rotate(Axis.ZP.rotation(wingZ));
        upper.rotate(Axis.YP.rotation(wingY));
        upper.rotate(Axis.XP.rotation(wingX));

        locals[2].identity()
                .translate(-56f * PIXEL, 0f, 0f)
                .rotate(Axis.ZP.rotation(tipZ));
        locals[3].identity().scale(-1f, 1f, 1f).mul(upper);
        locals[4].set(locals[2]);
    }
}
