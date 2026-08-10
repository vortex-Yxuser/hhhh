package com.vortex.hhhhvpn

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    private const val NAME = "hhhh_vpn_prefs_v2"

    fun load(ctx: Context): TunnelConfig {
        val p = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        return TunnelConfig(
            sshHost = p.getString("sshHost", "") ?: "",
            sshPort = p.getInt("sshPort", 22),
            sshUser = p.getString("sshUser", "") ?: "",
            sshPass = p.getString("sshPass", "") ?: "",
            payload = p.getString("payload", defaultPayload()) ?: defaultPayload(),
            proxyEnabled = p.getBoolean("proxyEnabled", false),
            proxyHost = p.getString("proxyHost", "") ?: "",
            proxyPort = p.getInt("proxyPort", 8080),
            localSocksPort = p.getInt("localSocksPort", 1080),
            connectTimeoutMs = p.getInt("connectTimeoutMs", 25000),
            enableTcpNoDelay = p.getBoolean("enableTcpNoDelay", true),
            mtu = p.getInt("mtu", 1500)
        )
    }

    fun save(ctx: Context, cfg: TunnelConfig) {
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().apply {
            putString("sshHost", cfg.sshHost)
            putInt("sshPort", cfg.sshPort)
            putString("sshUser", cfg.sshUser)
            putString("sshPass", cfg.sshPass)
            putString("payload", cfg.payload)
            putBoolean("proxyEnabled", cfg.proxyEnabled)
            putString("proxyHost", cfg.proxyHost)
            putInt("proxyPort", cfg.proxyPort)
            putInt("localSocksPort", cfg.localSocksPort)
            putInt("connectTimeoutMs", cfg.connectTimeoutMs)
            putBoolean("enableTcpNoDelay", cfg.enableTcpNoDelay)
            putInt("mtu", cfg.mtu)
            apply()
        }
    }

    fun defaultPayload(): String =
        "GET / HTTP/1.1[crlf]Host: [host][crlf]Connection: Upgrade[crlf]User-Agent: Mozilla/5.0[crlf][crlf]"
}
