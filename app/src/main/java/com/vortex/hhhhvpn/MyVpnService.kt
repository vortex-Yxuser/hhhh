package com.vortex.hhhhvpn

import android.app.*
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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

    private fun timeStr(): String {
        return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

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
                isRunning = true
                stopping.set(false)

                // عرض معلومات الجهاز تماماً مثل DarkTunnel
                val model = Build.MODEL ?: "Android"
                val manufacturer = Build.MANUFACTURER ?: ""
                val deviceName = if (model.startsWith(manufacturer, true)) model else "$manufacturer $model"
                val androidVer = Build.VERSION.RELEASE ?: "14"
                val sdkInt = Build.VERSION.SDK_INT
                sendLog("Running on $deviceName [${timeStr()}]")
                sendLog("(Android $androidVer) API $sdkInt. Version 1.0.26 Build 32")

                val manager = SshTunnelManager(config, this) { log ->
                    updateNotification(log.take(60))
                    sendLog(log)
                }
                tunnelManager = manager

                // 1. SSH Connection
                manager.connect()

                // 2. Local SOCKS5
                manager.startSocksProxy()

                // 3. TUN interface
                val builder = Builder()
                    .setSession("Yohan-VPN")
                    .addAddress("10.8.0.2", 32)
                    .addRoute("0.0.0.0", 0)
                    .addDnsServer("10.44.8.1")
                    .addDnsServer("41.110.32.3")
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

                var started = false
                try {
                    started = TProxyService.TProxyStartService(yaml.absolutePath, tunInterface!!.fd)
                } catch (t: Throwable) {
                    sendLog("✗ Native error: ${t.javaClass.simpleName}")
                    stopTunnel("Native crash prevented")
                    return@Thread
                }

                if (!started) {
                    sendLog("✗ Failed to start tun2socks engine")
                    stopTunnel("Engine start failed")
                    return@Thread
                }

                sendLog("DNS 10.44.8.1 [${timeStr()}]")
                sendLog("DNS 41.110.32.3 [${timeStr()}]")
                sendLog("Connected [${timeStr()}]")
                updateNotification("Connected ✅")

            } catch (e: Exception) {
                val msg = e.message ?: "Connection error"
                sendLog("Connection error: $msg [${timeStr()}]")
                sendLog("Connection closed [${timeStr()}]")
                stopTunnel(msg)
            }
        }, "VpnWorkerThread")

        workerThread?.start()
    }

    private fun stopTunnel(reason: String) {
        if (stopping.getAndSet(true)) return
        isRunning = false

        try {
            TProxyService.TProxyStopService()
        } catch (_: Exception) {}

        try {
            tunnelManager?.disconnect()
        } catch (_: Exception) {}

        try {
            tunInterface?.close()
        } catch (_: Exception) {}

        tunInterface = null
        tunnelManager = null

        sendLog("Connection closed [${timeStr()}]")
        updateNotification("Disconnected")

        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } catch (_: Exception) {}
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Yohan VPN Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(status: String): Notification {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Yohan VPN")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(status: String) {
        try {
            val notification = buildNotification(status)
            val manager = getSystemService(NotificationManager::class.java)
            manager?.notify(NOTIF_ID, notification)
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        stopTunnel("Service destroyed")
        super.onDestroy()
    }
}
