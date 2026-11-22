/*
 * DualPlay — runtime core registry. GPL-3.0-or-later.
 */
package dev.darkaxt.dualplay

import java.io.File

/** A pinned libretro core shipped in jniLibs (see cores/README.md for provenance and hashes). */
data class CoreSpec(
    val id: String,
    /** Name shown in menus. */
    val label: String,
    val library: String,
    val system: String,
    val extensions: Set<String>,
    /** Core options DualPlay depends on: applied at load and locked in the settings menus. */
    val options: Map<String, String> = emptyMap(),
    /** Options shown first in this core's settings menu (absent keys are skipped); the rest go under Advanced. */
    val curatedOptions: List<String> = emptyList(),
    /** The core outputs two stacked screens (top half, bottom half). */
    val stackedDualScreen: Boolean = false,
    /** Default watched bus region for the memory probe. */
    val probeAddress: Long,
    val probeLength: Int = 256,
    /** RetroAchievements console id (rc_consoles.h) per ROM extension. */
    val rcConsoles: Map<String, Int> = emptyMap(),
) {
    fun rcConsoleId(rom: File): Int = rcConsoles[rom.extension.lowercase()] ?: 0
}

object CoreRegistry {
    val MGBA = CoreSpec(
        id = "mgba",
        label = "mGBA",
        library = "libmgba_libretro_android.so",
        system = "gba",
        extensions = setOf("gba"),
        curatedOptions = listOf(
            "mgba_color_correction", "mgba_interframe_blending", "mgba_skip_bios", "mgba_idle_optimization",
            "mgba_frameskip", "mgba_solar_sensor_level",
        ),
        probeAddress = 0x0300_0000L, // IWRAM
        rcConsoles = mapOf("gba" to 5),
    )

    val GAMBATTE = CoreSpec(
        id = "gambatte",
        label = "Gambatte",
        library = "libgambatte_libretro_android.so",
        system = "gbc",
        extensions = setOf("gb", "gbc", "dmg", "cgb"),
        curatedOptions = listOf(
            "gambatte_gb_colorization", "gambatte_gb_internal_palette", "gambatte_gbc_color_correction",
            "gambatte_mix_frames", "gambatte_gb_hwmode", "gambatte_gb_bootloader",
        ),
        probeAddress = 0xC000L, // WRAM bank 0
        rcConsoles = mapOf("gb" to 4, "dmg" to 4, "gbc" to 6, "cgb" to 6),
    )

    val MELONDS_DS = CoreSpec(
        id = "melondsds",
        label = "melonDS DS",
        library = "libmelondsds_libretro_android.so",
        system = "nds",
        extensions = setOf("nds", "dsi"),
        options = mapOf(
            "melonds_number_of_screen_layouts" to "1",
            "melonds_screen_layout1" to "top-bottom",
            "melonds_screen_gap" to "0",
            "melonds_show_cursor" to "disabled",
        ),
        curatedOptions = listOf(
            "melonds_console_mode", "melonds_boot_mode", "melonds_firmware_language", "melonds_jit_enable",
            "melonds_threaded_renderer", "melonds_touch_mode", "melonds_mic_input",
        ),
        stackedDualScreen = true,
        probeAddress = 0x0200_0000L, // main RAM
        rcConsoles = mapOf("nds" to 18, "dsi" to 78),
    )

    val all = listOf(MGBA, GAMBATTE, MELONDS_DS)

    fun forRom(rom: File): CoreSpec? {
        val extension = rom.extension.lowercase()
        return all.firstOrNull { extension in it.extensions }
    }

    fun byId(id: String): CoreSpec? = all.firstOrNull { it.id == id }

    val romExtensions: Set<String> get() = all.flatMap { it.extensions }.toSet()
}
