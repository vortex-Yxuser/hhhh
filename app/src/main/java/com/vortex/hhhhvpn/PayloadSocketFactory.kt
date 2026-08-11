package com.vortex.hhhhvpn

import android.net.VpnService
import com.jcraft.jsch.SocketFactory
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException

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
            onLog("Connecting to proxy $targetHost port $targetPort")
        } else {
            targetHost = host ?: config.sshHost
            targetPort = port
            onLog("Direct TCP to $targetHost:$targetPort")
        }

        val socket = Socket()
        try {
            if (config.enableTcpNoDelay) socket.tcpNoDelay = true
            socket.receiveBufferSize = 256 * 1024
            socket.sendBufferSize = 256 * 1024
            socket.keepAlive = true

            val timeout = config.connectTimeoutMs.coerceIn(8000, 45000)
            socket.connect(InetSocketAddress(targetHost, targetPort), timeout)

            vpnService?.let {
                if (it.protect(socket)) {
                    onLog("Socket protected (no routing loop)")
                }
            }

            if (config.payload.isNotBlank()) {
                val finalPayload = applyPlaceholders(config.payload, host ?: config.sshHost, port)
                val bytes = finalPayload.toByteArray(Charsets.ISO_8859_1)
                onLog("Sending Payload: ${finalPayload.replace("\r\n", "[crlf]")}")
                socket.getOutputStream().write(bytes)
                socket.getOutputStream().flush()

                // Read and show proxy response (like DarkTunnel)
                try {
                    socket.soTimeout = 2500
                    val buf = ByteArray(1024)
                    val n = socket.getInputStream().read(buf)
                    if (n > 0) {
                        val response = String(buf, 0, n, Charsets.ISO_8859_1)
                            .lineSequence()
                            .firstOrNull()
                            ?.trim()
                            ?: ""
                        if (response.isNotBlank()) {
                            onLog("Response: $response")
                        }
                    }
                } catch (_: Exception) {
                    // no response is ok for some proxies
                } finally {
                    try { socket.soTimeout = 0 } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            try { socket.close() } catch (_: Exception) {}
            throw SocketException("Connection failed: ${e.message}")
        }

        return socket
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
