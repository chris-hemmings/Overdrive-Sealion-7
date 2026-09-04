package com.overdrive.app.navmap

/**
 * The view-only cluster mirror of the map. A REAL Activity subclass (NOT an
 * activity-alias) of [RoadSenseMapActivity], because `<activity-alias>` silently
 * IGNORES `launchMode` and `taskAffinity` — they're only honored on `<activity>`.
 * Using an alias meant the cluster instance inherited the target's `singleTop` +
 * default taskAffinity, so it shared a task with the interactive infotainment
 * instance and the two couldn't coexist (opening the infotainment map just
 * resurfaced the cluster one).
 *
 * As a distinct `<activity>` with its own `launchMode="singleInstance"` +
 * `taskAffinity="com.overdrive.app.cluster"`, this lives in a separate task on the
 * cluster display, fully independent of the infotainment [RoadSenseMapActivity].
 *
 * All behaviour is inherited; [RoadSenseMapActivity] detects cluster mode from its
 * launching component name (this class) — see its onCreate.
 */
class RoadSenseClusterMapActivity : RoadSenseMapActivity()
