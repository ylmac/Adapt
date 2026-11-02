package com.ylmac.adapt.render.adapt3d;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/**
 * 可绘制的模型：部件、材质、可选骨骼。
 * 部件可以刚体挂在某根骨头上，网格自己带权重时改走蒙皮。
 * 以后的复杂模型把顶点、索引、材质和骨骼填进来，提交路径不用再开一条。
 */
public final class AdaptModel {
    /** 每帧把骨骼的局部矩阵写进 {@code boneLocal}。根骨头的父级是实体姿势。 */
    @FunctionalInterface
    public interface Pose {
        void write(Matrix4f[] boneLocal);
    }

    private final Part[] parts;
    private final AdaptMesh[] meshes;
    private final AdaptMaterial[] materials;
    private final AdaptSkeleton skeleton;
    private final boolean skinned;

    private AdaptModel(Part[] parts, AdaptMesh[] meshes, AdaptMaterial[] materials, AdaptSkeleton skeleton) {
        this.parts = parts;
        this.meshes = meshes;
        this.materials = materials;
        this.skeleton = skeleton;
        boolean any = false;
        for (Part part : parts) {
            if (part.mesh >= 0 && meshes[part.mesh].skinned()) any = true;
        }
        this.skinned = any;
    }

    public int parts() {
        return parts.length;
    }

    public int bones() {
        return skeleton == null ? 0 : skeleton.count();
    }

    public AdaptSkeleton skeleton() {
        return skeleton;
    }

    public void submit(SubmitNodeCollector collector, PoseStack pose, Pose animation, int light) {
        int cursor = 0;
        while (cursor < parts.length) {
            if (parts[cursor].mesh < 0) {
                cursor++;
                continue;
            }
            AdaptMaterial material = materials[parts[cursor].material];
            int end = cursor + 1;
            while (end < parts.length
                    && parts[end].mesh >= 0
                    && materials[parts[end].material] == material) {
                end++;
            }
            submitRange(collector, pose, animation, light, material, cursor, end);
            cursor = end;
        }
    }

    private void submitRange(SubmitNodeCollector collector, PoseStack pose, Pose animation,
                             int light, AdaptMaterial material, int from, int to) {
        collector.submitCustomGeometry(pose, material.type(), (copied, buffer) -> {
            Matrix4f root = new Matrix4f(copied.pose());
            Matrix4f[] boneWorld = null;
            Matrix4f[] skin = null;
            if (skeleton != null) {
                Matrix4f[] local = identities(skeleton.count());
                if (animation != null) animation.write(local);
                boneWorld = new Matrix4f[skeleton.count()];
                skeleton.evaluate(root, local, boneWorld);
                if (skinned) {
                    skin = new Matrix4f[skeleton.count()];
                    skeleton.skin(boneWorld, skin);
                }
            }
            Matrix4f[] partWorld = new Matrix4f[parts.length];
            for (int i = 0; i < parts.length; i++) {
                partWorld[i] = worldOf(i, root, boneWorld, partWorld);
            }
            for (int i = from; i < to; i++) {
                AdaptMesh mesh = meshes[parts[i].mesh];
                Matrix4f[] bones = mesh.skinned() ? skin : null;
                AdaptDraw.write(buffer, mesh, partWorld[i], bones, light, null, material.bothSides());
            }
        });
    }

    private Matrix4f worldOf(int index, Matrix4f root, Matrix4f[] boneWorld, Matrix4f[] partWorld) {
        Part part = parts[index];
        if (part.bone >= 0) return boneWorld[part.bone];
        if (part.parent < 0) return new Matrix4f(root).mul(part.local);
        return new Matrix4f(partWorld[part.parent]).mul(part.local);
    }

    private static Matrix4f[] identities(int count) {
        Matrix4f[] out = new Matrix4f[count];
        for (int i = 0; i < count; i++) out[i] = new Matrix4f();
        return out;
    }

    public static Builder builder() {
        return new Builder();
    }

    private record Part(String name, int parent, int mesh, int material, int bone, Matrix4f local) {}

    public static final class Builder {
        private final List<Part> parts = new ArrayList<>();
        private final List<AdaptMesh> meshes = new ArrayList<>();
        private final List<AdaptMaterial> materials = new ArrayList<>();
        private AdaptSkeleton skeleton;

        public Builder skeleton(AdaptSkeleton skeleton) {
            this.skeleton = skeleton;
            return this;
        }

        public int mesh(AdaptMesh mesh) {
            meshes.add(mesh);
            return meshes.size() - 1;
        }

        public int material(AdaptMaterial material) {
            materials.add(material);
            return materials.size() - 1;
        }

        public int part(String name, int parent, int mesh, int material, int bone, Matrix4f localBind) {
            if (parent < -1 || parent >= parts.size()) {
                throw new IllegalArgumentException("Part parent must already exist: " + name);
            }
            if (mesh >= meshes.size() || material >= materials.size()) {
                throw new IllegalArgumentException("Part references a missing mesh or material: " + name);
            }
            if (bone >= 0 && (skeleton == null || bone >= skeleton.count())) {
                throw new IllegalArgumentException("Part bone is out of range: " + name);
            }
            parts.add(new Part(name, parent, mesh, material, bone,
                    localBind == null ? new Matrix4f() : new Matrix4f(localBind)));
            return parts.size() - 1;
        }

        public AdaptModel build() {
            return new AdaptModel(
                    parts.toArray(Part[]::new),
                    meshes.toArray(AdaptMesh[]::new),
                    materials.toArray(AdaptMaterial[]::new),
                    skeleton);
        }
    }
}
