/*
 * DualPlay — ROM library. GPL-3.0-or-later.
 */
package dev.darkaxt.dualplay.library

import android.content.Context
import android.os.storage.StorageManager
import dev.darkaxt.dualplay.CoreRegistry
import dev.darkaxt.dualplay.CoreSpec
import java.io.File

/** A console as the launcher shows it: one ES-DE folder name mapped to one pinned core. */
enum class GameSystem(val folder: String, val label: String, val core: CoreSpec) {
    GB("gb", "Game Boy", CoreRegistry.GAMBATTE),
    GBC("gbc", "Game Boy Color", CoreRegistry.GAMBATTE),
    GBA("gba", "Game Boy Advance", CoreRegistry.MGBA),
    NDS("nds", "Nintendo DS", CoreRegistry.MELONDS_DS);

    /** File extensions listed for this system: the core's ROM extensions plus archives. */
    val extensions: Set<String> get() = core.extensions + RomResolver.ARCHIVE_EXTENSIONS

    companion object {
        fun byFolder(name: String): GameSystem? = entries.firstOrNull { it.folder.equals(name, ignoreCase = true) }
    }
}

/** One listed game. [group] is the subfolder path below the system folder ("" at the top level). */
data class RomEntry(val file: File, val system: GameSystem, val group: String) {
    /** File name without the archive or ROM extension. */
    val title: String get() = file.name.substringBeforeLast('.')
}

/**
 * Finds games in ES-DE layout (`<volume>/ROMs/<system>/...`) on every mounted storage volume.
 * Needs all-files access to read removable storage.
 */
class RomLibrary(private val context: Context) {

    /** `ROMs` folders found on mounted volumes (any capitalisation). */
    fun roots(): List<File> {
        val storage = context.getSystemService(StorageManager::class.java)
        return storage.storageVolumes.mapNotNull { it.directory }
            .flatMap { volume -> volume.listFiles { f -> f.isDirectory && f.name.equals("roms", ignoreCase = true) }.orEmpty().toList() }
            .distinctBy { it.canonicalPath }
    }

    /** All games for [system] across all roots, sorted by group then title. */
    fun scan(system: GameSystem): List<RomEntry> {
        val found = ArrayList<RomEntry>()
        for (root in roots()) {
            val folders = root.listFiles { f -> f.isDirectory && f.name.equals(system.folder, ignoreCase = true) }.orEmpty()
            for (folder in folders) collect(folder, folder, system, found)
        }
        return found.sortedWith(compareBy({ it.group.lowercase() }, { it.title.lowercase() }))
    }

    private fun collect(base: File, dir: File, system: GameSystem, out: MutableList<RomEntry>) {
        val children = dir.listFiles() ?: return
        for (child in children) {
            if (child.name.startsWith('.')) continue
            if (child.isDirectory) {
                collect(base, child, system, out)
            } else if (child.extension.lowercase() in system.extensions) {
                val group = dir.relativeTo(base).invariantSeparatorsPath.let { if (it == ".") "" else it }
                out += RomEntry(child, system, group)
            }
        }
    }
}
