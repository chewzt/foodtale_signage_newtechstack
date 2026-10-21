package com.foodtale.signage

import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.charset.StandardCharsets

data class HostTarget(val http: String, val clockPort: Int)

class TempHead {
    private val lock = Any()
    private val heard = HashMap<Long, Long>()
    private var lastOriginAt = System.currentTimeMillis()
    private var lastHttpOkAt = System.currentTimeMillis()
    private var lastActingAt = 0L
    private var lastAnnounceAt = 0L
    private var collectSince = 0L
    private var retryAt = 0L
    private var actingDeviceId = 0L
    private var startPending = false
    private var joinTarget = HostTarget("", 0)
    private var handbackTarget = HostTarget("", 0)

    fun noteHttp(ok: Boolean) {
        if (!ok) return
        synchronized(lock) { lastHttpOkAt = System.currentTimeMillis() }
    }

    fun noteBeacon(b: Beacon) {
        val now = System.currentTimeMillis()
        val origin = originId()
        synchronized(lock) {
            val fromActing = b.role == "acting" && origin.isNotBlank() && b.originCmsId == origin
            val fromOrigin = b.role != "acting" && (origin.isBlank() || b.cmsId == origin)
            if (fromOrigin) {
                lastOriginAt = now
                if (Prefs.deviceName() == "Head") return
                val drifted = Prefs.cmsId().isNotBlank() && Prefs.cmsId() != origin
                val beaconHttp = b.http.trimEnd('/')
                val http = Prefs.http().trimEnd('/')
                val wrongHost = beaconHttp.isNotBlank() && http.isNotBlank() && http != beaconHttp
                if (Prefs.acting() || Prefs.followingActing() || drifted || wrongHost) {
                    handbackTarget = HostTarget(b.http, b.clockPort)
                }
                return
            }
            if (!fromActing || b.http.isBlank()) return
            lastActingAt = now
            if (Prefs.acting()) return
            if (now - lastOriginAt < STALE_MS) return
            val myId = Prefs.dmsDeviceId()
            if (actingDeviceId > 0L && myId > 0L && myId < actingDeviceId) return
            if (!Prefs.followingActing() || Prefs.http() != b.http) {
                joinTarget = HostTarget(b.http, b.clockPort)
            }
        }
    }

    fun noteFailover(notice: FailoverNotice) {
        if (notice.dmsDeviceId <= 0 || notice.originCmsId != originId()) return
        synchronized(lock) { heard[notice.dmsDeviceId] = System.currentTimeMillis() }
    }

    fun noteRole(actingDeviceId: Long) {
        synchronized(lock) { this.actingDeviceId = actingDeviceId }
    }

    fun tick() {
        val now = System.currentTimeMillis()
        val myId = Prefs.dmsDeviceId()
        var announce = false
        synchronized(lock) {
            if (!Prefs.paired() || Prefs.deviceName() == "Head" || myId == 0L || Prefs.acting()) {
                collectSince = 0L
                startPending = false
                return@synchronized
            }
            val quiet = now - lastOriginAt >= STALE_MS && now - lastHttpOkAt >= STALE_MS
            if (!quiet) {
                collectSince = 0L
                startPending = false
                return@synchronized
            }
            val better = actingDeviceId > myId
            val busy = !better && (now - lastActingAt < ACTING_BEACON_MS || now < retryAt)
            if (busy) {
                collectSince = 0L
                startPending = false
                return@synchronized
            }
            if (better && now >= retryAt) {
                startPending = true
                if (now - lastAnnounceAt >= ANNOUNCE_MS) {
                    lastAnnounceAt = now
                    announce = true
                }
                return@synchronized
            }
            if (collectSince == 0L) collectSince = now
            if (now - lastAnnounceAt >= ANNOUNCE_MS) {
                lastAnnounceAt = now
                announce = true
            }
            if (now - collectSince >= COLLECT_MS) {
                val alive = heard.filter { now - it.value < HEARD_MS }.keys + myId
                startPending = myId == alive.minOrNull()
            }
        }
        if (announce) announce()
    }

    fun takeHandback(): HostTarget {
        synchronized(lock) {
            val target = handbackTarget
            handbackTarget = HostTarget("", 0)
            return target
        }
    }

    fun takeJoin(): HostTarget {
        synchronized(lock) {
            val target = joinTarget
            joinTarget = HostTarget("", 0)
            return target
        }
    }

    fun consumeStart(): Boolean {
        synchronized(lock) {
            if (!startPending || Prefs.acting()) return false
            startPending = false
            return true
        }
    }

    fun retryLater() {
        synchronized(lock) {
            retryAt = System.currentTimeMillis() + STALE_MS
            startPending = false
            collectSince = 0L
        }
    }

    fun retryDue(): Boolean {
        synchronized(lock) {
            return retryAt != 0L && System.currentTimeMillis() >= retryAt
        }
    }

    fun claimAccepted() {
        synchronized(lock) {
            retryAt = 0L
            startPending = false
            collectSince = 0L
        }
    }

    private fun originId(): String = Prefs.originCmsId().ifBlank { Prefs.cmsId() }

    private fun announce() {
        val origin = originId()
        val id = Prefs.dmsDeviceId()
        if (origin.isBlank() || id == 0L) return
        val payload = JSONObject()
            .put("v", 1)
            .put("service", "foodtale-failover")
            .put("originCmsId", origin)
            .put("dmsDeviceId", id)
            .toString()
            .toByteArray(StandardCharsets.UTF_8)
        try {
            DatagramSocket().use { sock ->
                sock.broadcast = true
                sock.send(DatagramPacket(payload, payload.size, InetAddress.getByName("255.255.255.255"), 48720))
            }
        } catch (_: Exception) {
        }
    }

    private companion object {
        const val STALE_MS = 60_000L
        const val COLLECT_MS = 3_000L
        const val ANNOUNCE_MS = 1_000L
        const val HEARD_MS = 5_000L
        const val ACTING_BEACON_MS = 5_000L
    }
}
