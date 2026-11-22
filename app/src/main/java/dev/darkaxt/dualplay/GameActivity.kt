/*
 * DualPlay — game screen. GPL-3.0-or-later.
 */
package dev.darkaxt.dualplay

import android.content.pm.ApplicationInfo
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.util.Log
import android.view.Display
import android.view.KeyEvent
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
import androidx.compose.runtime.LaunchedEffect
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
import dev.darkaxt.dualplay.ui.Accent
import dev.darkaxt.dualplay.ui.DualPlayTheme
import dev.darkaxt.dualplay.ui.MenuRow
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
 * App-owned test extras (debuggable builds only): `rewindMb`, `rewindInterval`, `runahead`, `rewindTest`,
 * `ra`, `shader`, and `selfTest` (comma-separated steps: `states`, `menu`, `quit`).
 */
class GameActivity : AppCompatActivity() {

    private var retroView: GLRetroView? = null
    private var presentation: DualDexPlaceholder? = null
    private var bottomScreen: BottomScreenPresentation? = null
    private var probe: MemoryProbe? = null
    private var menuView: ComposeView? = null
    @Volatile private var achievements: RetroAchievementsBridge? = null

    private lateinit var core: CoreSpec
    private lateinit var title: String
    private lateinit var sramFile: File
    private lateinit var stateDir: File
    @Volatile private var lastSramHash: Int? = null

    private val menuOpen = mutableStateOf(false)
    private val menuSlot = mutableIntStateOf(1)
    private val menuMessage = mutableStateOf<String?>(null)
    private var selectHeld = false
    private var startHeld = false

    private val debuggable: Boolean
        get() = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        val saves = File(filesDir, "saves/${core.id}").apply { mkdirs() }
        sramFile = File(saves, "$title.srm")
        stateDir = File(filesDir, "states/${core.id}").apply { mkdirs() }
        val options = core.options + parseOptions(intent.getStringExtra("opt"))
        Log.i(TAG, "Loading ${rom.absolutePath} with core ${core.id} as '$title' options $options")

        val system = File(filesDir, "system").apply { mkdirs() }
        val savedRam = sramFile.takeIf(File::isFile)?.readBytes()
        lastSramHash = savedRam?.contentHashCode()
        val data = GLRetroViewData(this).apply {
            coreFilePath = core.library
            variables = options.map { Variable(it.key, it.value) }.toTypedArray()
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

    @androidx.compose.runtime.Composable
    private fun InGameMenu() {
        if (!menuOpen.value) return
        val first = androidx.compose.runtime.remember { FocusRequester() }
        BackHandler { closeMenu() }
        LaunchedEffect(Unit) { first.requestFocus() }
        Box(Modifier.fillMaxSize().background(Color(0x99000000))) {
            Column(
                Modifier.fillMaxHeight().width(420.dp).background(Color(0xF0101416)).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(title, color = Accent, fontSize = 18.sp, maxLines = 2)
                Text(core.id, color = Color(0xFF9AA5A0), fontSize = 14.sp, modifier = Modifier.padding(bottom = 12.dp))
                MenuRow("Resume", Modifier.focusRequester(first)) { closeMenu() }
                MenuRow(
                    "State slot", detail = menuSlot.intValue.toString(),
                    onLeft = { menuSlot.intValue = (menuSlot.intValue + SLOTS - 2) % SLOTS + 1 },
                    onRight = { menuSlot.intValue = menuSlot.intValue % SLOTS + 1 },
                ) { menuSlot.intValue = menuSlot.intValue % SLOTS + 1 }
                MenuRow("Save state", detail = stateLabel(menuSlot.intValue)) { saveState(menuSlot.intValue) }
                MenuRow("Load state") { if (loadState(menuSlot.intValue)) closeMenu() }
                MenuRow("Reset") { retroView?.reset(false); closeMenu() }
                MenuRow("Quit") { quitGame() }
                menuMessage.value?.let {
                    Text(it, color = Color(0xFFB8C4BE), fontSize = 15.sp, modifier = Modifier.padding(top = 12.dp))
                }
            }
        }
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
        val identify = testing && intent.getBooleanExtra("ra", false)
        val rewindMb = if (testing) intent.getIntExtra("rewindMb", 0) else 0
        val rewindInterval = intent.getIntExtra("rewindInterval", 1)
        val runAhead = if (testing) intent.getIntExtra("runahead", 0) else 0
        val rewindTest = if (testing) intent.getIntExtra("rewindTest", 0) else 0
        val shader = if (testing) intent.getStringExtra("shader") else null
        val selfTest = if (testing) intent.getStringExtra("selfTest").orEmpty().split(',').filter(String::isNotBlank) else emptyList()
        Thread({
            try {
                while (LibretroDroid.getFrameCount() < 1L) {
                    Thread.sleep(100)
                    if (isFinishing) return@Thread
                }
                LibretroDroid.setRewind(rewindMb * 1024L * 1024L, rewindInterval)
                LibretroDroid.setRunAhead(runAhead)
                if (!shader.isNullOrEmpty()) {
                    val preset = File(File(filesDir, "shaders"), shader)
                    Log.i(TAG, "shader preset ${preset.absolutePath} exists=${preset.isFile}")
                    LibretroDroid.setShaderPreset(preset.absolutePath)
                }
                Log.i(TAG, "runtime config rewindMb=$rewindMb interval=$rewindInterval runahead=$runAhead rewindTest=$rewindTest")
                if (identify && core.rcConsoleId(rom) != 0) {
                    val version = packageManager.getPackageInfo(packageName, 0).versionName ?: "dev"
                    achievements = RetroAchievementsBridge(version).also { it.install() }
                    LibretroDroid.achievementsIdentify(core.rcConsoleId(rom), rom.absolutePath)
                }
                val started = System.nanoTime()
                var rewindPhase = 0 // 0 waiting, 1 rewinding, 2 done
                var nextLog = 0L
                var nextSram = 30_000L
                var nextStep = 3_000L
                val steps = ArrayDeque(selfTest)
                while (!isFinishing) {
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
            "quit" -> runOnUiThread { quitGame() }
            else -> Log.w(TAG, "selfTest unknown step $step")
        }
    }

    private fun logRuntime(label: String) {
        val s = LibretroDroid.getRuntimeStats() ?: return
        Log.i(TAG, "runtime $label frame=${LibretroDroid.getFrameCount()} rewindEntries=${s[0]} " +
            "rewindBytes=${s[1]} captureUs=${s[2]} stepUs=${s[3]} runAhead=${s[4]} rewinding=${s[5]} encodeUs=${s.getOrNull(6)} shaderUs=${s.getOrNull(7)}")
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
        } else {
            val placeholder = DualDexPlaceholder(this, target, title)
            placeholder.show()
            presentation = placeholder
            probe = MemoryProbe(watch) { snapshot -> runOnUiThread { placeholder.render(snapshot) } }.also { it.start() }
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

    override fun onDestroy() {
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
        private const val SLOTS = 4

        /** File-name-safe form of a game title (save and state files). */
        fun safeName(raw: String): String = raw.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim().ifEmpty { "game" }
    }
}
