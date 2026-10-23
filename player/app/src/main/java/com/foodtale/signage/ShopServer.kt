package com.foodtale.signage

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * The head's CMS, in this process. Followers keep calling the same HTTP and UDP API.
 * Media files sit on disk. Devices, claims, and the playlist are memory, not sqlite.
 */
class ShopServer(private val context: Context) {
    private val gate = Any()
    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()
    private val mediaDir = File(context.filesDir, "shop-media").apply { mkdirs() }

    @Volatile private var running = false
    private var server: ServerSocket? = null
    private var clock: DatagramSocket? = null
    private var acceptThread: Thread? = null
    private var clockThread: Thread? = null
    private var beaconThread: Thread? = null
    private var reportThread: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private val cmsId = loadCmsId()
    private var originCmsId = ""
    private var role = ""
    private var nextId = 1L
    private val devices = mutableListOf<LocalDevice>()
    private val anchorFile = File(context.filesDir, "shop-anchor.json")
    private var playlistId = 0L
    private var playlistName = ""
    private var contentGeneration = -1
    private var syncGeneration = 0L
    private var startMasterMs = 0L
    private var startAt = ""
    private var panelCount = 1
    private val items = mutableListOf<LocalItem>()
    private val saved = HashMap<Long, String>()
    private var branchIsHead = true
    private var branchHeadId = 0L
    private var branchHeadName = ""
    private var brandBranch = ""
    private var brandColor = ""
    private var brandLogo = ""

