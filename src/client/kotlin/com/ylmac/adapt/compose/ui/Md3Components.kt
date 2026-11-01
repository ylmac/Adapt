package com.ylmac.adapt.compose.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ylmac.adapt.compose.host.LocalAdaptTextInput
import com.ylmac.adapt.compose.theme.AdaptFonts
import com.ylmac.adapt.compose.theme.MonetScheme

@Composable
fun Md3GlassPanel(
    mod: Modifier = Modifier,
    radius: Dp = 16.dp,
    alpha: Float = 0.62f,
    color: Color = MonetScheme.surfaceContainer,
    content: @Composable () -> Unit,
) {
    Box(
        mod
            .clip(RoundedCornerShape(radius))
            .background(color.copy(alpha = alpha)),
    ) {
        content()
    }
}

@Composable
fun Md3TextField(
    value: String,
    onValueChange: (String) -> Unit,
    mod: Modifier = Modifier,
    label: String = "",
    outlined: Boolean = false,
) {
    val focus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    // 受控值的最新快照：charTyped 回调里读它，避免闭包陈旧
    val latest = remember { mutableStateOf(value) }
    latest.value = value
    val textInput = LocalAdaptTextInput.current
    fun arm() {
        val input = textInput ?: return
        input.bindField(
            append = { ch ->
                val next = latest.value + ch
                latest.value = next
                onValueChange(next)
            },
            backspace = {
                val cur = latest.value
                if (cur.isNotEmpty()) {
                    val next = cur.dropLast(1)
                    latest.value = next
                    onValueChange(next)
                }
            },
        )
        com.ylmac.adapt.render.Probe.hit("in.bind")
    }
    DisposableEffect(focused, textInput) {
        if (focused) arm()
        onDispose {
            if (focused) textInput?.unbindField()
        }
    }
    Column(mod.fillMaxWidth()) {
        if (label.isNotEmpty()) {
            Text(
                label,
                color = MonetScheme.onSurfaceVariant,
                fontSize = 12.sp,
                fontFamily = AdaptFonts.PingFang,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        BasicTextField(
            value = value,
            onValueChange = {
                latest.value = it
                onValueChange(it)
            },
            singleLine = true,
            textStyle = TextStyle(
                color = MonetScheme.onSurface,
                fontSize = 15.sp,
                fontFamily = AdaptFonts.PingFang,
            ),
            cursorBrush = SolidColor(MonetScheme.primary),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focus)
                .onFocusChanged {
                    focused = it.isFocused
                    if (it.isFocused) arm()
                }
                .pointerInput(textInput) {
                    detectTapGestures(
                        onPress = {
                            arm()
                            focus.requestFocus()
                            tryAwaitRelease()
                        },
                    )
                }
                .then(
                    if (outlined) {
                        Modifier
                            .border(1.dp, if (focused) MonetScheme.primary else MonetScheme.outline, RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    } else {
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(MonetScheme.surfaceContainerLow)
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    },
                ),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty() && label.isEmpty()) {
                        Text("输入…", color = MonetScheme.onSurfaceVariant, fontFamily = AdaptFonts.PingFang)
                    }
                    inner()
                }
            },
        )
    }
}

@Composable
fun Md3Card(
    mod: Modifier = Modifier,
    radius: Dp = 16.dp,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    bordered: Boolean = false,
    container: Color = MonetScheme.surfaceContainerLow.copy(alpha = 0.9f),
    content: @Composable () -> Unit,
) {
    Box(
        mod
            .fillMaxWidth()
            .clip(RoundedCornerShape(radius))
            .background(container)
            .then(
                if (bordered) Modifier.border(1.dp, MonetScheme.outlineVariant, RoundedCornerShape(radius))
                else Modifier,
            )
            .padding(contentPadding),
    ) {
        content()
    }
}

@Composable
fun Md3TopAppBar(
    title: String,
    mod: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        mod
            .fillMaxWidth()
            .height(64.dp)
            .padding(start = 12.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(12.dp))
        }
        Text(
            title,
            Modifier.weight(1f),
            color = MonetScheme.primary,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = AdaptFonts.PingFang,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (trailing != null) trailing()
    }
}

@Composable
fun Md3FilledButton(
    text: String,
    onClick: () -> Unit,
    mod: Modifier = Modifier,
) {
    Box(
        mod
            .clip(RoundedCornerShape(20.dp))
            .background(MonetScheme.primary)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClick,
            )
            .padding(horizontal = 20.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = MonetScheme.onPrimary,
            fontWeight = FontWeight.Medium,
            fontFamily = AdaptFonts.PingFang,
        )
    }
}

@Composable
fun Md3OutlinedButton(
    text: String,
    onClick: () -> Unit,
    mod: Modifier = Modifier,
) {
    Box(
        mod
            .clip(RoundedCornerShape(20.dp))
            .border(1.dp, MonetScheme.outline, RoundedCornerShape(20.dp))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClick,
            )
            .padding(horizontal = 20.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = MonetScheme.primary,
            fontWeight = FontWeight.Medium,
            fontFamily = AdaptFonts.PingFang,
        )
    }
}
