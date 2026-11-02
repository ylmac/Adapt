package com.ylmac.adapt.render.adapt3d;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/** 网格工厂。圆环按半径和分段缓存，设置没变就不要每帧重造几千个顶点。 */
public final class AdaptMeshes {
    private static final ConcurrentHashMap<Key, AdaptMesh> TORUS = new ConcurrentHashMap<>();

    private AdaptMeshes() {}

    public static AdaptMesh torus(double majorRadius, double minorRadius, int majorSegments, int minorSegments) {
        Key key = new Key(Double.doubleToLongBits(majorRadius), Double.doubleToLongBits(minorRadius),
                majorSegments, minorSegments);
        AdaptMesh cached = TORUS.get(key);
        if (cached != null) return cached;
        AdaptMesh built = AdaptMesh.builder()
                .torus(majorRadius, minorRadius, majorSegments, minorSegments)
                .build();
        AdaptMesh raced = TORUS.putIfAbsent(key, built);
        return raced == null ? built : raced;
    }

    /** 已展开的四边形。每个数组至少 20 个数：四个顶点的 x,y,z,u,v。法线提交时重算。 */
    public static AdaptMesh fromQuads(List<float[]> quads) {
        AdaptMesh.Builder builder = AdaptMesh.builder();
        for (float[] quad : quads) {
            if (quad == null || quad.length < 20) continue;
            builder.quad(quad[0], quad[1], quad[2], quad[3], quad[4],
                    quad[5], quad[6], quad[7], quad[8], quad[9],
                    quad[10], quad[11], quad[12], quad[13], quad[14],
                    quad[15], quad[16], quad[17], quad[18], quad[19]);
        }
        return builder.build();
    }

    private record Key(long major, long minor, int majorSeg, int minorSeg) {}
}
