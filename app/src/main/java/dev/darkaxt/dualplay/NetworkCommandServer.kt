/*
 * DualPlay — RetroArch-compatible network commands for DualDex. GPL-3.0-or-later.
 */
package dev.darkaxt.dualplay

import android.util.Log
import com.swordfish.libretrodroid.LibretroDroid
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException

/**
 * Answers the read-only subset of RetroArch's UDP network commands that DualDex uses, on 127.0.0.1 only:
 * `VERSION`, `GET_STATUS`, `GET_CONFIG_PARAM` and `READ_CORE_MEMORY`. Memory reads go through the runtime's
 * RetroArch-exact memory map between frames. Write commands are not implemented, and other commands get no
 * reply. Reply formats follow RetroArch's `command.c`.
 */
class NetworkCommandServer(private val session: Session) {

    /** What the running game reports. [paused] is read on every status request. */
    class Session(
        /** RetroArch core-info `systemid`, for example `game_boy_advance`. */
        val systemId: String,
        val basename: String,
        val config: Map<String, String>,
        val paused: () -> Boolean,
    ) {
        /** CRC32 of the loaded content, set once it is computed. */
        @Volatile var crc32: Long? = null
    }

    @Volatile private var socket: DatagramSocket? = null
    private var thread: Thread? = null

    fun start(port: Int = PORT): Boolean {
        val bound = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                // IPv4 explicitly: Android's getLoopbackAddress() is ::1, and clients send to 127.0.0.1.
                bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port))
            }
        } catch (error: SocketException) {
            Log.w(GameActivity.TAG, "netcmd port $port unavailable: ${error.message}")
            return false
        }
        socket = bound
        thread = Thread({ serve(bound) }, "DualPlay-NetCmd").apply {
            isDaemon = true
            start()
        }
        Log.i(GameActivity.TAG, "netcmd listening ${bound.localSocketAddress} system=${session.systemId} game='${session.basename}'")
        return true
    }

    fun stop() {
        socket?.close()
        socket = null
        thread?.join(1_000)
        thread = null
    }

    private fun serve(socket: DatagramSocket) {
        val buffer = ByteArray(MAX_PACKET)
        val reply = ByteArray(MAX_PACKET)
        var served = 0L
        var reads = 0L
        var readNanos = 0L
        var nextLog = System.nanoTime() + LOG_INTERVAL_NS
        while (!socket.isClosed) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
            } catch (_: SocketException) {
                break
            }
            val command = String(packet.data, 0, packet.length, Charsets.US_ASCII).trim().trimEnd('\u0000')
            val started = System.nanoTime()
            val length = answer(command, reply)
            if (command.startsWith(READ)) {
                reads++
                readNanos += System.nanoTime() - started
            }
            if (length > 0) {
                try {
                    socket.send(DatagramPacket(reply, length, packet.socketAddress))
                } catch (_: SocketException) {
                    break
                }
            }
            served++
            if (System.nanoTime() >= nextLog) {
                Log.i(GameActivity.TAG, "netcmd served=$served reads=$reads avgReadUs=${if (reads > 0) readNanos / reads / 1_000 else 0}")
                nextLog = System.nanoTime() + LOG_INTERVAL_NS
            }
        }
        Log.i(GameActivity.TAG, "netcmd stopped served=$served reads=$reads")
    }

    /** Writes the reply for [command] into [out] and returns its length, or 0 for no reply. */
    internal fun answer(command: String, out: ByteArray): Int {
        val text = when {
            command == "VERSION" -> "$RETROARCH_VERSION\n"
            command == "GET_STATUS" -> status()
            command.startsWith("GET_CONFIG_PARAM ") -> {
                val name = command.removePrefix("GET_CONFIG_PARAM ").trim()
                "GET_CONFIG_PARAM $name ${session.config[name] ?: "unsupported"}\n"
            }
            command.startsWith("$READ ") -> return readMemory(command.removePrefix("$READ ").trim(), out)
            else -> return 0
        }
        val bytes = text.toByteArray(Charsets.US_ASCII)
        val length = minOf(bytes.size, out.size)
        bytes.copyInto(out, 0, 0, length)
        return length
    }

    private fun status(): String {
        val state = if (session.paused()) "PAUSED" else "PLAYING"
        val crc = session.crc32?.let { ",crc32=%08x".format(it) }.orEmpty()
        return "GET_STATUS $state ${session.systemId},${session.basename}$crc\n"
    }

    /** `READ_CORE_MEMORY <hex address> <decimal length>` → `READ_CORE_MEMORY <addr> XX XX ...` or ` -1 <reason>`. */
    private fun readMemory(arguments: String, out: ByteArray): Int {
        val parts = arguments.split(' ').filter(String::isNotEmpty)
        val address = parts.getOrNull(0)?.toLongOrNull(16)
        val requested = parts.getOrNull(1)?.toIntOrNull()
        val header = "$READ ${(address ?: 0L).toString(16)}"
        val text = StringBuilder(header)
        if (address == null || requested == null || requested <= 0) {
            text.append(" -1 invalid arguments\n")
            return copy(text, out)
        }
        val length = minOf(requested, MAX_READ)
        val data = ByteArray(length)
        val read = LibretroDroid.readMemory(address, data, 0, length)
        if (read <= 0) {
            text.append(" -1 address not mapped\n")
            return copy(text, out)
        }
        for (i in 0 until read) {
            val value = data[i].toInt() and 0xff
            text.append(' ').append(HEX[value ushr 4]).append(HEX[value and 0xf])
        }
        text.append('\n')
        return copy(text, out)
    }

    private fun copy(text: CharSequence, out: ByteArray): Int {
        val length = minOf(text.length, out.size)
        for (i in 0 until length) out[i] = text[i].code.toByte()
        return length
    }

    companion object {
        const val PORT = 55355
        /** The RetroArch release whose command protocol this follows. */
        const val RETROARCH_VERSION = "1.19.1"
        private const val READ = "READ_CORE_MEMORY"
        const val MAX_PACKET = 4096
        /** Largest read whose reply fits one packet: header plus three characters per byte. */
        private const val MAX_READ = (MAX_PACKET - 32) / 3
        private const val LOG_INTERVAL_NS = 10_000_000_000L
        private val HEX = "0123456789ABCDEF".toCharArray()

        /** RetroArch core-info `systemid` per DualPlay system. */
        fun systemId(system: String): String = when (system) {
            "gba" -> "game_boy_advance"
            "gb", "gbc" -> "game_boy"
            "nds" -> "nintendo_ds"
            else -> system
        }
    }
}
