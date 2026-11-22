/*
 * DualPlay — stage-1 spike. GPL-3.0-or-later.
 */
package dev.darkaxt.dualplay

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Display
import android.view.WindowManager
import android.widget.ScrollView
import android.widget.TextView

/** Bottom-display stand-in for DualDex: shows what the in-process memory API delivers. */
class DualDexPlaceholder(
    context: Context,
    display: Display,
    private val title: String,
) : Presentation(context, display) {

    private lateinit var text: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Controller input must stay with the game on the top display.
        window?.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        text = TextView(context).apply {
            typeface = Typeface.MONOSPACE
            textSize = 13f
            setTextColor(Color.rgb(0xE0, 0xE8, 0xD0))
            setBackgroundColor(Color.rgb(0x10, 0x18, 0x20))
            setPadding(24, 24, 24, 24)
            text = "DualDex placeholder — $title\nWaiting for the core…"
        }
        setContentView(ScrollView(context).apply { addView(text) })
    }

    fun render(s: MemoryProbe.Snapshot) {
        if (!::text.isInitialized) return
        val out = StringBuilder()
        out.append("DualDex placeholder — ").append(title).append('\n')
        out.append("display ").append(display.displayId).append(" · in-process core memory\n\n")
        out.append("frame ").append(s.frame)
            .append("   reads ").append(s.reads)
            .append("   read µs last/avg/max ").append(s.lastMicros).append('/')
            .append(s.averageMicros).append('/').append(s.maxMicros).append('\n')
        out.append("watch 0x%08X len %d  got %d  changed %d\n\n".format(
            s.region.address, s.region.length, s.bytesRead, s.changedBytes))

        out.append("memory map (").append(s.map.size).append(" regions)\n")
        s.map.forEach { out.append("  0x%08X  size 0x%06X  flags 0x%X\n".format(it.start, it.length, it.flags)) }
        out.append('\n')

        val shown = maxOf(0, minOf(s.bytesRead, s.bytes.size))
        for (row in 0 until shown step 16) {
            out.append("%08X ".format(s.region.address + row))
            for (i in row until minOf(row + 16, shown)) out.append(" %02X".format(s.bytes[i].toInt() and 0xFF))
            out.append('\n')
        }
        text.text = out
    }
}
