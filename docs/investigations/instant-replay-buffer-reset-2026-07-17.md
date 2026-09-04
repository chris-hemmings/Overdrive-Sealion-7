# Instant replay 30+30 fails after OEM dashcam initialization

Investigation date: 2026-07-17 (America/Sao_Paulo)

Status: root cause confirmed; minimal code fix and regression tests implemented on `fix/replay-ring-ownership`. The change has not been deployed to the car.

## Executive summary

Instant replay requests configured as 30 seconds before + 30 seconds after are rejected with `RESTART_REQUIRED`, even though the saved key-mapping configuration has always been 30+30 and continuous camera recording remains healthy.

The primary panoramic encoder correctly initializes the shared pre-record ring for 62 seconds (60 seconds requested plus 2 seconds of GOP/keyframe headroom). A few seconds later, `OemDashcamPipeline` creates its own encoder, marks it as using a per-instance ring, and calls `setPreRecordDuration(5)` before that private ring has been allocated.

`HardwareEventRecorderGpu.applyPreRecordRetentionWindow()` handles an already-allocated private ring correctly, but it does not handle the pre-init `useInstancePreRecordBuffer == true && preRecordBuffer == null` state. It falls through to the static shared-ring branch and changes the panoramic ring's `maxDurationUs` from 62 seconds to 5 seconds. The OEM encoder then allocates its own private 5-second ring, but the damage to the panoramic ring has already occurred.

This is an ownership bug, not a user configuration change, SD-card problem, encoder failure, or lack of recording.

## Environment and observed state

- Installed app: `braveheart-v33.1`.
- Fix branch: `fix/replay-ring-ownership`, based on upstream `main` at `85b9632d3917fbd55bf8e7e1cb9e42e8e85334a4`.
- Earlier branches inspected during diagnosis: `feat/telegram-pt-br` at `a594f1b472ac4d845c276386a420fe312557761e` and `feat/replay-improvements` at `54ac148057cdfdaab9b5371099f81d059493973b`.
- The ownership bug was present in both inspected branches and in the upstream base used for this fix.
- Saved replay binding: 30 seconds before + 30 seconds after.
- Primary encoder observed as H.264, 15 fps, 3 Mbps.
- Continuous DVR and sentry recordings continued to be created normally.

Live read-only API observations:

```text
GET /api/keymap/config
enabled: true
restartRequired: true
manual clip: beforeSeconds=30, afterSeconds=30

GET /status
replay.configured: true
replay.state: failed
preRecord.preRecordEnabled: true
preRecord.maxSeconds: 5
preRecord.currentSeconds: approximately 6.2
preRecord.totalKeyDrops: 0
preRecord.totalPDrops: 0
```

The decisive value is `preRecord.maxSeconds: 5`. The replay validator needs 62 seconds for a 30+30 request, so it rejects the request before export.

## Runtime evidence

The camera daemon log showed the same sequence after multiple pipeline/encoder restarts.

Primary panoramic encoder:

```text
2026-07-17 19:14:23.845  Pre-record retention window updated to 62s
2026-07-17 19:14:24.032  Reusing pre-record byte ring (24MB): 62s
2026-07-17 19:14:24.038  Encoder initialized successfully (... retained=62s)
```

OEM encoder a few seconds later:

```text
2026-07-17 19:14:26.888  Pre-record buffer marked per-instance (no static-shared sharing)
2026-07-17 19:14:26.890  Pre-record retention window updated to 5s
2026-07-17 19:14:26.999  Allocated per-instance pre-record byte ring: budget=8MB, duration=5s
2026-07-17 19:14:27.001  Encoder initialized successfully (... retained=5s)
```

Equivalent 62s -> 5s sequences were observed at approximately 15:31 and 18:32.

Replay presses then produced:

```text
Keymap manualClip before=30s after=30s -> RESTART_REQUIRED
```

No `replay_*.mp4` file was created. Normal `dvr_*.mp4` and sentry files continued to appear.

The main `cam_daemon.log` had been truncated in place at 15:08 after reaching about 6.4 MB, so the very first allocation of the shared ring is not present. That missing history does not affect the root-cause conclusion because the live 62s -> 5s overwrite sequence is present and repeats.

## Code path and root cause

### 1. Primary encoder correctly requests replay retention

`GpuSurveillancePipeline` creates the panoramic encoder and applies the maximum configured manual-clip window before encoder initialization:

- `app/src/main/java/com/overdrive/app/surveillance/GpuSurveillancePipeline.java:1730`
- `app/src/main/java/com/overdrive/app/surveillance/GpuSurveillancePipeline.java:1731`

