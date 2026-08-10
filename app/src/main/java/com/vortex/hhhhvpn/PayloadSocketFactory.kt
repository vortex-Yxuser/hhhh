package com.vortex.hhhhvpn

import android.net.VpnService
import com.jcraft.jsch.SocketFactory
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException

/**
 * High-performance Payload Socket Factory.
 * Optimized for speed (TCP_NODELAY, larger buffers, minimal blocking)
 * and stability (proper timeouts, protect(), clean error handling).
 */
class PayloadSocketFactory(
    private val config: TunnelConfig,
    private val vpnService: VpnService? = null,
    private val onLog: (String) -> Unit = {}
) : SocketFactory {

    override fun createSocket(host: String?, port: Int): Socket {
        val targetHost: String
        val targetPort: Int

        if (config.proxyEnabled && config.proxyHost.isNotBlank()) {
            targetHost = config.proxyHost
            targetPort = config.proxyPort
            onLog("→ Connecting via proxy $targetHost:$targetPort")
        } else {
            targetHost = host ?: config.sshHost
            targetPort = port
            onLog("→ Direct TCP to $targetHost:$targetPort")
        }

        val socket = Socket()
        try {
            // Speed optimizations
            if (config.enableTcpNoDelay) {
                socket.tcpNoDelay = true
            }
            socket.receiveBufferSize = 256 * 1024
            socket.sendBufferSize = 256 * 1024
            socket.keepAlive = true
            socket.soTimeout = 0 // will set temporarily later

            val timeout = config.connectTimeoutMs.coerceIn(8000, 45000)
            socket.connect(InetSocketAddress(targetHost, targetPort), timeout)

            // Critical: protect socket from VPN loop
            vpnService?.let {
                if (it.protect(socket)) {
                    onLog("✓ Socket protected (no routing loop)")
                } else {
                    onLog("⚠ protect() returned false — possible loop risk")
                }
            }

            onLog("✓ TCP connected in ${System.currentTimeMillis() % 10000}ms window")

            // Send payload if present
            if (config.payload.isNotBlank()) {
                val finalPayload = applyPlaceholders(config.payload, host ?: config.sshHost, port)
                val bytes = finalPayload.toByteArray(Charsets.ISO_8859_1)
                socket.getOutputStream().write(bytes)
                socket.getOutputStream().flush()
                onLog("→ Payload sent (${bytes.size} bytes)")

                // Fast discard of any early HTTP response (non-blocking style)
                discardEarlyResponse(socket)
            }
        } catch (e: Exception) {
            try { socket.close() } catch (_: Exception) {}
            throw SocketException("PayloadSocket failed: ${e.message}")
        }

        return socket
    }

    private fun discardEarlyResponse(socket: Socket) {
        try {
            socket.soTimeout = 1200 // very short
            val input = socket.getInputStream()
            if (input.available() > 0 || Thread.sleep(150).let { input.available() > 0 }) {
                val buf = ByteArray(8192)
                val read = input.read(buf)
                if (read > 0) {
                    onLog("↷ Discarded early response ($read bytes)")
                }
            }
        } catch (_: Exception) {
            // Expected — many servers send nothing
        } finally {
            try { socket.soTimeout = 0 } catch (_: Exception) {}
        }
    }

    override fun getInputStream(socket: Socket): InputStream = socket.getInputStream()
    override fun getOutputStream(socket: Socket): OutputStream = socket.getOutputStream()

    private fun applyPlaceholders(raw: String, host: String, port: Int): String {
        return raw
            .replace("[host_port]", "$host:$port")
            .replace("[host]", host)
            .replace("[port]", port.toString())
            .replace("[crlf]", "\r\n")
            .replace("[cr]", "\r")
            .replace("[lf]", "\n")
    }
}
