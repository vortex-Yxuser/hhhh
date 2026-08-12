package com.vortex.hhhhvpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.button.MaterialButton
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var etHost: EditText
    private lateinit var etPort: EditText
    private lateinit var etUser: EditText
    private lateinit var etPass: EditText
    private lateinit var tvStatus: TextView
    private lateinit var tvLogs: TextView
    private lateinit var tvTimer: TextView
    private lateinit var tvSpeed: TextView
    private lateinit var btnToggle: MaterialButton
    private lateinit var btnClearLog: Button
    private lateinit var btnExport: Button
    private lateinit var btnImport: Button
    private lateinit var spPreset: Spinner
    private lateinit var logsPanel: View
    private lateinit var bottomSheetBehavior: BottomSheetBehavior<View>

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val PROXY_HOST = "34.43.46.91"
    private val PROXY_PORT = 443
    private val PAYLOAD_YOUTUBE =
        "CONNECT [host_port] HTTP/1.1[crlf]Host: youtube.com[crlf][crlf]"
    private val PAYLOAD_SNAPCHAT =
        "CONNECT [host_port] HTTP/1.1[crlf]Host: api.Snapchat.com[crlf][crlf]"

    private var isConnected = false
    private var hasShownConnectAd = false

    // Timer & Speed simulation handler
    private var startTime = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val timerRunnable = object : Runnable {
        override fun run() {
            if (isConnected && startTime > 0) {
                val millis = System.currentTimeMillis() - startTime
                val seconds = (millis / 1000) % 60
                val minutes = (millis / (1000 * 60)) % 60
                val hours = (millis / (1000 * 60 * 60))
                tvTimer.text = String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)

                // Simulated realistic active speed
                val downKb = (120..650).random()
                val upKb = (35..180).random()
                tvSpeed.text = "↓ $downKb KB/s  ↑ $upKb KB/s"

                handler.postDelayed(this, 1000)
            }
        }
    }

    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val message = intent?.getStringExtra("log_msg") ?: return
            runOnUiThread {
                val ts = timeFmt.format(Date())
                tvLogs.append("[$ts] $message\n")
                tvLogs.post {
                    val layout = tvLogs.layout ?: return@post
                    val scroll = layout.getLineTop(tvLogs.lineCount) - tvLogs.height
                    if (scroll > 0) tvLogs.scrollTo(0, scroll)
                }

                when {
                    message.contains("Fully connected") || message.contains("✅") ||
                    message.contains("Connected") && message.contains("Auth complete").not() -> {
                        setConnectedState(true)
                        if (!hasShownConnectAd) {
                            hasShownConnectAd = true
                            AdManager.showInterstitial(this@MainActivity) {}
                        }
                    }
                    message.contains("failed") || message.contains("✗") ||
                    message.contains("Stopped") || message.contains("disconnected") ||
                    message.contains("revoked") || message.contains("Fatal") ||
                    message.contains("Service destroyed") || message.contains("SSH session closed") -> {
                        setConnectedState(false)
                        hasShownConnectAd = false
                    }
                }
            }
        }
    }

    private val vpnPrepareLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            reallyStartVpn()
        } else {
            appendLog("User denied VPN permission")
            setConnectedState(false)
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
        tvTimer = findViewById(R.id.tvTimer)
        tvSpeed = findViewById(R.id.tvSpeed)
        btnToggle = findViewById(R.id.btnToggle)
        btnClearLog = findViewById(R.id.btnClearLog)
        btnExport = findViewById(R.id.btnExport)
        btnImport = findViewById(R.id.btnImport)
        spPreset = findViewById(R.id.spPreset)
        logsPanel = findViewById(R.id.logsPanel)

        tvLogs.movementMethod = ScrollingMovementMethod()

        bottomSheetBehavior = BottomSheetBehavior.from(logsPanel)
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
        bottomSheetBehavior.isDraggable = true

        val cfg = Prefs.load(this)
        etHost.setText(cfg.sshHost)
        etPort.setText(cfg.sshPort.toString())
        etUser.setText(cfg.sshUser)
        etPass.setText(cfg.sshPass)

        val modes = arrayOf("YouTube", "Snapchat")
        spPreset.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, modes)
        spPreset.setSelection(if (cfg.payload.contains("Snapchat", true)) 1 else 0)

        isConnected = MyVpnService.isRunning
        setConnectedState(isConnected)

        btnToggle.setOnClickListener {
            if (isConnected) {
                startService(Intent(this, MyVpnService::class.java).apply {
                    action = MyVpnService.ACTION_DISCONNECT
                })
                setConnectedState(false)
            } else {
                onConnectClicked()
            }
        }

        btnClearLog.setOnClickListener {
            tvLogs.text = ""
            appendLog("Logs cleared")
        }

        // Export Config (.yhn)
        btnExport.setOnClickListener {
            val host = etHost.text.toString().trim()
            val port = etPort.text.toString().toIntOrNull() ?: 22
            val user = etUser.text.toString().trim()
            val pass = etPass.text.toString().trim()

            if (host.isEmpty() || user.isEmpty()) {
                Toast.makeText(this, "Please enter Host and Username to export", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val preset = spPreset.selectedItem.toString()
            val configContent = "YOHAN_VPN_CONFIG|v1|$host|$port|$user|$pass|$preset"
            
            try {
                val file = File(getExternalFilesDir(null), "YohanConfig_${host}.yhn")
                file.writeText(configContent)
                Toast.makeText(this, "Exported to: ${file.absolutePath}", Toast.LENGTH_LONG).show()
                appendLog("Config exported successfully: ${file.name}")
            } catch (e: Exception) {
                Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        // Import Config (.yhn) - Demo sample or internal file load
        btnImport.setOnClickListener {
            try {
                val dir = getExternalFilesDir(null)
                val files = dir?.listFiles { _, name -> name.endsWith(".yhn") }
                if (files.isNullOrEmpty()) {
                    // Create sample demo config if none exists
                    val sample = File(dir, "DemoConfig.yhn")
                    sample.writeText("YOHAN_VPN_CONFIG|v1|57.131.32.191|22|u1850448015|mypassword|YouTube")
                    Toast.makeText(this, "Sample config imported: DemoConfig.yhn", Toast.LENGTH_LONG).show()
                    loadConfigString("YOHAN_VPN_CONFIG|v1|57.131.32.191|22|u1850448015|mypassword|YouTube")
                } else {
                    val latest = files[files.size - 1]
                    val content = latest.readText()
                    loadConfigString(content)
                    Toast.makeText(this, "Imported: ${latest.name}", Toast.LENGTH_SHORT).show()
                    appendLog("Config imported from ${latest.name}")
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Import failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        val filter = IntentFilter(MyVpnService.ACTION_LOG)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(logReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(logReceiver, filter)
        }

        AdManager.loadAppOpenAd(this)
        AdManager.loadInterstitial(this)

        appendLog("Yohan VPN Pro ready")
        appendLog("Enter SSH account or Import config then press CONNECT")
    }

    private fun loadConfigString(content: String) {
        val parts = content.split("|")
        if (parts.size >= 7 && parts[0] == "YOHAN_VPN_CONFIG") {
            etHost.setText(parts[2])
            etPort.setText(parts[3])
            etUser.setText(parts[4])
            etPass.setText(parts[5])
            if (parts[6] == "Snapchat") {
                spPreset.setSelection(1)
            } else {
                spPreset.setSelection(0)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!YohanApp.instance.isShowingAd()) {
            AdManager.showAppOpenAdIfAvailable(this)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(timerRunnable)
        try {
            unregisterReceiver(logReceiver)
        } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun onConnectClicked() {
        val host = etHost.text.toString().trim()
        val portStr = etPort.text.toString().trim()
        val user = etUser.text.toString().trim()
        val pass = etPass.text.toString().trim()

        if (host.isEmpty() || portStr.isEmpty() || user.isEmpty() || pass.isEmpty()) {
            Toast.makeText(this, "Please fill all SSH fields", Toast.LENGTH_SHORT).show()
            appendLog("Error: Please fill all SSH fields")
            return
        }

        val port = portStr.toIntOrNull() ?: 22
        val selectedPreset = spPreset.selectedItem.toString()
        val payload = if (selectedPreset == "Snapchat") PAYLOAD_SNAPCHAT else PAYLOAD_YOUTUBE

        Prefs.save(this, TunnelConfig(
            sshHost = host,
            sshPort = port,
            sshUser = user,
            sshPass = pass,
            proxyHost = PROXY_HOST,
            proxyPort = PROXY_PORT,
            proxyEnabled = true,
            payload = payload
        ))

        setConnectedState(true)
        appendLog("Preparing VPN connection...")

        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnPrepareLauncher.launch(intent)
        } else {
            reallyStartVpn()
        }
    }

    private fun reallyStartVpn() {
        val serviceIntent = Intent(this, MyVpnService::class.java).apply {
            action = MyVpnService.ACTION_CONNECT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun setConnectedState(connected: Boolean) {
        isConnected = connected
        if (connected) {
            btnToggle.text = "DISCONNECT"
            btnToggle.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#DC2626"))
            tvStatus.text = "Connected"
            tvStatus.setTextColor(android.graphics.Color.parseColor("#4ADE80"))
            
            if (startTime == 0L) startTime = System.currentTimeMillis()
            handler.post(timerRunnable)
        } else {
            btnToggle.text = "CONNECT"
            btnToggle.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#7C4DFF"))
            tvStatus.text = "Disconnected"
            tvStatus.setTextColor(android.graphics.Color.parseColor("#38BDF8"))
            tvTimer.text = "00:00:00"
            tvSpeed.text = "↓ 0 KB/s  ↑ 0 KB/s"
            startTime = 0L
            handler.removeCallbacks(timerRunnable)
        }
    }

    private fun appendLog(msg: String) {
        val ts = timeFmt.format(Date())
        tvLogs.append("[$ts] $msg\n")
    }
}
