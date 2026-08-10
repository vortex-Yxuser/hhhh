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
    private lateinit var etPayload: EditText
    private lateinit var etProxyHost: EditText
    private lateinit var etProxyPort: EditText
    private lateinit var cbProxy: CheckBox
    private lateinit var tvStatus: TextView
    private lateinit var tvLogs: TextView
    private lateinit var btnConnect: Button
    private lateinit var btnDisconnect: Button
    private lateinit var btnClearLog: Button
    private lateinit var spPreset: Spinner

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val message = intent?.getStringExtra("log_msg") ?: return
            runOnUiThread {
                val ts = timeFmt.format(Date())
                tvLogs.append("\n[$ts] $message")
                // Auto scroll to bottom - stable way
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
                    message.contains("failed") || message.contains("✗") || message.contains("Stopped") || message.contains("disconnected") -> {
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
        etPayload = findViewById(R.id.etPayload)
        etProxyHost = findViewById(R.id.etProxyHost)
        etProxyPort = findViewById(R.id.etProxyPort)
        cbProxy = findViewById(R.id.cbProxy)
        tvStatus = findViewById(R.id.tvStatus)
        tvLogs = findViewById(R.id.tvLogs)
        btnConnect = findViewById(R.id.btnConnect)
        btnDisconnect = findViewById(R.id.btnDisconnect)
        btnClearLog = findViewById(R.id.btnClearLog)
        spPreset = findViewById(R.id.spPreset)

        tvLogs.movementMethod = ScrollingMovementMethod()

        // Load saved
        val cfg = Prefs.load(this)
        etHost.setText(cfg.sshHost)
        etPort.setText(cfg.sshPort.toString())
        etUser.setText(cfg.sshUser)
        etPass.setText(cfg.sshPass)
        etPayload.setText(cfg.payload)
        etProxyHost.setText(cfg.proxyHost)
        etProxyPort.setText(cfg.proxyPort.toString())
        cbProxy.isChecked = cfg.proxyEnabled

        // Presets
        val presets = arrayOf(
            "Custom / Manual",
            "Snapchat style",
            "YouTube style",
            "Generic Upgrade",
            "Fast minimal"
        )
        spPreset.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, presets)
        spPreset.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, pos: Int, id: Long) {
                when (pos) {
                    1 -> etPayload.setText("CONNECT [host_port] HTTP/1.1[crlf]Host: api.snapchat.com[crlf]Connection: Keep-Alive[crlf][crlf]")
                    2 -> etPayload.setText("CONNECT [host_port] HTTP/1.1[crlf]Host: youtube.com[crlf]Connection: Keep-Alive[crlf][crlf]")
                    3 -> etPayload.setText("GET / HTTP/1.1[crlf]Host: [host][crlf]Connection: Upgrade[crlf]User-Agent: Mozilla/5.0[crlf][crlf]")
                    4 -> etPayload.setText("GET / HTTP/1.1[crlf]Host: [host][crlf][crlf]")
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        tvStatus.text = if (MyVpnService.isRunning) "● CONNECTED" else "○ Disconnected"
        btnConnect.isEnabled = !MyVpnService.isRunning
        btnDisconnect.isEnabled = MyVpnService.isRunning

        btnConnect.setOnClickListener { onConnectClicked() }
        btnDisconnect.setOnClickListener {
            startService(Intent(this, MyVpnService::class.java).apply {
                action = MyVpnService.ACTION_DISCONNECT
            })
        }
        btnClearLog.setOnClickListener {
            tvLogs.text = "Logs cleared"
        }

        val filter = IntentFilter(MyVpnService.ACTION_LOG)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(logReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(logReceiver, filter)
        }

        appendLocalLog("HHHH SSH VPN Pro v2.0 ready — optimized for speed & stable logs")
    }

    private fun appendLocalLog(msg: String) {
        val ts = timeFmt.format(Date())
        tvLogs.append("\n[$ts] $msg")
    }

    private fun onConnectClicked() {
        val payload = etPayload.text.toString().trim()
        val config = TunnelConfig(
            sshHost = etHost.text.toString().trim(),
            sshPort = etPort.text.toString().trim().toIntOrNull() ?: 22,
            sshUser = etUser.text.toString().trim(),
            sshPass = etPass.text.toString(),
            payload = payload.ifBlank { Prefs.defaultPayload() },
            proxyEnabled = cbProxy.isChecked,
            proxyHost = etProxyHost.text.toString().trim(),
            proxyPort = etProxyPort.text.toString().trim().toIntOrNull() ?: 8080,
            localSocksPort = 1080,
            connectTimeoutMs = 25000,
            enableTcpNoDelay = true,
            mtu = 1500
        )

        if (config.sshHost.isBlank() || config.sshUser.isBlank()) {
            Toast.makeText(this, "Please enter SSH Host and Username", Toast.LENGTH_SHORT).show()
            return
        }

        Prefs.save(this, config)
        appendLocalLog("Config saved. Preparing VPN...")

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
