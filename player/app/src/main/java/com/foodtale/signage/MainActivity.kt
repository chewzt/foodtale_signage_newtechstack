package com.foodtale.signage

import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.Color
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

class MainActivity : AppCompatActivity() {
    private val main = Handler(Looper.getMainLooper())
    private var splashDone = false
    private val api = CmsApi()
    private val tempHead = TempHead()
    private lateinit var clock: ClockClient
    private var bus: PlayBus? = null
    private var beacon: BeaconListener? = null
    private var session: WallSession? = null
    private var engine: PlayerEngine? = null
    private var worker: Thread? = null
    @Volatile private var running = false
    @Volatile private var httpOk = false
    @Volatile private var updateHint = ""
    private var settingsVisible = false
    @Volatile private var claimBase = ""
    private val seenBeacons = ConcurrentHashMap<String, Long>()
    @Volatile private var claimToken = ""
    @Volatile private var claimBusy = false
    private var lastClaimRenew = 0L
    private var claimRunning = false
    private val hideChrome = Runnable {
        findViewById<View>(R.id.playerChrome)?.visibility = View.GONE
    }
    private var shownClaimUrl = ""
    private val pollClaim = object : Runnable {
        override fun run() {
            if (!claimRunning || Prefs.paired() || roleKnown) return
            if (!claimBusy) {
                claimBusy = true
                Thread {
                    try {
                        val result = fetchClaim()
                        main.post {
                            if (claimRunning) renderClaim(result)
                        }
                    } catch (e: Exception) {
                        main.post {
                            if (claimRunning) setClaimStatus(e.message ?: "The host is not ready yet.")
                        }
                    } finally {
                        claimBusy = false
                    }
                }.start()
            }
            main.postDelayed(this, 2_000)
        }
    }
    @Volatile private var roleBusy = false
    @Volatile private var roleKnown = false
    @Volatile private var roleAsked = false
    @Volatile private var knownHead = false
    @Volatile private var knownHeadId = 0L
    @Volatile private var knownHeadName = ""
    private val pollRole = object : Runnable {
        override fun run() {
            if (!BuildConfig.START_CMS) return
            val retry = roleKnown && !roleMatches()
            if ((!roleKnown || retry) && !roleBusy) {
                roleBusy = true
                Thread {
                    try {
                        applyRole()
                    } catch (_: PairingRevoked) {
                        roleKnown = false
                        roleAsked = false
                        main.post {
                            CmsHost.stop()
                            Prefs.claimCode("")
                            Prefs.headDeviceId(0)
                            Prefs.followedHeadId(0)
                            dropPairing()
                        }
                    } catch (_: Exception) {
                    } finally {
                        roleBusy = false
                    }
                }.start()
            }
            main.postDelayed(this, 10_000)
        }
    }
    private var lanCallback: ConnectivityManager.NetworkCallback? = null
    private val openWifiSettings = object : Runnable {
        override fun run() {
            if (lanUp() || settingsVisible) return
            try {
                startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                settingsVisible = true
            } catch (_: Exception) {
                main.postDelayed(this, 10_000)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        enterImmersive()
        startForegroundService(Intent(this, PlayerService::class.java))
        clock = ClockClient({ Prefs.http() })
        if (localHead()) {
            CmsHost.start(this)
            Prefs.http("http://127.0.0.1:8080")
        }
        if (Prefs.paired()) showPlayer() else showClaim()
        if (BuildConfig.START_CMS) main.post(pollRole)
        listenForLan()
        if (lanUp()) requestIgnoreBattery() else noteOffline()
    }

    override fun onResume() {
        super.onResume()
        enterImmersive()
        settingsVisible = false
        if (lanUp()) main.removeCallbacks(openWifiSettings) else noteOffline()
    }

    private fun localHead(): Boolean =
        BuildConfig.START_CMS && Prefs.paired() && Prefs.deviceName() == "Head"

    private fun lanUp(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    private fun noteOffline() {
        if (lanUp() || settingsVisible) return
        if (main.hasCallbacks(openWifiSettings)) return
        main.postDelayed(openWifiSettings, 10_000)
    }

    private fun noteOnline() {
        main.removeCallbacks(openWifiSettings)
        if (!lanUp() || !settingsVisible) return
        try {
            startActivity(
                Intent(this, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                ),
            )
        } catch (_: Exception) {
        }
    }

    private fun listenForLan() {
        if (lanCallback != null) return
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                main.post { if (lanUp()) noteOnline() else noteOffline() }
            }

            override fun onLost(network: Network) {
                main.post { noteOffline() }
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                main.post { if (lanUp()) noteOnline() else noteOffline() }
            }
        }
        lanCallback = callback
        cm.registerDefaultNetworkCallback(callback)
    }

    private fun stopListeningForLan() {
        val callback = lanCallback ?: return
        lanCallback = null
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        try {
            cm.unregisterNetworkCallback(callback)
        } catch (_: Exception) {
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersive()
    }

    private fun enterImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                )
        }
    }

    private fun requestIgnoreBattery() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val pm = getSystemService(PowerManager::class.java) ?: return
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        } catch (_: Exception) {
        }
    }

    private fun showClaim() {
        running = false
        session?.release()
        session = null
        engine?.release()
        engine = null
        bus?.stop()
        bus = null
        setContentView(R.layout.activity_pair)
        claimRunning = true
        claimToken = ""
        shownClaimUrl = ""
        beacon?.stop()
        beacon = BeaconListener { b ->
            if (b.http.isBlank() || b.http.contains("127.0.0.1")) return@BeaconListener
            claimBase = b.http
            seenBeacons[b.http] = System.currentTimeMillis()
        }.also { it.start() }
        if (BuildConfig.START_CMS) {
            setClaimStatus("Registering this screen with DMS…")
        } else {
            setClaimStatus("Looking for the branch host…")
        }
        main.removeCallbacks(pollClaim)
        main.post(pollClaim)
    }

    private fun fetchOwnClaim(): JSONObject {
        val dms = CmsHost.dmsPublicUrl()
        if (dms.isBlank()) return JSONObject().put("ready", false).put("reason", "dms_url_missing")
        val code = Prefs.claimCode()
        if (code.isBlank()) return JSONObject().put("ready", false).put("reason", "registering")
        val show = api.claimShow(dms, code)
        if (show.optBoolean("revoked")) throw PairingRevoked()
        if (show.optBoolean("claimed")) return JSONObject().put("ready", true).put("waiting_role", true)
        // The code only stays valid while this screen is showing it. Renew
        // before the 10 minute window ends, and immediately if it already died.
        val now = System.currentTimeMillis()
        if (show.optBoolean("expired") || now - lastClaimRenew > 60_000) {
            api.registerClaim(dms, code, Prefs.installationId())
            lastClaimRenew = now
        }
        return JSONObject().put("ready", true).put("code", code).put("url", "$dms/claim/$code")
    }

    private fun roleMatches(): Boolean {
        if (!roleKnown) return false
        return if (knownHead) headSettled() else followerSettled()
    }

    private fun headSettled(): Boolean {
        return Prefs.paired() && Prefs.deviceName() == "Head" && CmsHost.wanted()
    }

    private fun followerSettled(): Boolean {
        return Prefs.paired() &&
            Prefs.deviceName() != "Head" &&
            !CmsHost.wanted() &&
            !Prefs.http().contains("127.0.0.1")
    }

    private fun applyRole() {
        if (!BuildConfig.START_CMS) return
        if (Prefs.acting()) return
        if (!roleKnown && headSettled()) {
            roleKnown = true
            knownHead = true
            return
        }
        if (roleMatches()) return
        if (roleKnown && !knownHead && (knownHeadId != 0L || roleAsked)) {
            becomeFollower(Prefs.claimCode(), knownHeadId, knownHeadName)
            return
        }
        if (roleKnown && knownHead) {
            becomeHead(Prefs.claimCode())
            return
        }
        val dms = CmsHost.dmsPublicUrl()
        if (dms.isBlank()) return
        var code = Prefs.claimCode()
        if (code.isBlank() && Prefs.paired()) {
            CmsHost.start(this)
            if (!waitForCms()) return
            code = api.claimHead("http://127.0.0.1:8080").optString("code")
            if (code.isBlank()) return
            Prefs.claimCode(code)
        }
        if (code.isBlank()) {
            code = newClaimCode()
            try {
                api.registerClaim(dms, code, Prefs.installationId())
            } catch (e: Exception) {
                if (!e.message.orEmpty().contains("already used")) throw e
                code = newClaimCode()
                api.registerClaim(dms, code, Prefs.installationId())
            }
            Prefs.claimCode(code)
            return
        }
        val show = api.claimShow(dms, code)
        if (show.optBoolean("revoked")) throw PairingRevoked()
        if (!show.optBoolean("claimed")) return
        val role = api.claimRole(dms, code)
        roleAsked = true
        roleKnown = true
        Prefs.dmsDeviceId(role.optLong("device_id"))
        knownHead = role.optBoolean("is_head")
        knownHeadId = role.optLong("head_device_id")
        knownHeadName = role.optString("head_device_name")
        Prefs.headDeviceId(knownHeadId)
        if (knownHead) {
            becomeHead(code)
            return
        }
        becomeFollower(code, knownHeadId, knownHeadName)
    }

    private fun becomeHead(code: String) {
        CmsHost.start(this)
        if (!waitForCms()) return
        val adopted = api.adoptClaim("http://127.0.0.1:8080", code)
        val token = adopted.optString("device_token")
        if (token.isBlank()) return
        Prefs.http("http://127.0.0.1:8080")
        Prefs.cmsId(adopted.optString("cms_id"))
        Prefs.originCmsId(Prefs.cmsId())
        Prefs.token(token)
        Prefs.deviceId(adopted.optLong("device_id"))
        Prefs.deviceName("Head")
        Prefs.etag("")
        main.post { if (claimRunning) showPlayer() }
    }

    private fun becomeFollower(code: String, headId: Long, headName: String) {
        val wasHead = Prefs.deviceName() == "Head"
        if (CmsHost.wanted() || wasHead || Prefs.http().contains("127.0.0.1")) {
            CmsHost.stop()
            Prefs.token("")
            if (wasHead) {
                Prefs.cmsId("")
                Prefs.originCmsId("")
            }
            Prefs.acting(false)
            Prefs.followingActing(false)
            Prefs.etag("")
            Prefs.manifestJson("")
            if (Prefs.http().contains("127.0.0.1")) Prefs.http("")
            Prefs.deviceName("Screen")
            Prefs.followedHeadId(0)
        }
        if (headId == 0L) {
            main.post {
                if (!claimRunning) showClaim()
                hideClaimCode()
                setClaimStatus("This branch has no host yet.")
            }
            return
        }
        if (!claimRunning) {
            main.post { showClaim() }
        }
        val remembered = Prefs.http().trimEnd('/')
        val candidates = (seenBeacons.entries
            .filter { System.currentTimeMillis() - it.value < 15_000 }
            .map { it.key } + remembered)
            .filter { it.isNotBlank() && !it.contains("127.0.0.1") }
            .distinct()
        if (candidates.isEmpty()) {
            main.post {
                hideClaimCode()
                setClaimStatus(if (headName.isBlank()) "Looking for the branch host" else "Looking for host $headName")
            }
            return
        }
        for (base in candidates) {
            try {
                val joined = api.joinClaim(base, code)
                val token = joined.optString("device_token")
                if (token.isBlank()) continue
                Prefs.http(base)
                Prefs.cmsId(joined.optString("cms_id"))
                Prefs.originCmsId(Prefs.cmsId())
                Prefs.followingActing(false)
                Prefs.token(token)
                Prefs.deviceId(joined.optLong("device_id"))
                Prefs.deviceName("Screen")
                Prefs.headDeviceId(headId)
                Prefs.followedHeadId(headId)
                Prefs.etag("")
                main.post { if (claimRunning) showPlayer() }
                return
            } catch (_: Exception) {
            }
        }
        main.post {
            hideClaimCode()
            setClaimStatus(if (headName.isBlank()) "Looking for the branch host" else "Looking for host $headName")
        }
    }

    private fun becomeActing() {
        val dms = CmsHost.dmsPublicUrl()
        val code = Prefs.claimCode()
        val origin = Prefs.originCmsId().ifBlank { Prefs.cmsId() }
        if (dms.isBlank() || code.isBlank() || origin.isBlank()) {
            tempHead.retryLater()
            return
        }
        try {
            api.claimActing(dms, code)
        } catch (e: Exception) {
            val msg = e.message.orEmpty()
            if (msg.contains("already has a temp head") || msg.contains("Head is still reporting") || msg.contains("cannot become")) {
                releaseLocalActing()
            }
            tempHead.retryLater()
            return
        }
        tempHead.claimAccepted()
        Prefs.acting(true)
        Prefs.followingActing(false)
        CmsHost.start(this)
        if (!waitForCms()) {
            failActing(dms, code)
            return
        }
        try {
            val adopted = api.adoptActing("http://127.0.0.1:8080", code, origin)
            val token = adopted.optString("device_token")
            if (token.isBlank()) {
                failActing(dms, code)
                return
            }
            Prefs.http("http://127.0.0.1:8080")
            Prefs.token(token)
            Prefs.deviceId(adopted.optLong("device_id"))
            Prefs.deviceName("Screen")
            Prefs.etag("")
            main.post { if (claimRunning) showPlayer() }
        } catch (_: Exception) {
            failActing(dms, code)
        }
    }

    private fun failActing(dms: String, code: String) {
        try {
            api.clearActing(dms, code)
        } catch (_: Exception) {
        }
        CmsHost.stop()
        Prefs.acting(false)
        tempHead.retryLater()
    }

    private fun releaseLocalActing() {
        CmsHost.stop()
        Prefs.acting(false)
        Prefs.followingActing(false)
        Prefs.token("")
        Prefs.etag("")
        Prefs.manifestJson("")
        session?.dropManifest()
    }

    private fun handback(target: HostTarget) {
        val dms = CmsHost.dmsPublicUrl()
        val code = Prefs.claimCode()
        if (Prefs.acting() && dms.isNotBlank() && code.isNotBlank()) {
            try {
                api.clearActing(dms, code)
            } catch (_: Exception) {
            }
        }
        CmsHost.stop()
        Prefs.acting(false)
        Prefs.followingActing(false)
        Prefs.token("")
        Prefs.etag("")
        Prefs.manifestJson("")
        session?.dropManifest()
        Prefs.http(target.http)
        Prefs.deviceName("Screen")
        clock.setPort(target.clockPort)
        knownHead = false
        roleKnown = true
        becomeFollower(code, Prefs.headDeviceId(), knownHeadName)
    }

    private fun rejoinAt(target: HostTarget, keepCmsId: Boolean, following: Boolean) {
        val code = Prefs.claimCode()
        if (code.isBlank() || target.http.isBlank()) return
        try {
            val joined = api.joinClaim(target.http, code)
            val token = joined.optString("device_token")
            if (token.isBlank()) return
            Prefs.http(target.http)
            Prefs.token(token)
            Prefs.deviceId(joined.optLong("device_id"))
            Prefs.deviceName("Screen")
            Prefs.etag("")
            if (!keepCmsId) {
                Prefs.cmsId(joined.optString("cms_id"))
                Prefs.originCmsId(Prefs.cmsId())
            }
            Prefs.followingActing(following)
            Prefs.acting(false)
            Prefs.manifestJson("")
            session?.dropManifest()
            clock.setPort(target.clockPort)
            main.post { if (claimRunning) showPlayer() }
        } catch (_: Exception) {
        }
    }

    private fun waitForCms(): Boolean {
        for (i in 1..20) {
            if (CmsHost.ready()) return true
            Thread.sleep(500)
        }
        return CmsHost.ready()
    }

    private fun newClaimCode(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return buildString(6) {
            repeat(6) { append(alphabet[Random.nextInt(alphabet.length)]) }
        }
    }

    private fun fetchClaim(): JSONObject {
        return if (BuildConfig.START_CMS) {
            fetchOwnClaim()
        } else {
            val base = claimBase
            if (base.isBlank()) error("Looking for the branch host…")
            api.claimScreen(base, "Screen", claimToken)
        }
    }

    private fun renderClaim(result: JSONObject) {
        if (result.optBoolean("waiting_role")) {
            hideClaimCode()
            val current = findViewById<TextView>(R.id.status)?.text?.toString().orEmpty()
            if (!current.startsWith("Looking for") && current != "This branch has no host yet.") {
                setClaimStatus("Claimed. Checking whether this screen is the branch host.")
            }
            return
        }
        if (result.optString("reason") == "registering") {
            hideClaimCode()
            setClaimStatus("Registering this screen with DMS…")
            return
        }
        if (!result.optBoolean("ready")) {
            hideClaimCode()
            setClaimStatus(
                if (result.optString("reason") == "dms_url_missing") "The host has no DMS address yet." else "Cannot reach DMS right now.",
            )
            return
        }
        if (!BuildConfig.START_CMS && !result.optBoolean("head_claimed")) {
            hideClaimCode()
            findViewById<TextView>(R.id.claimInstructions).text =
                "Scan the host first. This screen shows a code only after the host is claimed."
            setClaimStatus("Scan the host first")
            return
        }
        val token = result.optString("device_token")
        if (token.isNotBlank()) claimToken = token
        if (result.optBoolean("claimed") && token.isNotBlank() && result.optString("cms_id").isNotBlank()) {
            val base = result.optString("http").ifBlank {
                if (BuildConfig.START_CMS) "http://127.0.0.1:8080" else claimBase
            }
            Prefs.http(base)
            Prefs.cmsId(result.optString("cms_id"))
            Prefs.originCmsId(Prefs.cmsId())
            Prefs.token(token)
            Prefs.deviceId(result.optLong("device_id"))
            Prefs.deviceName(if (BuildConfig.START_CMS) "Head" else "Screen")
            Prefs.etag("")
            stopClaim()
            showPlayer()
            return
        }
        val url = result.optString("url")
        val code = result.optString("code")
        if (url.isBlank() || code.isBlank()) {
            hideClaimCode()
            setClaimStatus("No claim code yet.")
            return
        }
        findViewById<TextView>(R.id.claimInstructions).text =
            "Scan the code with a phone that is signed in to DMS. Confirm the branch on the phone. Choose the video in DMS after that."
        if (url != shownClaimUrl) {
            findViewById<ImageView>(R.id.claimQr).setImageBitmap(ClaimQr.bitmap(url, 560))
            shownClaimUrl = url
        }
        findViewById<ImageView>(R.id.claimQr).visibility = View.VISIBLE
        findViewById<TextView>(R.id.claimCode).text = code
        setClaimStatus("")
    }

    private fun hideClaimCode() {
        shownClaimUrl = ""
        findViewById<ImageView>(R.id.claimQr)?.visibility = View.GONE
        findViewById<TextView>(R.id.claimCode)?.text = ""
    }

    private fun setClaimStatus(text: String) {
        findViewById<TextView>(R.id.status)?.text = text
    }

    private fun stopClaim() {
        claimRunning = false
        main.removeCallbacks(pollClaim)
    }

    private fun dropPairing() {
        Prefs.token("")
        Prefs.cmsId("")
        Prefs.originCmsId("")
        Prefs.dmsDeviceId(0)
        Prefs.acting(false)
        Prefs.followingActing(false)
        Prefs.deviceId(0)
        Prefs.etag("")
        Prefs.manifestJson("")
        Prefs.followedHeadId(0)
        running = false
        session?.release()
        session = null
        engine?.release()
        engine = null
        bus?.stop()
        bus = null
        showClaim()
    }

    private fun showPlayer() {
        stopClaim()
        beacon?.stop()
        setContentView(R.layout.activity_player)
        main.removeCallbacks(hideChrome)
        findViewById<View>(R.id.playerChrome)?.visibility = View.VISIBLE
        findViewById<View>(R.id.pointerCatcher).setOnHoverListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_HOVER_MOVE ||
                event.actionMasked == MotionEvent.ACTION_HOVER_ENTER
            ) {
                revealChrome()
            }
            false
        }
        findViewById<View>(R.id.systemSettings).setOnClickListener {
            revealChrome()
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (_: Exception) {
            }
        }
        val hud = findViewById<TextView>(R.id.hud)
        hud.setOnLongClickListener {
            Prefs.speedCatchup(!Prefs.speedCatchup())
            Toast.makeText(
                this,
                if (Prefs.speedCatchup()) "Speed catch-up ON (test only)" else "Speed catch-up OFF",
                Toast.LENGTH_SHORT,
            ).show()
            session?.let { it.refreshHud() }
            true
        }
        val view = findViewById<PlayerView>(R.id.playerView)
        val preload = findViewById<PlayerView>(R.id.preloadView)
        val still = findViewById<ImageView>(R.id.stillView)
        view.useController = false
        view.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        preload.useController = false
        engine?.release()
        val eng = PlayerEngine(
            this,
            onEnded = { session?.onEnded() },
            onReady = { session?.onReady() },
            onEvent = { type, severity, message -> session?.noteEvent(type, severity, message) },
        )
        engine = eng
        eng.attach(view, preload, still)
        eng.watchPicture { updateSplash() }
        session?.release()
        bus?.stop()
        val playBus = PlayBus(this) { msg -> main.post { session?.onPlayMsg(msg) } }
        playBus.start()
        bus = playBus
        val cache = MediaCache(this)
        val emptyMedia = findViewById<View>(R.id.emptyMedia)
        session = WallSession(eng, clock, playBus, api, cache) { line ->
            main.post {
                hud.text = listOf(line, updateHint).filter { it.isNotBlank() }.joinToString("\n")
                val hasMedia = session?.manifest?.items?.isNotEmpty() == true && session?.showingGap != true
                emptyMedia.visibility = if (hasMedia) View.GONE else View.VISIBLE
                updateSplash()
            }
        }
        clock.restore()
        api.parseCached(Prefs.manifestJson())?.let { session?.applyManifest(it) }
        updateSplash()
        beacon = BeaconListener(
            onBeacon = { b ->
                tempHead.noteBeacon(b)
                val origin = Prefs.originCmsId().ifBlank { Prefs.cmsId() }
                val fromOrigin = b.role != "acting" && (origin.isBlank() || b.cmsId == origin)
                val drifted = origin.isNotBlank() && Prefs.cmsId().isNotBlank() && Prefs.cmsId() != origin
                if (!fromOrigin || Prefs.acting() || Prefs.followingActing() || drifted) return@BeaconListener
                if (!httpOk || Prefs.http() != b.http) {
                    Prefs.http(b.http)
                    clock.setPort(b.clockPort)
                }
            },
            onFailover = { notice -> tempHead.noteFailover(notice) },
        ).also { it.start() }
        startLoops()
    }

    private fun updateSplash() {
        val splash = findViewById<View>(R.id.splash) ?: return
        val playing = engine?.snapshotPlaying == true && engine?.pictureOnScreen() == true
        val knownEmpty = session?.manifest != null && session?.manifest?.items?.isEmpty() == true
        if (playing || knownEmpty) splashDone = true
        val show = !splashDone
        splash.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return
        paintBrand()
        val bar = findViewById<ProgressBar>(R.id.splashBar) ?: return
        val time = findViewById<TextView>(R.id.splashTime)
        val total = session?.playlistMs ?: 0L
        val loaded = (session?.loadedMs ?: 0L).coerceIn(0L, if (total > 0) total else 0L)
        val downloading = total > 0L && loaded < total
        if (!downloading) {
            if (!bar.isIndeterminate) bar.isIndeterminate = true
            time?.text = ""
            return
        }
        if (bar.isIndeterminate) bar.isIndeterminate = false
        bar.max = 1000
        bar.progress = ((loaded * 1000) / total).toInt()
        time?.text = "${clockText(loaded)} / ${clockText(total)}"
    }

    private fun rememberBrand(man: Manifest) {
        if (man.brandBranch.isNotBlank()) Prefs.brandBranch(man.brandBranch)
        if (man.brandColor.isNotBlank()) Prefs.brandColor(man.brandColor)
        val logo = man.brandLogo
        val dest = File(filesDir, "brand-logo")
        if (logo.isBlank()) {
            if (dest.exists()) dest.delete()
            Prefs.brandLogo("")
            main.post { updateSplash() }
            return
        }
        if (Prefs.brandLogo() != logo || !dest.exists()) {
            val base = Prefs.http().trimEnd('/')
            val url = if (logo.startsWith("http")) logo else "$base$logo"
            try {
                api.download(url, dest)
                Prefs.brandLogo(logo)
            } catch (_: Exception) {
            }
        }
        main.post { updateSplash() }
    }

    private fun paintBrand() {
        val splash = findViewById<View>(R.id.splash) ?: return
        val color = brandColor(Prefs.brandColor())
        splash.setBackgroundColor(color)
        val ink = if (isDark(color)) Color.WHITE else Color.parseColor("#161513")
        findViewById<TextView>(R.id.splashBranch)?.let {
            it.text = Prefs.brandBranch()
            it.setTextColor(ink)
        }
        findViewById<TextView>(R.id.splashTime)?.setTextColor(ink)
        findViewById<ProgressBar>(R.id.splashBar)?.let { bar ->
            bar.progressTintList = ColorStateList.valueOf(ink)
            bar.progressBackgroundTintList = ColorStateList.valueOf(ink and 0x55FFFFFF)
        }
        val logo = findViewById<ImageView>(R.id.splashLogo) ?: return
        val file = File(filesDir, "brand-logo")
        if (!file.exists()) {
            logo.setImageDrawable(null)
            logo.visibility = View.GONE
            return
        }
        val stamp = file.lastModified()
        if (logo.tag == stamp) return
        val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return
        logo.setImageBitmap(bitmap)
        logo.tag = stamp
        logo.visibility = View.VISIBLE
    }

    private fun brandColor(raw: String): Int {
        val text = raw.trim()
        if (text.matches(Regex("#[0-9A-Fa-f]{6}"))) {
            return Color.parseColor(text)
        }
        return Color.parseColor("#2C3E50")
    }

    private fun isDark(color: Int): Boolean {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        return (r * 299 + g * 587 + b * 114) / 1000 < 160
    }

    private fun clockText(ms: Long): String {
        val seconds = ms / 1000
        return "%d:%02d".format(seconds / 60, seconds % 60)
    }

    private fun startLoops() {
        running = false
        worker?.join(200)
        running = true
        worker = Thread {
            var n = 0
            var lastClock = 0L
            var actingResumed = false
            while (running) {
                val now = System.currentTimeMillis()
                try {
                    if (n % 7 == 0 && Prefs.paired() && Prefs.deviceName() != "Head" && Prefs.claimCode().isNotBlank()) {
                        val dms = CmsHost.dmsPublicUrl()
                        if (dms.isNotBlank()) {
                            try {
                                val role = api.claimRole(dms, Prefs.claimCode())
                                val actingId = role.optLong("acting_device_id")
                                tempHead.noteRole(actingId)
                                if (Prefs.dmsDeviceId() == 0L) Prefs.dmsDeviceId(role.optLong("device_id"))
                                if (Prefs.acting() && CmsHost.wanted() && actingId != Prefs.dmsDeviceId()) {
                                    releaseLocalActing()
                                }
                            } catch (_: PairingRevoked) {
                                main.post { dropPairing() }
                                return@Thread
                            } catch (_: Exception) {
                            }
                        }
                    }
                    if (Prefs.acting() && !CmsHost.wanted() && (!actingResumed || tempHead.retryDue())) {
                        actingResumed = true
                        becomeActing()
                    }
                    val playing = session?.playing == true
                    val settling = session?.settling == true
                    val clockEveryMs = when {
                        playing -> 8000L
                        settling -> 250L
                        else -> 1000L
                    }
                    if (Prefs.http().isNotBlank() && now - lastClock >= clockEveryMs) {
                        lastClock = now
                        try {
                            clock.sample()
                            httpOk = true
                        } catch (_: Exception) {
                            httpOk = false
                        }
                    }
                    if (Prefs.http().isNotBlank() && Prefs.paired() && n % 2 == 0) {
                        try {
                            val man = api.manifest(
                                Prefs.http(),
                                Prefs.token(),
                                clock.offsetMs,
                                clock.rttMs,
                                session?.statusQuery().orEmpty(),
                            )
                            httpOk = true
                            val foreignCms = man != null && man.cmsId.isNotBlank() && man.cmsId != Prefs.cmsId()
                                && !Prefs.acting() && !Prefs.followingActing()
                            if (foreignCms) {
                                httpOk = false
                            } else {
                                if (man != null && !man.branchIsHead && Prefs.deviceName() == "Head" && CmsHost.wanted()) {
                                    knownHead = false
                                    if (man.branchHeadId != 0L) knownHeadId = man.branchHeadId
                                    if (man.branchHeadName.isNotBlank()) knownHeadName = man.branchHeadName
                                    roleKnown = true
                                }
                                if (man != null) {
                                    rememberBrand(man)
                                    session?.applyManifest(man)
                                }
                                session?.ensureDownloads()
                            }
                        } catch (_: PairingRevoked) {
                            main.post { dropPairing() }
                            return@Thread
                        } catch (_: Exception) {
                            httpOk = false
                        }
                    }
                    main.post { session?.tickPlayBus() }
                    if (n % 16 == 0) {
                        session?.tickCms()
                        if (Prefs.dmsDeviceId() == 0L && Prefs.claimCode().isNotBlank()) {
                            val dms = CmsHost.dmsPublicUrl()
                            if (dms.isNotBlank()) {
                                try {
                                    val role = api.claimRole(dms, Prefs.claimCode())
                                    Prefs.dmsDeviceId(role.optLong("device_id"))
                                    if (Prefs.originCmsId().isBlank() && Prefs.cmsId().isNotBlank()) {
                                        Prefs.originCmsId(Prefs.cmsId())
                                    }
                                } catch (_: PairingRevoked) {
                                    main.post { dropPairing() }
                                    return@Thread
                                } catch (_: Exception) {
                                }
                            }
                        }
                    }
                    tempHead.noteHttp(httpOk)
                    tempHead.tick()
                    val back = tempHead.takeHandback()
                    if (back.http.isNotBlank()) {
                        handback(back)
                    } else {
                        val join = tempHead.takeJoin()
                        if (join.http.isNotBlank()) {
                            rejoinAt(join, keepCmsId = true, following = true)
                        } else if (tempHead.consumeStart()) {
                            becomeActing()
                        }
                    }
                    if (n % 100 == 0 && Prefs.http().isNotBlank()) {
                        try {
                            val ver = api.appVersion(Prefs.http())
                            val code = ver.optInt("version_code", 0)
                            updateHint = if (code > BuildConfig.VERSION_CODE) {
                                "APK update v${ver.optString("version")} at ${ver.optString("apk_url")}"
                            } else {
                                ""
                            }
                        } catch (_: Exception) {
                        }
                    }
                } catch (e: Exception) {
                    main.post { findViewById<TextView>(R.id.hud)?.text = e.message }
                }
                n++
                try {
                    Thread.sleep(300)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }.also { it.isDaemon = true; it.start() }
    }

    private fun revealChrome() {
        val chrome = findViewById<View>(R.id.playerChrome) ?: return
        chrome.visibility = View.VISIBLE
        main.removeCallbacks(hideChrome)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_HOVER_MOVE ||
            ev.actionMasked == MotionEvent.ACTION_HOVER_ENTER
        ) {
            revealChrome()
        }
        return super.dispatchGenericMotionEvent(ev)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_MOVE || ev.actionMasked == MotionEvent.ACTION_HOVER_MOVE) {
            revealChrome()
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onDestroy() {
        running = false
        stopClaim()
        main.removeCallbacks(hideChrome)
        main.removeCallbacks(openWifiSettings)
        stopListeningForLan()
        worker?.interrupt()
        session?.release()
        beacon?.stop()
        bus?.stop()
        engine?.release()
        super.onDestroy()
    }
}
