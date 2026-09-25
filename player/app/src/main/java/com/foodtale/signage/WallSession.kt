package com.foodtale.signage

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.util.ArrayDeque

/**
 * The visible decoder keeps running through a cut. The other decoder
 * prerolls the next item. At the cut we swap. A late swap still plays.
 * Each device presses play earlier by its own recent opening lag, and only
 * when the parked decoder is already ready. Drift of 50–800ms is a 3%
 * nudge toward the timeline. Past 800ms, one seek per cut.
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
    private var lastPresenceAt = 0L
    private val seenPeers = LinkedHashMap<Long, Long>()
    private var axisRun: Runnable? = null
    private var lastSlotEpoch: Long = -1
    private var cutBusy = false
    private var pulseRun: Runnable? = null
    private var soughtThisPlay = false
    private var appliedSpeed = 1f
    private var stallResumes = 0
    private var learnedLead = 0L
    private var leadThisPlay = 0L
    private var lateThisPlay = 0L
    private var openingSampled = false
    private var armedBase = 0L
    private val leadSamples = ArrayDeque<Long>()
    @Volatile private var reportQuery: String = ""

    val playing: Boolean get() = engine.snapshotPlaying
    val settling: Boolean get() = playArmed && !engine.snapshotPlaying

    private companion object {
        const val CUT_PAD_MS = 180L
        const val SETTLE_MS = 350L
        const val MISS_SLACK_MS = 40L
        const val NUDGE_MS = 50L
        const val SEEK_MS = 800L
        const val LEAD_CAP_MS = 400L
    }

    fun applyManifest(m: Manifest) {
        onMain {
            val prev = manifest
            manifest = m
            Prefs.deviceId(m.deviceId)
            if (m.cmsId.isNotBlank()) Prefs.cmsId(m.cmsId)
            engine.mute(!m.isLeader)
            if (m.items.isNotEmpty()) Prefs.manifestJson(m.toJson().toString())
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
            if (msg.action == "PRESENCE") {
                onPresence(msg)
                return@onMain
            }
            val m = manifest ?: return@onMain
            if (msg.group != m.group) {
                lastDrop = "grp ${msg.group}!=${m.group}"
                paintHud()
                return@onMain
            }
            if (msg.action == "START") lastLeaderAt = clock.syncedNow()
            lastDrop = ""
            when (msg.action) {
                "START" -> onStart(msg)
                else -> Unit
            }
        }
    }

    fun onEnded() {
        onMain {
            val pos = engine.snapshotPositionMs
            val item = manifest?.items?.getOrNull(index)
            val dur = if (item != null) itemDur(item).toLong() else 0L
            if (dur > 0L && pos > 500L && pos < dur - 1000L && stallResumes < 2) {
                stallResumes++
                engine.seekTo(pos.coerceAtLeast(0L))
                engine.play()
                lastDrop = "re $pos"
                return@onMain
            }
            handlingEnded = true
            if (playArmed) return@onMain
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
            announcePresence()
            tryPendingStarts()
            val m = manifest ?: run {
                paintHud()
                return@onMain
            }
            if (m.isLeader && engine.snapshotPlaying) {
                cancelPulse()
                startBurstLeft = 0
            }
            if (engine.snapshotPlaying) {
                preloadNext()
                nudgeDrift()
            } else {
                unstickTail()
            }
            if (!m.isLeader && lastLeaderAt > 0 && clock.syncedNow() - lastLeaderAt > 2000) {
                val now = clock.syncedNow()
                if (Prefs.deviceId() == nextLeader(m) && now - lastFailoverAt > 2000) {
                    lastFailoverAt = now
                    enterBarrier(index, now, now + SETTLE_MS + 400)
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
                    .put("playing", engine.snapshotPlaying)
                    .put("status", reportQuery),
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
        if (playArmed && idx == index) return
        val now = clock.syncedNow()
        if (msg.targetEpoch < now - MISS_SLACK_MS) {
            lastDrop = "START late ${now - msg.targetEpoch}ms"
            return
        }
        enterBarrier(idx, lastSlotEpoch, msg.targetEpoch)
    }

    private fun announcePresence() {
        val id = Prefs.deviceId()
        if (id == 0L) return
        val now = clock.syncedNow()
        if (now - lastPresenceAt < 1000) return
        lastPresenceAt = now
        val m = manifest
        bus.send(
            PlayMsg(
                v = 1,
                action = "PRESENCE",
                group = m?.group ?: "",
                itemId = id.toString(),
                index = 0,
                targetEpoch = 0,
                posMs = 0,
                sendTime = now,
            ),
            m?.let { peerIps(it) } ?: emptyList(),
        )
    }

    private fun onPresence(msg: PlayMsg) {
        val id = msg.itemId.toLongOrNull() ?: return
        if (id == 0L || id == Prefs.deviceId()) return
        val m = manifest
        if (m != null && msg.group.isNotBlank() && msg.group != m.group) return
        seenPeers[id] = clock.syncedNow()
        paintHud()
    }

    private fun seenCount(): Int {
        val now = clock.syncedNow()
        val it = seenPeers.entries.iterator()
        while (it.hasNext()) {
            if (now - it.next().value > 3000) it.remove()
        }
        return seenPeers.size
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
        val run = object : Runnable {
            override fun run() {
                if (holdingLateJoin || !playArmed) return
                val file = fileFor(index)
                if (file == null || !engine.start(file)) {
                    playRun = this
                    main.postDelayed(this, 40)
                    paintHud("preload")
                    return
                }
                val late = clock.syncedNow() - playAtEpoch
                playArmed = false
                holdingLateJoin = false
                soughtThisPlay = false
                appliedSpeed = 1f
                stallResumes = 0
                openingSampled = false
                lateThisPlay = late.coerceAtLeast(0L)
                cancelPulse()
                if (late > MISS_SLACK_MS) lastDrop = "late ${late}ms"
            }
        }
        playRun = run
        if (!engine.ready) paintHud("preload")
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
        val boundary = nextCutEpoch(m, now)
        val wakeAt = boundary - learnedLead
        val wait = wakeAt - now
        val run = Runnable { onAxisCut() }
        axisRun = run
        when {
            wait <= 0 -> main.post(run)
            wait > 32 -> main.postDelayed({ armAxis() }, (wait - 12).coerceAtLeast(1))
            else -> main.postDelayed({
                if (clock.syncedNow() >= wakeAt) onAxisCut() else armAxis()
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
            val boundary = nextCutEpoch(m, now)
            val until = boundary - now
            val early = until in 1..LEAD_CAP_MS
            val slot = if (early) boundary else slotEpoch(m, now)
            val idx = if (early) liveIndex(m, boundary) else liveIndex(m, now)
            if (playArmed && idx == index) return
            if (holdingLateJoin && slot == lastSlotEpoch) return
            enterBarrier(idx, slot)
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
        val file = fileFor(index) ?: return
        if (engine.snapshotPlaying) engine.preload(file) else engine.prepare(file)
        preparedId = item.id
    }

    private fun preloadNext() {
        val m = manifest ?: return
        if (m.items.isEmpty()) return
        val idx = when {
            clock.lastOkAt == 0L || m.startMasterMs <= 0 -> (index + 1) % m.items.size
            else -> liveIndex(m, nextCutEpoch(m, clock.syncedNow()))
        }
        val file = fileFor(idx) ?: return
        engine.preload(file)
    }

    private fun fileFor(idx: Int): File? {
        val item = manifest?.items?.getOrNull(idx) ?: return null
        val file = cache.fileFor(item.sha256)
        return if (file.isFile) file else null
    }

    private fun nudgeDrift() {
        val m = manifest ?: return
        if (m.startMasterMs <= 0L) return
        val now = clock.syncedNow()
        if (now < m.startMasterMs) return
        val timeline = now - anchorSlot(m, now)
        val driftNow = engine.snapshotPositionMs - timeline
        if (!openingSampled &&
            engine.snapshotPositionMs >= 80L &&
            appliedSpeed == 1f &&
            timeline in -400L..1500L &&
            kotlin.math.abs(driftNow) <= 2000L &&
            lateThisPlay <= 120L
        ) {
            noteOpening(driftNow)
        }
        if (timeline < 400L) return
        val item = manifest?.items?.getOrNull(index) ?: return
        val dur = itemDur(item).toLong()
        val expected = timeline.coerceAtLeast(0L)
        if (expected > dur - 200L) {
            if (appliedSpeed != 1f) {
                engine.setSpeed(1f)
                appliedSpeed = 1f
            }
            return
        }
        val drift = engine.snapshotPositionMs - timeline
        val speed = when {
            drift < -SEEK_MS -> 1f
            drift > NUDGE_MS -> 0.97f
            drift < -NUDGE_MS -> 1.03f
            else -> 1f
        }
        if (drift < -SEEK_MS && !soughtThisPlay) {
            engine.seekTo(expected)
            soughtThisPlay = true
            lastDrop = "seek ${drift}ms"
        }
        if (appliedSpeed != speed) {
            engine.setSpeed(speed)
            appliedSpeed = speed
        }
    }

    private fun noteOpening(drift: Long) {
        if (openingSampled) return
        openingSampled = true
        val next = (leadThisPlay - drift).coerceIn(0L, LEAD_CAP_MS)
        leadSamples.addLast(next)
        while (leadSamples.size > 5) leadSamples.removeFirst()
        val sorted = leadSamples.sorted()
        val updated = sorted[sorted.size / 2]
        if (updated == learnedLead) return
        learnedLead = updated
        armAxis()
    }

    private fun unstickTail() {
        val m = manifest ?: return
        if (m.startMasterMs <= 0L) return
        val now = clock.syncedNow()
        if (now < m.startMasterMs) return
        val item = m.items.getOrNull(index) ?: return
        val dur = itemDur(item).toLong()
        val pos = engine.snapshotPositionMs
        if (pos < dur - 80L) return
        val expected = (now - slotEpoch(m, now)).coerceAtLeast(0L)
        if (expected >= dur - 200L) return
        engine.setSpeed(1f)
        appliedSpeed = 1f
        engine.seekTo(expected.coerceAtMost(dur - 200L))
        engine.play()
        lastDrop = "tail"
    }

    private fun anchorSlot(m: Manifest, now: Long): Long {
        if (lastSlotEpoch > 0L) return lastSlotEpoch
        return slotEpoch(m, now)
    }

    private fun driftMs(): Long {
        val m = manifest ?: return 0L
        if (m.startMasterMs <= 0L) return 0L
        val now = clock.syncedNow()
        if (now < m.startMasterMs) return 0L
        return engine.snapshotPositionMs - (now - anchorSlot(m, now))
    }

    private fun prepareUpcoming() {
        val m = manifest ?: return
        if (m.items.isEmpty()) return
        val idx = when {
            clock.lastOkAt == 0L || m.startMasterMs <= 0 -> index.coerceIn(0, m.items.lastIndex)
            clock.syncedNow() < m.startMasterMs -> 0
            else -> liveIndex(m, nextCutEpoch(m, clock.syncedNow()))
        }
        index = idx
        prepareCurrent()
    }

    private fun enterBarrier(idx: Int, slot: Long, announcedPlayAt: Long? = null) {
        val m = manifest ?: return
        if (idx !in m.items.indices) return
        val now = clock.syncedNow()
        val base = announcedPlayAt ?: maxOf(slot, now)
        if (announcedPlayAt != null) {
            if (armedBase == announcedPlayAt && idx == index && (playArmed || engine.snapshotPlaying)) return
        } else if (slot == lastSlotEpoch && (playArmed || engine.snapshotPlaying || holdingLateJoin)) {
            return
        }
        holdingLateJoin = false
        handlingEnded = false
        index = idx
        if (slot > 0) lastSlotEpoch = slot
        engine.mute(!m.isLeader)
        prepareCurrent()
        val file = fileFor(idx)
        val lead = if (file != null && engine.canStart(file)) learnedLead else 0L
        leadThisPlay = lead
        armedBase = base
        schedulePlayAt(base - lead)
        if (m.isLeader) {
            emitStart(m.items[idx], base)
            startPulse()
        }
    }

    private fun startPulse() {
        cancelPulse()
        var n = 0
        val run = object : Runnable {
            override fun run() {
                val m = manifest ?: return
                if (!m.isLeader || !playArmed || engine.snapshotPlaying || n >= 10) return
                lastStart?.let { bus.send(it.copy(sendTime = clock.syncedNow()), peerIps(m)) }
                n++
                pulseRun = this
                main.postDelayed(this, 40)
            }
        }
        pulseRun = run
        main.post(run)
    }

    private fun cancelPulse() {
        pulseRun?.let { main.removeCallbacks(it) }
        pulseRun = null
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
    }

    private fun paintHud(extra: String = "") {
        val m = manifest
        val role = if (m?.isLeader == true) "LEADER" else "SLAVE"
        val item = m?.items?.getOrNull(index)
        val clk = if (clock.lastOkAt == 0L) "NOCLK" else "clk"
        val ready = if (engine.ready) "READY" else "PREP"
        val line = buildString {
            append(role)
            append(" v").append(BuildConfig.VERSION_NAME)
            append(" id=").append(m?.deviceId ?: "-")
            append(" ").append(clk)
            append(" ").append(ready)
            append(" off=").append(clock.offsetMs).append("ms")
            append(" rtt=").append(clock.rttMs).append("ms")
            append(" seen=").append(seenCount())
            append(" gen=").append(m?.syncGeneration ?: 0)
            append(" item=").append(item?.id ?: "-")
            append(" bus=").append(bus.recvCount)
            if (m?.isLeader != true) append(" MUTE")
            if (holdingLateJoin) append(" HOLD")
            if (settling) {
                append(" SETTLE ")
                append((playAtEpoch - clock.syncedNow()).coerceAtLeast(0))
                append("ms")
            }
            append(" spd=").append("%.2f".format(engine.currentSpeed()))
            append(" drift=").append(driftMs()).append("ms")
            append(" lead=").append(learnedLead)
            if (lastDrop.isNotBlank()) append(" ").append(lastDrop)
            if (extra.isNotBlank()) append(" ").append(extra)
            append("\n").append(Prefs.http())
        }
        publishReport()
        hud(line)
    }

    fun statusQuery(): String = reportQuery

    private fun publishReport() {
        val m = manifest
        val now = clock.syncedNow()
        val axis = if (m != null && m.startMasterMs > 0L) now - m.startMasterMs else -1L
        val drop = lastDrop.replace(Regex("[^A-Za-z0-9_.+-]"), "_").take(48)
        val phase = when {
            holdingLateJoin -> "HOLD"
            settling -> "SETTLE"
            engine.snapshotPlaying -> "PLAY"
            else -> "IDLE"
        }
        reportQuery = buildString {
            append("st=")
            append(BuildConfig.VERSION_NAME)
            append(if (m?.isLeader == true) ",L" else ",S")
            append(",").append(phase)
            append(",drop=").append(drop)
            append(",idx=").append(index)
            append(",item=").append(m?.items?.getOrNull(index)?.id ?: 0)
            append(",gen=").append(m?.syncGeneration ?: 0)
            append(",rdy=").append(if (engine.ready) 1 else 0)
            append(",play=").append(if (engine.snapshotPlaying) 1 else 0)
            append(",seen=").append(seenCount())
            append(",axis=").append(axis)
            append(",pos=").append(engine.snapshotPositionMs)
            append(",late=").append(if (playAtEpoch > 0L) now - playAtEpoch else 0L)
            append(",spd=").append("%.2f".format(engine.currentSpeed()))
            append(",drift=").append(driftMs())
            append(",lead=").append(learnedLead)
        }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            main.post(block)
        }
    }
}
