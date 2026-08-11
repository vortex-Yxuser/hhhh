package com.vortex.hhhhvpn;

/**
 * JNI bridge for hev-socks5-tunnel.
 * PKGNAME/CLSNAME must match Application.mk: com/vortex/hhhhvpn / TProxyService
 */
public class TProxyService {
    static {
        try {
            System.loadLibrary("hev-socks5-tunnel");
        } catch (UnsatisfiedLinkError e) {
            android.util.Log.e("TProxyService", "Failed to load hev-socks5-tunnel", e);
        }
    }

    public static native boolean TProxyStartService(String configPath, int fd);
    public static native boolean TProxyStopService();
    public static native boolean TProxyIsRunning();
    public static native long[] TProxyGetStats();
}
