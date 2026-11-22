/*
 * DualPlay — DS bottom screen on the secondary display. GPL-3.0-or-later.
 */
package dev.darkaxt.dualplay

import android.annotation.SuppressLint
import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.swordfish.libretrodroid.LibretroDroid

/**
 * Shows the lower half of a stacked dual-screen core frame on the secondary display through the
 * runtime's secondary surface, and forwards touch to the core's pointer. Stays non-focusable so
 * controller input remains with the game window.
 */
class BottomScreenPresentation(
    context: Context,
    display: Display,
) : Presentation(context, display), SurfaceHolder.Callback {

    private lateinit var stats: TextView

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)

        val surfaceView = SurfaceView(context).apply {
            holder.addCallback(this@BottomScreenPresentation)
            setOnTouchListener { view, event -> forwardTouch(view.width, view.height, event) }
        }
        stats = TextView(context).apply {
            typeface = Typeface.MONOSPACE
            textSize = 10f
            setTextColor(Color.argb(0xC0, 0xE0, 0xE8, 0xD0))
            setPadding(8, 4, 8, 4)
        }
        setContentView(FrameLayout(context).apply {
            setBackgroundColor(Color.BLACK)
            addView(surfaceView, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(stats, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END))
        })
    }

    private fun forwardTouch(width: Int, height: Int, event: MotionEvent): Boolean {
        if (width <= 0 || height <= 0) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE ->
                LibretroDroid.onSecondaryTouchEvent(event.x / width, event.y / height)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                LibretroDroid.onSecondaryTouchEvent(-1f, -1f)
        }
        return true
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        LibretroDroid.setSecondaryCrop(0f, 0.5f, 1f, 1f)
        LibretroDroid.setSecondarySurface(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        // EGL picks up the new size on the next frame.
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        // Returns once the runtime no longer uses the surface.
        LibretroDroid.setSecondarySurface(null)
    }

    fun render(s: MemoryProbe.Snapshot) {
        if (!::stats.isInitialized) return
        stats.text = "f%d  read µs p50/p99/max %d/%d/%d".format(s.frame, s.p50Micros, s.p99Micros, s.maxMicros)
    }
}
