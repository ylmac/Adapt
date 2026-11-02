package com.ylmac.adapt.compose.screens.music

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier as Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ylmac.adapt.compose.theme.MonetScheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image
import java.io.File
import java.net.URI

/**
 * — rounded art with `music_note` (or custom) placeholder.
 * Prefer local path / bytes; fall back to http(s) URL when possible.
 */
@Composable
fun CoverImage(
    source: String = "",
    bytes: ByteArray? = null,
    radius: Dp = 8.dp,
    icon: String = "music_note",
    iconSize: Int = 26,
    mod: Modifier = Modifier,
) {
    var bitmap by remember(source, bytes?.size) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(source, bytes?.size) {
        bitmap = withContext(Dispatchers.IO) {
            decodeCover(source, bytes)
        }
    }

    Box(
        mod
            .clip(RoundedCornerShape(radius))
            .background(MonetScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        Md3Icon(
            name = icon,
            size = iconSize.sp,
            color = MonetScheme.onSurfaceVariant,
        )
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

internal fun decodeCover(source: String?, bytes: ByteArray?): ImageBitmap? {
    try {
        if (bytes != null && bytes.isNotEmpty()) {
            val img = Image.makeFromEncoded(bytes) ?: return null
            return img.toComposeImageBitmap()
        }
        val src = source?.trim().orEmpty()
        if (src.isEmpty()) return null
        val data: ByteArray = when {
            src.startsWith("http://") || src.startsWith("https://") -> {
                URI(src).toURL().openStream().use { it.readBytes() }
            }
            src.startsWith("file:") -> {
                File(URI(src)).takeIf { it.isFile }?.readBytes() ?: return null
            }
            else -> {
                val f = File(src)
                if (!f.isFile) return null
                f.readBytes()
            }
        }
        val img = Image.makeFromEncoded(data) ?: return null
        return img.toComposeImageBitmap()
    } catch (_: Throwable) {
        return null
    }
}
