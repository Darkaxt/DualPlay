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
import com.swordfish.libretrodroid.ShaderConfig
import java.io.File

/**
 * Game on the default (top) display through GLRetroView; a placeholder DualDex [Presentation]
 * on the first presentation display reads core memory in-process.
 *
 * Intent extras: `rom` (absolute path; default: first .gba in external files `roms/`),
 * `addr` / `len` (hex bus address and byte count of the watched region).
 */
class SpikeActivity : AppCompatActivity() {

    private var retroView: GLRetroView? = null
    private var presentation: DualDexPlaceholder? = null
    private var probe: MemoryProbe? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideSystemBars()

        val rom = resolveRom()
        if (rom == null) {
            setContentView(TextView(this).apply {
                text = "No ROM. Push a .gba to ${romDirectory().absolutePath} or pass --es rom <path>."
                textSize = 20f
            })
            return
        }
        Log.i(TAG, "Loading ${rom.absolutePath}")

        val saves = File(filesDir, "saves").apply { mkdirs() }
        val system = File(filesDir, "system").apply { mkdirs() }
        val data = GLRetroViewData(this).apply {
            coreFilePath = "libmgba_libretro_android.so"
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
            address = intent.getStringExtra("addr")?.toLongOrNull(16) ?: DEFAULT_ADDRESS,
            length = intent.getStringExtra("len")?.toIntOrNull() ?: DEFAULT_LENGTH,
        )
        showPresentation(watch, rom.name)
    }

    private fun showPresentation(watch: MemoryProbe.Region, title: String) {
        val displayManager = getSystemService(DisplayManager::class.java)
        val target: Display? = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            .firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }
        Log.i(TAG, "Displays: " + displayManager.displays.joinToString { "${it.displayId}:${it.name}" } +
            "; presentation target: ${target?.displayId}")
        if (target == null) return

        val placeholder = DualDexPlaceholder(this, target, title)
        placeholder.show()
        presentation = placeholder
        probe = MemoryProbe(watch) { snapshot -> runOnUiThread { placeholder.render(snapshot) } }.also { it.start() }
    }

    override fun onDestroy() {
        probe?.stop()
        probe = null
        presentation?.dismiss()
        presentation = null
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
        return romDirectory().listFiles { f -> f.extension.equals("gba", ignoreCase = true) }
            ?.sortedBy { it.name }
            ?.firstOrNull()
    }

    companion object {
        const val TAG = "DualPlay"
        const val DEFAULT_ADDRESS = 0x0300_0000L // GBA IWRAM
        const val DEFAULT_LENGTH = 256
    }
}
