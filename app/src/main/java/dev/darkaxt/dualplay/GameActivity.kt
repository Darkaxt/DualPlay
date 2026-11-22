/*
 * DualPlay — game screen. GPL-3.0-or-later.
 */
package dev.darkaxt.dualplay

import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.Display
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.compose.BackHandler
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.swordfish.libretrodroid.GLRetroView
import com.swordfish.libretrodroid.GLRetroViewData
import com.swordfish.libretrodroid.LibretroDroid
import com.swordfish.libretrodroid.ShaderConfig
import com.swordfish.libretrodroid.Variable
import dev.darkaxt.dualplay.ra.RaAccount
import dev.darkaxt.dualplay.ui.Accent
import dev.darkaxt.dualplay.ui.DualPlayTheme
import dev.darkaxt.dualplay.ui.MenuRow
import dev.darkaxt.dualplay.ui.rememberInitialFocus
import dev.darkaxt.dualplay.dualdex.DualDexHosting
import dev.darkaxt.dualplay.dualdex.DualDexPresentation
import dev.darkaxt.dualplay.settings.Frontend
import dev.darkaxt.dualplay.settings.ON
import dev.darkaxt.dualplay.settings.OptionInfo
import dev.darkaxt.dualplay.settings.Resolved
import dev.darkaxt.dualplay.settings.SettingsList
import dev.darkaxt.dualplay.settings.SettingsStore
import dev.darkaxt.dualplay.settings.Source
import dev.darkaxt.dualplay.settings.settingsSections
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

/**
 * Runs one game: the core on the default (top) display through GLRetroView, and on the secondary display
 * either the DS bottom screen (stacked dual-screen cores) or the DualDex stand-in, both reading core memory
 * in-process.
 *
 * Battery saves live in `files/saves/<core>/<title>.srm` (loaded at start, written on pause, every 30 s when
 * changed, and on quit). Save states live in `files/states/<core>/<title>.st<slot>`. Back, Mode or
 * Select+Start opens the in-game menu, which pauses the core.
 *
 * Intent extras: `rom` (absolute path of a playable file), `core` (core id; default by extension), `title`
 * (save-file name; default the ROM file name), `addr` / `len` (hex bus address and byte count of the watched
 * region), `opt` (comma-separated `key=value` core options).
 * Settings resolve game > core > defaults ([SettingsStore]). Changes made on the in-game Settings page apply
 * at once where the core allows; leaving the page asks whether to save them for this game, for all games of
 * the core, or for this session only. Hotkeys: hold L2 to rewind (when enabled), hold R2 to fast-forward.
 *
 * App-owned test extras (debuggable builds only): `rewindMb`, `rewindInterval`, `runahead`, `rewindTest`,
 * `ra`, `shader` (override settings); `testCoreSet` / `testGameSet` (`key=value,...` written to the core or
 * game layer before launch); `selfTest` (comma-separated steps, 3 s apart: `states`, `menu`, `settings`,
 * `scope`, `saveGame`, `saveCore`, `shader:<preset>`, `resolve`, `wait`, `quit`, `recreate`). A recreated
 * instance does not run them again.
 */
class GameActivity : AppCompatActivity() {

    private var retroView: GLRetroView? = null
    private var presentation: DualDexPlaceholder? = null
    private var dualDex: DualDexPresentation? = null
    private var bottomScreen: BottomScreenPresentation? = null
    private var probe: MemoryProbe? = null
    private var menuView: ComposeView? = null
    @Volatile private var achievements: RetroAchievementsBridge? = null
    @Volatile private var netcmd: NetworkCommandServer? = null

    private lateinit var core: CoreSpec
    private lateinit var title: String
    private lateinit var sramFile: File
    private lateinit var stateDir: File
    @Volatile private var lastSramHash: Int? = null
    /** Set in onDestroy, also for a recreation (isFinishing stays false then), so the runtime thread stops. */
    @Volatile private var destroyed = false
    /** This instance's view rendered a frame (the global frame count may still be the replaced instance's). */
    @Volatile private var firstFrame = false
    /** A state written by the instance this one replaces (configuration change Android cannot hand over). */
    private var resumeState: File? = null
    private var recreated = false

    private val menuOpen = mutableStateOf(false)
    private val menuSlot = mutableIntStateOf(1)
    private val menuMessage = mutableStateOf<String?>(null)
    private var selectHeld = false
    private var startHeld = false

    private enum class Page { MAIN, SETTINGS, SCOPE }
    private val menuPage = mutableStateOf(Page.MAIN)

    private lateinit var store: SettingsStore
    /** Saved settings resolved for this game. */
    private val saved = mutableStateOf(Resolved(emptyMap(), emptyMap()))
    /** Changes kept for this session only. */
    private val session = mutableStateMapOf<String, String>()
    /** Changes made on the Settings page and not yet given a scope. */
    private val pending = mutableStateMapOf<String, String>()

