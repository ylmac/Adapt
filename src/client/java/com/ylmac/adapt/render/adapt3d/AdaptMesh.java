package com.ylmac.adapt.render.adapt3d;

import java.util.Arrays;

/**
 * CPU 侧网格。建成之后不可变，可以缓存、被多个部件共用。
 * <p>
 * 拓扑是四边形或三角形。三角形在提交到原版实体管线时补成退化四边形，
 * 因为那些管线只吃 QUADS。复杂模型用索引，不要每帧把顶点摊开重写一遍。
 * 法线数组为空时，提交阶段用变换后的顶点叉乘，负缩放（镜像）不会把面翻进模型里面。
 * 有法线时按法线矩阵变换，给圆环这类光滑曲面用。
 * 权重非空时每个顶点最多 4 根骨头。
 */
public final class AdaptMesh {
    public enum Topology { QUADS, TRIANGLES }

    final Topology topology;
    final float[] position;
    final float[] normal;
    final float[] uv;
    final int[] color;
    final int[] joints;
    final float[] weights;
    final int[] indices;

    private AdaptMesh(Topology topology, float[] position, float[] normal, float[] uv, int[] color,
                      int[] joints, float[] weights, int[] indices) {
        this.topology = topology;
        this.position = position;
        this.normal = normal;
        this.uv = uv;
        this.color = color;
        this.joints = joints;
        this.weights = weights;
        this.indices = indices;
    }

    public int vertexCount() {
        return position.length / 3;
    }

    public int primitiveCount() {
        if (topology == Topology.QUADS) return indices.length / 4;
        return indices.length / 3;
    }

    public Topology topology() {
        return topology;
    }

    public boolean smooth() {
        return normal != null;
    }

