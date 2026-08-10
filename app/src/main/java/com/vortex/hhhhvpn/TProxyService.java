package com.vortex.hhhhvpn;

/**
 * JNI bridge for hev-socks5-tunnel (tun2socks).
 * Signature matches the official library expectations.
 */
public class TProxyService {
    static {
        System.loadLibrary("hev-socks5-tunnel");
    }

    /**
     * Start the tun2socks engine.
     * @param configPath path to YAML config
     * @param tunFd file descriptor of the TUN interface
     * @return true if started successfully
     */
    public static native boolean TProxyStartService(String configPath, int tunFd);

    /**
     * Stop the engine cleanly.
     */
    public static native void TProxyStopService();
}