    private val fastForward = mutableStateOf(false)
    private var fastForwardLatched = false
    private var ffKey = false
    private var ffAxis = false
    private var rewindKey = false
    private var rewindAxis = false
    private var rewindingNow = false

    private val debuggable: Boolean
        get() = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        logLifecycle(savedInstanceState)
        hideSystemBars()

        val rom = intent.getStringExtra("rom")?.let(::File)?.takeIf(File::canRead)
        if (rom == null) {
            showError("No readable ROM was passed (extra `rom`).")
            return
        }
        val resolvedCore = intent.getStringExtra("core")?.let(CoreRegistry::byId) ?: CoreRegistry.forRom(rom)
        if (resolvedCore == null) {
            showError("No core for ${rom.name}.")
            return
        }
        core = resolvedCore
        title = safeName(intent.getStringExtra("title") ?: rom.nameWithoutExtension)
        val saves = savesDirectory(core).apply { mkdirs() }
        sramFile = File(saves, "$title.srm")
        migratePrivateSave(core, sramFile)
        stateDir = File(filesDir, "states/${core.id}").apply { mkdirs() }
        store = SettingsStore(this)
        if (debuggable) {
            parseOptions(intent.getStringExtra("testCoreSet")).takeIf { it.isNotEmpty() }?.let { store.saveCore(core, it) }
            parseOptions(intent.getStringExtra("testGameSet")).takeIf { it.isNotEmpty() }?.let { store.saveGame(core, title, it) }
        }
        saved.value = store.resolve(core, title)
        restoreMap(savedInstanceState?.getBundle(STATE_SESSION), session)
        restoreMap(savedInstanceState?.getBundle(STATE_PENDING), pending)
        recreated = savedInstanceState != null
        resumeState = resumeFile().takeIf { savedInstanceState?.getBoolean(STATE_RESUME) == true && it.isFile }
        logSettings("launch")
        val coreVariables = store.launchVariables(core, saved.value).associate { it.key!! to it.value!! } +
            (session + pending).filterKeys { !Frontend.isFrontend(it) && it !in core.options } +
            parseOptions(intent.getStringExtra("opt"))
        Log.i(TAG, "Loading ${rom.absolutePath} with core ${core.id} as '$title' options $coreVariables")

        val system = File(filesDir, "system").apply { mkdirs() }
        val savedRam = sramFile.takeIf(File::isFile)?.readBytes()
        lastSramHash = savedRam?.contentHashCode()
        val data = GLRetroViewData(this).apply {
            coreFilePath = core.library
            variables = coreVariables.map { Variable(it.key, it.value) }.toTypedArray()
            gameFilePath = rom.absolutePath
            systemDirectory = system.absolutePath
            savesDirectory = saves.absolutePath
            saveRAMState = savedRam
            shader = ShaderConfig.Sharp
            preferLowLatencyAudio = true
        }
        Log.i(TAG, "sram load ${sramFile.name} bytes=${savedRam?.size ?: 0}")

