/*
 * DualPlay — launcher. GPL-3.0-or-later.
 */
package dev.darkaxt.dualplay

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import dev.darkaxt.dualplay.library.GameSystem
import dev.darkaxt.dualplay.library.RomEntry
import dev.darkaxt.dualplay.library.RomLibrary
import dev.darkaxt.dualplay.library.RomResolver
import dev.darkaxt.dualplay.ui.Accent
import dev.darkaxt.dualplay.ui.DualPlayTheme
import dev.darkaxt.dualplay.ui.MenuRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Controller-first launcher on the top display: storage access → systems with game counts → games grouped by
 * subfolder → extraction (archives) → [GameActivity].
 *
 * App-owned test extras (debuggable builds only): `screen=<system folder>` opens that system's list;
 * `autoLaunch=<substring>` launches the first game whose file name contains it (within `screen` if given).
 */
class MainActivity : ComponentActivity() {

    private sealed interface Screen {
        data object Systems : Screen
        data class Games(val system: GameSystem) : Screen
        data class Extracting(val entry: RomEntry, val bytes: Long, val error: String? = null) : Screen
    }

    private var hasAccess by mutableStateOf(false)
    private var screen by mutableStateOf<Screen>(Screen.Systems)
    private var library by mutableStateOf<Map<GameSystem, List<RomEntry>>?>(null)
    private var pendingAutoLaunch: String? = null

    private val resolver by lazy { RomResolver(File(cacheDir, "extracted")) }

