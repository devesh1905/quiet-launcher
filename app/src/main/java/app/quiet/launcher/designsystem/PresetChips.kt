package app.quiet.launcher.designsystem

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.quiet.launcher.core.platform.AppCategory

/** A preset filter above an app list: everything, everything distracting, or one category. */
sealed interface AppPreset {
    val label: String
    fun matches(cat: AppCategory?): Boolean

    data object All : AppPreset {
        override val label = "All"
        override fun matches(cat: AppCategory?) = true
    }

    data object Distracting : AppPreset {
        override val label = "Distracting"
        override fun matches(cat: AppCategory?) = cat?.distracting == true
    }

    data class Of(val category: AppCategory) : AppPreset {
        override val label = category.label
        override fun matches(cat: AppCategory?) = cat == category
    }
}

/** Presets that have at least one app, in a useful order. */
fun presetsFor(categories: Map<String, AppCategory>): List<Pair<AppPreset, Int>> {
    if (categories.isEmpty()) return emptyList()
    val counts = categories.values.groupingBy { it }.eachCount()
    val distracting = categories.values.count { it.distracting }
    val order = listOf(
        AppCategory.GAMES, AppCategory.SOCIAL, AppCategory.VIDEO, AppCategory.SHOPPING, AppCategory.NEWS,
        AppCategory.MESSAGING, AppCategory.MUSIC, AppCategory.PRODUCTIVITY,
    )
    return buildList {
        add(AppPreset.All to categories.size)
        if (distracting > 0) add(AppPreset.Distracting to distracting)
        order.forEach { c -> counts[c]?.takeIf { it > 0 }?.let { add(AppPreset.Of(c) to it) } }
    }
}

/**
 * A horizontally scrolling row of preset chips. When a preset other than All is chosen and
 * [onSelectAll] is given, a quiet "Select all" action appears under the row.
 */
@Composable
fun PresetChips(
    presets: List<Pair<AppPreset, Int>>,
    selected: AppPreset,
    onSelect: (AppPreset) -> Unit,
    modifier: Modifier = Modifier,
    gutter: Dp = 24.dp,
    selectAllLabel: String = "Select all",
    onSelectAll: (() -> Unit)? = null,
) {
    if (presets.isEmpty()) return
    val c = Q.colors
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = gutter),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            presets.forEach { (p, n) ->
                val on = p == selected
                val t by animateFloatAsState(if (on) 1f else 0f, spring(0.8f, 600f), label = "preset")
                Box(
                    Modifier.height(44.dp).clip(RoundedCornerShape(22.dp))
                        .background(c.ink.copy(alpha = t))
                        .border(1.dp, if (on) c.ink else c.edge, RoundedCornerShape(22.dp))
                        .pressable(pressedScale = 0.95f) { onSelect(p) }
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText(
                        "${p.label}  $n",
                        style = QType.secondary(15.sp).copy(color = lerp(c.ink, c.onInk, t)),
                    )
                }
            }
        }
        if (onSelectAll != null && selected != AppPreset.All) {
            Box(Modifier.padding(horizontal = gutter).height(44.dp).pressable(onClick = onSelectAll), contentAlignment = Alignment.CenterStart) {
                BasicText(selectAllLabel, style = QType.secondary(15.sp).copy(color = c.ink))
            }
        }
    }
}
