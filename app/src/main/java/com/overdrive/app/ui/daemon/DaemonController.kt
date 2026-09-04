package com.overdrive.app.ui.daemon

import com.overdrive.app.ui.model.DaemonType

/**
 * Interface for controlling daemon processes.
 * Each daemon type has its own implementation.
 */
interface DaemonController {
    /**
     * The type of daemon this controller manages.
     */
    val type: DaemonType
    
    /**
     * Start the daemon.
     */
    fun start(callback: DaemonCallback)
    
    /**
     * Stop the daemon and clean up all resources.
     * This should:
     * - Kill the main daemon process
     * - Kill all child processes
     * - Restore any modified system settings
     * - Clear temporary files
     */
    fun stop(callback: DaemonCallback)
    
    /**
     * Check if the daemon is currently running.
     */
    fun isRunning(callback: (Boolean) -> Unit)
    
    /**
     * Perform full cleanup without callbacks.
     * Called during app shutdown or emergency cleanup.
     */
    fun cleanup()

    /**
     * Release controller-side resources (executor threads, schedulers,
     * cached buffers) WITHOUT killing the daemon process. Called from
     * `DaemonsViewModel.onCleared` so the ViewModel can be torn down on
     * Activity recreate / process exit without (a) leaking the
     * controller's executor threads or (b) killing the daemons that are
     * deliberately persistent (run as UID 2000 in their own processes).
     *
     * Default impl is a no-op for controllers that own no extra threads.
     * ZrokController overrides to shut down its dedicated reconcile
     * scheduler + AdbShellExecutor.
     */
    fun releaseResources() {}
}
