package com.kaiharimoto.neue.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import com.kaiharimoto.neue.kit.H1
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.Numeral
import com.kaiharimoto.neue.theme.Mu

/**
 * Page anatomy (§4): numeral and title on a baseline, a micro-caps line under
 * them, actions to the right, a structural rule below.
 */
@Composable
fun PageHeader(
    numeral: Int?,
    title: String,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val c = Mu.colors
    Row(
        Modifier
            .fillMaxWidth()
            .drawBehind { drawLine(c.ink, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx()) }
            .padding(start = 32.dp, end = 32.dp, top = 32.dp, bottom = 16.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (numeral != null) Numeral(numeral, Modifier.padding(bottom = 6.dp))
                H1(title)
            }
            if (subtitle != null) Micro(subtitle, Modifier.padding(top = 8.dp), color = c.ink45)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

/** How long ago, the family's way: `just now`, `5m ago`, `3h ago`, `2d ago`, then a date. */
fun ago(epochMs: Long, now: Long): String {
    val s = (now - epochMs) / 1000
    return when {
        s < 60 -> "just now"
        s < 3600 -> "${s / 60}m ago"
        s < 86_400 -> "${s / 3600}h ago"
        s < 86_400 * 14 -> "${s / 86_400}d ago"
        else -> java.time.Instant.ofEpochMilli(epochMs).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()
    }
}
