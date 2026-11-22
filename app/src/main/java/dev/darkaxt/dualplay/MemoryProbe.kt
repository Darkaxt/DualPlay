/*
 * DualPlay — stage-1 spike. GPL-3.0-or-later.
 */
package dev.darkaxt.dualplay

import android.util.Log
import com.swordfish.libretrodroid.LibretroDroid

/**
 * Polls one bus-address region through the runtime's in-process memory API on its own thread.
 * Each read takes the core lock, so it lands between frames; it may wait for the frame being
 * emulated but not for frame pacing (D-MEM-2).
 */
class MemoryProbe(
    private val region: Region,
    private val intervalMs: Long = 100,
    private val onSnapshot: (Snapshot) -> Unit,
) {
    data class Region(val address: Long, val length: Int)

    /** One descriptor after RetroArch preprocessing (select 0 = explicit range match). */
    data class MappedRegion(
        val start: Long,
        val select: Long,
        val disconnect: Long,
        val length: Long,
        val flags: Long,
        val hasData: Boolean,
    )

    data class Snapshot(
        val region: Region,
        val frame: Long,
        val bytes: ByteArray,
        val bytesRead: Int,
        val changedBytes: Int,
        val reads: Long,
        val lastMicros: Long,
        val averageMicros: Long,
        val maxMicros: Long,
        val p50Micros: Long,
        val p99Micros: Long,
        val map: List<MappedRegion>,
    )

    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        running = true
        thread = Thread(::loop, "DualPlay-MemoryProbe").also { it.start() }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread?.join(500)
        thread = null
    }

    private fun loop() {
        val current = ByteArray(region.length)
        val previous = ByteArray(region.length)
        var map = emptyList<MappedRegion>()
        var reads = 0L
        var totalMicros = 0L
        var maxMicros = 0L
        val samples = LongArray(SAMPLE_WINDOW)

        while (running) {
            try {
                Thread.sleep(intervalMs)
            } catch (_: InterruptedException) {
                break
            }
            val frame = LibretroDroid.getFrameCount()
            if (frame == 0L) continue // game not running yet

            if (map.isEmpty()) {
                map = LibretroDroid.getMemoryRegions().toList().chunked(6)
                    .map { MappedRegion(it[0], it[1], it[2], it[3], it[4], it[5] != 0L) }
                Log.i(SpikeActivity.TAG, "Memory map: " + map.joinToString {
                    "0x%08X+0x%X sel=0x%X disc=0x%X".format(it.start, it.length, it.select, it.disconnect)
                })
            }

            val started = System.nanoTime()
            val read = LibretroDroid.readMemory(region.address, current, 0, region.length)
            val micros = (System.nanoTime() - started) / 1_000
            reads++
            totalMicros += micros
            if (micros > maxMicros) maxMicros = micros
            samples[(reads - 1).toInt() % samples.size] = micros
            val filled = minOf(reads, samples.size.toLong()).toInt()

            var changed = 0
            for (i in 0 until region.length) if (current[i] != previous[i]) changed++
            current.copyInto(previous)

            val sorted = samples.copyOf(filled).also { it.sort() }
            val p50 = sorted[(filled - 1) / 2]
            val p99 = sorted[((filled - 1) * 99) / 100]
            if (reads % 50 == 0L) {
                Log.i(SpikeActivity.TAG, "probe frame=$frame read=$read changed=$changed " +
                    "avgUs=${totalMicros / reads} p50Us=$p50 p99Us=$p99 maxUs=$maxMicros window=$filled")
            }
            onSnapshot(Snapshot(region, frame, current.copyOf(), read, changed, reads, micros,
                totalMicros / reads, maxMicros, p50, p99, map))
        }
    }

    private companion object {
        const val SAMPLE_WINDOW = 1024
    }
}
