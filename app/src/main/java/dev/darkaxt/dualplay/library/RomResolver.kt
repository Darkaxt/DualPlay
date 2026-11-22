/*
 * DualPlay — playable ROM resolution (archive extraction cache). GPL-3.0-or-later.
 */
package dev.darkaxt.dualplay.library

import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * Turns a listed file into a path a core can load. Plain ROMs are used in place. For `.zip` and `.7z`, the first
 * entry with one of the core's extensions is extracted once into [cacheRoot] and reused while the archive's path,
 * size and modification time are unchanged. The least recently used extractions are evicted above [cacheLimitBytes].
 */
class RomResolver(private val cacheRoot: File, private val cacheLimitBytes: Long = 4L shl 30) {

    class ResolveException(message: String) : IOException(message)

    /** Returns the playable file. [progress] receives bytes written so far during an extraction. */
    fun resolve(rom: File, romExtensions: Set<String>, progress: (Long) -> Unit = {}): File {
        val extension = rom.extension.lowercase()
        if (extension !in ARCHIVE_EXTENSIONS) return rom

        val slot = File(cacheRoot, key(rom))
        slot.listFiles()?.firstOrNull { it.isFile && !it.name.endsWith(PARTIAL) }?.let { cached ->
            slot.setLastModified(System.currentTimeMillis())
            return cached
        }
        slot.deleteRecursively()
        slot.mkdirs()
        val result = when (extension) {
            "zip" -> extractZip(rom, slot, romExtensions, progress)
            else -> extract7z(rom, slot, romExtensions, progress)
        }
        evict(keep = slot)
        return result
    }

    private fun extractZip(rom: File, slot: File, romExtensions: Set<String>, progress: (Long) -> Unit): File =
        ZipFile(rom).use { zip ->
            val entry = zip.entries().asSequence()
                .firstOrNull { !it.isDirectory && it.name.substringAfterLast('.').lowercase() in romExtensions }
                ?: throw ResolveException("No ROM inside ${rom.name}")
            zip.getInputStream(entry).use { write(it, slot, entry.name, progress) }
        }

    private fun extract7z(rom: File, slot: File, romExtensions: Set<String>, progress: (Long) -> Unit): File =
        SevenZFile.builder().setFile(rom).get().use { archive ->
            var entry = archive.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.substringAfterLast('.').lowercase() in romExtensions) {
                    return archive.getInputStream(entry).use { write(it, slot, entry.name, progress) }
                }
                entry = archive.nextEntry
            }
            throw ResolveException("No ROM inside ${rom.name}")
        }

    /** Streams [input] into the slot under the entry's base name; the file appears only once complete. */
    private fun write(input: InputStream, slot: File, entryName: String, progress: (Long) -> Unit): File {
        val name = entryName.substringAfterLast('/').substringAfterLast('\\')
        val target = File(slot, name)
        val partial = File(slot, name + PARTIAL)
        partial.outputStream().buffered(1 shl 20).use { out ->
            val buffer = ByteArray(1 shl 16)
            var total = 0L
            var lastReport = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
                total += read
                if (total - lastReport >= (4 shl 20)) {
                    progress(total)
                    lastReport = total
                }
            }
            progress(total)
        }
        if (!partial.renameTo(target)) throw ResolveException("Cannot finish extracting $name")
        return target
    }

    private fun evict(keep: File) {
        val slots = cacheRoot.listFiles { f -> f.isDirectory }.orEmpty().sortedBy { it.lastModified() }
        var total = slots.sumOf { slot -> slot.walkTopDown().filter { it.isFile }.sumOf { it.length() } }
        for (slot in slots) {
            if (total <= cacheLimitBytes) break
            if (slot == keep) continue
            val size = slot.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            if (slot.deleteRecursively()) total -= size
        }
    }

    private fun key(rom: File): String {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest("${rom.canonicalPath}|${rom.length()}|${rom.lastModified()}".toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(20)
    }

    companion object {
        val ARCHIVE_EXTENSIONS = setOf("zip", "7z")
        private const val PARTIAL = ".partial"
    }
}
