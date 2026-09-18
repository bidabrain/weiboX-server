package com.weibox.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * 按倍率生成排版。倍率来自设置里的「字体大小」，1f 等于改动前的固定值。
 *
 * 注意这是叠加在系统字体大小之上的：系统设为特大、app 再设为超大，两者相乘。
 */
fun weiboXTypography(scale: Float = 1f): Typography {
    fun scaled(value: Float): TextUnit = (value * scale).sp
    return Typography(
        bodyLarge    = TextStyle(fontSize = scaled(15f), lineHeight = scaled(22f)),
        bodyMedium   = TextStyle(fontSize = scaled(13f), lineHeight = scaled(18f)),
        bodySmall    = TextStyle(fontSize = scaled(12f), lineHeight = scaled(16f)),
        labelSmall   = TextStyle(fontSize = scaled(11f), color = Color.Unspecified),
        titleLarge   = TextStyle(fontSize = scaled(20f), fontWeight = FontWeight.Bold),
        titleMedium  = TextStyle(fontSize = scaled(16f), fontWeight = FontWeight.SemiBold),
        titleSmall   = TextStyle(fontSize = scaled(14f), fontWeight = FontWeight.Medium)
    )
}
