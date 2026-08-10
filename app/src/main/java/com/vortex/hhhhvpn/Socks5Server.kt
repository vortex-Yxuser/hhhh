package com.vortex.hhhhvpn

import com.jcraft.jsch.ChannelDirectTCPIP
import com.jcraft.jsch.Session
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lightweight high-performance local SOCKS5 server over SSH Direct-TCPIP.
 * Designed for stability under concurrent load.
 */
class Socks5Server(
    private val session: Session,
    private val port: Int,
    private val onLog: (String) -> Unit = {}
) {
    private var serverSocket: ServerSocket? = null
    private val running = AtomicBoolean(false)
    private val pool: ExecutorService = Executors.newCachedThreadPool()

    fun start() {
        if (running.get()) return
        serverSocket = ServerSocket(port, 128, InetAddress.getByName("127.0.0.1"))
        running.set(true)
        Thread({
            while (running.get()) {
                try {
                    val client = serverSocket?.accept() ?: break
                    pool.execute { handleClient(client) }
                } catch (e: Exception) {
                    if (running.get()) onLog("SOCKS accept error: ${e.message}")
                }
            }
        }, "Socks5-Accept").apply { isDaemon = true }.start()
    }

    fun stop() {
        running.set(false)
        try { serverSocket?.close() } catch (_: Exception) {}
        pool.shutdownNow()
    }

    private fun handleClient(client: Socket) {
        var channel: ChannelDirectTCPIP? = null
        try {
            client.tcpNoDelay = true
            val input = client.getInputStream()
            val output = client.getOutputStream()

            // SOCKS5 greeting
            val ver = input.read()
            if (ver != 0x05) {
                client.close()
                return
            }
            val nMethods = input.read()
            if (nMethods > 0) input.skip(nMethods.toLong())
            // No auth
            output.write(byteArrayOf(0x05, 0x00))
            output.flush()

            // Request
            val req = ByteArray(4)
            if (input.read(req) != 4 || req[0] != 0x05.toByte() || req[1] != 0x01.toByte()) {
                client.close()
                return
            }
            val atyp = req[3].toInt() and 0xFF
            val destHost: String
            val destPort: Int

            when (atyp) {
                0x01 -> { // IPv4
                    val addr = ByteArray(4)
                    input.read(addr)
                    destHost = InetAddress.getByAddress(addr).hostAddress ?: "0.0.0.0"
                    destPort = ((input.read() and 0xFF) shl 8) or (input.read() and 0xFF)
                }
                0x03 -> { // Domain
                    val len = input.read()
                    val domain = ByteArray(len)
                    input.read(domain)
                    destHost = String(domain)
                    destPort = ((input.read() and 0xFF) shl 8) or (input.read() and 0xFF)
                }
                0x04 -> { // IPv6 - basic support
                    val addr = ByteArray(16)
                    input.read(addr)
                    destHost = InetAddress.getByAddress(addr).hostAddress ?: "::"
                    destPort = ((input.read() and 0xFF) shl 8) or (input.read() and 0xFF)
                }
                else -> {
                    replyError(output, 0x08)
                    client.close()
                    return
                }
            }

            // Open SSH direct-tcpip channel
            channel = session.openChannel("direct-tcpip") as ChannelDirectTCPIP
            channel.setHost(destHost)
            channel.setPort(destPort)
            channel.connect(15000)

            // Success reply
            output.write(byteArrayOf(
                0x05, 0x00, 0x00, 0x01,
                0x00, 0x00, 0x00, 0x00, // bind addr
                0x00, 0x00              // bind port
            ))
            output.flush()

            // Bidirectional pipe
            val chIn = channel.inputStream
            val chOut = channel.outputStream

            val t1 = Thread({ pipe(input, chOut) }, "pipe-c2s")
            val t2 = Thread({ pipe(chIn, output) }, "pipe-s2c")
            t1.isDaemon = true
            t2.isDaemon = true
            t1.start()
            t2.start()
            t1.join()
            t2.join()
        } catch (e: Exception) {
            // silent for speed under load
        } finally {
            try { channel?.disconnect() } catch (_: Exception) {}
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun pipe(from: InputStream, to: OutputStream) {
        val buf = ByteArray(32 * 1024)
        try {
            while (true) {
                val n = from.read(buf)
                if (n <= 0) break
                to.write(buf, 0, n)
                to.flush()
            }
        } catch (_: IOException) {
        }
    }

    private fun replyError(out: OutputStream, code: Int) {
        try {
            out.write(byteArrayOf(0x05, code.toByte(), 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            out.flush()
        } catch (_: Exception) {}
    }
}
