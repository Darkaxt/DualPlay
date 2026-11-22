/*
 * DualPlay — settings list (launcher and in-game). GPL-3.0-or-later.
 */
package dev.darkaxt.dualplay.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.darkaxt.dualplay.CoreSpec
import dev.darkaxt.dualplay.ui.Accent
import dev.darkaxt.dualplay.ui.MenuRow
import dev.darkaxt.dualplay.ui.rememberInitialFocus

data class SettingsSection(val title: String, val options: List<OptionInfo>, val collapsible: Boolean = false)

/** Frontend settings, then the core's curated options, then every other option under Advanced. */
fun settingsSections(core: CoreSpec, store: SettingsStore): List<SettingsSection> {
    val catalog = store.catalog(core).orEmpty().filter { it.key !in core.options }
    val curated = core.curatedOptions.mapNotNull { key -> catalog.firstOrNull { it.key == key } }
    return listOf(
        SettingsSection("DualPlay", store.frontendOptions()),
        SettingsSection(core.label, curated),
        SettingsSection("Advanced", catalog - curated.toSet(), collapsible = true),
    ).filter { it.options.isNotEmpty() }
}

fun displayValue(option: OptionInfo, value: String): String = when {
    option.key == Frontend.SHADER -> if (value.isEmpty()) "None" else value.substringAfterLast('/').removeSuffix(".slangp")
    value == ON -> "On"
    value == OFF -> "Off"
    else -> value
}

private fun step(option: OptionInfo, current: String, delta: Int): String {
    val index = option.values.indexOf(current).let { if (it < 0) option.values.indexOf(option.default).coerceAtLeast(0) else it }
    return option.values[(index + delta + option.values.size) % option.values.size]
}

/**
 * Controller-first option list: Left/Right step a value, A steps forward. Values set at game level show
 * "· game". [note] is shown above the list (for example where changes are saved).
 */
@Composable
fun SettingsList(
    sections: List<SettingsSection>,
    values: Map<String, String>,
    sources: Map<String, Source>,
    note: String?,
    onChange: (OptionInfo, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var advancedOpen by remember { mutableStateOf(false) }
    val firstKey = sections.firstOrNull()?.options?.firstOrNull()?.key
    val focus = rememberInitialFocus(firstKey)
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (note != null) item { Text(note, color = Color(0xFF9AA5A0), fontSize = 15.sp, modifier = Modifier.padding(bottom = 8.dp)) }
        if (sections.isEmpty()) item { Text("No settings.", color = Color(0xFF9AA5A0), fontSize = 16.sp) }
        for (section in sections) {
            item(key = "h:" + section.title) {
                if (section.collapsible) {
                    MenuRow(
                        section.title,
                        detail = if (advancedOpen) "hide" else "${section.options.size} options",
                    ) { advancedOpen = !advancedOpen }
                } else {
                    Text(section.title, color = Accent, fontSize = 16.sp, modifier = Modifier.padding(start = 6.dp, top = 12.dp, bottom = 4.dp))
                }
            }
            if (section.collapsible && !advancedOpen) continue
            for (option in section.options) {
                item(key = option.key) {
                    val current = values[option.key] ?: option.default
                    val marker = if (sources[option.key] == Source.GAME) "  · game" else ""
                    MenuRow(
                        option.label,
                        if (option.key == firstKey) Modifier.focusRequester(focus) else Modifier,
                        detail = displayValue(option, current) + marker,
                        onLeft = { onChange(option, step(option, current, -1)) },
                        onRight = { onChange(option, step(option, current, 1)) },
                    ) { onChange(option, step(option, current, 1)) }
                }
            }
        }
    }
}
