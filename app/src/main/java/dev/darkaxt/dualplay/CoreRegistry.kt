/*
 * DualPlay — runtime core registry. GPL-3.0-or-later.
 */
package dev.darkaxt.dualplay

import java.io.File

/** A pinned libretro core shipped in jniLibs (see cores/README.md for provenance and hashes). */
data class CoreSpec(
    val id: String,
    val library: String,
    val system: String,
    val extensions: Set<String>,
    /** Core options applied at load (key to value). */
    val options: Map<String, String> = emptyMap(),
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
        library = "libmgba_libretro_android.so",
        system = "gba",
        extensions = setOf("gba"),
        probeAddress = 0x0300_0000L, // IWRAM
        rcConsoles = mapOf("gba" to 5),
    )

    val GAMBATTE = CoreSpec(
        id = "gambatte",
        library = "libgambatte_libretro_android.so",
        system = "gbc",
        extensions = setOf("gb", "gbc", "dmg", "cgb"),
        probeAddress = 0xC000L, // WRAM bank 0
        rcConsoles = mapOf("gb" to 4, "dmg" to 4, "gbc" to 6, "cgb" to 6),
    )

    val MELONDS_DS = CoreSpec(
        id = "melondsds",
        library = "libmelondsds_libretro_android.so",
        system = "nds",
        extensions = setOf("nds", "dsi"),
        options = mapOf(
            "melonds_number_of_screen_layouts" to "1",
            "melonds_screen_layout1" to "top-bottom",
            "melonds_screen_gap" to "0",
            "melonds_show_cursor" to "disabled",
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
