/*
 * DualPlay — stage-1 spike. GPL-3.0-or-later.
 */
package dev.darkaxt.dualplay

import android.hardware.display.DisplayManager
import android.os.Bundle
import android.util.Log
import android.view.Display
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.swordfish.libretrodroid.GLRetroView
import com.swordfish.libretrodroid.GLRetroViewData
import com.swordfish.libretrodroid.LibretroDroid
import com.swordfish.libretrodroid.ShaderConfig
import com.swordfish.libretrodroid.Variable
import java.io.File

/**
 * Game on the default (top) display through GLRetroView. The secondary display shows either the
 * DS bottom screen (stacked dual-screen cores) or a placeholder DualDex [Presentation]; both read
 * core memory in-process through the memory probe.
 *
 * Intent extras: `rom` (absolute path; default: first known ROM in external files `roms/`),
 * `core` (core id overriding the extension match), `addr` / `len` (hex bus address and byte count
 * of the watched region), `opt` (comma-separated `key=value` core options).
 */
class SpikeActivity : AppCompatActivity() {

    private var retroView: GLRetroView? = null
    private var presentation: DualDexPlaceholder? = null
    private var bottomScreen: BottomScreenPresentation? = null
    private var probe: MemoryProbe? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideSystemBars()

        val rom = resolveRom()
        if (rom == null) {
            setContentView(TextView(this).apply {
                text = "No ROM. Push a ROM to ${romDirectory().absolutePath} or pass --es rom <path>."
                textSize = 20f
            })
            return
        }
        val core = intent.getStringExtra("core")?.let(CoreRegistry::byId) ?: CoreRegistry.forRom(rom)
        if (core == null) {
            setContentView(TextView(this).apply {
                text = "No core for ${rom.name}."
                textSize = 20f
            })
            return
        }
        val options = core.options + parseOptions(intent.getStringExtra("opt"))
        Log.i(TAG, "Loading ${rom.absolutePath} with core ${core.id} options $options")

        val saves = File(filesDir, "saves").apply { mkdirs() }
        val system = File(filesDir, "system").apply { mkdirs() }
        val data = GLRetroViewData(this).apply {
            coreFilePath = core.library
            variables = options.map { Variable(it.key, it.value) }.toTypedArray()
            gameFilePath = rom.absolutePath
            systemDirectory = system.absolutePath
            savesDirectory = saves.absolutePath
            shader = ShaderConfig.Sharp
            preferLowLatencyAudio = true
        }

        val view = GLRetroView(this, data)
        retroView = view
        lifecycle.addObserver(view)
        setContentView(FrameLayout(this).apply {
            addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        })
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        view.requestFocus()

        val watch = MemoryProbe.Region(
            address = intent.getStringExtra("addr")?.toLongOrNull(16) ?: core.probeAddress,
            length = intent.getStringExtra("len")?.toIntOrNull() ?: core.probeLength,
        )
        if (core.stackedDualScreen) view.setFrameCrop(0f, 0f, 1f, 0.5f)
        showPresentation(watch, rom.name, core)
        logCoreOptionsOnce()
    }

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

    /** Logs the core's option keys and current values once the game runs (option discovery). */
    private fun logCoreOptionsOnce() {
        Thread({
            while (LibretroDroid.getFrameCount() < 60L) {
                try { Thread.sleep(250) } catch (_: InterruptedException) { return@Thread }
                if (isFinishing) return@Thread
            }
            val variables = retroView?.getVariables() ?: return@Thread
            variables.forEach { Log.i(TAG, "core option ${it.key}=${it.value} :: ${it.description}") }
        }, "DualPlay-OptionLog").start()
    }

    private fun parseOptions(raw: String?): Map<String, String> =
        raw.orEmpty().split(',').mapNotNull { entry ->
            val parts = entry.split('=', limit = 2)
            if (parts.size == 2 && parts[0].isNotBlank()) parts[0].trim() to parts[1].trim() else null
        }.toMap()

    override fun onDestroy() {
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

    private fun romDirectory(): File = File(getExternalFilesDir(null), "roms").apply { mkdirs() }

    private fun resolveRom(): File? {
        intent.getStringExtra("rom")?.let { return File(it).takeIf(File::canRead) }
        val known = CoreRegistry.romExtensions
        return romDirectory().listFiles { f -> f.extension.lowercase() in known }
            ?.sortedBy { it.name }
            ?.firstOrNull()
    }

    companion object {
        const val TAG = "DualPlay"
    }
}