    private val debuggable: Boolean
        get() = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (debuggable) {
            intent.getStringExtra("screen")?.let(GameSystem::byFolder)?.let { screen = Screen.Games(it) }
            pendingAutoLaunch = intent.getStringExtra("autoLaunch")
        }
        setContent { DualPlayTheme { Root() } }
    }

    override fun onResume() {
        super.onResume()
        val access = Environment.isExternalStorageManager()
        if (access != hasAccess || library == null) {
            hasAccess = access
            if (access) rescan()
        }
    }

    private fun rescan() {
        lifecycleScope.launch {
            val started = System.nanoTime()
            val scanned = withContext(Dispatchers.IO) {
                val lib = RomLibrary(this@MainActivity)
                GameSystem.entries.associateWith { lib.scan(it) }
            }
            library = scanned
            Log.i(TAG, "library scan ms=${(System.nanoTime() - started) / 1_000_000} " +
                scanned.entries.joinToString { "${it.key.folder}=${it.value.size}" })
            pendingAutoLaunch?.let { needle ->
                pendingAutoLaunch = null
                val within = (screen as? Screen.Games)?.system
                val match = scanned.filterKeys { within == null || it == within }.values.flatten()
                    .firstOrNull { it.file.name.contains(needle, ignoreCase = true) }
                Log.i(TAG, "autoLaunch '$needle' -> ${match?.file}")
                match?.let(::launch)
            }
        }
    }

    private fun launch(entry: RomEntry) {
        val returnTo = screen
        screen = Screen.Extracting(entry, 0)
        lifecycleScope.launch {
            val started = System.nanoTime()
            val playable = try {
                withContext(Dispatchers.IO) {
                    resolver.resolve(entry.file, entry.system.core.extensions) { bytes ->
                        runOnUiThread { if ((screen as? Screen.Extracting)?.entry == entry) screen = Screen.Extracting(entry, bytes) }
                    }
                }
            } catch (error: Exception) {
                Log.w(TAG, "resolve failed ${entry.file}", error)
                screen = Screen.Extracting(entry, 0, error.message ?: error.javaClass.simpleName)
                return@launch
            }
            Log.i(TAG, "resolved ${entry.file.name} -> ${playable.absolutePath} bytes=${playable.length()} " +
                "ms=${(System.nanoTime() - started) / 1_000_000}")
            screen = returnTo
            val game = Intent(this@MainActivity, GameActivity::class.java)
                .putExtra("rom", playable.absolutePath)
                .putExtra("core", entry.system.core.id)
                .putExtra("title", entry.title)
            if (debuggable) intent.getStringExtra("selfTest")?.let { game.putExtra("selfTest", it) }
            startActivity(game)
        }
    }

    @Composable
    private fun Root() {
        Column(
            Modifier.fillMaxSize().background(Color(0xFF0E1113)).padding(horizontal = 48.dp, vertical = 28.dp),
        ) {
            when {
                !hasAccess -> AccessScreen()
                else -> when (val current = screen) {
                    Screen.Systems -> SystemsScreen()
                    is Screen.Games -> GamesScreen(current.system)
                    is Screen.Extracting -> ExtractingScreen(current)
                }
            }
        }
    }

    @Composable
    private fun Header(title: String, subtitle: String? = null) {
        Text(title, color = Accent, fontSize = 30.sp)
        if (subtitle != null) Text(subtitle, color = Color(0xFF9AA5A0), fontSize = 16.sp)
        Spacer(16)
    }

    @Composable
    private fun Spacer(height: Int) = androidx.compose.foundation.layout.Spacer(Modifier.padding(top = height.dp))

    @Composable
    private fun AccessScreen() {
        val focus = remember { FocusRequester() }
        Header("DualPlay", "Games are read from the ROMs folder on your storage (ES-DE layout).")
        Text(
            "DualPlay needs all-files access to read your ROMs on the SD card. It only reads them; saves stay inside DualPlay.",
            color = Color(0xFFE4E8E6), fontSize = 18.sp,
        )
        Spacer(16)
        MenuRow("Grant access", Modifier.focusRequester(focus)) {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
        }
        LaunchedEffect(Unit) { focus.requestFocus() }
    }

    @Composable
    private fun SystemsScreen() {
        val lib = library
        val focus = remember { FocusRequester() }
        Header("DualPlay", if (lib == null) "Scanning…" else "${lib.values.sumOf { it.size }} games")
        if (lib == null) return
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            GameSystem.entries.forEachIndexed { index, system ->
                val count = lib[system].orEmpty().size
                MenuRow(
                    system.label,
                    if (index == 0) Modifier.focusRequester(focus) else Modifier,
                    detail = "$count",
                ) { if (count > 0) screen = Screen.Games(system) }
            }
        }
        LaunchedEffect(Unit) { focus.requestFocus() }
    }

    @Composable
    private fun GamesScreen(system: GameSystem) {
        val entries = library?.get(system).orEmpty()
        val focus = remember { FocusRequester() }
        BackHandler { screen = Screen.Systems }
        Header(system.label, "${entries.size} games")
        val rows: List<Any> = remember(entries) {
            buildList {
                var lastGroup: String? = null
                for (entry in entries) {
                    if (entry.group != lastGroup) {
                        lastGroup = entry.group
                        if (entry.group.isNotEmpty()) add(entry.group)
                    }
                    add(entry)
                }
            }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val firstGame = rows.indexOfFirst { it is RomEntry }
            items(rows.size) { index ->
                when (val row = rows[index]) {
                    is String -> Text(row, color = Accent, fontSize = 16.sp, modifier = Modifier.padding(start = 6.dp, top = 14.dp, bottom = 4.dp))
                    is RomEntry -> MenuRow(
                        row.title,
                        if (index == firstGame) Modifier.focusRequester(focus) else Modifier,
                        detail = row.file.extension.lowercase(),
                    ) { launch(row) }
                }
            }
        }
        LaunchedEffect(system, entries.size) { if (entries.isNotEmpty()) runCatching { focus.requestFocus() } }
    }

    @Composable
    private fun ExtractingScreen(state: Screen.Extracting) {
        val focus = remember { FocusRequester() }
        Header(state.entry.title, state.entry.system.label)
        if (state.error == null) {
            Text("Preparing… ${state.bytes / (1 shl 20)} MB", color = Color(0xFFE4E8E6), fontSize = 20.sp)
        } else {
            BackHandler { screen = Screen.Games(state.entry.system) }
            Text("Could not open: ${state.error}", color = Color(0xFFE8A0A0), fontSize = 18.sp)
            Spacer(16)
            MenuRow("Back", Modifier.focusRequester(focus)) { screen = Screen.Games(state.entry.system) }
            LaunchedEffect(Unit) { focus.requestFocus() }
        }
    }

    companion object {
        private const val TAG = GameActivity.TAG
    }
}