For 30+30, `ManualClipService.getConfiguredRetentionSeconds()` returns 60. `HardwareEventRecorderGpu.effectivePreRecordRetentionSeconds()` adds 2 seconds of GOP headroom and returns 62:

- `app/src/main/java/com/overdrive/app/surveillance/HardwareEventRecorderGpu.java:1716`

The shared ring is static by design so it survives primary encoder reinitializations:

- `app/src/main/java/com/overdrive/app/surveillance/HardwareEventRecorderGpu.java:261`
- `app/src/main/java/com/overdrive/app/surveillance/HardwareEventRecorderGpu.java:263`

### 2. OEM encoder is explicitly configured to own a private ring

`OemDashcamPipeline.initEglAndEncoder()` does this in order:

1. Creates a second `HardwareEventRecorderGpu`.
2. Calls `setUseInstancePreRecordBuffer(true)`.
3. Resolves the OEM event pre-roll, currently 5 seconds.
4. Calls `setPreRecordDuration(5)`.
5. Only later calls `encoder.init()`, where the private ring is allocated.

Relevant lines:

- `app/src/main/java/com/overdrive/app/camera/OemDashcamPipeline.java:1165`
- `app/src/main/java/com/overdrive/app/camera/OemDashcamPipeline.java:1178`
- `app/src/main/java/com/overdrive/app/camera/OemDashcamPipeline.java:1184`
- `app/src/main/java/com/overdrive/app/camera/OemDashcamPipeline.java:1185`

The ownership contract explicitly says the OEM encoder must not touch the shared ring:

- `app/src/main/java/com/overdrive/app/surveillance/HardwareEventRecorderGpu.java:1891`
- `app/src/main/java/com/overdrive/app/surveillance/HardwareEventRecorderGpu.java:1896`

### 3. Pre-init private-owner state falls through to the shared ring

`setPreRecordDuration()` always calls `applyPreRecordRetentionWindow()`:

- `app/src/main/java/com/overdrive/app/surveillance/HardwareEventRecorderGpu.java:1668`
- `app/src/main/java/com/overdrive/app/surveillance/HardwareEventRecorderGpu.java:1674`

`applyPreRecordRetentionWindow()` only takes the instance-owned path when both conditions are already true:

```java
if (preRecordBufferIsInstance && preRecordBuffer != null) {
    // update private ring
    return;
}
```

Relevant lines:

- `app/src/main/java/com/overdrive/app/surveillance/HardwareEventRecorderGpu.java:1733`
- `app/src/main/java/com/overdrive/app/surveillance/HardwareEventRecorderGpu.java:1742`

Before `encoder.init()`, however:

```text
useInstancePreRecordBuffer == true
preRecordBufferIsInstance == false
preRecordBuffer == null
```

There is no guard for that valid pre-init state. Execution therefore falls through to:

```java
synchronized (bufferLock) {
    if (sharedPreRecordBuffer != null) {
        sharedPreRecordBuffer.setMaxDurationUs(desiredUs);
    }
}
```

Relevant lines:

- `app/src/main/java/com/overdrive/app/surveillance/HardwareEventRecorderGpu.java:1745`
- `app/src/main/java/com/overdrive/app/surveillance/HardwareEventRecorderGpu.java:1747`

The OEM setter therefore changes the panoramic shared ring to 5 seconds before the OEM private ring exists.

### 4. Replay validation correctly rejects the now-short ring

`ManualClipService.requestClip()` asks the primary encoder whether it can retain the complete 60-second request:

- `app/src/main/java/com/overdrive/app/recording/ManualClipService.java:220`
- `app/src/main/java/com/overdrive/app/recording/ManualClipService.java:224`

`canRetainManualClip()` requires both enough byte capacity and a duration policy of at least 62 seconds:

- `app/src/main/java/com/overdrive/app/surveillance/HardwareEventRecorderGpu.java:1705`
- `app/src/main/java/com/overdrive/app/surveillance/HardwareEventRecorderGpu.java:1713`

After the OEM overwrite, `ring.getMaxDurationUs()` is 5 seconds, so the request returns `RESTART_REQUIRED` without attempting export.

## Why restarting alone is not a durable workaround

A cold camera-daemon restart can recreate the panoramic ring at 62 seconds, but normal startup also initializes the OEM dashcam encoder. The same pre-init setter then reduces the shared ring to 5 seconds again a few seconds later.

The repeated runtime sequence confirms this. A restart may appear to help only during the short interval before OEM initialization or when the OEM pipeline is disabled/not started.

Possible temporary workarounds, not yet applied:

