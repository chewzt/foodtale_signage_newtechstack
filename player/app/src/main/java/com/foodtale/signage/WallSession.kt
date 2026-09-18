package com.foodtale.signage

import android.os.Handler
import android.os.Looper
import org.json.JSONObject

/**
 * Cuts follow the CMS axis (startMasterMs + item durations), same as the
 * Flutter wall: each device schedules play locally. UDP START is optional
 * tightening, not the loop trigger.
 */
class WallSession(
    private val engine: PlayerEngine,
    private val clock: ClockClient,
    private val bus: PlayBus,
    private val api: CmsApi,
    private val cache: MediaCache,
    private val hud: (String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())

    @Volatile var manifest: Manifest? = null
        private set
    @Volatile var lastDeltaMs: Long = 0
        private set

    private var index = 0
    private var preparedId: Long = -1
    private var lastLeaderAt: Long = 0
    private var holdingLateJoin = false
    private var playAtEpoch: Long = 0
    private var playArmed = false
    private var queuedStart: PlayMsg? = null
    private var startBurstLeft = 0
    private var lastStart: PlayMsg? = null
    private var playRun: Runnable? = null
    private var lastFailoverAt: Long = 0
    private var lastCmsBeat: Long = 0
    private var lastDrop = ""
    private var handlingEnded = false
    private var axisRun: Runnable? = null
    private var lastSlotEpoch: Long = -1
    private var cutBusy = false

    val playing: Boolean get() = engine.snapshotPlaying

    fun applyManifest(m: Manifest) {
        onMain {
            val prev = manifest
            manifest = m
            Prefs.deviceId(m.deviceId)
            if (m.cmsId.isNotBlank()) Prefs.cmsId(m.cmsId)
            engine.mute(!m.isLeader)
            if (prev == null || prev.syncGeneration != m.syncGeneration || prev.playlistId != m.playlistId) {
                index = 0
                holdingLateJoin = false
                playArmed = false
                playAtEpoch = 0
                queuedStart = null
                handlingEnded = false
                lastSlotEpoch = -1
                axisRun?.let { main.removeCallbacks(it) }
                cancelPlay()
                prepareCurrent()
                armAxis()
            }
            paintHud()
        }
    }

    fun onPlayMsg(msg: PlayMsg) {
        onMain {
            val m = manifest ?: return@onMain
            if (msg.group != m.group) {
                lastDrop = "grp ${msg.group}!=${m.group}"
                paintHud()
                return@onMain
            }
            lastLeaderAt = clock.syncedNow()
            lastDrop = ""
            when (msg.action) {
                "START" -> onStart(msg)
                "HEARTBEAT" -> onHeartbeat(msg)
                else -> Unit
            }
        }
    }

    fun onEnded() {
        onMain {
            if (handlingEnded) return@onMain
            handlingEnded = true
            val m = manifest ?: return@onMain
            if (m.items.isEmpty()) return@onMain
            holdingLateJoin = false
            prepareCurrent()
            paintHud("hold for axis cut")
            armAxis()
        }
    }

    fun onReady() {
        onMain {
            handlingEnded = false
            if (playArmed) armPlay()
            armAxis()
            tryPendingStarts()
            paintHud()
        }
    }

    fun tickPlayBus() {
        onMain {
            engine.capture()
            tryPendingStarts()
            val m = manifest ?: return@onMain
            if (m.isLeader && startBurstLeft > 0) {
                lastStart?.let {
                    bus.send(it.copy(sendTime = clock.syncedNow()), peerIps(m))
                    startBurstLeft--
                }
            }
            if (m.isLeader && engine.snapshotPlaying) {
                val item = m.items.getOrNull(index) ?: return@onMain
                bus.send(
                    PlayMsg(
                        v = 1,
                        action = "HEARTBEAT",
                        group = m.group,
                        itemId = item.id.toString(),
                        index = index,
                        targetEpoch = 0,
                        posMs = engine.snapshotPositionMs,
                        sendTime = clock.syncedNow(),
                    ),
                    peerIps(m),
                )
            } else if (!m.isLeader && lastLeaderAt > 0 && clock.syncedNow() - lastLeaderAt > 2000) {
                val now = clock.syncedNow()
                if (Prefs.deviceId() == nextLeader(m) && now - lastFailoverAt > 2000) {
                    lastFailoverAt = now
                    val item = m.items.getOrNull(index) ?: return@onMain
                    emitStart(item, now + 800)
                }
            }
            paintHud()
        }
    }

    fun tickCms() {
        val now = clock.syncedNow()
        if (now - lastCmsBeat < 5000) return
        lastCmsBeat = now
        val m = manifest ?: return
        try {
            api.heartbeat(
                Prefs.http(),
                Prefs.token(),
                JSONObject()
                    .put("clock_offset_ms", clock.offsetMs)
                    .put("clock_rtt_ms", clock.rttMs)
                    .put("index", index)
                    .put("item_id", m.items.getOrNull(index)?.id ?: 0)
                    .put("lag_ms", lastDeltaMs)
                    .put("playing", engine.snapshotPlaying),
            )
        } catch (_: Exception) {
        }
    }

    fun ensureDownloads() {
        val m = manifest ?: return
        for (item in m.items) {
            if (item.sha256.isBlank()) continue
            if (!cache.has(item.sha256)) {
                api.download(item.url, cache.fileFor(item.sha256))
            }
        }
        onMain { if (preparedId < 0) prepareCurrent() }
    }

    fun refreshHud() {
        onMain { paintHud() }
    }

    private fun tryPendingStarts() {
        val m = manifest ?: return
        if (m.items.isEmpty()) return
        if (clock.lastOkAt == 0L) return
        queuedStart?.let {
            queuedStart = null
            onStart(it)
        }
        armAxis()
    }

    private fun onStart(msg: PlayMsg) {
        val m = manifest ?: return
        if (clock.lastOkAt == 0L) {
            queuedStart = msg
            lastDrop = "wait clock"
            paintHud()
            return
        }
        val itemId = msg.itemId.toLongOrNull()
        val byId = if (itemId != null) m.items.indexOfFirst { it.id == itemId } else -1
        val idx = when {
            byId >= 0 -> byId
            m.items.isEmpty() -> -1
            else -> msg.index.mod(m.items.size)
        }
        if (idx < 0) return
        index = idx
        val delay = msg.targetEpoch - clock.syncedNow()
        if (delay < -2000) {
            holdingLateJoin = true
            playArmed = false
            cancelPlay()
            engine.pause()
            prepareCurrent()
            paintHud("late join — wait next cut")
            return
        }
        holdingLateJoin = false
        handlingEnded = false
        val localId = m.items[idx].id
        if (preparedId != localId || !engine.ready) prepareCurrent()
        engine.mute(!m.isLeader)
        schedulePlayAt(msg.targetEpoch)
    }

    private fun onHeartbeat(msg: PlayMsg) {
        val m = manifest ?: return
        if (m.isLeader) return
        engine.capture()
        val expected = msg.posMs + (clock.syncedNow() - msg.sendTime)
        lastDeltaMs = engine.snapshotPositionMs - expected
        paintHud()
    }

    private fun schedulePlayAt(epoch: Long) {
        playAtEpoch = epoch
        playArmed = true
        armPlay()
    }

    private fun armPlay() {
        cancelPlay()
        if (holdingLateJoin || !playArmed) return
        val wait = playAtEpoch - clock.syncedNow()
        val run = Runnable {
            if (holdingLateJoin || !playArmed) return@Runnable
            if (!engine.ready) return@Runnable
            playArmed = false
            engine.play()
        }
        playRun = run
        if (!engine.ready) {
            paintHud("preload")
            return
        }
        if (wait <= 0) {
            main.post(run)
        } else {
            main.postDelayed(run, wait)
            paintHud("wait ${wait}ms")
        }
    }

    private fun cancelPlay() {
        playRun?.let { main.removeCallbacks(it) }
        playRun = null
    }

    private fun itemDur(item: MediaItem): Int =
        item.durationMs.coerceAtLeast(item.fileDurationMs).coerceAtLeast(1)

    private fun totalDur(m: Manifest): Int =
        m.items.sumOf { itemDur(it) }.coerceAtLeast(1)

    private fun nextCutEpoch(m: Manifest, now: Long): Long {
        val origin = m.startMasterMs
        if (m.items.isEmpty() || origin <= 0) return origin
        if (now < origin) return origin
        val total = totalDur(m).toLong()
        val elapsed = now - origin
        val mod = elapsed % total
        var cursor = 0L
        for (item in m.items) {
            cursor += itemDur(item)
            if (mod < cursor) return origin + (elapsed - mod) + cursor
        }
        return origin + (elapsed - mod) + total
    }

    private fun slotEpoch(m: Manifest, now: Long): Long {
        val origin = m.startMasterMs
        if (m.items.isEmpty() || origin <= 0) return origin
        if (now < origin) return origin
        val total = totalDur(m).toLong()
        val elapsed = now - origin
        val mod = ((elapsed % total) + total) % total
        var cursor = 0L
        for (i in m.items.indices) {
            val d = itemDur(m.items[i]).toLong()
            if (mod < cursor + d) return origin + (elapsed - mod) + cursor
            cursor += d
        }
        return origin + (elapsed - mod)
    }

    private fun liveIndex(m: Manifest, now: Long): Int {
        val origin = m.startMasterMs
        if (m.items.isEmpty() || origin <= 0 || now < origin) return 0
        val total = totalDur(m).toLong()
        val mod = ((now - origin) % total + total) % total
        var cursor = 0L
        for (i in m.items.indices) {
            val d = itemDur(m.items[i]).toLong()
            if (mod < cursor + d) return i
            cursor += d
        }
        return 0
    }

    private fun armAxis() {
        axisRun?.let { main.removeCallbacks(it) }
        val m = manifest ?: return
        if (m.items.isEmpty() || clock.lastOkAt == 0L || m.startMasterMs <= 0) return
        val now = clock.syncedNow()
        val live = timelineWait(m, now)
        if (live > 0) {
            val run = Runnable { onAxisCut() }
            axisRun = run
            main.postDelayed(run, live)
            return
        }
        val cutAt = nextCutEpoch(m, now)
        val wait = cutAt - now
        val run = Runnable { onAxisCut() }
        axisRun = run
        when {
            wait <= 0 -> main.post(run)
            wait > 32 -> main.postDelayed({ armAxis() }, (wait - 12).coerceAtLeast(1))
            else -> main.postDelayed({
                if (clock.syncedNow() >= cutAt) onAxisCut() else armAxis()
            }, 4)
        }
    }

    private fun timelineWait(m: Manifest, now: Long): Long {
        val origin = m.startMasterMs
        return if (origin > 0 && now < origin) origin - now else 0
    }

    private fun onAxisCut() {
        if (cutBusy) return
        cutBusy = true
        try {
            val m = manifest ?: return
            if (m.items.isEmpty() || m.startMasterMs <= 0) return
            val now = clock.syncedNow()
            if (now < m.startMasterMs) return
            val slot = slotEpoch(m, now)
            val idx = liveIndex(m, now)
            if (slot == lastSlotEpoch && (engine.snapshotPlaying || playArmed)) return
            index = idx
            lastSlotEpoch = slot
            holdingLateJoin = false
            handlingEnded = false
            prepareCurrent()
            val item = m.items.getOrNull(index) ?: return
            if (m.isLeader) {
                emitStart(item, slot.coerceAtLeast(now))
            } else {
                schedulePlayAt(now)
            }
        } finally {
            cutBusy = false
            armAxis()
        }
    }

    private fun nextLeader(m: Manifest): Long {
        val ids = m.peers.map { it.id }.sorted()
        return ids.filter { it != m.leaderId }.minOrNull() ?: m.deviceId
    }

    private fun peerIps(m: Manifest): List<String> =
        m.peers.map { it.ip }.filter { it.isNotBlank() }

    private fun prepareCurrent() {
        val m = manifest ?: return
        val item = m.items.getOrNull(index) ?: return
        val file = cache.fileFor(item.sha256)
        if (!file.isFile) return
        engine.prepare(file)
        preparedId = item.id
    }

    private fun emitStart(item: MediaItem, target: Long) {
        val m = manifest ?: return
        val msg = PlayMsg(
            v = 1,
            action = "START",
            group = m.group,
            itemId = item.id.toString(),
            index = index,
            targetEpoch = target,
            posMs = 0,
            sendTime = clock.syncedNow(),
        )
        lastStart = msg
        startBurstLeft = 8
        bus.send(msg, peerIps(m))
        onStart(msg)
    }

    private fun paintHud(extra: String = "") {
        val m = manifest
        val role = if (m?.isLeader == true) "LEADER" else "SLAVE"
        val item = m?.items?.getOrNull(index)
        val clk = if (clock.lastOkAt == 0L) "NOCLK" else "clk"
        val ready = if (engine.ready) "READY" else "PREP"
        val line = buildString {
            append(role)
            append(" id=").append(m?.deviceId ?: "-")
            append(" ").append(clk)
            append(" ").append(ready)
            append(" off=").append(clock.offsetMs).append("ms")
            append(" rtt=").append(clock.rttMs).append("ms")
            append(" Δ=").append(lastDeltaMs).append("ms")
            append(" gen=").append(m?.syncGeneration ?: 0)
            append(" item=").append(item?.id ?: "-")
            append(" bus=").append(bus.recvCount)
            if (m?.isLeader != true) append(" MUTE")
            if (holdingLateJoin) append(" HOLD")
            if (playArmed && !engine.snapshotPlaying) append(" ARMED")
            if (Prefs.speedCatchup()) append(" SPEED")
            if (lastDrop.isNotBlank()) append(" ").append(lastDrop)
            if (extra.isNotBlank()) append(" ").append(extra)
            append("\n").append(Prefs.http())
        }
        hud(line)
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            main.post(block)
        }
    }
}
