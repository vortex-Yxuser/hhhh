package com.vortex.hhhhvpn

import android.net.VpnService
import com.jcraft.jsch.SocketFactory
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PayloadSocketFactory(
    private val config: TunnelConfig,
    private val vpnService: VpnService? = null,
    private val onLog: (String) -> Unit = {}
) : SocketFactory {

    private fun timeStr(): String {
        return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
    }

    override fun createSocket(host: String?, port: Int): Socket {
        val targetHost: String
        val targetPort: Int

        // استخدام البروكسي الخفي دون طباعة عنوانه في السجلات للحفاظ على الخصوصية
        if (config.proxyEnabled && config.proxyHost.isNotBlank()) {
            targetHost = config.proxyHost
            targetPort = config.proxyPort
            onLog("Connecting to proxy... [${timeStr()}]")
        } else {
            targetHost = host ?: config.sshHost
            targetPort = port
            onLog("Connecting to server $targetHost:$targetPort [${timeStr()}]")
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
                    // حماية المقبس بنجاح دون إزعاج المستخدم بسجلات تقنية
                }
            }

            if (config.payload.isNotBlank()) {
                val finalPayload = applyPlaceholders(config.payload, host ?: config.sshHost, port)
                val bytes = finalPayload.toByteArray(Charsets.ISO_8859_1)
                
                // إخفاء تفاصيل Payload الفعلية من السجلات كما طلبت، وطباعة رسالة مرتبة مشابهة لـ DarkTunnel
                onLog("Sending Payload... [${timeStr()}]")
                socket.getOutputStream().write(bytes)
                socket.getOutputStream().flush()

                try {
                    socket.soTimeout = 3000
                    val buf = ByteArray(2048)
                    val n = socket.getInputStream().read(buf)
                    if (n > 0) {
                        val response = String(buf, 0, n, Charsets.ISO_8859_1)
                            .lineSequence()
                            .firstOrNull()
                            ?.trim()
                            ?: ""
                        if (response.isNotBlank()) {
                            onLog("Response: $response [${timeStr()}]")
                        }
                    }
                } catch (_: Exception) {
                    // تخطي انتهاء مهلة الرد إذا لم يرسل الخادم استجابة مباشرة
                } finally {
                    try { socket.soTimeout = 0 } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            try { socket.close() } catch (_: Exception) {}
            onLog("Connecting to server error, client disconnected [${timeStr()}]")
            onLog("Connection closed [${timeStr()}]")
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
