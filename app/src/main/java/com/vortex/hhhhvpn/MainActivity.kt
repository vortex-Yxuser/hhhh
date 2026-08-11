package com.vortex.hhhhvpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var etHost: EditText
    private lateinit var etPort: EditText
    private lateinit var etUser: EditText
    private lateinit var etPass: EditText
    private lateinit var tvStatus: TextView
    private lateinit var tvLogs: TextView
    private lateinit var btnConnect: Button
    private lateinit var btnDisconnect: Button
    private lateinit var btnClearLog: Button
    private lateinit var spPreset: Spinner

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    // Internal only - never shown in UI
    private val PROXY_HOST = "34.43.46.91"
    private val PROXY_PORT = 443

    private val PAYLOAD_YOUTUBE =
        "CONNECT [host_port] HTTP/1.1[crlf]Host: youtube.com[crlf][crlf]"
    private val PAYLOAD_SNAPCHAT =
        "CONNECT [host_port] HTTP/1.1[crlf]Host: api.Snapchat.com[crlf][crlf]"

    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val message = intent?.getStringExtra("log_msg") ?: return
            runOnUiThread {
                val ts = timeFmt.format(Date())
                tvLogs.append("\n[$ts] $message")
                tvLogs.post {
                    val layout = tvLogs.layout ?: return@post
                    val scroll = layout.getLineTop(tvLogs.lineCount) - tvLogs.height
                    if (scroll > 0) tvLogs.scrollTo(0, scroll)
                }

                when {
                    message.contains("Fully connected") || message.contains("✅") -> {
                        tvStatus.text = "● CONNECTED"
                        tvStatus.setTextColor(0xFF4CAF50.toInt())
                        btnConnect.isEnabled = false
                        btnDisconnect.isEnabled = true
                    }
                    message.contains("failed") || message.contains("✗") ||
                    message.contains("Stopped") || message.contains("disconnected") ||
                    message.contains("revoked") || message.contains("Fatal") ||
                    message.contains("Service destroyed") -> {
                        tvStatus.text = "○ Disconnected"
                        tvStatus.setTextColor(0xFFEF5350.toInt())
                        btnConnect.isEnabled = true
                        btnDisconnect.isEnabled = false
                    }
                }
            }
        }
    }

    private val vpnPrepareLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startVpnService()
        } else {
            tvStatus.text = "VPN permission denied"
            appendLocalLog("User denied VPN permission")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etHost = findViewById(R.id.etHost)
        etPort = findViewById(R.id.etPort)
        etUser = findViewById(R.id.etUser)
        etPass = findViewById(R.id.etPass)
        tvStatus = findViewById(R.id.tvStatus)
        tvLogs = findViewById(R.id.tvLogs)
        btnConnect = findViewById(R.id.btnConnect)
        btnDisconnect = findViewById(R.id.btnDisconnect)
        btnClearLog = findViewById(R.id.btnClearLog)
        spPreset = findViewById(R.id.spPreset)

        tvLogs.movementMethod = ScrollingMovementMethod()

        // Load saved SSH only
        val cfg = Prefs.load(this)
        etHost.setText(cfg.sshHost)
        etPort.setText(cfg.sshPort.toString())
        etUser.setText(cfg.sshUser)
        etPass.setText(cfg.sshPass)

        // Mode selector (YouTube / Snapchat only)
        val modes = arrayOf("YouTube", "Snapchat")
        spPreset.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, modes)

        // Restore last mode if possible
        if (cfg.payload.contains("Snapchat", ignoreCase = true)) {
            spPreset.setSelection(1)
        } else {
            spPreset.setSelection(0)
        }

        tvStatus.text = if (MyVpnService.isRunning) "● CONNECTED" else "○ Disconnected"
        if (MyVpnService.isRunning) {
            tvStatus.setTextColor(0xFF4CAF50.toInt())
        }
        btnConnect.isEnabled = !MyVpnService.isRunning
        btnDisconnect.isEnabled = MyVpnService.isRunning

        btnConnect.setOnClickListener { onConnectClicked() }
        btnDisconnect.setOnClickListener {
            startService(Intent(this, MyVpnService::class.java).apply {
                action = MyVpnService.ACTION_DISCONNECT
            })
        }
        btnClearLog.setOnClickListener {
            tvLogs.text = ""
            appendLocalLog("Logs cleared")
        }

        val filter = IntentFilter(MyVpnService.ACTION_LOG)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(logReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(logReceiver, filter)
        }

        appendLocalLog("Yohan VPN ready")
        appendLocalLog("Enter SSH account then press CONNECT")
    }

    private fun appendLocalLog(msg: String) {
        val ts = timeFmt.format(Date())
        tvLogs.append("\n[$ts] $msg")
    }

    private fun onConnectClicked() {
        val isSnapchat = spPreset.selectedItemPosition == 1
        val payload = if (isSnapchat) PAYLOAD_SNAPCHAT else PAYLOAD_YOUTUBE

        val config = TunnelConfig(
            sshHost = etHost.text.toString().trim(),
            sshPort = etPort.text.toString().trim().toIntOrNull() ?: 22,
            sshUser = etUser.text.toString().trim(),
            sshPass = etPass.text.toString(),
            payload = payload,
            proxyEnabled = true,
            proxyHost = PROXY_HOST,
            proxyPort = PROXY_PORT,
            localSocksPort = 1080,
            connectTimeoutMs = 25000,
            enableTcpNoDelay = true,
            mtu = 1500
        )

        if (config.sshHost.isBlank() || config.sshUser.isBlank()) {
            Toast.makeText(this, "Enter SSH Host and Username", Toast.LENGTH_SHORT).show()
            return
        }

        Prefs.save(this, config)
        appendLocalLog("Connecting with ${if (isSnapchat) "Snapchat" else "YouTube"} mode...")

        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnPrepareLauncher.launch(intent)
        } else {
            startVpnService()
        }
    }

    private fun startVpnService() {
        tvStatus.text = "Connecting..."
        tvStatus.setTextColor(0xFFFFC107.toInt())
        btnConnect.isEnabled = false
        val intent = Intent(this, MyVpnService::class.java).apply {
            action = MyVpnService.ACTION_CONNECT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    override fun onDestroy() {
        try { unregisterReceiver(logReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }
}
