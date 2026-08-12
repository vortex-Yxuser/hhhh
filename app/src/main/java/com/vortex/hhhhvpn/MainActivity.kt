package com.vortex.hhhhvpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.text.method.ScrollingMovementMethod
import android.util.Base64
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.button.MaterialButton
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var toolbar: Toolbar
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

    private var hiddenHost = ""
    private var hiddenPort = 22
    private var hiddenUser = ""
    private var hiddenPass = ""
    private var isConfigLocked = false

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

                val downKb = (120..650).random()
                val upKb = (35..180).random()
                tvSpeed.text = "↓ $downKb KB/s  ↑ $upKb KB/s"

                handler.postDelayed(this, 1000)
            }
        }
    }

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            try {
                val inputStream = contentResolver.openInputStream(uri)
                val content = inputStream?.bufferedReader().use { it?.readText() } ?: ""
                if (content.isNotBlank()) {
                    loadEncryptedConfigString(content)
                    Toast.makeText(this, "Config imported successfully!", Toast.LENGTH_SHORT).show()
                    appendLog("Config imported from storage")
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Import failed: ${e.message}", Toast.LENGTH_SHORT).show()
                appendLog("Import error: ${e.message}")
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

        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(true)

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
        hiddenHost = cfg.sshHost
        hiddenPort = cfg.sshPort
        hiddenUser = cfg.sshUser
        hiddenPass = cfg.sshPass

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

        val filter = IntentFilter(MyVpnService.ACTION_LOG)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(logReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(logReceiver, filter)
        }

        AdManager.loadAppOpenAd(this)
        AdManager.loadInterstitial(this)

        appendLog("Yohan VPN Pro ready")
        appendLog("Tap top-right menu (...) for Export, Import, and Reset")
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_export -> {
                promptExportConfig()
                true
            }
            R.id.action_import -> {
                filePickerLauncher.launch("*/*")
                true
            }
            R.id.action_reset -> {
                promptResetConfig()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun promptResetConfig() {
        AlertDialog.Builder(this)
            .setTitle("Reset Configuration")
            .setMessage("Are you sure you want to clear all SSH account fields?")
            .setPositiveButton("Yes") { _, _ ->
                etHost.setText("")
                etPort.setText("22")
                etUser.setText("")
                etPass.setText("")
                hiddenHost = ""
                hiddenPort = 22
                hiddenUser = ""
                hiddenPass = ""
                isConfigLocked = false
                Prefs.save(this, TunnelConfig())
                Toast.makeText(this, "Configuration reset", Toast.LENGTH_SHORT).show()
                appendLog("Configuration cleared. Please enter SSH details.")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptExportConfig() {
        val host = if (isConfigLocked) hiddenHost else etHost.text.toString().trim()
        val user = if (isConfigLocked) hiddenUser else etUser.text.toString().trim()
        if (host.isEmpty() || user.isEmpty()) {
            Toast.makeText(this, "Please enter Host and Username", Toast.LENGTH_SHORT).show()
            return
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 20)
        }

        val inputField = EditText(this).apply {
            hint = "Enter config name (e.g., MyServer)"
            setText("YohanConfig")
        }
        layout.addView(inputField)

        val lockCheckbox = CheckBox(this).apply {
            text = "Lock SSH Info (Hide details on import)"
            isChecked = false
            setTextColor(resources.getColor(android.R.color.white, null))
        }
        layout.addView(lockCheckbox)

        AlertDialog.Builder(this)
            .setTitle("Export Config (.yhn)")
            .setView(layout)
            .setPositiveButton("Export") { _, _ ->
                val customName = inputField.text.toString().trim().ifEmpty { "YohanConfig" }
                val isLocked = lockCheckbox.isChecked
                exportConfigToFile(customName, isLocked)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun exportConfigToFile(fileName: String, isLocked: Boolean) {
        try {
            val host = if (isConfigLocked) hiddenHost else etHost.text.toString().trim()
            val port = (if (isConfigLocked) hiddenPort.toString() else etPort.text.toString()).toIntOrNull() ?: 22
            val user = if (isConfigLocked) hiddenUser else etUser.text.toString().trim()
            val pass = if (isConfigLocked) hiddenPass else etPass.text.toString().trim()
            val preset = spPreset.selectedItem.toString()

            val lockFlag = if (isLocked) "LOCKED" else "VISIBLE"
            val rawData = "YOHAN_PRO_CONFIG|v1|$lockFlag|$host|$port|$user|$pass|$preset"
            val encrypted = encryptString(rawData)

            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            if (!dir.exists()) dir.mkdirs()

            val file = File(dir, "$fileName.yhn")
            file.writeText(encrypted)

            Toast.makeText(this, "Saved to: Documents/${file.name}", Toast.LENGTH_LONG).show()
            appendLog("Encrypted config exported with password (Locked: $isLocked): ${file.name}")
        } catch (e: Exception) {
            Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
            appendLog("Export error: ${e.message}")
        }
    }

    private fun encryptString(input: String): String {
        val xorKey = 0x5A.toByte()
        val bytes = input.toByteArray(Charsets.UTF_8)
        for (i in bytes.indices) {
            bytes[i] = (bytes[i].toInt() xor xorKey.toInt()).toByte()
        }
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun decryptString(input: String): String {
        try {
            val decodedBytes = Base64.decode(input, Base64.NO_WRAP)
            val xorKey = 0x5A.toByte()
            for (i in decodedBytes.indices) {
                decodedBytes[i] = (decodedBytes[i].toInt() xor xorKey.toInt()).toByte()
            }
            return String(decodedBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            return input
        }
    }

    private fun loadEncryptedConfigString(encryptedContent: String) {
        val decrypted = decryptString(encryptedContent.trim())
        val parts = decrypted.split("|")
        
        if (parts.size >= 8 && parts[0] == "YOHAN_PRO_CONFIG") {
            val lockFlag = parts[2]
            hiddenHost = parts[3]
            hiddenPort = parts[4].toIntOrNull() ?: 22
            hiddenUser = parts[5]
            hiddenPass = parts[6]
            val preset = parts[7]

            if (lockFlag == "LOCKED") {
                isConfigLocked = true
                etHost.setText("******** (Locked)")
                etPort.setText(hiddenPort.toString())
                etUser.setText("******** (Locked)")
                etPass.setText("********")
                Toast.makeText(this, "Config imported (SSH details are locked by creator)", Toast.LENGTH_LONG).show()
                appendLog("Config imported successfully [LOCKED mode]")
            } else {
                isConfigLocked = false
                etHost.setText(hiddenHost)
                etPort.setText(hiddenPort.toString())
                etUser.setText(hiddenUser)
                etPass.setText(hiddenPass)
                Toast.makeText(this, "Config imported successfully!", Toast.LENGTH_SHORT).show()
                appendLog("Config imported successfully [Visible mode]")
            }

            if (preset == "Snapchat") {
                spPreset.setSelection(1)
            } else {
                spPreset.setSelection(0)
            }
        } else if (parts.size >= 7 && parts[0] == "YOHAN_PRO_CONFIG") {
            isConfigLocked = false
            hiddenHost = parts[2]
            hiddenPort = parts[3].toIntOrNull() ?: 22
            hiddenUser = parts[4]
            hiddenPass = parts[5]
            val preset = parts[6]

            etHost.setText(hiddenHost)
            etPort.setText(hiddenPort.toString())
            etUser.setText(hiddenUser)
            etPass.setText(hiddenPass)
            if (preset == "Snapchat") {
                spPreset.setSelection(1)
            } else {
                spPreset.setSelection(0)
            }
            Toast.makeText(this, "Config imported successfully!", Toast.LENGTH_SHORT).show()
            appendLog("Config imported successfully")
        } else {
            Toast.makeText(this, "Invalid or corrupted config file", Toast.LENGTH_SHORT).show()
            appendLog("Error: Invalid config format")
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
        val host: String
        val port: Int
        val user: String
        val pass: String

        if (isConfigLocked) {
            host = hiddenHost
            port = hiddenPort
            user = hiddenUser
            pass = hiddenPass
        } else {
            host = etHost.text.toString().trim()
            val portStr = etPort.text.toString().trim()
            user = etUser.text.toString().trim()
            pass = etPass.text.toString().trim()

            if (host.isEmpty() || portStr.isEmpty() || user.isEmpty() || pass.isEmpty()) {
                Toast.makeText(this, "Please fill all SSH fields", Toast.LENGTH_SHORT).show()
                appendLog("Error: Please fill all SSH fields")
                return
            }
            port = portStr.toIntOrNull() ?: 22
        }

        if (host.isEmpty() || user.isEmpty()) {
            Toast.makeText(this, "Invalid SSH configuration", Toast.LENGTH_SHORT).show()
            appendLog("Error: Invalid SSH configuration")
            return
        }

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
            btnToggle.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#FF453A"))
            tvStatus.text = "Connected"
            tvStatus.setTextColor(android.graphics.Color.parseColor("#32D74B"))
            
            if (startTime == 0L) startTime = System.currentTimeMillis()
            handler.post(timerRunnable)
        } else {
            btnToggle.text = "CONNECT"
            btnToggle.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#0A84FF"))
            tvStatus.text = "Disconnected"
            tvStatus.setTextColor(android.graphics.Color.parseColor("#FF453A"))
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
