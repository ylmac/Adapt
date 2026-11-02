package com.ylmac.adapt.render.adapt3d;

import net.minecraft.client.renderer.rendertype.RenderType;

/**
 * 一次绘制用的材质。贴图和混合方式都在 {@link RenderType} 里。
 * {@code bothSides} 为真时背面再写一遍反向顶点，负缩放（镜像翅膀）不会只剩单面。
 */
public record AdaptMaterial(RenderType type, boolean bothSides) {
}
