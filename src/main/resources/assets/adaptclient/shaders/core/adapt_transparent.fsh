#version 330

// Adapt 天空系统占位管线：全透明输出。
// 原版 STARS（星星）/ SUNRISE_SUNSET（朝霞）/ CELESTIAL（日月纹理）/
// CLOUDS+FLAT_CLOUDS（原版云几何）全部由 adapt_sky 的程序化实现替代，
// 这些管线的绘制以透明色输出（在各自混合模式下等价于无操作）。
// 顶点着色器沿用各管线原版 vsh（几何参数生成保持不变）。

out vec4 fragColor;

void main() {
    fragColor = vec4(0.0);
}
