package com.ylmac.adapt.render.adapt3d;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.texture.OverlayTexture;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * 把网格写进原版的自定义几何。刚体用一张矩阵；带权重的网格用骨骼蒙皮矩阵。
 * 平面法线在变换之后叉乘，这样镜像缩放后面的朝向仍然朝外。
 */
public final class AdaptDraw {
    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    private AdaptDraw() {}

    public static void submit(SubmitNodeCollector collector, PoseStack pose,
                              AdaptMaterial material, AdaptMesh mesh,
                              int light, AdaptColor color) {
        if (mesh == null || mesh.primitiveCount() == 0) return;
        if (mesh.skinned()) {
            throw new IllegalArgumentException("Skinned meshes must be submitted through AdaptModel");
        }
        collector.submitCustomGeometry(pose, material.type(), (copied, buffer) ->
                write(buffer, mesh, copied.pose(), null, light, color, material.bothSides()));
    }

    static void write(VertexConsumer buffer, AdaptMesh mesh, Matrix4f matrix, Matrix4f[] skin,
                      int light, AdaptColor color, boolean bothSides) {
        if (mesh.primitiveCount() == 0) return;
        if (mesh.skinned()) {
            if (skin == null) {
                throw new IllegalArgumentException("Skinned mesh missing bone matrices");
            }
            writeSkinned(buffer, mesh, matrix, skin, light, color, bothSides);
        } else {
            writeRigid(buffer, mesh, matrix, light, color, bothSides);
        }
    }

    private static void writeRigid(VertexConsumer buffer, AdaptMesh mesh, Matrix4f matrix,
                                   int light, AdaptColor color, boolean bothSides) {
        Scratch s = SCRATCH.get();
        Matrix3f normals = null;
        if (mesh.normal != null) {
            normals = s.normal;
            matrix.normal(normals);
        }
        int step = mesh.topology == AdaptMesh.Topology.QUADS ? 4 : 3;
        for (int p = 0; p < mesh.indices.length; p += step) {
            int n = step == 4 ? 4 : 3;
            for (int k = 0; k < n; k++) {
                load(mesh, mesh.indices[p + k], matrix, null, null, s, k, color);
            }
            if (n == 3) copyCorner(s, 2, 3);
            emit(buffer, s, mesh.normal != null, light, bothSides);
        }
    }

    private static void writeSkinned(VertexConsumer buffer, AdaptMesh mesh, Matrix4f fallback,
                                     Matrix4f[] skin, int light, AdaptColor color, boolean bothSides) {
        Scratch s = SCRATCH.get();
        Matrix3f[] boneNormals = null;
        if (mesh.normal != null) {
            if (s.boneNormals == null || s.boneNormals.length < skin.length) {
                s.boneNormals = new Matrix3f[skin.length];
            }
            boneNormals = s.boneNormals;
            for (int i = 0; i < skin.length; i++) {
                if (boneNormals[i] == null) boneNormals[i] = new Matrix3f();
                if (skin[i] != null) skin[i].normal(boneNormals[i]);
            }
        }
        int step = mesh.topology == AdaptMesh.Topology.QUADS ? 4 : 3;
        for (int p = 0; p < mesh.indices.length; p += step) {
            int n = step == 4 ? 4 : 3;
            for (int k = 0; k < n; k++) {
                load(mesh, mesh.indices[p + k], fallback, skin, boneNormals, s, k, color);
            }
            if (n == 3) copyCorner(s, 2, 3);
            emit(buffer, s, mesh.normal != null, light, bothSides);
        }
    }

    private static void load(AdaptMesh mesh, int index, Matrix4f matrix, Matrix4f[] skin,
                             Matrix3f[] boneNormals, Scratch s, int corner, AdaptColor color) {
        float x = mesh.position[index * 3];
        float y = mesh.position[index * 3 + 1];
        float z = mesh.position[index * 3 + 2];
        Vector4f out = s.corner[corner];
        if (skin == null) {
            out.set(x, y, z, 1f);
            matrix.transform(out);
        } else {
            skinPosition(mesh, index, x, y, z, skin, matrix, out, s.tmp);
        }
        float u = mesh.uv[index * 2];
        float v = mesh.uv[index * 2 + 1];
        s.u[corner] = u;
        s.v[corner] = v;
        int meshColor = mesh.color[index];
        s.argb[corner] = color == null ? meshColor : color.argb(u, v, meshColor);
        if (mesh.normal != null) {
            float nx = mesh.normal[index * 3];
            float ny = mesh.normal[index * 3 + 1];
            float nz = mesh.normal[index * 3 + 2];
            Vector3f normal = s.normals[corner];
            if (skin == null) {
                s.normal.transform(nx, ny, nz, normal);
            } else {
                skinNormal(mesh, index, nx, ny, nz, boneNormals, normal, s.nTmp);
            }
        }
    }