    public boolean skinned() {
        return weights != null;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private static final int WHITE = 0xFFFFFFFF;
        private float[] pos = new float[192];
        private float[] nrm;
        private float[] uv = new float[128];
        private int[] col = new int[64];
        private int[] joints;
        private float[] weights;
        private int[] idx = new int[128];
        private int verts;
        private int normals;
        private int indices;
        private Topology topology;

        public int vertex(float x, float y, float z, float u, float v) {
            return vertex(x, y, z, 0f, 0f, 0f, false, u, v, WHITE);
        }

        public int vertex(float x, float y, float z, float nx, float ny, float nz, float u, float v) {
            return vertex(x, y, z, nx, ny, nz, true, u, v, WHITE);
        }

        public int vertex(float x, float y, float z, float nx, float ny, float nz,
                          float u, float v, int argb) {
            return vertex(x, y, z, nx, ny, nz, true, u, v, argb);
        }

        private int vertex(float x, float y, float z, float nx, float ny, float nz, boolean hasNormal,
                           float u, float v, int argb) {
            if (verts == col.length) grow();
            int i = verts++;
            pos[i * 3] = x;
            pos[i * 3 + 1] = y;
            pos[i * 3 + 2] = z;
            uv[i * 2] = u;
            uv[i * 2 + 1] = v;
            col[i] = argb;
            if (hasNormal) {
                ensureNormals();
                nrm[i * 3] = nx;
                nrm[i * 3 + 1] = ny;
                nrm[i * 3 + 2] = nz;
                normals++;
            }
            if (joints != null) {
                weights[i * 4] = 1f;
            }
            return i;
        }

        /** 未调用时整张网格是刚体。调用后每个顶点默认绑在 0 号骨头、权重 1。 */
        public Builder weights(int vertex, int j0, float w0, int j1, float w1,
                               int j2, float w2, int j3, float w3) {
            if (vertex < 0 || vertex >= verts) {
                throw new IllegalArgumentException("Vertex " + vertex + " is outside 0.." + verts);
            }
            ensureSkin();
            int o = vertex * 4;
            joints[o] = j0;
            joints[o + 1] = j1;
            joints[o + 2] = j2;
            joints[o + 3] = j3;
            weights[o] = w0;
            weights[o + 1] = w1;
            weights[o + 2] = w2;
            weights[o + 3] = w3;
            return this;
        }

        public Builder face(int a, int b, int c, int d) {
            require(Topology.QUADS);
            addIndex(a);
            addIndex(b);
            addIndex(c);
            addIndex(d);
            return this;
        }

        public Builder triangle(int a, int b, int c) {
            require(Topology.TRIANGLES);
            addIndex(a);
            addIndex(b);
            addIndex(c);
            return this;
        }

        public Builder quad(float x0, float y0, float z0, float u0, float v0,
                            float x1, float y1, float z1, float u1, float v1,
                            float x2, float y2, float z2, float u2, float v2,
                            float x3, float y3, float z3, float u3, float v3) {
            int a = vertex(x0, y0, z0, u0, v0);
            int b = vertex(x1, y1, z1, u1, v1);
            int c = vertex(x2, y2, z2, u2, v2);
            int d = vertex(x3, y3, z3, u3, v3);
            return face(a, b, c, d);
        }

        /**
         * 像素盒子的六个面。顶点顺序跟 1.8 {@code TexturedQuad} 一样：
         * 第 0 个顶点拿 (u2, v1)，第 1 个拿 (u1, v1)。按顺序铺会采样到黑边。
         * {@code unit} 是一像素对应的世界单位，{@code atlas} 是贴图边长。
         */
        public Builder box(float x, float y, float z, int dx, int dy, int dz,
                           int u, int v, float unit, float atlas) {
            float x1 = x + dx;
            float y1 = y + dy;
            float z1 = z + dz;
            pixelFace(x1, y, z1, x1, y, z, x1, y1, z, x1, y1, z1,
                    u + dz + dx, v + dz, u + dz + dx + dz, v + dz + dy, unit, atlas);
            pixelFace(x, y, z, x, y, z1, x, y1, z1, x, y1, z,
                    u, v + dz, u + dz, v + dz + dy, unit, atlas);
            pixelFace(x1, y, z1, x, y, z1, x, y, z, x1, y, z,
                    u + dz, v, u + dz + dx, v + dz, unit, atlas);
            pixelFace(x1, y1, z, x, y1, z, x, y1, z1, x1, y1, z1,
                    u + dz + dx, v + dz, u + dz + dx + dx, v, unit, atlas);
            pixelFace(x1, y, z, x, y, z, x, y1, z, x1, y1, z,
                    u + dz, v + dz, u + dz + dx, v + dz + dy, unit, atlas);
            pixelFace(x, y, z1, x1, y, z1, x1, y1, z1, x, y1, z1,
                    u + dz + dx + dz, v + dz, u + dz + dx + dz + dx, v + dz + dy, unit, atlas);
            return this;
        }

        /** 翅膀膜：一张在像素空间里的四边形。 */
        public Builder membrane(float x, float y, float z, int width, int depth,
                                int u, int v, float unit, float atlas) {
            float x1 = x + width;
            float z1 = z + depth;
            pixelFace(x1, y, z, x, y, z, x, y, z1, x1, y, z1,
                    u, v + depth, u + width, v, unit, atlas);
            return this;
        }

        /**
         * 光滑圆环。主圆 {@code majorSeg} 段，截面 {@code minorSeg} 段，每面四个顶点。
         * 法线是解析法线，不是面法线。
         */
        public Builder torus(double majorRadius, double minorRadius, int majorSeg, int minorSeg) {
            if (majorSeg < 3 || minorSeg < 3) {
                throw new IllegalArgumentException("Torus segments must be at least 3");
            }
            int base = verts;
            for (int major = 0; major < majorSeg; major++) {
                double theta = Math.PI * 2.0 * major / majorSeg;
                float cosTheta = (float) Math.cos(theta);
                float sinTheta = (float) Math.sin(theta);
                for (int minor = 0; minor < minorSeg; minor++) {
                    double phi = Math.PI * 2.0 * minor / minorSeg;
                    float cosPhi = (float) Math.cos(phi);
                    float sinPhi = (float) Math.sin(phi);
                    float ring = (float) (majorRadius + minorRadius * cosPhi);
                    vertex(ring * cosTheta,
                            (float) (minorRadius * sinPhi),
                            ring * sinTheta,
                            cosPhi * cosTheta, sinPhi, cosPhi * sinTheta,
                            (float) (theta / (Math.PI * 2.0)),
                            (float) (phi / (Math.PI * 2.0)));
                }
            }
            for (int major = 0; major < majorSeg; major++) {
                int major1 = (major + 1) % majorSeg;
                for (int minor = 0; minor < minorSeg; minor++) {
                    int minor1 = (minor + 1) % minorSeg;
                    int v00 = base + major * minorSeg + minor;
                    int v10 = base + major1 * minorSeg + minor;
                    int v11 = base + major1 * minorSeg + minor1;
                    int v01 = base + major * minorSeg + minor1;
                    face(v00, v10, v11, v01);
                }
            }
            return this;
        }

        public AdaptMesh build() {
            Topology topo = topology == null ? Topology.QUADS : topology;
            float[] outN = normals == verts && nrm != null ? Arrays.copyOf(nrm, verts * 3) : null;
            int[] outJ = joints == null ? null : Arrays.copyOf(joints, verts * 4);
            float[] outW = weights == null ? null : Arrays.copyOf(weights, verts * 4);
            return new AdaptMesh(topo,
                    Arrays.copyOf(pos, verts * 3),
                    outN,
                    Arrays.copyOf(uv, verts * 2),
                    Arrays.copyOf(col, verts),
                    outJ,
                    outW,
                    Arrays.copyOf(idx, indices));
        }

        private void pixelFace(float x0, float y0, float z0,
                               float x1, float y1, float z1,
                               float x2, float y2, float z2,
                               float x3, float y3, float z3,
                               int u1, int v1, int u2, int v2,
                               float unit, float atlas) {
            // 第 0 顶点用 (u2, v1)，第 1 顶点用 (u1, v1)。
            quad(x0 * unit, y0 * unit, z0 * unit, u2 / atlas, v1 / atlas,
                    x1 * unit, y1 * unit, z1 * unit, u1 / atlas, v1 / atlas,
                    x2 * unit, y2 * unit, z2 * unit, u1 / atlas, v2 / atlas,
                    x3 * unit, y3 * unit, z3 * unit, u2 / atlas, v2 / atlas);
        }

        private void require(Topology next) {
            if (topology == null) topology = next;
            else if (topology != next) {
                throw new IllegalStateException("Mesh already uses " + topology);
            }
        }

        private void addIndex(int vertex) {
            if (vertex < 0 || vertex >= verts) {
                throw new IllegalArgumentException("Index " + vertex + " is outside 0.." + verts);
            }
            if (indices == idx.length) idx = Arrays.copyOf(idx, idx.length * 2);
            idx[indices++] = vertex;
        }

        private void ensureNormals() {
            if (nrm != null) return;
            nrm = new float[col.length * 3];
        }

        private void ensureSkin() {
            if (joints != null) return;
            joints = new int[col.length * 4];
            weights = new float[col.length * 4];
            for (int i = 0; i < verts; i++) weights[i * 4] = 1f;
        }

        private void grow() {
            int next = col.length * 2;
            pos = Arrays.copyOf(pos, next * 3);
            uv = Arrays.copyOf(uv, next * 2);
            col = Arrays.copyOf(col, next);
            if (nrm != null) nrm = Arrays.copyOf(nrm, next * 3);
            if (joints != null) {
                joints = Arrays.copyOf(joints, next * 4);
                weights = Arrays.copyOf(weights, next * 4);
            }
        }
    }
}
