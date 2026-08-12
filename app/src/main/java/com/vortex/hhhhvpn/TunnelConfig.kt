package com.vortex.hhhhvpn

data class TunnelConfig(
    val sshHost: String = "",
    val sshPort: Int = 22,
    val sshUser: String = "",
    val sshPass: String = "",
    val payload: String = "",
    val proxyEnabled: Boolean = false,
    val proxyHost: String = "",
    val proxyPort: Int = 8080,
    val localSocksPort: Int = 1080,
    val connectTimeoutMs: Int = 25000,
    val enableTcpNoDelay: Boolean = true,
    val mtu: Int = 1500,
    val splitTunneling: Boolean = false,
    val bypassApps: List<String> = emptyList()
)
