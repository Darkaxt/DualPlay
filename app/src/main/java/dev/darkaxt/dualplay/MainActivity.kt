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
import dev.darkaxt.dualplay.settings.SettingsList
import dev.darkaxt.dualplay.settings.SettingsStore
import dev.darkaxt.dualplay.settings.settingsSections
import dev.darkaxt.dualplay.library.RomEntry
import dev.darkaxt.dualplay.library.RomLibrary
import dev.darkaxt.dualplay.library.RomResolver
import dev.darkaxt.dualplay.ui.Accent
import dev.darkaxt.dualplay.ui.DualPlayTheme
import dev.darkaxt.dualplay.ui.MenuRow
import dev.darkaxt.dualplay.ui.rememberInitialFocus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import dev.darkaxt.dualplay.ra.RaAccount
import java.io.File

/**
 * Controller-first launcher on the top display: storage access → systems with game counts → games grouped by
 * subfolder → extraction (archives) → [GameActivity].
 *
 * Core settings (from the systems screen) are saved at core level for all games of that core.
 *
 * App-owned test extras (debuggable builds only): `screen=<system folder>` opens that system's list, and
 * `screen=settings:<core id>` that core's settings;
 * `autoLaunch=<substring>` launches the first game whose file name contains it (within `screen` if given).
 */
class MainActivity : ComponentActivity() {

    private sealed interface Screen {
        data object Systems : Screen
        data class Games(val system: GameSystem) : Screen
        data class CoreSettings(val core: CoreSpec) : Screen
        data object Account : Screen
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
            val requested = intent.getStringExtra("screen").orEmpty()
            GameSystem.byFolder(requested)?.let { screen = Screen.Games(it) }
            CoreRegistry.byId(requested.removePrefix("settings:"))?.let { screen = Screen.CoreSettings(it) }
            if (requested == "account") screen = Screen.Account
            pendingAutoLaunch = intent.getStringExtra("autoLaunch")
        }
        loadAccount()
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

    private fun loadAccount() {
        lifecycleScope.launch { raUser = withContext(Dispatchers.IO) { RaAccount(this@MainActivity).load()?.user } }
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
            if (debuggable) {
                for (key in listOf("selfTest", "testCoreSet", "testGameSet")) intent.getStringExtra(key)?.let { game.putExtra(key, it) }
            }
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
                    is Screen.CoreSettings -> CoreSettingsScreen(current.core)
                    Screen.Account -> AccountScreen()
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
        val focus = rememberInitialFocus()
        Header("DualPlay", "Games are read from the ROMs folder on your storage (ES-DE layout).")
        Text(
            "DualPlay needs all-files access to read your ROMs on the SD card. It only reads them; saves stay inside DualPlay.",
            color = Color(0xFFE4E8E6), fontSize = 18.sp,
        )
        Spacer(16)
        MenuRow("Grant access", Modifier.focusRequester(focus)) {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
        }
    }