- Re-save the unchanged Key Mapping configuration after OEM initialization. That calls `ManualClipService.onKeymapConfigChanged()`, which reapplies 62 seconds to the primary encoder's live ring. This should work only if the existing byte budget is already sufficient; the observed primary initialization suggests it is.
- Disable the OEM dashcam pipeline. This changes user-visible recording behavior and should not be done merely to work around replay.
- Use a total replay window of at most 3 seconds so 2 seconds of GOP headroom still fit inside the overwritten 5-second policy. The current UI presets make this impractical and it does not solve the bug.

The correct solution is a code fix.

## Recommended minimal fix

Make `applyPreRecordRetentionWindow()` stop before the shared-ring branch when a per-instance encoder is still waiting for `init()` to allocate its private ring.

Conceptual patch:

```java
private void applyPreRecordRetentionWindow(int retentionSeconds) {
    final int clamped = Math.max(1, Math.min(62, retentionSeconds));
    final long desiredUs = clamped * 1_000_000L;
    final int desiredBudget = computePreRecordBudgetBytes(clamped, bitrate);

    if (preRecordBufferIsInstance && preRecordBuffer != null) {
        // Existing private-ring update logic.
        return;
    }

    if (useInstancePreRecordBuffer && preRecordBuffer == null) {
        // Before init(), the requested durations are already stored in the
        // encoder fields. init() will allocate the private ring with them.
        return;
    }

    synchronized (bufferLock) {
        if (sharedPreRecordBuffer != null) {
            // Existing shared-ring update logic.
        }
    }
}
```

This narrow guard preserves the documented behavior when ownership is changed after `init()`: that change remains deferred until the next encoder initialization. The important invariant for the pre-init path is:

```text
useInstancePreRecordBuffer == true && preRecordBuffer == null
    => sharedPreRecordBuffer is not mutated
```

### Why this fix is preferable

- It enforces the ownership contract in the central buffer-management method.
- It protects every future per-instance encoder caller, not just `OemDashcamPipeline`.
- It preserves the intended pre-init behavior: setters update desired fields, and `init()` allocates the private ring using those fields.
- It avoids reordering OEM initialization or adding a replay-specific workaround elsewhere.
- It does not reallocate direct memory at runtime.

## Regression coverage

### Unit/regression test

The regression test creates a shared ring with a 62-second policy, then configures a second encoder as per-instance and calls `setPreRecordDuration(5)` before its `init()`.

Expected:

```text
shared ring maxDurationUs remains 62_000_000
private encoder remembers preRecordDurationSeconds == 5
```

The test uses test-only reflection to install, inspect, and reset the relevant ring state without adding a production test seam.

Also test a post-init instance-owned ring update to ensure it changes only the private ring.

### On-device acceptance test

1. Keep the saved Key Mapping at 30+30.
2. Cold-start the Camera daemon with OEM dashcam enabled.
3. Wait until both panoramic and OEM encoders report initialized.
4. Verify `GET /status` reports `preRecord.maxSeconds == 62`.
5. Verify `GET /api/keymap/config` reports `restartRequired == false`.
6. Wait through at least one OEM start/stop cycle and recheck both values.
7. Trigger the mapped replay key.
8. Expect `ACCEPTED`, followed by `Instant replay saved` and a new `replay_*.mp4`.
9. Confirm normal DVR/sentry recording was not interrupted.

### Additional observability worth adding

- Include the ring owner (`shared` or `instance`) and encoder role (`pano`, `OEM`, `stream`) in retention-update log lines.
- Include configured replay seconds, ring budget bytes, and `maxDurationUs` in `/status`.
- Log a warning if a per-instance encoder ever attempts to enter the shared-ring update branch.

## Rejected earlier hypotheses

- **User changed 30+30 after startup:** rejected. The configuration was unchanged, and the code path explains a repeatable overwrite independent of a config edit.
- **The approximately 24 MB shared arena was slightly too small:** not the decisive failure. Live status showed `maxSeconds == 5`, and `canRetainManualClip()` fails on duration even before byte capacity matters.
- **SD-card/storage failure:** rejected. Normal DVR and sentry files continued to be finalized.
- **Camera/encoder stopped:** rejected. The pipeline was running and continuously adding packets with zero key/P-frame drops.
- **A camera-daemon restart alone fixes it:** rejected as a durable solution because OEM initialization repeats the overwrite.

## Implementation handoff

1. Review the ownership guard in `HardwareEventRecorderGpu.applyPreRecordRetentionWindow()`.
2. Run the regression tests and Android build.
3. Review the diff for direct-memory lifecycle regressions.
4. Do not deploy to the car or restart production daemons without Thiago's explicit approval.