        val view = GLRetroView(this, data)
        retroView = view
        lifecycleScope.launch {
            view.getGLRetroEvents().first { it is GLRetroView.GLRetroEvents.FrameRendered }
            firstFrame = true
        }
        lifecycle.addObserver(view)
        val menu = ComposeView(this).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            setContent { DualPlayTheme { InGameMenu() } }
        }
        menuView = menu
        setContentView(FrameLayout(this).apply {
            addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(menu, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        })
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        view.requestFocus()

        val watch = MemoryProbe.Region(
            address = intent.getStringExtra("addr")?.toLongOrNull(16) ?: core.probeAddress,
            length = intent.getStringExtra("len")?.toIntOrNull() ?: core.probeLength,
        )
        if (core.stackedDualScreen) view.setFrameCrop(0f, 0f, 1f, 0.5f)
        showPresentation(watch, title, core)
        startRuntime(rom)
    }

    // ---- In-game menu ----

    @Composable
    private fun InGameMenu() {
        if (!menuOpen.value) return
        BackHandler { menuBack() }
        val page = menuPage.value
        Box(Modifier.fillMaxSize().background(Color(0x99000000))) {
            Column(
                Modifier.fillMaxHeight().width(if (page == Page.SETTINGS) 680.dp else 440.dp)
                    .background(Color(0xF0101416)).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(title, color = Accent, fontSize = 18.sp, maxLines = 2)
                Text(
                    if (page == Page.SETTINGS) "${core.label} · Settings" else core.label,
                    color = Color(0xFF9AA5A0), fontSize = 14.sp, modifier = Modifier.padding(bottom = 8.dp),
                )
                menuMessage.value?.let { Text(it, color = Color(0xFFB8C4BE), fontSize = 15.sp) }
                when (page) {
                    Page.MAIN -> MainPage()
                    Page.SETTINGS -> SettingsList(
                        sections = remember { settingsSections(core, store) },
                        values = saved.value.values + session + pending,
                        sources = saved.value.sources,
                        note = "Changes apply now where possible. Back to choose where to save them.",
                        onChange = ::changeSetting,
                    )
                    Page.SCOPE -> ScopePage()
                }
            }
        }
    }

    @Composable
    private fun MainPage() {
        val first = rememberInitialFocus()
        MenuRow("Resume", Modifier.focusRequester(first)) { closeMenu() }
        MenuRow(
            "State slot", detail = menuSlot.intValue.toString(),
            onLeft = { menuSlot.intValue = (menuSlot.intValue + SLOTS - 2) % SLOTS + 1 },
            onRight = { menuSlot.intValue = menuSlot.intValue % SLOTS + 1 },
        ) { menuSlot.intValue = menuSlot.intValue % SLOTS + 1 }
        MenuRow("Save state", detail = stateLabel(menuSlot.intValue)) { saveState(menuSlot.intValue) }
        MenuRow("Load state") { if (loadState(menuSlot.intValue)) closeMenu() }
        MenuRow("Fast-forward", detail = if (fastForward.value) "On ×${ffSpeed()}" else "Off") {
            fastForwardLatched = !fastForwardLatched
            updateHotkeys()
        }
        MenuRow("Settings") {
            menuMessage.value = null
            menuPage.value = Page.SETTINGS
        }
        MenuRow("Reset") { retroView?.reset(false); closeMenu() }
        MenuRow("Quit") { quitGame() }
    }

    @Composable
    private fun ScopePage() {
        val first = rememberInitialFocus()
        Text(
            "Save ${pending.size} changed setting${if (pending.size == 1) "" else "s"} for:",
            color = Color(0xFFE4E8E6), fontSize = 18.sp, modifier = Modifier.padding(vertical = 8.dp),
        )
        MenuRow("This game", Modifier.focusRequester(first)) { commitPending(Scope.GAME) }
        MenuRow("All ${core.label} games") { commitPending(Scope.CORE) }
        MenuRow("This session only") { commitPending(Scope.SESSION) }
    }

    private enum class Scope { GAME, CORE, SESSION }

    private fun menuBack() {
        when (menuPage.value) {
            Page.MAIN -> closeMenu()
            Page.SETTINGS -> menuPage.value = if (pending.isEmpty()) Page.MAIN else Page.SCOPE
            Page.SCOPE -> menuPage.value = Page.SETTINGS
        }
    }

    private fun commitPending(scope: Scope) {
        val changes = pending.toMap()
        when (scope) {
            Scope.GAME -> store.saveGame(core, title, changes)
            Scope.CORE -> {
                store.saveCore(core, changes)
                // This game follows the new core-level values rather than an older game override.
                store.clearGame(core, title, changes.keys)
            }
            Scope.SESSION -> session.putAll(changes)
        }
        pending.clear()
        if (scope != Scope.SESSION) {
            changes.keys.forEach(session::remove)
            saved.value = store.resolve(core, title)
        }
        Log.i(TAG, "settings saved scope=${scope.name.lowercase()} " + changes.entries.joinToString(" ") { "${it.key}=${it.value}" })
        logSettings("saved")
        menuMessage.value = when (scope) {
            Scope.GAME -> "Saved for this game."
            Scope.CORE -> "Saved for all ${core.label} games."
            Scope.SESSION -> "Kept for this session."
        }
        menuPage.value = Page.MAIN
    }

    private fun effective(key: String): String? = pending[key] ?: session[key] ?: saved.value[key]

    private fun ffSpeed(): Int = effective(Frontend.FF_SPEED)?.toIntOrNull() ?: 3

    private fun changeSetting(option: OptionInfo, value: String) {
        pending[option.key] = value
        when (option.key) {
            Frontend.SHADER -> LibretroDroid.setShaderPreset(if (value.isEmpty()) "" else store.shaderFile(value).absolutePath)
            Frontend.REWIND, Frontend.REWIND_MB -> applyRewind()
            Frontend.RUN_AHEAD -> LibretroDroid.setRunAhead(value.toIntOrNull() ?: 0)
            Frontend.FF_SPEED -> if (fastForward.value) retroView?.frameSpeed = ffSpeed()
            else -> if (!option.restart) retroView?.updateVariables(Variable(option.key, value))
        }
        menuMessage.value = if (option.restart) "${option.label.replace(" (Restart)", "")}: applies on the next launch." else null
        Log.i(TAG, "setting changed ${option.key}=$value live=${!option.restart}")
    }

    private fun applyRewind() {
        val on = effective(Frontend.REWIND) == ON
        val mb = effective(Frontend.REWIND_MB)?.toIntOrNull() ?: 64
        LibretroDroid.setRewind(if (on) mb * 1024L * 1024L else 0L, 1)
    }

    private fun logSettings(label: String) {
        val r = saved.value
        val keys = r.values.keys.filter { Frontend.isFrontend(it) || r.sources[it] != Source.DEFAULT }
        Log.i(TAG, "settings $label " + keys.joinToString(" ") { "$it=${r.values[it]}(${r.sources[it]?.name?.lowercase()})" })
    }

    // ---- Hotkeys: hold L2 to rewind, hold R2 to fast-forward (buttons or analog triggers) ----

    private fun updateHotkeys() {
        val rewind = rewindKey || rewindAxis
        if (rewind != rewindingNow) {
            rewindingNow = rewind
            LibretroDroid.setRewinding(rewind)
        }
        val ff = ffKey || ffAxis || fastForwardLatched
        if (ff != fastForward.value) {
            fastForward.value = ff
            retroView?.frameSpeed = if (ff) ffSpeed() else 1
            Log.i(TAG, "fast-forward ${if (ff) "x${ffSpeed()}" else "off"}")
        }
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (!menuOpen.value && retroView != null && event.isFromSource(InputDevice.SOURCE_JOYSTICK)) {
            rewindAxis = maxOf(event.getAxisValue(MotionEvent.AXIS_LTRIGGER), event.getAxisValue(MotionEvent.AXIS_BRAKE)) > 0.6f
            ffAxis = maxOf(event.getAxisValue(MotionEvent.AXIS_RTRIGGER), event.getAxisValue(MotionEvent.AXIS_GAS)) > 0.6f
            updateHotkeys()
        }
        return super.dispatchGenericMotionEvent(event)
    }

    private fun stateLabel(slot: Int): String {
        val file = stateFile(slot)
        return if (file.isFile) java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
            .format(java.util.Date(file.lastModified())) else "empty"
    }

    private fun openMenu() {
        if (menuOpen.value || retroView == null) return
        LibretroDroid.setPaused(true)
        menuMessage.value = null
        menuPage.value = Page.MAIN
        rewindKey = false
        rewindAxis = false
        ffKey = false
        ffAxis = false
        updateHotkeys()
        menuOpen.value = true
        // The game view holds input focus; hand it to the overlay so the first row shows focus.
        menuView?.requestFocus()
        persistSram("menu")
    }

    private fun closeMenu() {
        if (!menuOpen.value) return
        menuOpen.value = false
        LibretroDroid.setPaused(false)
        retroView?.requestFocus()
    }

    private fun stateFile(slot: Int) = File(stateDir, "$title.st$slot")

    private fun saveState(slot: Int): Boolean {
        val view = retroView ?: return false
        val started = System.nanoTime()
        val bytes = view.serializeState(false)
        if (bytes.isEmpty()) {
            menuMessage.value = "This core cannot save states."
            return false
        }
        writeAtomically(stateFile(slot), bytes)
        val ms = (System.nanoTime() - started) / 1_000_000
        Log.i(TAG, "state save slot=$slot bytes=${bytes.size} ms=$ms")
        menuMessage.value = "Saved slot $slot."
        menuSlot.intValue = slot
        return true
    }

    private fun loadState(slot: Int): Boolean {
        val view = retroView ?: return false
        val file = stateFile(slot)
        if (!file.isFile) {
            menuMessage.value = "Slot $slot is empty."
            return false
        }
        val started = System.nanoTime()
        val ok = view.unserializeState(file.readBytes(), false)
        Log.i(TAG, "state load slot=$slot ok=$ok ms=${(System.nanoTime() - started) / 1_000_000}")
        if (!ok) menuMessage.value = "Slot $slot could not be loaded."
        return ok
    }

    private fun quitGame() {
        persistSram("quit")
        finish()
    }

    // ---- Battery saves ----

    /** Writes the core's save RAM when it is non-empty and changed since the last load or write. */
    @Synchronized
    private fun persistSram(reason: String) {
        val view = retroView ?: return
        if (!::sramFile.isInitialized || LibretroDroid.getFrameCount() < 1L) return
        val bytes = try {
            view.serializeSRAM(false)
        } catch (error: Exception) {
            Log.w(TAG, "sram read failed ($reason)", error)
            return
        }
        if (bytes.isEmpty()) return
        val hash = bytes.contentHashCode()
        if (hash == lastSramHash) return
        writeAtomically(sramFile, bytes)
        lastSramHash = hash
        Log.i(TAG, "sram saved ($reason) ${sramFile.name} bytes=${bytes.size}")
    }

    private fun writeAtomically(target: File, bytes: ByteArray) {
        val temp = File(target.parentFile, target.name + ".tmp")
        temp.outputStream().use { it.write(bytes); it.fd.sync() }
        if (!temp.renameTo(target)) {
            target.delete()
            temp.renameTo(target)
        }
    }

    override fun onPause() {
        persistSram("pause")
        super.onPause()
    }

    // ---- Input ----

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (menuOpen.value || retroView == null) return super.dispatchKeyEvent(event)
        when (event.keyCode) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_MODE -> {
                if (event.action == KeyEvent.ACTION_UP) openMenu()
                return true
            }
            KeyEvent.KEYCODE_BUTTON_L2 -> {
                rewindKey = event.action == KeyEvent.ACTION_DOWN
                updateHotkeys()
                return true
            }
            KeyEvent.KEYCODE_BUTTON_R2 -> {
                ffKey = event.action == KeyEvent.ACTION_DOWN
                updateHotkeys()
                return true
            }
            KeyEvent.KEYCODE_BUTTON_SELECT -> selectHeld = event.action == KeyEvent.ACTION_DOWN
            KeyEvent.KEYCODE_BUTTON_START -> startHeld = event.action == KeyEvent.ACTION_DOWN
        }
        val handled = super.dispatchKeyEvent(event)
        if (selectHeld && startHeld) {
            selectHeld = false
            startHeld = false
            // Release both in the core so neither stays held while the menu is open.
            retroView?.sendKeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_SELECT)
            retroView?.sendKeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_START)
            openMenu()
        }
        return handled
    }

    // ---- Runtime thread: features, periodic stats and saves, app-owned self-tests ----

    private fun startRuntime(rom: File) {
        val testing = debuggable
        // Unsaved changes carried over a recreation apply on top of the saved settings.
        val s = saved.value.let { Resolved(it.values + session + pending, it.sources) }
        val identify = (testing && intent.getBooleanExtra("ra", false)) || s.flag(Frontend.ACHIEVEMENTS)
        val rewindMb = when {
            testing && intent.hasExtra("rewindMb") -> intent.getIntExtra("rewindMb", 0)
            s.flag(Frontend.REWIND) -> s.int(Frontend.REWIND_MB)
            else -> 0
        }
        val rewindInterval = intent.getIntExtra("rewindInterval", 1)
        val runAhead = if (testing && intent.hasExtra("runahead")) intent.getIntExtra("runahead", 0) else s.int(Frontend.RUN_AHEAD)
        val rewindTest = if (testing) intent.getIntExtra("rewindTest", 0) else 0
        val shader = (if (testing) intent.getStringExtra("shader") else null) ?: s[Frontend.SHADER]
        val selfTest = if (testing && !recreated) intent.getStringExtra("selfTest").orEmpty().split(',').filter(String::isNotBlank) else emptyList()
        Thread({
            try {
                while (!firstFrame) {
                    Thread.sleep(100)
                    if (isFinishing || destroyed) return@Thread
                }
                resumeState?.let { file ->
                    val ok = retroView?.unserializeState(file.readBytes(), false) == true
                    file.delete()
                    resumeState = null
                    Log.i(TAG, "lifecycle resume state ok=$ok")
                }
                LibretroDroid.setRewind(rewindMb * 1024L * 1024L, rewindInterval)
                LibretroDroid.setRunAhead(runAhead)
                if (!shader.isNullOrEmpty()) {
                    val preset = store.shaderFile(shader)
                    Log.i(TAG, "shader preset ${preset.absolutePath} exists=${preset.isFile}")
                    LibretroDroid.setShaderPreset(preset.absolutePath)
                }
                Log.i(TAG, "runtime config rewindMb=$rewindMb interval=$rewindInterval runahead=$runAhead rewindTest=$rewindTest")
                if (s.flag(Frontend.NETCMD)) startNetworkCommands(rom)
                if (identify && core.rcConsoleId(rom) != 0) {
                    val version = packageManager.getPackageInfo(packageName, 0).versionName ?: "dev"
                    achievements = RetroAchievementsBridge(version).also { it.install() }
                    LibretroDroid.achievementsIdentify(core.rcConsoleId(rom), rom.absolutePath)
                    val account = RaAccount(this).load()
                    Log.i(TAG, "rcheevos account ${if (account != null) "user=${account.user}" else "none (identify only)"}")
                    if (account != null) {
                        LibretroDroid.achievementsLogin(account.user.toByteArray(), ByteArray(0), account.token.toByteArray())
                    }
                }
                val started = System.nanoTime()
                var rewindPhase = 0 // 0 waiting, 1 rewinding, 2 done
                var nextLog = 0L
                var nextSram = 30_000L
                var nextStep = 3_000L
                val steps = ArrayDeque(selfTest)
                var catalogSaved = false
                while (!isFinishing && !destroyed) {
                    val elapsedMs = (System.nanoTime() - started) / 1_000_000
                    if (rewindTest > 0 && rewindPhase == 0 && elapsedMs >= rewindTest * 1000L) {
                        logRuntime("before-rewind")
                        LibretroDroid.setRewinding(true)
                        rewindPhase = 1
                    } else if (rewindPhase == 1 && elapsedMs >= rewindTest * 1000L + 3000L) {
                        logRuntime("during-rewind")
                        LibretroDroid.setRewinding(false)
                        rewindPhase = 2
                    }
                    if (elapsedMs >= nextLog) {
                        logRuntime("periodic")
                        nextLog = elapsedMs + 5000L
                    }
                    if (!catalogSaved && elapsedMs >= 1_000L) {
                        saveCatalog()
                        catalogSaved = true
                    }
                    if (elapsedMs >= nextSram) {
                        persistSram("periodic")
                        nextSram = elapsedMs + 30_000L
                    }
                    if (steps.isNotEmpty() && elapsedMs >= nextStep) {
                        runSelfTestStep(steps.removeFirst())
                        nextStep = elapsedMs + 3_000L
                    }
                    Thread.sleep(50)
                }
            } catch (_: InterruptedException) {
            }
        }, "DualPlay-Runtime").start()
    }

    private fun netcmdSelfTest() {
        val commands = listOf(
            "VERSION", "GET_STATUS", "GET_CONFIG_PARAM savefile_directory", "GET_CONFIG_PARAM video_driver",
            "READ_CORE_MEMORY 2000000 16", "READ_CORE_MEMORY 3000000 8", "READ_CORE_MEMORY 10 4",
        )
        try {
            java.net.DatagramSocket().use { socket ->
                socket.soTimeout = 1_000
                val server = java.net.InetSocketAddress("127.0.0.1", NetworkCommandServer.PORT)
                val buffer = ByteArray(4096)
                for (command in commands) {
                    val bytes = command.toByteArray(Charsets.US_ASCII)
                    socket.send(java.net.DatagramPacket(bytes, bytes.size, server))
                    val reply = java.net.DatagramPacket(buffer, buffer.size)
                    val text = try {
                        socket.receive(reply)
                        String(reply.data, 0, reply.length, Charsets.US_ASCII).trimEnd()
                    } catch (_: java.net.SocketTimeoutException) {
                        "<no reply>"
                    }
                    Log.i(TAG, "netcmd test '$command' -> '$text'")
                }
            }
        } catch (error: java.io.IOException) {
            Log.w(TAG, "netcmd test failed: $error")
        }
    }

    private fun runSelfTestStep(step: String) {
        Log.i(TAG, "selfTest step=$step frame=${LibretroDroid.getFrameCount()}")
        when (step) {
            "states" -> {
                val savedAt = LibretroDroid.getFrameCount()
                val saved = saveState(SLOTS)
                Thread.sleep(2_000)
                val beforeLoad = LibretroDroid.getFrameCount()
                val loaded = saved && loadState(SLOTS)
                Log.i(TAG, "selfTest states saved=$saved loaded=$loaded savedAtFrame=$savedAt loadedAtFrame=$beforeLoad")
            }
            "menu" -> runOnUiThread { openMenu() }
            "settings" -> runOnUiThread {
                openMenu()
                menuPage.value = Page.SETTINGS
            }
            "scope" -> runOnUiThread { scopeSelfTest() }
            "resolve" -> {
                logSettings("selfTest")
                val layered = retroView?.getVariables().orEmpty()
                    .filter { saved.value.sources[it.key] == Source.CORE || saved.value.sources[it.key] == Source.GAME }
                Log.i(TAG, "selfTest core reports " + layered.joinToString(" ") { "${it.key}=${it.value}" })
            }
            "saveGame" -> runOnUiThread { if (pending.isNotEmpty()) commitPending(Scope.GAME) }
            "saveCore" -> runOnUiThread { if (pending.isNotEmpty()) commitPending(Scope.CORE) }
            "wait" -> Unit
            "quit" -> runOnUiThread { quitGame() }
            "recreate" -> runOnUiThread { recreate() }
            // Sends DualDex's commands to the local network-command server over real UDP and logs the replies.
            "netcmd" -> Thread({ netcmdSelfTest() }, "DualPlay-NetCmdTest").start()
            // The hosted DualDex's RetroArch session view (connection, ROM resolution, save folder).
            "dualdex" -> Log.i(TAG, "selfTest dualdex ${DualDexHosting.state()}")
            // `shader:<preset>` (or `shader:none`): the Settings page's shader row, as a Left/Right press sets it.
            else -> if (step.startsWith("shader:")) runOnUiThread {
                openMenu()
                menuPage.value = Page.SETTINGS
                val option = store.frontendOptions().first { it.key == Frontend.SHADER }
                val value = step.removePrefix("shader:").let { if (it == "none") "" else it }
                if (value in option.values) changeSetting(option, value) else Log.w(TAG, "selfTest no preset $value")
            } else {
                Log.w(TAG, "selfTest unknown step $step")
            }
        }
    }

    /** Changes the first live core option to its next value and leaves the Settings page (scope prompt). */
    private fun scopeSelfTest() {
        openMenu()
        menuPage.value = Page.SETTINGS
        val option = settingsSections(core, store).flatMap { it.options }
            .firstOrNull { !Frontend.isFrontend(it.key) && !it.restart } ?: return
        val current = effective(option.key) ?: option.default
        val next = option.values[(option.values.indexOf(current) + 1) % option.values.size]
        Log.i(TAG, "selfTest scope change ${option.key} $current -> $next")
        changeSetting(option, next)
        menuBack()
    }

    /** Records the options the core reports (labels, values, defaults) for the settings menus. */
    private fun saveCatalog() {
        val variables = retroView?.getVariables() ?: return
        val count = store.saveCatalog(core, variables)
        saved.value = store.resolve(core, title)
        Log.i(TAG, "catalog ${core.id} options=$count curated " + variables.filter { it.key in core.curatedOptions }
            .joinToString(" ") { "${it.key}=${it.value}" })
    }

    private fun logRuntime(label: String) {
        val s = LibretroDroid.getRuntimeStats() ?: return
        Log.i(TAG, "runtime $label frame=${LibretroDroid.getFrameCount()} rewindEntries=${s[0]} " +
            "rewindBytes=${s[1]} captureUs=${s[2]} stepUs=${s[3]} runAhead=${s[4]} rewinding=${s[5]} encodeUs=${s.getOrNull(6)} shaderUs=${s.getOrNull(7)} bottomShaderUs=${s.getOrNull(8)}")
    }

    // ---- Secondary display ----

    private fun showPresentation(watch: MemoryProbe.Region, title: String, core: CoreSpec) {
        val displayManager = getSystemService(DisplayManager::class.java)
        val target: Display? = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            .firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }
        Log.i(TAG, "Displays: " + displayManager.displays.joinToString { "${it.displayId}:${it.name}" } +
            "; presentation target: ${target?.displayId}")
        if (target == null) return

        if (core.stackedDualScreen) {
            val screen = BottomScreenPresentation(this, target)
            screen.show()
            bottomScreen = screen
            probe = MemoryProbe(watch) { snapshot -> runOnUiThread { screen.render(snapshot) } }.also { it.start() }
        } else if (saved.value.flag(Frontend.NETCMD) && core.system in DUALDEX_SYSTEMS &&
            showDualDex(target)
        ) {
            return
        } else {
            val placeholder = DualDexPlaceholder(this, target, title)
            placeholder.show()
            presentation = placeholder
            probe = MemoryProbe(watch) { snapshot -> runOnUiThread { placeholder.render(snapshot) } }.also { it.start() }
        }
    }

    /** DualDex on the bottom display, hosted in this process. Falls back to the placeholder if it cannot start. */
    private fun showDualDex(target: Display): Boolean {
        val version = packageManager.getPackageInfo(packageName, 0).versionName
        val host = DualDexHosting.start(this, "DualPlay ${version ?: "dev"}") ?: return false
        val origin = host.localOrigin ?: return false
        val screen = DualDexPresentation(this, target, host, origin)
        screen.show()
        dualDex = screen
        Log.i(TAG, "dualdex shown on display ${target.displayId}")
        return true
    }

    private fun startNetworkCommands(rom: File) {
        val session = NetworkCommandServer.Session(
            systemId = NetworkCommandServer.systemId(core.system),
            basename = title,
            config = mapOf(
                "savefile_directory" to sramFile.parentFile!!.absolutePath,
                "savestate_directory" to stateDir.absolutePath,
                "system_directory" to File(filesDir, "system").absolutePath,
            ),
            paused = { menuOpen.value },
        )
        val server = NetworkCommandServer(session)
        // The UDP listener serves the standalone DualDex app; the hosted DualDex calls the handler directly.
        server.start()
        netcmd = server
        if (core.stackedDualScreen) return // DualDex has no DS support; skip hashing large DS images.
        val started = System.nanoTime()
        val crc = java.util.zip.CRC32()
        val sha = java.security.MessageDigest.getInstance("SHA-256")
        rom.inputStream().buffered(1 shl 16).use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                crc.update(buffer, 0, count)
                sha.update(buffer, 0, count)
            }
        }
        session.crc32 = crc.value
        val sha256 = sha.digest().joinToString("") { "%02x".format(it) }
        Log.i(TAG, "netcmd content crc32=%08X ms=%d".format(crc.value, (System.nanoTime() - started) / 1_000_000))
        if (destroyed) return
        DualDexHosting.hostSession(rom, core.system, title, crc.value, sha256)?.let { DualDexHosting.attach(server, it) }
    }

    /**
     * Battery saves live in shared storage (`DualPlay/saves/<core>`) when DualPlay has all-files access, so DualDex
     * can read them; otherwise in app storage. RetroArch's folders are never used.
     */
    private fun savesDirectory(core: CoreSpec): File =
        if (Environment.isExternalStorageManager()) File(Environment.getExternalStorageDirectory(), "DualPlay/saves/${core.id}")
        else File(filesDir, "saves/${core.id}")

    /** Moves a save written by an earlier build (app storage) to [target] once, keeping the old file as `.migrated`. */
    private fun migratePrivateSave(core: CoreSpec, target: File) {
        val old = File(filesDir, "saves/${core.id}/${target.name}")
        if (old.absolutePath == target.absolutePath || !old.isFile || target.exists()) return
        val bytes = old.readBytes()
        writeAtomically(target, bytes)
        if (target.readBytes().contentEquals(bytes)) {
            old.renameTo(File(old.parentFile, old.name + ".migrated"))
            Log.i(TAG, "sram migrated ${old.name} bytes=${bytes.size} -> ${target.parent}")
        }
    }

    private fun parseOptions(raw: String?): Map<String, String> =
        raw.orEmpty().split(',').mapNotNull { entry ->
            val parts = entry.split('=', limit = 2)
            if (parts.size == 2 && parts[0].isNotBlank()) parts[0].trim() to parts[1].trim() else null
        }.toMap()

    private fun showError(message: String) {
        setContentView(TextView(this).apply {
            text = message
            textSize = 20f
        })
    }

    /**
     * D-LIFE-1: one line per onCreate. A second instance in the same process logs which configuration
     * fields changed since the previous one (Configuration.diff bits, see ActivityInfo.CONFIG_*).
     */
    private fun logLifecycle(savedInstanceState: Bundle?) {
        val config = Configuration(resources.configuration)
        val previous = lastConfiguration
        createdInProcess++
        val diff = previous?.diff(config)
        Log.i(
            TAG,
            "lifecycle create n=$createdInProcess task=$taskId restored=${savedInstanceState != null}" +
                (if (diff != null) " configDiff=0x${Integer.toHexString(diff)}" else "") +
                " display=${display?.displayId} orientation=${config.orientation} size=${config.screenWidthDp}x${config.screenHeightDp}dp",
        )
        lastConfiguration = config
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val diff = lastConfiguration?.diff(newConfig)
        Log.i(TAG, "lifecycle configChanged (handled) diff=0x${Integer.toHexString(diff ?: 0)}")
        lastConfiguration = Configuration(newConfig)
    }

    /**
     * D-LIFE-1: some configuration changes cannot be declared in configChanges (an overlay change gives
     * CONFIG_ASSETS_PATHS), so Android recreates the activity. The running game is handed over as a
     * resume state, with the unsaved settings (session and pending); battery saves are already written in onPause.
     */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (!isChangingConfigurations || !::stateDir.isInitialized || !firstFrame) return
        val bytes = retroView?.serializeState(false) ?: ByteArray(0)
        if (bytes.isNotEmpty()) {
            writeAtomically(resumeFile(), bytes)
            outState.putBoolean(STATE_RESUME, true)
        }
        outState.putBundle(STATE_SESSION, Bundle().apply { session.forEach { (k, v) -> putString(k, v) } })
        outState.putBundle(STATE_PENDING, Bundle().apply { pending.forEach { (k, v) -> putString(k, v) } })
        Log.i(TAG, "lifecycle handover state bytes=${bytes.size} session=${session.size} pending=${pending.size}")
    }

    private fun restoreMap(bundle: Bundle?, target: MutableMap<String, String>) =
        bundle?.keySet()?.forEach { key -> bundle.getString(key)?.let { target[key] = it } }

    private fun resumeFile() = File(stateDir, "$title.resume")

    override fun onDestroy() {
        destroyed = true
        netcmd?.let(DualDexHosting::detach)
        netcmd?.stop()
        netcmd = null
        dualDex?.dismiss()
        dualDex = null
        Log.i(TAG, "lifecycle destroy finishing=$isFinishing changingConfig=$isChangingConfigurations")
        achievements?.shutdown()
        achievements = null
        probe?.stop()
        probe = null
        presentation?.dismiss()
        presentation = null
        bottomScreen?.dismiss()
        bottomScreen = null
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    companion object {
        const val TAG = "DualPlay"
        /** Systems DualDex supports (`CoreSpec.system`). */
        private val DUALDEX_SYSTEMS = setOf("gb", "gbc", "gba")
        private var createdInProcess = 0
        private var lastConfiguration: Configuration? = null
        private const val SLOTS = 4
        private const val STATE_RESUME = "dualplay.resume"
        private const val STATE_SESSION = "dualplay.session"
        private const val STATE_PENDING = "dualplay.pending"

        /** File-name-safe form of a game title (save and state files). */
        fun safeName(raw: String): String = raw.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim().ifEmpty { "game" }
    }
}
