/*
 * DualPlay — RetroAchievements HTTP bridge for the runtime's rcheevos client. GPL-3.0-or-later.
 */
package dev.darkaxt.dualplay

import android.util.Log
import com.swordfish.libretrodroid.LibretroDroid
import dev.darkaxt.dualplay.ra.RaAccount
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Performs rcheevos server calls off the emulation thread and hands the responses back to the runtime.
 * Events are forwarded to [onEvent]; the default logs them.
 */
class RetroAchievementsBridge(
    appVersion: String,
    private val onEvent: (event: Int, value: Long, text: String) -> Unit = { event, value, text ->
        Log.i(GameActivity.TAG, "rcheevos ${eventName(event)} value=$value text=${loggable(event, text)}")
    },
) : LibretroDroid.AchievementsListener {

    private val userAgent = RaAccount.userAgent(appVersion)
    private val http: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "DualPlay-RA-HTTP").apply { isDaemon = true }
    }

    fun install() = LibretroDroid.achievementsSetListener(this)

    fun shutdown() {
        LibretroDroid.achievementsSetListener(null)
        http.shutdownNow()
    }

    override fun onServerCall(id: Long, url: ByteArray, postData: ByteArray, contentType: ByteArray) {
        val target = String(url, Charsets.UTF_8)
        val body = postData
        val type = String(contentType, Charsets.UTF_8)
        http.execute {
            var status: Int
            var response = ByteArray(0)
            try {
                val connection = URL(target).openConnection() as HttpURLConnection
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                connection.setRequestProperty("User-Agent", userAgent)
                if (body.isNotEmpty()) {
                    connection.requestMethod = "POST"
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", type.ifEmpty { "application/x-www-form-urlencoded" })
                    connection.outputStream.use { it.write(body) }
                }
                status = connection.responseCode
                val stream = if (status >= 400) connection.errorStream else connection.inputStream
                response = stream?.use { it.readBytes() } ?: ByteArray(0)
                connection.disconnect()
            } catch (error: IOException) {
                Log.w(GameActivity.TAG, "rcheevos request failed: ${error.message}")
                status = RETRYABLE_CLIENT_ERROR
            }
            LibretroDroid.achievementsServerResponse(id, response, status)
        }
    }

    override fun onEvent(event: Int, value: Long, text: ByteArray) {
        onEvent(event, value, String(text, Charsets.UTF_8))
    }

    companion object {
        /** RC_API_SERVER_RESPONSE_RETRYABLE_CLIENT_ERROR */
        private const val RETRYABLE_CLIENT_ERROR = -2

        /** Event text safe for logcat: a login reports "user\ntoken", so only the user is kept. */
        fun loggable(event: Int, text: String): String =
            if (event == 3) text.substringBefore('\n') + (if ('\n' in text) "|<token>" else "") else text.replace('\n', '|')

        fun eventName(event: Int): String = when (event) {
            0 -> "log"
            1 -> "hash"
            2 -> "identified"
            3 -> "login"
            4 -> "game-loaded"
            5 -> "achievement"
            6 -> "server-error"
            else -> "event$event"
        }
    }
}
