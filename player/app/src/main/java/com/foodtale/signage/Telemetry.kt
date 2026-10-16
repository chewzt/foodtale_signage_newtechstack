package com.foodtale.signage

import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class Telemetry {
    private val lock = Any()
    private val events = ArrayDeque<JSONObject>()
    private val pendingIds = HashSet<String>()
    private var playedMs = 0L
    private var lastPlayAt = 0L
    private var sentPlayed = 0L
    private var playToken: String? = null
    private var driftAbsSum = 0L
    private var driftSamples = 0
    private var sentDriftAbsSum = 0L
    private var sentDriftSamples = 0
    private var speedNudges = 0
    private var sentNudges = 0
    private var lastSpeedNudging = false
    private var clockBad = false
    private var lastDrop = ""

    @Volatile var speed = 1f
    @Volatile var driftMs = 0L
    @Volatile var leadMs = 0L
    @Volatile var phase = "IDLE"
    @Volatile var version = ""

    fun played(onScreen: Boolean, now: Long = SystemClock.elapsedRealtime()) {
        synchronized(lock) {
            if (onScreen) {
                if (lastPlayAt > 0L) {
                    playedMs += (now - lastPlayAt).coerceIn(0L, 10_000L)
                }
                lastPlayAt = now
            } else {
                lastPlayAt = 0L
            }
        }
    }

    fun sampleDrift(drift: Long) {
        synchronized(lock) {
            driftAbsSum += kotlin.math.abs(drift)
            driftSamples += 1
        }
    }

    fun noteSpeed(speed: Float) {
        val nudging = kotlin.math.abs(speed - 1f) > 0.001f
        if (nudging && !lastSpeedNudging) {
            synchronized(lock) { speedNudges += 1 }
        }
        lastSpeedNudging = nudging
    }

    fun event(type: String, severity: String, message: String, meta: JSONObject? = null) {
        synchronized(lock) {
            while (events.size >= 40) {
                events.removeFirst()
            }
            val row = JSONObject()
                .put("id", UUID.randomUUID().toString())
                .put("at", System.currentTimeMillis())
                .put("type", type)
                .put("severity", severity)
                .put("message", message.take(240))
            if (meta != null) row.put("meta", meta)
            events.addLast(row)
        }
    }

    fun clock(unsynced: Boolean, offsetMs: Long) {
        if (unsynced == clockBad) return
        clockBad = unsynced
        if (unsynced) {
            event("clock_unsynced", "error", "clock offset ${offsetMs}ms")
        } else {
            event("clock_ok", "info", "clock recovered offset ${offsetMs}ms")
        }
    }

    fun drop(value: String) {
        if (value == lastDrop) return
        lastDrop = value
        when {
            value.startsWith("seek") -> event("seek", "warn", value)
            value == "wait clock" -> event("clock_wait", "warn", value)
            value.startsWith("START late") -> {
                val lateMs = Regex("""-?\d+""").find(value)?.value?.toLongOrNull() ?: 0L
                if (lateMs >= 800L) event("start_late", "warn", value)
            }
            value == "tail" -> event("tail", "info", value)
        }
    }

    fun snapshot(): JSONObject {
        synchronized(lock) {
            val batch = JSONArray()
            pendingIds.clear()
            for (row in events) {
                batch.put(row)
                pendingIds.add(row.optString("id"))
            }
            sentPlayed = playedMs
            sentDriftAbsSum = driftAbsSum
            sentDriftSamples = driftSamples
            sentNudges = speedNudges
            if (playToken == null) playToken = UUID.randomUUID().toString()
            return JSONObject()
                .put("played_ms", playedMs)
                .put("played_token", playToken)
                .put("drift_abs_sum", driftAbsSum)
                .put("drift_samples", driftSamples)
                .put("speed_nudges", speedNudges)
                .put("events", batch)
        }
    }

    fun ack() {
        synchronized(lock) {
            playedMs = (playedMs - sentPlayed).coerceAtLeast(0L)
            sentPlayed = 0L
            driftAbsSum = (driftAbsSum - sentDriftAbsSum).coerceAtLeast(0L)
            driftSamples = (driftSamples - sentDriftSamples).coerceAtLeast(0)
            speedNudges = (speedNudges - sentNudges).coerceAtLeast(0)
            sentDriftAbsSum = 0L
            sentDriftSamples = 0
            sentNudges = 0
            playToken = null
            events.removeAll { pendingIds.contains(it.optString("id")) }
            pendingIds.clear()
        }
    }
}
