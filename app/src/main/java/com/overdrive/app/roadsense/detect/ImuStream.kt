package com.overdrive.app.roadsense.detect

/**
 * The primary process-boundary seam for RoadSense (D-023).
 *
 * RoadSense's detection pipeline (GravityFrame → EventDetector → …) consumes IMU
 * samples without caring where they came from. There are three implementations:
 *
 *   - **app side:** `RoadSenseImuSidecarService` reads the real `-iner` sensors
 *     (via [ImuSource]) and pushes batched samples to the daemon over IPC.
 *   - **daemon side:** an IPC receiver decodes those batches and replays them
 *     into the pipeline. (The daemon is where ALL logic runs, D-023.)
 *   - **test/replay:** a fake that feeds recorded CSV/synthetic samples, so the
 *     whole engine is unit/replay-testable with zero hardware.
 *
 * Keeping the pipeline behind this interface is what let us move IMU acquisition
 * to the app process (where 100 Hz is proven, F-005) while running detection in
 * the daemon (where GPS + vehicle singletons already live, D-020) — the two
 * sides only ever exchange [ImuAccelSample] / [ImuGyroSample] (the shared types).
 *
 * Callbacks are invoked on the producer's thread (sensor thread app-side, IPC
 * reader thread daemon-side). Implementations document their threading; the
 * consumer (the pipeline) is single-threaded and must not block these callbacks.
 */
interface ImuStream {

    /** Sink the stream pushes samples into. One sink, set before [start]. */
    interface Listener {
        /** A real accelerometer sample (device frame, m/s²). Hot path — no blocking. */
        fun onAccel(sample: ImuAccelSample)
        /** A real gyroscope sample (device frame, rad/s). Hot path — no blocking. */
        fun onGyro(sample: ImuGyroSample)
        /**
         * Stream health changed (e.g. sensor unavailable, IPC dropped, resumed).
         * Lets the controller surface R-EXT-6 graceful fallback / calibration
         * level without the pipeline guessing from sample starvation.
         */
        fun onStreamState(state: State) {}
    }

    enum class State { ACTIVE, UNAVAILABLE }

    /** Register the sink. Must be called before [start]. */
    fun setListener(listener: Listener?)

    /**
     * Begin producing samples at the given rate hint. RoadSense uses [Rate.FULL]
     * while DRIVING; the sidecar maps it to a SensorManager delay (FASTEST).
     */
    fun start(rate: Rate = Rate.FULL)

    /** Stop producing and release the underlying source (ACC OFF → silent, D-021). */
    fun stop()

    enum class Rate {
        /** ~100 Hz (SENSOR_DELAY_FASTEST) — driving, full detection. */
        FULL,
    }
}
