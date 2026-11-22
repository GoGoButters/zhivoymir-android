package com.movtery.zalithlauncher.zhivoymir

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.movtery.zalithlauncher.R

/** Оформление как у ПК-окна (dev/launcher): фон, пиксельный шрифт Press Start 2P (OFL, assets/zhivoymir/OFL.txt) */
object ZhivoyMirStyle {
    val font = FontFamily(Font(R.font.zhivoymir_font))

    val TextLight = Color(0xFFE0E0E0)
    val TextHint = Color(0xFFA0A0A0)
    val TextError = Color(0xFFFF5555)
    val Splash = Color(0xFFFFFF00)
    val FieldBorder = Color(0xFFA0A0A0)

    fun pixel(size: TextUnit, color: Color = Color.White) = TextStyle(
        fontFamily = font,
        fontSize = size,
        lineHeight = size * 1.5f,
        color = color,
        shadow = Shadow(color = Color(color.red / 4, color.green / 4, color.blue / 4, color.alpha), offset = Offset(3f, 3f)),
        textAlign = TextAlign.Center,
    )
}

/** Фоновая картинка с затемнением снизу, чтобы поля и кнопки читались */
@Composable
fun ZhivoyMirBackground(content: @Composable BoxScope.() -> Unit) {
    Box(modifier = Modifier.fillMaxSize()) {
        Image(
            painter = painterResource(R.drawable.zhivoymir_background),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(0.2f to Color.Transparent, 1f to Color(0xAA000000)))
        )
        content()
    }
}

@Composable
fun ZhivoyMirTitle(modifier: Modifier = Modifier) {
    Text(
        text = "ЖИВОЙ МИР",
        modifier = modifier,
        style = ZhivoyMirStyle.pixel(30.sp, Color(0xFFFFF2D0)).copy(
            shadow = Shadow(Color(0xFF3F2A10), Offset(6f, 6f))
        ),
    )
}

/** Обязательная надпись (условия лицензии ZL2) */
@Composable
fun ZhivoyMirNotice(modifier: Modifier = Modifier) {
    Text(
        text = "Неофициальная модификация Zalith Launcher 2",
        modifier = modifier
            .background(Color(0x99000000))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        style = ZhivoyMirStyle.pixel(9.sp, ZhivoyMirStyle.Splash),
    )
}

/** Кнопка в стиле Minecraft (как McButton ПК-окна) */
@Composable
fun McButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    fontSize: TextUnit = 14.sp,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val face = when {
        !enabled -> Color(0xFF4A4A4A)
        pressed -> Color(0xFF8A8FC8)
        else -> Color(0xFF707070)
    }
    val light = if (pressed) Color(0xFFA8ADE8) else Color(0xFF8C8C8C)
    val dark = if (pressed) Color(0xFF5A5F98) else Color(0xFF4C4C4C)
    Box(
        modifier = modifier
            .border(2.dp, if (pressed) Color.White else Color(0xFF2A2A2A))
            .padding(2.dp)
            .drawBehind {
                drawRect(face)
                val edge = 3.dp.toPx()
                drawRect(light, size = Size(size.width, edge))
                drawRect(dark, topLeft = Offset(0f, size.height - edge), size = Size(size.width, edge))
            }
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = ZhivoyMirStyle.pixel(
                fontSize,
                when {
                    !enabled -> ZhivoyMirStyle.TextHint
                    pressed -> Color(0xFFFFFFA0)
                    else -> ZhivoyMirStyle.TextLight
                }
            ),
        )
    }
}
