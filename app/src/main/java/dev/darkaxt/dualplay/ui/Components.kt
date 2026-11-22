/*
 * DualPlay — shared controller-first UI pieces. GPL-3.0-or-later.
 */
package dev.darkaxt.dualplay.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Accent = Color(0xFF7FD4A8)

private val Scheme = darkColorScheme(
    primary = Accent,
    background = Color(0xFF0E1113),
    surface = Color(0xFF161B1E),
    onBackground = Color(0xFFE4E8E6),
    onSurface = Color(0xFFE4E8E6),
)

@Composable
fun DualPlayTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = Scheme, content = content)

/**
 * A focusable list row: D-pad focus shows the accent outline; A (DPAD_CENTER fallback) or touch activates.
 * [onLeft]/[onRight] make the row a value stepper.
 */
@Composable
fun MenuRow(
    title: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    onLeft: (() -> Unit)? = null,
    onRight: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> onLeft?.let { it(); true } ?: false
                    Key.DirectionRight -> onRight?.let { it(); true } ?: false
                    else -> false
                }
            }
            .background(if (focused) Color(0xFF22302A) else Color.Transparent, shape)
            .border(2.dp, if (focused) Accent else Color.Transparent, shape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            title,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 20.sp,
            fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (detail != null) {
            Text(
                if (onLeft != null || onRight != null) "◂  $detail  ▸" else detail,
                color = if (focused) Accent else Color(0xFF9AA5A0),
                fontSize = 18.sp,
                maxLines = 1,
                modifier = Modifier.padding(start = 16.dp),
            )
        }
    }
}
