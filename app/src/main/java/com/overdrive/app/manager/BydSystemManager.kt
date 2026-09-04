package com.overdrive.app.manager

import android.content.Context
import com.overdrive.app.byd.BydEventClient
import com.overdrive.app.byd.SentryEventHandler
import com.overdrive.app.client.CameraDaemonClient
import com.overdrive.app.daemon.management.DaemonManager
import com.overdrive.app.logging.LogManager
// import com.overdrive.app.shell.PrivilegedShellSetup

/**
 * Manages BYD event system initialization and lifecycle.
 */
class BydSystemManager(
    private val context: Context,
    private val daemonManager: DaemonManager,
    private val logManager: LogManager
) {
    
    companion object {
        private const val TAG = "BydSystemManager"
    }
    
    /**
     * Initialize the BYD system (privileged shell + permissions + event daemon).
     */
    fun initialize(callback: InitCallback) {
        logManager.info(TAG, "Initializing BYD system...")
        
        Thread {
            // PrivilegedShellSetup disabled — all daemons now run via ADB shell (UID 2000)
            // if (PrivilegedShellSetup.isShellAvailable()) {
            //     callback.onProgress("Privileged shell already available")
            //     grantBydPermissions(object : PermissionCallback {
            //         override fun onGranted(count: Int) {
            //             callback.onProgress("Granted $count permissions")
            //             startEventSystem(callback)
            //         }
            //         override fun onFailed(count: Int) {
            //             callback.onProgress("Failed to grant $count permissions")
            //             startEventSystem(callback)
            //         }
            //     })
            //     return@Thread
            // }
            // 
            // // Setup privileged shell
            // callback.onProgress("Setting up privileged shell...")
            // PrivilegedShellSetup.init(context)
            // PrivilegedShellSetup.setup(object : PrivilegedShellSetup.SetupCallback {
            //     override fun onSuccess() {
            //         callback.onProgress("Privileged shell ready")
            //         grantBydPermissions(object : PermissionCallback {
            //             override fun onGranted(count: Int) {
            //                 callback.onProgress("Granted $count permissions")
            //                 startEventSystem(callback)
            //             }
            //             override fun onFailed(count: Int) {
            //                 callback.onProgress("Failed to grant $count permissions")
            //                 startEventSystem(callback)
            //             }
            //         })
            //     }
            //     
            //     override fun onFailure(reason: String) {
            //         callback.onFailure("Shell setup failed: $reason")
            //     }
            //     
            //     override fun onProgress(message: String) {
            //         callback.onProgress(message)
            //     }
            // })
            
            // Skip straight to event system startup
            startEventSystem(callback)
        }.start()
    }
    
    /**
     * Grant BYD-specific permissions.
     */
    fun grantBydPermissions(callback: PermissionCallback) {
        Thread {
            val permissions = listOf(
                // BYD hardware permissions
                "android.permission.BYDAUTO_BODYWORK_COMMON",
                "android.permission.BYDAUTO_BODYWORK_GET",
                "android.permission.BYDAUTO_BODYWORK_SET",
                "android.permission.BYDAUTO_RADAR_GET",
                "android.permission.BYDAUTO_RADAR_COMMON",
                // Location permissions for GPS tracking
                "android.permission.ACCESS_FINE_LOCATION",
                "android.permission.ACCESS_COARSE_LOCATION",
                "android.permission.ACCESS_BACKGROUND_LOCATION"
            )
            
            var granted = 0
            var failed = 0
            
            for (perm in permissions) {
                // PrivilegedShellSetup disabled
                // if (PrivilegedShellSetup.grantPermission(perm)) {
                //     granted++
                // } else {
                //     failed++
                // }
                failed++
            }
            
            if (failed > 0) {
                callback.onFailed(failed)
            } else {
                callback.onGranted(granted)
            }
        }.start()
    }
    
    /**
     * Start the BYD event system.
     */
    private fun startEventSystem(callback: InitCallback) {
        // Start BydEventDaemon
        // PrivilegedShellSetup disabled
        // val daemonStarted = PrivilegedShellSetup.startBydEventDaemon()
        // if (daemonStarted) {
        //     logManager.info(TAG, "BydEventDaemon started")
        // }
        logManager.info(TAG, "BydEventDaemon start skipped (PrivilegedShellSetup disabled)")
        
        // Setup DaemonClient for camera control
        val camDaemonClient = CameraDaemonClient()
        if (camDaemonClient.connect()) {
            logManager.info(TAG, "Connected to CamDaemon")
            SentryEventHandler.daemonClient = camDaemonClient
        }
        
        callback.onSuccess()
    }
    
    /**
     * Connect event client.
     */
    fun connectEventClient() {
        BydEventClient.connect()
        logManager.info(TAG, "BydEventClient connecting...")
    }
    
    /**
     * Disconnect event client.
     */
    fun disconnectEventClient() {
        BydEventClient.disconnect()
        logManager.info(TAG, "BydEventClient disconnected")
    }
    
    /**
     * Check if event client is connected.
     */
    fun isEventClientConnected(): Boolean {
        return BydEventClient.isConnected()
    }
    
    /**
     * Enable sentry mode.
     */
    fun enableSentryMode() {
        SentryEventHandler.enableSentryMode()
        logManager.info(TAG, "Sentry mode enabled")
    }
    
    /**
     * Disable sentry mode.
     */
    fun disableSentryMode() {
        SentryEventHandler.disableSentryMode()
        logManager.info(TAG, "Sentry mode disabled")
    }
    
    /**
     * Check if sentry mode is enabled.
     */
    fun isSentryModeEnabled(): Boolean {
        return SentryEventHandler.isSentryModeEnabled()
    }
    
    /**
     * Set event callbacks.
     */
    fun setEventCallbacks(callbacks: BydEventCallbacks) {
        BydEventClient.addListener(object : BydEventClient.EventListener {
            override fun onConnected() = callbacks.onConnected()
            override fun onDisconnected() = callbacks.onDisconnected()
            override fun onPowerLevelChanged(level: Int, levelName: String) = 
                callbacks.onPowerLevelChanged(level, levelName)
            override fun onRadarEvent(area: Int, areaName: String, state: Int, stateName: String) = 
                callbacks.onRadarEvent(area, areaName, state, stateName)
            override fun onBatteryInfo(voltageLevel: Int, voltageLevelName: String, voltage: Double) = 
                callbacks.onBatteryInfo(voltageLevel, voltageLevelName, voltage)
            override fun onBatteryVoltageLevelChanged(level: Int, levelName: String) {}
        })
    }
    
    /**
     * Shutdown BYD system.
     */
    fun shutdown() {
        disconnectEventClient()
        logManager.info(TAG, "BYD system shutdown")
    }
}

/**
 * Initialization callback interface.
 */
interface InitCallback {
    fun onSuccess()
    fun onFailure(reason: String)
    fun onProgress(message: String)
}

/**
 * Permission callback interface.
 */
interface PermissionCallback {
    fun onGranted(count: Int)
    fun onFailed(count: Int)
}

/**
 * BYD event callbacks interface.
 */
interface BydEventCallbacks {
    fun onConnected()
    fun onDisconnected()
    fun onPowerLevelChanged(level: Int, levelName: String)
    fun onRadarEvent(area: Int, areaName: String, state: Int, stateName: String)
    fun onBatteryInfo(voltageLevel: Int, voltageLevelName: String, voltage: Double)
}
