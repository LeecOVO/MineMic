package com.aiaixyz.minemic.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp

/** Measure the user's actual font size before choosing an inline or stacked layout. */
@Composable
internal fun textWidth(text: AnnotatedString, style: TextStyle): Dp {
    val measurer = rememberTextMeasurer()
    val width = measurer.measure(text, style, softWrap = false, maxLines = 1).size.width
    return with(LocalDensity.current) { width.toDp() }
}

@Composable
internal fun textWidth(text: String, style: TextStyle): Dp = textWidth(AnnotatedString(text), style)

@Composable
internal fun ControlHeading(
    label: String, reserveLabel: String = label,
    style: TextStyle = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
    control: @Composable () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Reserve the longest label to keep related controls in the same arrangement.
        if (textWidth(reserveLabel, style) + 64.dp > maxWidth) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(label, style = style)
                Box(Modifier.align(Alignment.End)) { control() }
            }
        } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(label, Modifier.weight(1f), style = style)
            control()
        }
    }
}
