package com.overdrive.app.ui.daemon

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.overdrive.app.launcher.AdbDaemonLauncher
import com.overdrive.app.ui.model.DaemonStatus
import com.overdrive.app.ui.model.DaemonType
import com.overdrive.app.ui.util.PreferencesManager

/**
 * Controller for the Cloudflared Tunnel.
 */
class CloudflaredController(
    private val context: android.content.Context,
    private val adbLauncher: AdbDaemonLauncher
) : DaemonController {
    
    override val type = DaemonType.CLOUDFLARED_TUNNEL
    
    private val _tunnelUrl = MutableLiveData<String?>()
    val tunnelUrl: LiveData<String?> = _tunnelUrl

    /**
     * Check if cloudflared is correctly configured (either free or paid with token).
     */
    fun isConfigured(): Boolean {
        return com.overdrive.app.config.CloudflaredPaidConfig.isConfigured()
    }
    
    override fun start(callback: DaemonCallback) {
        if (!isConfigured()) {
            // SOTA: Reset state if misconfigured to allow user to retry
            if (com.overdrive.app.config.CloudflaredPaidConfig.isPaidVersion() && 
                com.overdrive.app.config.CloudflaredPaidConfig.getToken().isEmpty()) {
                val resetConfig = org.json.JSONObject()
                resetConfig.put("isPaid", false)
                resetConfig.put("token", "")
                Thread {
                    com.overdrive.app.config.UnifiedConfigManager.setCloudflared(resetConfig)
                }.start()
            }
            callback.onError("Paid version requires a token")
            return
        }
        callback.onStatusChanged(DaemonStatus.STARTING, "Starting cloudflared tunnel...")
        
        adbLauncher.launchTunnel(object : AdbDaemonLauncher.TunnelCallback {
            override fun onLog(message: String) {
                callback.onStatusChanged(DaemonStatus.STARTING, message)
            }
            
            override fun onTunnelUrl(url: String) {
                _tunnelUrl.postValue(url)
                PreferencesManager.setLastTunnelUrl(url)
                callback.onStatusChanged(DaemonStatus.RUNNING, url)
            }
            
            override fun onError(error: String) {
                callback.onError(error)
            }
        })
    }
    
    override fun stop(callback: DaemonCallback) {
        callback.onStatusChanged(DaemonStatus.STOPPING, "Stopping cloudflared tunnel...")
        
        adbLauncher.stopTunnel(object : AdbDaemonLauncher.LaunchCallback {
            override fun onLog(message: String) {
                callback.onStatusChanged(DaemonStatus.STOPPING, message)
            }
            
            override fun onLaunched() {
                _tunnelUrl.postValue(null)
                callback.onStatusChanged(DaemonStatus.STOPPED, "Tunnel stopped")
            }
            
            override fun onError(error: String) {
                _tunnelUrl.postValue(null)
                callback.onError(error)
            }
        })
    }
    
    override fun isRunning(callback: (Boolean) -> Unit) {
        adbLauncher.isTunnelRunning(callback)
    }
    
    /**
     * Refresh the tunnel URL from log file (useful when daemon is already running).
     * Also tries to get the last saved URL from preferences if log doesn't have it.
     */
    fun refreshTunnelUrl(callback: ((String?) -> Unit)? = null) {
        adbLauncher.getTunnelUrl { url ->
            if (url != null) {
                _tunnelUrl.postValue(url)
                PreferencesManager.setLastTunnelUrl(url)
                callback?.invoke(url)
            } else {
                // Try to get last saved URL from preferences
                val lastUrl = PreferencesManager.getLastTunnelUrl()
                if (!lastUrl.isNullOrEmpty()) {
                    _tunnelUrl.postValue(lastUrl)
                    callback?.invoke(lastUrl)
                } else {
                    callback?.invoke(null)
                }
            }
        }
    }
    
    override fun cleanup() {
        // ps+awk+kill instead of pkill -f. The latter would self-match
        // because executeShellCommand wraps the body in `sh -c "<cmd>"`
        // and the wrapper's argv contains the literal "cloudflared" —
        // pkill would SIGKILL the calling shell before `echo done` runs,
        // so the callback never fires `onLaunched`. ps+awk+kill filters
        // by PID list and excludes the calling shell's PID via $$.
        adbLauncher.executeShellCommand(
            "MY_PID=\$\$; ps -A -o PID,ARGS | grep -F cloudflared | grep -v grep " +
                "| awk '{print \$1}' | while read pid; do " +
                "if [ \"\$pid\" != \"\$MY_PID\" ]; then kill -9 \$pid 2>/dev/null; fi; done; " +
                "echo done",
            object : AdbDaemonLauncher.LaunchCallback {
                override fun onLog(message: String) {}
                override fun onLaunched() {}
                override fun onError(error: String) {}
            }
        )
        _tunnelUrl.postValue(null)
    }
    
    /**
     * Get the current tunnel URL if available.
     */
    fun getTunnelUrl(): String? = _tunnelUrl.value
}