    private static void skinPosition(AdaptMesh mesh, int index, float x, float y, float z,
                                     Matrix4f[] skin, Matrix4f fallback, Vector4f out, Vector4f tmp) {
        out.set(0f, 0f, 0f, 0f);
        float sum = 0f;
        int base = index * 4;
        for (int k = 0; k < 4; k++) {
            float w = mesh.weights[base + k];
            if (w == 0f) continue;
            int joint = mesh.joints[base + k];
            if (joint < 0 || joint >= skin.length || skin[joint] == null) continue;
            tmp.set(x, y, z, 1f);
            skin[joint].transform(tmp);
            out.add(tmp.x * w, tmp.y * w, tmp.z * w, tmp.w * w);
            sum += w;
        }
        if (sum == 0f) {
            out.set(x, y, z, 1f);
            fallback.transform(out);
        }
    }

    private static void skinNormal(AdaptMesh mesh, int index, float x, float y, float z,
                                   Matrix3f[] boneNormals, Vector3f out, Vector3f tmp) {
        out.set(0f, 0f, 0f);
        if (boneNormals == null) return;
        int base = index * 4;
        for (int k = 0; k < 4; k++) {
            float w = mesh.weights[base + k];
            if (w == 0f) continue;
            int joint = mesh.joints[base + k];
            if (joint < 0 || joint >= boneNormals.length || boneNormals[joint] == null) continue;
            tmp.set(x, y, z);
            boneNormals[joint].transform(tmp);
            out.add(tmp.x * w, tmp.y * w, tmp.z * w);
        }
    }

    private static void copyCorner(Scratch s, int from, int to) {
        s.corner[to].set(s.corner[from]);
        s.u[to] = s.u[from];
        s.v[to] = s.v[from];
        s.argb[to] = s.argb[from];
        s.normals[to].set(s.normals[from]);
    }

    private static void emit(VertexConsumer buffer, Scratch s, boolean smooth,
                             int light, boolean bothSides) {
        if (!smooth) {
            flatNormal(s.corner[0], s.corner[1], s.corner[2], s.nTmp);
        }
        for (int k = 0; k < 4; k++) {
            Vector3f normal = smooth ? s.normals[k] : s.nTmp;
            put(buffer, s.corner[k], s.u[k], s.v[k], s.argb[k], normal, light);
        }
        if (!bothSides) return;
        if (!smooth) s.nTmp.negate();
        for (int k = 3; k >= 0; k--) {
            Vector3f normal = smooth ? s.normals[k].negate() : s.nTmp;
            put(buffer, s.corner[k], s.u[k], s.v[k], s.argb[k], normal, light);
            if (smooth) s.normals[k].negate();
        }
    }

    private static void flatNormal(Vector4f a, Vector4f b, Vector4f c, Vector3f out) {
        float ex1 = b.x - a.x;
        float ey1 = b.y - a.y;
        float ez1 = b.z - a.z;
        float ex2 = c.x - a.x;
        float ey2 = c.y - a.y;
        float ez2 = c.z - a.z;
        out.set(ey1 * ez2 - ez1 * ey2, ez1 * ex2 - ex1 * ez2, ex1 * ey2 - ey1 * ex2);
    }

    private static void put(VertexConsumer buffer, Vector4f position, float u, float v, int argb,
                            Vector3f normal, int light) {
        if (normal.lengthSquared() < 1.0e-8f) normal.set(0f, 1f, 0f);
        else normal.normalize();
        buffer.addVertex(position.x, position.y, position.z)
                .setColor(argb)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setUv1(0, 0)
                .setLight(light)
                .setNormal(normal.x, normal.y, normal.z);
    }

    private static final class Scratch {
        final Vector4f[] corner = {new Vector4f(), new Vector4f(), new Vector4f(), new Vector4f()};
        final Vector4f tmp = new Vector4f();
        final Vector3f[] normals = {new Vector3f(), new Vector3f(), new Vector3f(), new Vector3f()};
        final Vector3f nTmp = new Vector3f();
        final Matrix3f normal = new Matrix3f();
        final float[] u = new float[4];
        final float[] v = new float[4];
        final int[] argb = new int[4];
        Matrix3f[] boneNormals;
    }
}