    fun start() {
        if (running) return
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress("0.0.0.0", 8080))
        server = socket
        val clockSocket = DatagramSocket(null)
        clockSocket.reuseAddress = true
        clockSocket.bind(InetSocketAddress(8123))
        clock = clockSocket
        running = true
        loadAnchor()
        holdRadio()
        acceptThread = Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            acceptLoop(socket)
        }.also { it.isDaemon = true; it.name = "shop-http"; it.start() }
        clockThread = Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            clockLoop(clockSocket)
        }.also { it.isDaemon = true; it.name = "shop-clock"; it.start() }
        beaconThread = Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            beaconLoop()
        }.also { it.isDaemon = true; it.name = "shop-beacon"; it.start() }
        reportThread = Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            reportLoop()
        }.also { it.isDaemon = true; it.name = "shop-report"; it.start() }
        Log.i(TAG, "listening ${advertise()} cms=$cmsId dms=${dmsUrl()}")
    }

    fun stop() {
        running = false
        try {
            server?.close()
        } catch (_: Exception) {
        }
        try {
            clock?.close()
        } catch (_: Exception) {
        }
        server = null
        clock = null
        dropRadio()
    }

    private fun holdRadio() {
        val power = context.getSystemService(PowerManager::class.java)
        val wake = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "foodtale:clock")
        wake.setReferenceCounted(false)
        wake.acquire()
        wakeLock = wake
        val wifi = context.getSystemService(WifiManager::class.java)
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        val radio = wifi.createWifiLock(mode, "foodtale:clock")
        radio.setReferenceCounted(false)
        radio.acquire()
        wifiLock = radio
    }

    private fun dropRadio() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {
        }
        try {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
        wifiLock = null
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running) {
            try {
                val client = socket.accept()
                Thread {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                    handle(client)
                }.also { it.isDaemon = true; it.name = "shop-req"; it.start() }
            } catch (_: Exception) {
                if (running) Log.w(TAG, "accept stopped")
                break
            }
        }
    }

    private fun handle(socket: Socket) {
        socket.use { client ->
            client.soTimeout = 20_000
            val req = readRequest(client.getInputStream()) ?: return
            val remote = client.inetAddress?.hostAddress?.substringBefore('%').orEmpty()
            try {
                route(client, req, remote)
            } catch (e: Exception) {
                Log.w(TAG, "route ${req.method} ${req.path}: ${e.message}")
                writeJson(client, 500, JSONObject().put("error", e.message ?: "error"))
            }
        }
    }

    private fun route(client: Socket, req: HttpRequest, remote: String) {
        val path = req.path.substringBefore('?')
        when {
            req.method == "GET" && path == "/api/health" -> writeJson(client, 200, health())
            req.method == "GET" && path == "/api/app/version" -> writeJson(client, 200, version())
            req.method == "POST" && path == "/api/claim/adopt" -> adopt(client, req, head = true)
            req.method == "POST" && path == "/api/claim/adopt-acting" -> adopt(client, req, head = false)
            req.method == "POST" && path == "/api/claim/join" -> join(client, req)
            req.method == "GET" && path == "/api/claim/head" -> claimHead(client)
            req.method == "GET" && path == "/api/device/manifest" -> manifest(client, req, remote)
            req.method == "POST" && path == "/api/device/heartbeat" -> heartbeat(client, req, remote)
            req.method == "GET" && path.startsWith("/media/") -> media(client, path.removePrefix("/media/"))
            else -> writeText(client, 404, "not found")
        }
    }

    private fun health(): JSONObject = JSONObject()
        .put("ok", true)
        .put("cms_id", cmsId)
        .put("http", advertise())
        .put("clock_port", 8123)
        .put("beacon_port", 48720)
        .put("master_ms", SystemClock.elapsedRealtime())

    private fun version(): JSONObject = JSONObject()
        .put("version", BuildConfig.VERSION_NAME)
        .put("version_code", BuildConfig.VERSION_CODE)
        .put("apk_url", advertise() + "/foodtale-player.apk")
        .put("available", false)

    private fun adopt(client: Socket, req: HttpRequest, head: Boolean) {
        val dms = dmsUrl()
        if (dms.isBlank()) {
            writeText(client, 503, "dms url missing")
            return
        }
        val body = JSONObject(String(req.body, Charsets.UTF_8))
        val code = body.optString("code").trim().uppercase()
        val origin = body.optString("origin_cms_id").trim()
        if (code.isBlank() || (!head && origin.isBlank())) {
            writeText(client, 400, "bad json")
            return
        }
        val roleJson = dmsJson("$dms/api/claims/$code/role")
        if (head) {
            if (!roleJson.optBoolean("is_head") || roleJson.optLong("head_device_id") != roleJson.optLong("device_id")) {
                writeText(client, 409, "not the branch head")
                return
            }
        } else if (!roleJson.optBoolean("is_acting") || roleJson.optLong("acting_device_id") != roleJson.optLong("device_id")) {
            writeText(client, 409, "not the temp head")
            return
        }
        val device = synchronized(gate) {
            role = if (head) "head" else "acting"
            originCmsId = if (head) "" else origin
            branchIsHead = head
            branchHeadId = roleJson.optLong("head_device_id")
            branchHeadName = roleJson.optString("head_device_name")
            upsert(code, if (head) "Head" else "Screen", role)
        }
        Thread { refresh() }.also { it.isDaemon = true; it.start() }
        writeJson(client, 200, claimJson(device))
    }

    private fun join(client: Socket, req: HttpRequest) {
        val dms = dmsUrl()
        if (dms.isBlank()) {
            writeText(client, 503, "dms url missing")
            return
        }
        val code = JSONObject(String(req.body, Charsets.UTF_8)).optString("code").trim().uppercase()
        if (code.isBlank()) {
            writeText(client, 400, "bad json")
            return
        }
        val host = synchronized(gate) { devices.firstOrNull { it.role == "head" || it.role == "acting" } }
            ?: run {
                writeText(client, 409, "cms is not the branch head")
                return
            }
        val guest = dmsJson("$dms/api/claims/$code/role")
        val self = dmsJson("$dms/api/claims/${host.code}/role")
        if (guest.optLong("branch_id") == 0L || guest.optLong("branch_id") != self.optLong("branch_id")) {
            writeText(client, 422, "device is not in this branch")
            return
        }
        val device = synchronized(gate) {
            devices.firstOrNull { it.code == code } ?: upsert(code, "Screen", "screen")
        }
        writeJson(client, 200, claimJson(device))
    }

    private fun claimHead(client: Socket) {
        val code = Prefs.claimCode()
        if (dmsUrl().isBlank() || code.isBlank()) {
            writeJson(client, 200, JSONObject().put("ready", false).put("reason", "dms_url_missing"))
            return
        }
        val device = synchronized(gate) {
            role = "head"
            branchIsHead = true
            originCmsId = ""
            upsert(code, "Head", "head")
        }
        writeJson(client, 200, claimJson(device))
    }

    private fun manifest(client: Socket, req: HttpRequest, remote: String) {
        val device = bearer(req) ?: run {
            writeText(client, 401, "unauthorized")
            return
        }
        synchronized(gate) {
            if (!remote.startsWith("127.")) device.ip = remote
            device.seenAt = System.currentTimeMillis()
        }
        val host = req.headers["host"]?.trim().orEmpty()
        val base = if (host.isBlank()) advertise() else "http://$host"
        val body = manifestJson(device, base).toString()
        val etag = "\"" + sha1(body.toByteArray()) + "\""
        val match = req.headers["if-none-match"]?.trim().orEmpty()
        if (match.isNotBlank() && match == etag) {
            writeBytes(client, 304, ByteArray(0), "application/json", etag)
            return
        }
        writeBytes(client, 200, body.toByteArray(Charsets.UTF_8), "application/json", etag)
    }

    private fun heartbeat(client: Socket, req: HttpRequest, remote: String) {
        val device = bearer(req) ?: run {
            writeText(client, 401, "unauthorized")
            return
        }
        val body = JSONObject(String(req.body, Charsets.UTF_8))
        synchronized(gate) {
            if (!remote.startsWith("127.")) device.ip = remote
            device.seenAt = System.currentTimeMillis()
            if (body.has("playing")) device.playing = body.optBoolean("playing")
            device.itemId = body.optLong("item_id")
            device.speed = if (body.has("speed")) body.optDouble("speed") else device.speed
            device.driftMs = if (body.has("drift_ms")) body.optInt("drift_ms") else device.driftMs
            device.clockOffsetMs = if (body.has("clock_offset_ms")) body.optInt("clock_offset_ms") else device.clockOffsetMs
            device.clockRttMs = if (body.has("clock_rtt_ms")) body.optInt("clock_rtt_ms") else device.clockRttMs
            device.leadMs = if (body.has("lead_ms")) body.optInt("lead_ms") else device.leadMs
            device.phase = body.optString("phase", device.phase)
            device.appVersion = body.optString("app_version", device.appVersion)
            device.playedMs = body.optLong("played_ms")
            device.driftAbsSum = body.optLong("drift_abs_sum")
            device.driftSamples = body.optInt("drift_samples")
            device.speedNudges = body.optInt("speed_nudges")
            device.uptimeMs = body.optLong("uptime_ms")
            val events = body.optJSONArray("events")
            if (events != null) {
                for (i in 0 until events.length()) device.events.add(events.getJSONObject(i))
                while (device.events.size > 40) device.events.removeAt(0)
            }
        }
        writeJson(client, 200, JSONObject().put("ok", true))
    }

    private fun media(client: Socket, sha: String) {
        val file = File(mediaDir, sha)
        if (!file.isFile) {
            writeText(client, 404, "not found")
            return
        }
        val head = "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: ${file.length()}\r\nConnection: close\r\n\r\n"
        val out = client.getOutputStream()
        out.write(head.toByteArray(Charsets.UTF_8))
        file.inputStream().use { it.copyTo(out) }
        out.flush()
    }

    private fun manifestJson(device: LocalDevice, base: String): JSONObject {
        val mine = synchronized(gate) { items.filter { it.row == device.panel } }
        val peers = synchronized(gate) { devices.filter { it.code.isNotBlank() && it.token.isNotBlank() } }
        val leader = synchronized(gate) { devices.firstOrNull { it.role == "head" || it.role == "acting" }?.id ?: 0L }
        val itemArr = JSONArray()
        for (item in mine) {
            itemArr.put(
                JSONObject()
                    .put("id", item.id)
                    .put("type", item.type)
                    .put("url", base.trimEnd('/') + "/media/" + item.sha)
                    .put("sha256", item.sha)
                    .put("duration_ms", item.durationMs)
                    .put("fit", "cut")
                    .put("file_duration_ms", item.fileDurationMs),
            )
        }
        val peerArr = JSONArray()
        for (peer in peers) {
            peerArr.put(JSONObject().put("id", peer.id).put("name", peer.name).put("ip", peer.ip))
        }
        return JSONObject()
            .put("playlist_name", playlistName.ifBlank { "Unassigned" })
            .put("brand_branch", brandBranch)
            .put("brand_color", brandColor)
            .put("brand_logo", brandLogo)
            .put("playlist_id", if (mine.isEmpty()) 0 else playlistId)
            .put("device_id", device.id)
            .put("cms_id", cmsId)
            .put("kind", "playlist")
            .put("panel_count", panelCount)
            .put("panel_index", device.panel)
            .put("peer_count", peers.size.coerceAtLeast(1))
            .put("peers", if (peerArr.length() == 0) JSONArray().put(JSONObject().put("id", device.id).put("name", device.name).put("ip", device.ip)) else peerArr)
            .put("leader_id", leader)
            .put("branch_is_head", branchIsHead)
            .put("branch_head_id", branchHeadId)
            .put("branch_head_name", branchHeadName)
            .put("start_at", startAt.ifBlank { utcNow() })
            .put("start_master_ms", if (mine.isEmpty()) 0 else startMasterMs)
            .put("sync_generation", if (mine.isEmpty()) 0 else syncGeneration)
            .put("items", itemArr)
    }

    private fun reportLoop() {
        while (running) {
            try {
                refresh()
            } catch (e: Exception) {
                Log.w(TAG, "report ${e.message}")
            }
            try {
                Thread.sleep(30_000)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    private fun refresh() {
        val dms = dmsUrl()
        if (dms.isBlank()) return
        val rows = synchronized(gate) {
            devices.filter { it.code.isNotBlank() }.map { device ->
                device.itemLabel = items.firstOrNull { it.id == device.itemId }?.label().orEmpty()
                device.snapshot()
            }
        }
        if (rows.isEmpty()) return
        val payload = JSONObject().put("devices", JSONArray().apply { rows.forEach { put(it.second) } })
        val headCode = synchronized(gate) { devices.firstOrNull { it.role == "head" || it.role == "acting" }?.code }.orEmpty()
        if (headCode.isBlank()) return
        val response = dmsJson("$dms/api/claims/$headCode/report", payload)
        synchronized(gate) {
            for (row in rows) row.first.events.clear()
        }
        val returned = response.optJSONArray("devices") ?: JSONArray()
        var source: JSONObject? = null
        var sourceCode = headCode
        for (i in 0 until returned.length()) {
            val row = returned.getJSONObject(i)
            val code = row.optString("code")
            if (row.optBoolean("revoked")) {
                synchronized(gate) { devices.removeAll { it.code == code } }
                continue
            }
            val playback = row.optJSONObject("playback") ?: continue
            synchronized(gate) {
                devices.firstOrNull { it.code == code }?.panel = playback.optInt("screen_row")
            }
            if (playback.optBoolean("assigned") && source == null) {
                source = playback
                sourceCode = code
            }
        }
        val playback = source ?: return
        val brand = response.optJSONObject("brand")
        materialize(dms, sourceCode, playback, brand)
    }

    private fun materialize(dms: String, code: String, playback: JSONObject, brand: JSONObject?) {
        val active = activePlayback(playback)
        val wall = wallItems(active)
        if (!active.optBoolean("assigned") && wall.length() == 0) return
        val nextGeneration = active.optInt("generation", playback.optInt("generation"))
        val nextPlaylist = active.optLong("playlist_id", playback.optLong("playlist_id"))
        val built = mutableListOf<LocalItem>()
        var rows = 1
        for (i in 0 until wall.length()) {
            val item = wall.getJSONObject(i)
            val type = item.optString("type")
            if (type != "video" && type != "image" && type != "hold" && type != "gap") continue
            val row = item.optInt("row_index")
            if (row + 1 > rows) rows = row + 1
            val duration = item.optInt("duration_ms").coerceAtLeast(1)
            if (type == "hold" || type == "gap") {
                built.add(LocalItem(item.optLong("id"), type, "", duration, 0, row, type))
                continue
            }
            val itemId = item.optLong("id")
            if (itemId <= 0L) continue
            val sha = ensureFile(dms, code, itemId)
            val fileDur = item.optInt("file_duration_ms", duration).coerceAtLeast(1)
            built.add(LocalItem(itemId, type, sha, duration, fileDur, row, item.optString("name")))
        }
        if (built.isEmpty()) return
        synchronized(gate) {
            val changed = nextPlaylist != playlistId || nextGeneration != contentGeneration
            playlistId = nextPlaylist
            playlistName = active.optString("playlist_name", playback.optString("playlist_name"))
            contentGeneration = nextGeneration
            panelCount = rows
            items.clear()
            items.addAll(built)
            if (changed || startMasterMs == 0L) restartAnchor()
            if (brand != null) {
                brandBranch = brand.optString("branch_name")
                brandColor = brand.optString("primary_color")
                brandLogo = brand.optString("logo_version")
            }
        }
        Log.i(TAG, "playlist $nextPlaylist content $nextGeneration sync $syncGeneration items ${built.size}")
    }

    private fun ensureFile(dms: String, code: String, itemId: Long): String {
        synchronized(gate) { saved[itemId] }?.let { return it }
        val req = Request.Builder().url("$dms/api/claims/$code/items/$itemId/file").get().build()
        http.newCall(req).execute().use { resp ->
            val bytes = resp.body?.bytes() ?: error("empty file")
            if (!resp.isSuccessful) error("file ${resp.code}")
            val sha = sha256(bytes)
            File(mediaDir, sha).writeBytes(bytes)
            synchronized(gate) { saved[itemId] = sha }
            return sha
        }
    }

    private fun activePlayback(playback: JSONObject): JSONObject {
        val schedule = playback.optJSONObject("schedule") ?: return playback
        val id = activePlaylistId(schedule)
        val nested = schedule.optJSONObject("playlists")?.optJSONObject(id.toString())
        return nested ?: playback
    }

    private fun wallItems(playback: JSONObject): JSONArray {
        val wall = playback.optJSONArray("wall")
        if (wall != null && wall.length() > 0) return wall
        val row = playback.optJSONArray("row_items")
        if (row != null && row.length() > 0) return row
        return playback.optJSONArray("items") ?: JSONArray()
    }

    private fun activePlaylistId(schedule: JSONObject): Long {
        val zone = schedule.optString("timezone").ifBlank { "Asia/Kuala_Lumpur" }
        val tz = TimeZone.getTimeZone(zone)
        val cal = java.util.Calendar.getInstance(tz)
        var weekday = cal.get(java.util.Calendar.DAY_OF_WEEK) - 1
        if (weekday == 0) weekday = 7
        val minute = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
        val yesterday = if (weekday == 1) 7 else weekday - 1
        val slots = schedule.optJSONArray("slots") ?: JSONArray()
        for (i in 0 until slots.length()) {
            val slot = slots.getJSONObject(i)
            val start = clockMinutes(slot.optString("start"))
            val end = clockMinutes(slot.optString("end"))
            val day = slot.optInt("weekday")
            if (end > start) {
                if (day == weekday && minute >= start && minute < end) return slot.optLong("playlist_id")
            } else {
                if (day == weekday && minute >= start) return slot.optLong("playlist_id")
                if (day == yesterday && minute < end) return slot.optLong("playlist_id")
            }
        }
        return schedule.optLong("fallback_playlist_id")
    }

    private fun beaconLoop() {
        val sock = DatagramSocket(null)
        try {
            sock.reuseAddress = true
            sock.broadcast = true
            sock.bind(InetSocketAddress(0))
            val packet = DatagramPacket(ByteArray(1), 1, InetAddress.getByName("255.255.255.255"), 48720)
            while (running) {
                val roleNow: String
                val originNow: String
                synchronized(gate) {
                    roleNow = role
                    originNow = originCmsId
                }
                if (roleNow == "head" || roleNow == "acting") {
                    val body = JSONObject()
                        .put("v", 1)
                        .put("service", "foodtale-cms")
                        .put("cmsId", cmsId)
                        .put("http", advertise())
                        .put("clockPort", 8123)
                        .put("role", roleNow)
                        .put("originCmsId", originNow)
                        .toString()
                        .toByteArray(Charsets.UTF_8)
                    packet.data = body
                    packet.length = body.size
                    try {
                        sock.send(packet)
                    } catch (e: Exception) {
                        Log.w(TAG, "beacon ${e.message}")
                    }
                }
                Thread.sleep(1_000)
            }
        } catch (e: Exception) {
            Log.w(TAG, "beacon loop ${e.message}")
        } finally {
            sock.close()
        }
    }

    private fun clockLoop(socket: DatagramSocket) {
        val buf = ByteArray(2048)
        while (running) {
            try {
                val packet = DatagramPacket(buf, buf.size)
                socket.receive(packet)
                val req = JSONObject(String(packet.data, 0, packet.length, Charsets.UTF_8))
                val tMaster = SystemClock.elapsedRealtime()
                val resp = JSONObject()
                    .put("v", 1)
                    .put("t1", req.optLong("t1"))
                    .put("tMaster", tMaster)
                    .put("t2send", SystemClock.elapsedRealtime())
                    .toString()
                    .toByteArray(Charsets.UTF_8)
                socket.send(DatagramPacket(resp, resp.size, packet.address, packet.port))
            } catch (e: Exception) {
                if (running) Log.w(TAG, "clock ${e.message}")
            }
        }
    }

    private fun upsert(code: String, name: String, deviceRole: String): LocalDevice {
        val existing = devices.firstOrNull { it.code == code }
        if (existing != null) {
            existing.role = deviceRole
            existing.name = name
            if (deviceRole == "head" || deviceRole == "acting") existing.ip = advertise().removePrefix("http://").substringBefore(":")
            return existing
        }
        val created = LocalDevice(
            id = nextId++,
            name = name,
            token = UUID.randomUUID().toString().replace("-", ""),
            code = code,
            role = deviceRole,
        )
        if (deviceRole == "head" || deviceRole == "acting") {
            created.ip = advertise().removePrefix("http://").substringBefore(":")
        }
        devices.add(created)
        return created
    }

    private fun claimJson(device: LocalDevice): JSONObject = JSONObject()
        .put("ready", true)
        .put("head_claimed", true)
        .put("claimed", true)
        .put("code", device.code)
        .put("device_token", device.token)
        .put("cms_id", cmsId)
        .put("device_id", device.id)
        .put("http", advertise())

    private fun bearer(req: HttpRequest): LocalDevice? {
        val header = req.headers["authorization"] ?: return null
        if (!header.startsWith("Bearer ")) return null
        val token = header.removePrefix("Bearer ").trim()
        return synchronized(gate) { devices.firstOrNull { it.token == token } }
    }

    private fun dmsJson(url: String, body: JSONObject? = null): JSONObject {
        val builder = Request.Builder().url(url)
        if (body != null) {
            builder.post(body.toString().toRequestBody("application/json".toMediaType()))
        }
        http.newCall(builder.build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("dms ${resp.code}: $text")
            return JSONObject(text.ifBlank { "{}" })
        }
    }

    private fun dmsUrl(): String = CmsHost.dmsPublicUrl()

    private fun advertise(): String {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return "http://127.0.0.1:8080"
        val props = cm.getLinkProperties(cm.activeNetwork) ?: return "http://127.0.0.1:8080"
        val ip = props.linkAddresses.firstOrNull { link ->
            val addr = link.address
            addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress
        }?.address?.hostAddress ?: return "http://127.0.0.1:8080"
        return "http://$ip:8080"
    }

    private fun loadCmsId(): String {
        val fromEnv = File("/data/local/tmp/foodtale/cms.env").takeIf { it.canRead() }?.readLines()
            ?.firstOrNull { it.trim().startsWith("CMS_ID=") }
            ?.substringAfter("=")
            ?.trim()
            .orEmpty()
        if (fromEnv.isNotBlank()) return fromEnv
        return Prefs.installationId()
    }

    private fun nextStart(): Long {
        var master = SystemClock.elapsedRealtime() + 8_000L
        val rem = master % 1000L
        if (rem != 0L) master += 1000L - rem
        return master
    }

    // Go RestartSync: a new boot clock gets a new anchor, and sync_generation
    // increments so every player drops the old group and arms on this anchor.
    private fun restartAnchor() {
        startMasterMs = nextStart()
        startAt = utcPlus(startMasterMs - SystemClock.elapsedRealtime())
        syncGeneration += 1
        val body = JSONObject()
            .put("sync_generation", syncGeneration)
            .put("playlist_id", playlistId)
            .put("content_generation", contentGeneration)
            .put("start_master_ms", startMasterMs)
            .put("start_at", startAt)
        anchorFile.writeText(body.toString())
        Log.i(TAG, "restart sync gen=$syncGeneration start=$startMasterMs")
    }

    private fun loadAnchor() {
        if (!anchorFile.isFile) return
        try {
            syncGeneration = JSONObject(anchorFile.readText()).optLong("sync_generation")
        } catch (e: Exception) {
            Log.w(TAG, "anchor ${e.message}")
        }
    }

    private fun utcNow(): String = utcPlus(0)

    private fun utcPlus(deltaMs: Long): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(Date(System.currentTimeMillis() + deltaMs))
    }

    private fun clockMinutes(value: String): Int {
        if (value.length < 5) return 0
        val hour = value.substring(0, 2).toIntOrNull() ?: return 0
        val minute = value.substring(3, 5).toIntOrNull() ?: return 0
        return hour * 60 + minute
    }

    private fun sha1(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-1").digest(bytes))

    private fun sha256(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private fun writeJson(socket: Socket, code: Int, body: JSONObject) {
        writeBytes(socket, code, body.toString().toByteArray(Charsets.UTF_8), "application/json", null)
    }

    private fun writeText(socket: Socket, code: Int, body: String) {
        writeBytes(socket, code, body.toByteArray(Charsets.UTF_8), "text/plain", null)
    }

    private fun writeBytes(socket: Socket, code: Int, body: ByteArray, type: String, etag: String?) {
        val reason = when (code) {
            200 -> "OK"
            304 -> "Not Modified"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            404 -> "Not Found"
            409 -> "Conflict"
            422 -> "Unprocessable Entity"
            500 -> "Internal Server Error"
            503 -> "Service Unavailable"
            else -> "Error"
        }
        val head = buildString {
            append("HTTP/1.1 $code $reason\r\n")
            append("Content-Type: $type\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Connection: close\r\n")
            if (etag != null) append("ETag: $etag\r\n")
            append("\r\n")
        }
        val out = socket.getOutputStream()
        out.write(head.toByteArray(Charsets.UTF_8))
        out.write(body)
        out.flush()
    }

    private fun readRequest(input: InputStream): HttpRequest? {
        val head = ByteArrayOutputStream()
        while (head.size() < 65_536) {
            val b = input.read()
            if (b < 0) return null
            head.write(b)
            val raw = head.toByteArray()
            val n = raw.size
            if (n >= 4 && raw[n - 4] == 13.toByte() && raw[n - 3] == 10.toByte() && raw[n - 2] == 13.toByte() && raw[n - 1] == 10.toByte()) {
                break
            }
        }
        val text = head.toString(Charsets.UTF_8.name())
        val blocks = text.split("\r\n\r\n", limit = 2)
        val lines = blocks[0].split("\r\n")
        val parts = lines.firstOrNull()?.split(" ") ?: return null
        if (parts.size < 2) return null
        val headers = mutableMapOf<String, String>()
        for (line in lines.drop(1)) {
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        val body = if (length > 0) readExact(input, length) else ByteArray(0)
        return HttpRequest(parts[0], parts[1], headers, body)
    }

    private fun readExact(input: InputStream, length: Int): ByteArray {
        val out = ByteArray(length)
        var off = 0
        while (off < length) {
            val n = input.read(out, off, length - off)
            if (n < 0) break
            off += n
        }
        return if (off == length) out else out.copyOf(off)
    }

    private class LocalDevice(
        val id: Long,
        var name: String,
        val token: String,
        var code: String,
        var role: String,
    ) {
        var panel: Int = 0
        var ip: String = ""
        var playing: Boolean = false
        var itemId: Long = 0
        var itemLabel: String = ""
        var seenAt: Long = 0
        var speed: Double? = null
        var driftMs: Int? = null
        var clockOffsetMs: Int? = null
        var clockRttMs: Int? = null
        var leadMs: Int? = null
        var phase: String = ""
        var appVersion: String = ""
        var playedMs: Long = 0
        var driftAbsSum: Long = 0
        var driftSamples: Int = 0
        var speedNudges: Int = 0
        var uptimeMs: Long = 0
        val events: MutableList<JSONObject> = mutableListOf()

        fun snapshot(): Pair<LocalDevice, JSONObject> {
            val age = if (seenAt == 0L) 1 shl 20 else ((System.currentTimeMillis() - seenAt) / 1000L).toInt().coerceAtLeast(0)
            val row = JSONObject()
                .put("code", code)
                .put("playing", playing && age <= 20)
                .put("item", itemLabel)
                .put("seen_seconds", age)
            speed?.let { row.put("speed", it) }
            driftMs?.let { row.put("drift_ms", it) }
            clockOffsetMs?.let { row.put("clock_offset_ms", it) }
            clockRttMs?.let { row.put("clock_rtt_ms", it) }
            leadMs?.let { row.put("lead_ms", it) }
            if (phase.isNotBlank()) row.put("phase", phase)
            if (appVersion.isNotBlank()) row.put("app_version", appVersion)
            if (playedMs > 0) row.put("played_ms", playedMs)
            if (driftAbsSum > 0) row.put("drift_abs_sum", driftAbsSum)
            if (driftSamples > 0) row.put("drift_samples", driftSamples)
            if (speedNudges > 0) row.put("speed_nudges", speedNudges)
            if (uptimeMs > 0) row.put("uptime_ms", uptimeMs)
            if (events.isNotEmpty()) row.put("events", JSONArray(events))
            return this to row
        }
    }

    private data class LocalItem(
        val id: Long,
        val type: String,
        val sha: String,
        val durationMs: Int,
        val fileDurationMs: Int,
        val row: Int,
        val name: String,
    ) {
        fun label(): String = when (type) {
            "image" -> if (name.isBlank()) "image.jpg" else "$name.jpg"
            "video" -> if (name.isBlank()) "media.mp4" else "$name.mp4"
            else -> name.ifBlank { type }
        }
    }

    private data class HttpRequest(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: ByteArray,
    )

    private companion object {
        const val TAG = "ShopServer"
    }
}