    @Composable
    private fun SystemsScreen() {
        val lib = library
        val focus = rememberInitialFocus()
        Header("DualPlay", if (lib == null) "Scanning…" else "${lib.values.sumOf { it.size }} games")
        if (lib == null) return
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(GameSystem.entries.size) { index ->
                val system = GameSystem.entries[index]
                val count = lib[system].orEmpty().size
                MenuRow(
                    system.label,
                    if (index == 0) Modifier.focusRequester(focus) else Modifier,
                    detail = "$count",
                ) { if (count > 0) screen = Screen.Games(system) }
            }
            item {
                Text("Settings", color = Accent, fontSize = 16.sp, modifier = Modifier.padding(start = 6.dp, top = 18.dp, bottom = 4.dp))
            }
            items(CoreRegistry.all.size) { index ->
                val core = CoreRegistry.all[index]
                MenuRow(core.label, detail = core.system.uppercase()) { screen = Screen.CoreSettings(core) }
            }
            item {
                MenuRow("RetroAchievements", detail = raUser ?: "Not signed in") { screen = Screen.Account }
            }
        }
    }

    @Composable
    private fun GamesScreen(system: GameSystem) {
        val entries = library?.get(system).orEmpty()
        val focus = rememberInitialFocus(system, entries.size)
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
    }

    /** Signed-in RetroAchievements user name, refreshed when the account screen changes it. */
    private var raUser by mutableStateOf<String?>(null)

    @Composable
    private fun AccountScreen() {
        val account = remember { RaAccount(this) }
        var user by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        var message by remember { mutableStateOf<String?>(null) }
        val focus = rememberInitialFocus(raUser)
        BackHandler { screen = Screen.Systems }
        Header(
            "RetroAchievements",
            "Softcore achievements for supported games. Your password is sent once to RetroAchievements to get a " +
                "sign-in token; only the user name and token are kept, encrypted on this device.",
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.widthIn(max = 720.dp)) {
            val signedIn = raUser
            if (signedIn != null) {
                Text("Signed in as $signedIn", color = Color(0xFFE4E8E6), fontSize = 20.sp)
                MenuRow("Sign out", Modifier.focusRequester(focus)) {
                    account.signOut()
                    raUser = null
                    message = "Signed out."
                    Log.i(TAG, "ra account signed out")
                }
            } else {
                OutlinedTextField(
                    value = user, onValueChange = { user = it.trim() }, singleLine = true,
                    label = { Text("User name") }, modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                )
                OutlinedTextField(
                    value = password, onValueChange = { password = it }, singleLine = true,
                    label = { Text("Password") }, modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                )
                MenuRow(if (busy) "Signing in…" else "Sign in") {
                    if (busy || user.isEmpty() || password.isEmpty()) return@MenuRow
                    busy = true
                    message = null
                    val name = user
                    val secret = password
                    lifecycleScope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching {
                                val version = packageManager.getPackageInfo(packageName, 0).versionName ?: "dev"
                                RaAccount.signIn(name, secret, version).also(account::save)
                            }
                        }
                        password = ""
                        busy = false
                        result.onSuccess {
                            raUser = it.user
                            message = null
                            Log.i(TAG, "ra account signed in user=${it.user}")
                        }.onFailure {
                            message = it.message ?: it.javaClass.simpleName
                            Log.w(TAG, "ra account sign-in failed: ${it.javaClass.simpleName}")
                        }
                    }
                }
            }
            message?.let { Text(it, color = Color(0xFFE0A0A0), fontSize = 16.sp) }
        }
    }

    @Composable
    private fun CoreSettingsScreen(core: CoreSpec) {
        val store = remember { SettingsStore(this) }
        var resolved by remember(core) { mutableStateOf(store.resolve(core, null)) }
        val sections = remember(core) { settingsSections(core, store) }
        BackHandler { screen = Screen.Systems }
        Header("${core.label} settings", "Saved for all ${core.label} games. Games can override them from the in-game menu.")
        SettingsList(
            sections = sections,
            values = resolved.values,
            sources = resolved.sources,
            note = if (store.catalog(core) == null) "Core options appear here after a ${core.label} game has run once." else null,
            onChange = { option, value ->
                store.saveCore(core, mapOf(option.key to value))
                resolved = store.resolve(core, null)
                Log.i(TAG, "core setting ${core.id} ${option.key}=$value")
            },
        )
    }

    @Composable
    private fun ExtractingScreen(state: Screen.Extracting) {
        val focus = rememberInitialFocus()
        Header(state.entry.title, state.entry.system.label)
        if (state.error == null) {
            Text("Preparing… ${state.bytes / (1 shl 20)} MB", color = Color(0xFFE4E8E6), fontSize = 20.sp)
        } else {
            BackHandler { screen = Screen.Games(state.entry.system) }
            Text("Could not open: ${state.error}", color = Color(0xFFE8A0A0), fontSize = 18.sp)
            Spacer(16)
            MenuRow("Back", Modifier.focusRequester(focus)) { screen = Screen.Games(state.entry.system) }
        }
    }

    companion object {
        private const val TAG = GameActivity.TAG
    }
}
