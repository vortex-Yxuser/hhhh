package com.vortex.hhhhvpn

import android.app.*
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class MyVpnService : VpnService() {

    companion object {
        const val ACTION_CONNECT = "com.vortex.hhhhvpn.CONNECT"
        const val ACTION_DISCONNECT = "com.vortex.hhhhvpn.DISCONNECT"
        const val ACTION_LOG = "com.vortex.hhhhvpn.LOG"
        const val CHANNEL_ID = "hhhh_vpn_channel"
        const val NOTIF_ID = 1001

        @Volatile
        var isRunning = false
            private set
    }

    private var tunInterface: ParcelFileDescriptor? = null
    private var tunnelManager: SshTunnelManager? = null
    private var workerThread: Thread? = null
    private val stopping = AtomicBoolean(false)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                stopTunnel("User disconnected")
                return START_NOT_STICKY
            }
            else -> {
                startForeground(NOTIF_ID, buildNotification("Connecting..."))
                startTunnel()
            }
        }
        return START_STICKY
    }

    private fun sendLog(message: String) {
        try {
            val intent = Intent(ACTION_LOG).apply {
                putExtra("log_msg", message)
                setPackage(packageName)
            }
            sendBroadcast(intent)
        } catch (_: Exception) {}
    }

    private fun startTunnel() {
        if (isRunning || stopping.get()) return
        val config = Prefs.load(applicationContext)

        workerThread = Thread({
            try {
                sendLog("▶ Starting HHHH Pro connection...")
                val manager = SshTunnelManager(config, this) { log ->
                    updateNotification(log.take(60))
                    sendLog(log)
                }
                tunnelManager = manager

                // 1. SSH
                manager.connect()

                // 2. Local SOCKS
                manager.startSocksProxy()

                // 3. TUN interface
                val builder = Builder()
                    .setSession("HHHH-SSH-VPN-Pro")
                    .addAddress("10.8.0.2", 32)
                    .addRoute("0.0.0.0", 0)
                    .addDnsServer("1.1.1.1")
                    .addDnsServer("8.8.8.8")
                    .setMtu(config.mtu)
                    .setBlocking(true)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    try {
                        builder.addDisallowedApplication(packageName)
                    } catch (_: Exception) {}
                }

                tunInterface = builder.establish()
                if (tunInterface == null) {
                    sendLog("✗ Failed to establish TUN interface")
                    stopTunnel("TUN failed")
                    return@Thread
                }
                sendLog("✓ TUN interface ready (fd=${tunInterface!!.fd})")

                // 4. Write config for hev-socks5-tunnel
                val yaml = File(filesDir, "tun2socks.yaml")
                yaml.writeText(
                    """
                    tunnel:
                      mtu: ${config.mtu}
                    socks5:
                      address: 127.0.0.1
                      port: ${config.localSocksPort}
                      udp: 'udp'
                    misc:
                      task-stack-size: 81920
                    """.trimIndent()
                )
                sendLog("→ Starting tun2socks engine...")

                // Catch Error (UnsatisfiedLinkError) as well as Exception
                var started = false
                try {
                    started = TProxyService.TProxyStartService(yaml.absolutePath, tunInterface!!.fd)
                } catch (t: Throwable) {
                    sendLog("✗ Native error: ${t.javaClass.simpleName}: ${t.message?.take(80)}")
                    stopTunnel("Native crash prevented")
                    return@Thread
                }

                if (!started) {
                    sendLog("✗ tun2socks returned false")
                    stopTunnel("tun2socks failed")
                    return@Thread
                }

                isRunning = true
                sendLog("✅ Fully connected — traffic is now tunneled")
                updateNotification("Connected • HHHH Pro")

            } catch (e: Exception) {
                val msg = "✗ Connection failed: ${e.message?.take(120)}"
                sendLog(msg)
                updateNotification(msg)
                stopTunnel(msg)
            } catch (t: Throwable) {
                sendLog("✗ Fatal: ${t.javaClass.simpleName}")
                stopTunnel("Fatal error")
            }
        }, "HHHH-VPN-Worker").apply {
            isDaemon = false
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private fun stopTunnel(reason: String = "Stopped") {
        if (!stopping.compareAndSet(false, true)) return
        isRunning = false
        sendLog("○ $reason")

        try { TProxyService.TProxyStopService() } catch (_: Throwable) {}
        try { tunnelManager?.disconnect() } catch (_: Exception) {}
        try { tunInterface?.close() } catch (_: Exception) {}

        tunInterface = null
        tunnelManager = null
        workerThread = null

        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {}
        stopSelf()
        stopping.set(false)
    }

    override fun onDestroy() {
        stopTunnel("Service destroyed")
        super.onDestroy()
    }

    override fun onRevoke() {
        stopTunnel("VPN revoked by system")
        super.onRevoke()
    }

    private fun buildNotification(text: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "HHHH SSH VPN", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "VPN connection status"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("HHHH SSH VPN Pro")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification(text: String) {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm?.notify(NOTIF_ID, buildNotification(text))
        } catch (_: Exception) {}
    }
}
