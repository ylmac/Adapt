package com.ylmac.adapt.compose.theme

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import com.ylmac.adapt.AdaptClient

/** Compose 字体：Material Symbols Rounded 连字图标 + PingFang 多字重正文。 */
object AdaptFonts {
    val MaterialSymbolsRounded: FontFamily by lazy {
        loadIconFamily(
            "MaterialSymbolsRounded",
            "/assets/adaptclient/qml-retired/fonts/MaterialSymbolsRounded.ttf",
            "/assets/adaptclient/fonts/MaterialSymbolsRounded.ttf",
        )
    }

    val PingFang: FontFamily by lazy {
        loadWeightedFamily(
            identity = "PingFangSC",
            regularPaths = arrayOf(
                "/assets/adaptclient/qml-retired/fonts/PingFangSC-Regular.otf",
                "/assets/adaptclient/fonts/PingFangSC-Regular.otf",
            ),
            mediumPaths = arrayOf(
                "/assets/adaptclient/qml-retired/fonts/PingFangSC-Medium.otf",
                "/assets/adaptclient/fonts/PingFangSC-Medium.otf",
            ),
        )
    }

    /** 兼容旧引用：Medium 面已并入 [PingFang]。 */
    val PingFangMedium: FontFamily get() = PingFang

    /** 预热懒加载并打日志，避免首帧字体空白。 */
    @JvmStatic
    fun warm() {
        val icon = MaterialSymbolsRounded
        val body = PingFang
        AdaptClient.LOGGER.info(
            "[AdaptFonts] warm icon={} body={}",
            icon !== FontFamily.Default,
            body !== FontFamily.Default,
        )
    }

    private fun loadIconFamily(identity: String, vararg paths: String): FontFamily {
        val bytes = loadBytes(*paths) ?: run {
            AdaptClient.LOGGER.warn("[AdaptFonts] missing font {}", identity)
            return FontFamily.Default
        }
        return try {
            FontFamily(Font(identity = identity, data = bytes, weight = FontWeight.Normal))
        } catch (t: Throwable) {
            AdaptClient.LOGGER.warn("[AdaptFonts] load {} failed: {}", identity, t.toString())
            FontFamily.Default
        }
    }

    private fun loadWeightedFamily(
        identity: String,
        regularPaths: Array<String>,
        mediumPaths: Array<String>,
    ): FontFamily {
        val regular = loadBytes(*regularPaths)
        if (regular == null) {
            AdaptClient.LOGGER.warn("[AdaptFonts] missing font {}", identity)
            return FontFamily.Default
        }
        val medium = loadBytes(*mediumPaths)
        return try {
            if (medium != null) {
                // SemiBold/Bold 显式落到 Medium，避免 CJK 伪粗体发虚
                FontFamily(
                    Font(identity = "$identity-Regular", data = regular, weight = FontWeight.Normal),
                    Font(identity = "$identity-Medium", data = medium, weight = FontWeight.Medium),
                    Font(identity = "$identity-SemiBold", data = medium, weight = FontWeight.SemiBold),
                    Font(identity = "$identity-Bold", data = medium, weight = FontWeight.Bold),
                )
            } else {
                FontFamily(Font(identity = identity, data = regular, weight = FontWeight.Normal))
            }
        } catch (t: Throwable) {
            AdaptClient.LOGGER.warn("[AdaptFonts] load {} failed: {}", identity, t.toString())
            FontFamily.Default
        }
    }

    private fun loadBytes(vararg paths: String): ByteArray? {
        for (path in paths) {
            try {
                val bytes = AdaptFonts::class.java.getResourceAsStream(path)?.use { it.readAllBytes() }
                if (bytes != null && bytes.isNotEmpty()) {
                    AdaptClient.LOGGER.info("[AdaptFonts] loaded {} ({} bytes)", path, bytes.size)
                    return bytes
                }
            } catch (_: Throwable) {
            }
        }
        return null
    }
}
