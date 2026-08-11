package com.vortex.hhhhvpn

import android.net.VpnService
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import java.util.Properties

class SshTunnelManager(
    private val config: TunnelConfig,
    private val vpnService: VpnService? = null,
    private val onLog: (String) -> Unit = {}
) {
    private var session: Session? = null
    private var socksServer: Socks5Server? = null

    @Throws(Exception::class)
    fun connect(): Session {
        val jsch = JSch()
        val s = jsch.getSession(config.sshUser, config.sshHost, config.sshPort)
        s.setPassword(config.sshPass)
        s.setSocketFactory(PayloadSocketFactory(config, vpnService, onLog))

        val props = Properties().apply {
            put("StrictHostKeyChecking", "no")
            put("PreferredAuthentications", "password,publickey,keyboard-interactive")
            put("kex", "curve25519-sha256,curve25519-sha256@libssh.org,ecdh-sha2-nistp256,ecdh-sha2-nistp384,ecdh-sha2-nistp521,diffie-hellman-group-exchange-sha256,diffie-hellman-group14-sha256,diffie-hellman-group14-sha1,diffie-hellman-group1-sha1")
            put("server_host_key", "ssh-ed25519,ecdsa-sha2-nistp256,ecdsa-sha2-nistp384,ecdsa-sha2-nistp521,rsa-sha2-512,rsa-sha2-256,ssh-rsa,ssh-dss")
            put("cipher.c2s", "aes128-gcm@openssh.com,aes256-gcm@openssh.com,chacha20-poly1305@openssh.com,aes128-ctr,aes192-ctr,aes256-ctr")
            put("cipher.s2c", "aes128-gcm@openssh.com,aes256-gcm@openssh.com,chacha20-poly1305@openssh.com,aes128-ctr,aes192-ctr,aes256-ctr")
            put("mac.c2s", "hmac-sha2-256-etm@openssh.com,hmac-sha2-512-etm@openssh.com,hmac-sha2-256,hmac-sha2-512")
            put("mac.s2c", "hmac-sha2-256-etm@openssh.com,hmac-sha2-512-etm@openssh.com,hmac-sha2-256,hmac-sha2-512")
            put("compression.s2c", "none")
            put("compression.c2s", "none")
        }
        s.setConfig(props)
        s.timeout = config.connectTimeoutMs

        onLog("SSH handshake to ${config.sshHost}:${config.sshPort} ...")
        val start = System.currentTimeMillis()
        s.connect(config.connectTimeoutMs)
        val took = System.currentTimeMillis() - start

        // Try to log server version
        try {
            val ver = s.serverVersion
            if (!ver.isNullOrBlank()) {
                onLog("SSH Version: $ver")
            }
        } catch (_: Exception) {}

        onLog("Auth complete")
        onLog("SSH connected in ${took}ms")
        session = s
        return s
    }

    @Throws(Exception::class)
    fun startSocksProxy(): Socks5Server {
        val s = session ?: throw IllegalStateException("Call connect() first")
        val server = Socks5Server(s, config.localSocksPort, onLog)
        server.start()
        socksServer = server
        onLog("Local SOCKS5 ready on 127.0.0.1:${config.localSocksPort}")
        return server
    }

    fun disconnect() {
        try { socksServer?.stop() } catch (_: Exception) {}
        try { session?.disconnect() } catch (_: Exception) {}
        socksServer = null
        session = null
        onLog("SSH session closed")
    }

    fun isConnected(): Boolean = session?.isConnected == true
}
