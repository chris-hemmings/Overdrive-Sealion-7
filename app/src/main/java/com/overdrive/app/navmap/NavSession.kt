package com.overdrive.app.navmap

import com.overdrive.app.navmap.nav.NavRoute

/**
 * Process-wide active-navigation state, shared between the two map instances:
 *  - the INTERACTIVE map on the infotainment display (display 0) PUBLISHES here
 *    when the user selects/starts/stops a route (it's the control surface);
 *  - the VIEW-ONLY cluster mirror (the RoadSenseClusterMapActivity alias on the
 *    fission display) OBSERVES and re-renders the same route in real time.
 *
 * Both instances live in the SAME app process, so this is a plain in-memory
 * singleton + listener set — no IPC. The cluster is non-touch, so it never
 * writes here; it only listens. Publishing is idempotent and cheap.
 *
 * Threading: all publish() / addListener() / removeListener() calls are expected
 * on the main thread (both Activities drive their map there). The listener set is
 * a CopyOnWriteArrayList so a late add/remove during dispatch is safe.
 */
object NavSession {

    /** Immutable snapshot of the active trip. route == null ⇒ no active nav. */
    data class State(
        val route: NavRoute?,
        val destLabel: String,
        val navigating: Boolean
    )

    @Volatile
    var state: State = State(null, "", false)
        private set

    fun interface Listener {
        fun onNavStateChanged(state: State)
    }

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<Listener>()

    /**
     * Add a listener and immediately replay the current state to it (so a cluster
     * instance launched AFTER a route was set still picks it up — edge-only
     * dispatch would otherwise miss the existing trip; cf. the lock-gate replay
     * lesson). Returns the listener for symmetric removal.
     */
    fun addListener(l: Listener): Listener {
        listeners.add(l)
        try { l.onNavStateChanged(state) } catch (_: Throwable) {}
        return l
    }

    fun removeListener(l: Listener?) { if (l != null) listeners.remove(l) }

    /** Publish an active route (selected on the infotainment map). navigating=true. */
    fun publishRoute(route: NavRoute, destLabel: String) {
        update(State(route, destLabel, navigating = true))
    }

    /** Publish a route PREVIEW (chosen but guidance not started yet). navigating=false. */
    fun publishPreview(route: NavRoute, destLabel: String) {
        update(State(route, destLabel, navigating = false))
    }

    /** Clear the active trip (route ended / cancelled). */
    fun clear() {
        if (state.route == null && !state.navigating) return
        update(State(null, "", false))
    }

    private fun update(next: State) {
        state = next
        for (l in listeners) {
            try { l.onNavStateChanged(next) } catch (_: Throwable) {}
        }
    }
}
