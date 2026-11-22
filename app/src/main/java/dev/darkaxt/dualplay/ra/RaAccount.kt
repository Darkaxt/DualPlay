/*
 * DualPlay — RetroAchievements account. GPL-3.0-or-later.
 */
package dev.darkaxt.dualplay.ra

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.swordfish.libretrodroid.LibretroDroid
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** A signed-in RetroAchievements user: the name and the API token from `login2` (never the password). */
data class RaCredentials(val user: String, val token: String)

/**
 * Stores [RaCredentials] in app-private storage (`files/ra/account.bin`, no backup), encrypted with an
 * AES-GCM key held in the Android Keystore. The password is sent once to RetroAchievements over HTTPS
 * to obtain the token and is not kept.
 */
class RaAccount(context: Context) {

    private val file = File(context.filesDir, "ra/account.bin")

    fun load(): RaCredentials? = runCatching {
        if (!file.isFile) return null
        val bytes = file.readBytes()
        val iv = bytes.copyOfRange(0, IV_BYTES)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        val json = JSONObject(String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8))
        RaCredentials(json.getString("user"), json.getString("token"))
    }.getOrNull()

    fun save(credentials: RaCredentials) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val plain = JSONObject().put("user", credentials.user).put("token", credentials.token).toString()
        val sealed = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeBytes(sealed)
        if (!temp.renameTo(file)) throw IOException("could not replace ${file.name}")
    }

    fun signOut() {
        file.delete()
        runCatching { KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(KEY_ALIAS) }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "dualplay_ra_account"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val LOGIN_URL = "https://retroachievements.org/dorequest.php"

        fun userAgent(appVersion: String): String =
            "DualPlay/$appVersion (Android ${Build.VERSION.RELEASE}) ${LibretroDroid.achievementsUserAgentClause()}"

        /**
         * Exchanges a user name and password for a token (`r=login2`). Blocking: call off the main thread.
         * Returns the credentials, or throws with RetroAchievements' error message.
         */
        fun signIn(user: String, password: String, appVersion: String): RaCredentials {
            fun enc(value: String) = URLEncoder.encode(value, "UTF-8")
            val body = "r=login2&u=${enc(user)}&p=${enc(password)}".toByteArray(Charsets.UTF_8)
            val connection = URL(LOGIN_URL).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.connectTimeout = 15_000
                connection.readTimeout = 15_000
                connection.setRequestProperty("User-Agent", userAgent(appVersion))
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                connection.outputStream.use { it.write(body) }
                val status = connection.responseCode
                val text = (if (status in 200..299) connection.inputStream else connection.errorStream)
                    ?.use { String(it.readBytes(), Charsets.UTF_8) }.orEmpty()
                val json = runCatching { JSONObject(text) }.getOrNull()
                    ?: throw IOException("RetroAchievements answered HTTP $status")
                if (!json.optBoolean("Success")) {
                    throw IOException(json.optString("Error").ifEmpty { "Sign-in failed (HTTP $status)" })
                }
                val token = json.optString("Token")
                if (token.isEmpty()) throw IOException("RetroAchievements returned no token")
                return RaCredentials(json.optString("User").ifEmpty { user }, token)
            } finally {
                connection.disconnect()
            }
        }
    }
}
