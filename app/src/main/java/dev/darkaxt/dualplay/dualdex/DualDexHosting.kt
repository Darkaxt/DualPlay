/*
 * DualPlay — DualDex hosted in-process on the bottom display. GPL-3.0-or-later.
 */
package dev.darkaxt.dualplay.dualdex

import android.content.Context
import android.os.Environment
import android.util.Log
import com.darkaxt.dualdex.host.DualDexCompanionHost
import com.darkaxt.dualdex.retroarch.NetworkCommandTransport
import com.darkaxt.dualdex.retroarch.RomIndexEntry
import com.darkaxt.dualdex.retroarch.RomPlatform
import com.darkaxt.dualdex.setup.HostSession
import com.darkaxt.dualdex.storage.SharedStorageGateway
import dev.darkaxt.dualplay.GameActivity
import dev.darkaxt.dualplay.NetworkCommandServer
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * One DualDex companion for the process, as the DualDex app has. It talks to the running game through
 * [NetworkCommandServer.answer] directly (no socket) and is told which ROM is loaded, so it needs neither DualDex's ROM
 * library nor RetroArch's folders: its shared-storage roots are empty, so it indexes nothing and finds no RetroArch
 * config to patch. Saves are found from the `savefile_directory` reply (DualPlay's own saves folder).
 */
object DualDexHosting {
    @Volatile private var host: DualDexCompanionHost? = null
    @Volatile private var server: NetworkCommandServer? = null
    @Volatile private var session: HostSession? = null

    /** Starts the companion once; returns it, or `null` if it could not start (logged). */
    @Synchronized
    fun start(context: Context, appVersion: String?): DualDexCompanionHost? {
        val existing = host ?: DualDexCompanionHost(
            context.applicationContext,
            DualDexCompanionHost.Config(
                sharedStorage = SharedStorageGateway(
                    accessCheck = { Environment.isExternalStorageManager() },
                    rootProvider = { emptyList() },
                ),
                networkCommandTransportFactory = { InProcessTransport() },
                hostSession = { session },
                appVersion = appVersion,
            ),
        ).also { host = it }
        if (!existing.start()) {
            Log.w(GameActivity.TAG, "dualdex host failed to start", existing.startupFailure)
            return null
        }
        Log.i(GameActivity.TAG, "dualdex host started origin=${existing.localOrigin}")
        return existing
    }

    /** The running game: DualDex polls [server] and loads [rom]. */
    fun attach(server: NetworkCommandServer, rom: HostSession) {
        this.server = server
        session = rom
        Log.i(GameActivity.TAG, "dualdex attached ${rom.rom.platform} crc32=${rom.rom.crc32} '${rom.rom.gameBasename}'")
    }

    fun detach(server: NetworkCommandServer) {
        if (this.server !== server) return
        this.server = null
        session = null
    }

    fun state(): String {
        val view = host?.retroArchSetup?.snapshot() ?: return "no host"
        return "connection=${view.connection} resolution=${view.resolution} system=${view.systemId} " +
            "crc32=${view.contentCrc32} source=${view.activeSource} saves=${view.savefileDirectory} message='${view.message}'"
    }

    /** A [HostSession] for the extracted ROM [rom], identified by its CRC32 and SHA-256. */
    fun hostSession(rom: File, system: String, basename: String, crc32: Long, sha256: String): HostSession? {
        val platform = when (system) {
            "gba" -> RomPlatform.GBA
            "gbc" -> RomPlatform.GBC
            "gb" -> RomPlatform.GB
            else -> return null
        }
        return HostSession(
            RomIndexEntry(
                sourceId = rom.toURI().toString(),
                sourceName = rom.name,
                archiveEntry = null,
                platform = platform,
                gameBasename = basename,
                crc32 = "%08X".format(crc32),
                sha256 = sha256,
                sourceSize = rom.length(),
                sourceLastModifiedEpochMs = rom.lastModified(),
            ),
        )
    }

    /** Answers each command in the caller's thread through the game's command handler; replies queue for [poll]. */
    private class InProcessTransport : NetworkCommandTransport {
        private val replies = ConcurrentLinkedQueue<ByteArray>()
        private val buffer = ByteArray(NetworkCommandServer.MAX_PACKET)

        @Synchronized
        override fun send(payload: ByteArray) {
            val target = server ?: return
            val command = String(payload, Charsets.US_ASCII).trim().trimEnd('\u0000')
            val length = target.answer(command, buffer)
            if (length > 0) replies.add(buffer.copyOf(length))
        }

        override fun poll(): ByteArray? = replies.poll()

        override fun close() = replies.clear()
    }
}
