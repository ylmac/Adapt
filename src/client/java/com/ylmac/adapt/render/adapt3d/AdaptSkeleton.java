package com.ylmac.adapt.render.adapt3d;

import org.joml.Matrix4f;

/**
 * 骨骼。父节点必须排在子节点前面。
 * {@code inverseBind} 为空表示单位矩阵：顶点已经在骨骼局部空间里，权重 1 就等于刚体挂到这根骨头上。
 * 有绑定姿势的模型（以后的蒙皮网格）填 inverse bind，蒙皮矩阵是世界骨骼乘 inverse bind。
 */
public final class AdaptSkeleton {
    private final String[] names;
    private final int[] parent;
    private final Matrix4f[] inverseBind;

    private AdaptSkeleton(String[] names, int[] parent, Matrix4f[] inverseBind) {
        this.names = names;
        this.parent = parent;
        this.inverseBind = inverseBind;
    }

    public int count() {
        return names.length;
    }

    public String name(int index) {
        return names[index];
    }

    public int parent(int index) {
        return parent[index];
    }

    public int index(String name) {
        for (int i = 0; i < names.length; i++) {
            if (names[i].equals(name)) return i;
        }
        return -1;
    }

    /** {@code world[i] = 父世界矩阵 * local[i]}，根骨头的父矩阵是 {@code root}。 */
    public void evaluate(Matrix4f root, Matrix4f[] local, Matrix4f[] world) {
        for (int i = 0; i < parent.length; i++) {
            if (world[i] == null) world[i] = new Matrix4f();
            if (parent[i] < 0) world[i].set(root).mul(local[i]);
            else world[i].set(world[parent[i]]).mul(local[i]);
        }
    }

    /** 蒙皮用。没有 inverse bind 时结果就是世界矩阵本身。 */
    public void skin(Matrix4f[] world, Matrix4f[] skinOut) {
        for (int i = 0; i < parent.length; i++) {
            if (skinOut[i] == null) skinOut[i] = new Matrix4f();
            if (inverseBind[i] == null) skinOut[i].set(world[i]);
            else skinOut[i].set(world[i]).mul(inverseBind[i]);
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final java.util.ArrayList<String> names = new java.util.ArrayList<>();
        private final java.util.ArrayList<Integer> parent = new java.util.ArrayList<>();
        private final java.util.ArrayList<Matrix4f> inverseBind = new java.util.ArrayList<>();

        public Builder bone(String name, int parentIndex) {
            return bone(name, parentIndex, null);
        }

        public Builder bone(String name, int parentIndex, Matrix4f inverseBindMatrix) {
            if (parentIndex < -1 || parentIndex >= names.size()) {
                throw new IllegalArgumentException("Bone parent must already exist: " + name);
            }
            names.add(name);
            parent.add(parentIndex);
            inverseBind.add(inverseBindMatrix == null ? null : new Matrix4f(inverseBindMatrix));
            return this;
        }

        public AdaptSkeleton build() {
            int n = names.size();
            Matrix4f[] inv = new Matrix4f[n];
            int[] parents = new int[n];
            for (int i = 0; i < n; i++) {
                parents[i] = parent.get(i);
                inv[i] = inverseBind.get(i);
            }
            return new AdaptSkeleton(names.toArray(String[]::new), parents, inv);
        }
    }
}
