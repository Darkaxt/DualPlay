/*
 * DualPlay — DualDex hosted in-process on the bottom display. GPL-3.0-or-later.
 */
package dev.darkaxt.dualplay.dualdex

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Display
import android.view.WindowManager
import com.darkaxt.dualdex.host.DualDexCompanionHost
import com.darkaxt.dualdex.web.DualDexWebView
import dev.darkaxt.dualplay.GameActivity

/**
 * DualDex's companion UI on the secondary display. The window is not focusable, so controller input stays with the
 * game on the top display; the bottom screen still takes touch.
 */
class DualDexPresentation(
    context: Context,
    display: Display,
    private val host: DualDexCompanionHost,
    private val origin: String,
) : Presentation(context, display) {

    private var view: DualDexWebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        val web = DualDexWebView(
            context,
            origin,
            picker = null,
            onNativeSetupRoute = { route -> Log.i(GameActivity.TAG, "dualdex setup route $route ignored in DualPlay") },
            onMainFrameFailure = { reason -> Log.w(GameActivity.TAG, "dualdex page failed: $reason") },
        ).apply {
            setBackgroundColor(Color.BLACK)
            isFocusable = false
        }
        view = web
        setContentView(web)
        web.open()
    }

    override fun onStart() {
        super.onStart()
        view?.let(host::activateCompanionSurface)
    }

    override fun onStop() {
        view?.let(host::pauseCompanionSurface)
        super.onStop()
    }

    override fun dismiss() {
        view?.let(host::releaseCompanionSurface)
        view = null
        super.dismiss()
    }
}
