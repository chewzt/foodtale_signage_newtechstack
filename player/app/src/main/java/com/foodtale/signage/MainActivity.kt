package com.foodtale.signage

import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

class MainActivity : AppCompatActivity() {
    private val main = Handler(Looper.getMainLooper())
    private val api = CmsApi()
    private lateinit var clock: ClockClient
    private var bus: PlayBus? = null
    private var beacon: BeaconListener? = null
    private var session: WallSession? = null
    private var engine: PlayerEngine? = null
    private var worker: Thread? = null
    @Volatile private var running = false
    @Volatile private var httpOk = false
    @Volatile private var updateHint = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        enterImmersive()
        startForegroundService(Intent(this, PlayerService::class.java))
        requestIgnoreBattery()
        clock = ClockClient({ Prefs.http() })
        if (Prefs.paired()) showPlayer() else showPair()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersive()
    }

    override fun onResume() {
        super.onResume()
        enterImmersive()
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

    private fun showPair() {
        setContentView(R.layout.activity_pair)
        val status = findViewById<TextView>(R.id.status)
        val url = findViewById<EditText>(R.id.serverUrl)
        val code = findViewById<EditText>(R.id.pairingCode)
        val name = findViewById<EditText>(R.id.deviceName)
        url.setText(Prefs.http())
        name.setText(Prefs.deviceName())
        beacon?.stop()
        beacon = BeaconListener { b ->
            main.post {
                if (url.text.isBlank()) url.setText(b.http)
                status.text = "Found Pi ${b.http}  cmsId=${b.cmsId.take(8)}…"
            }
            if (Prefs.http().isBlank()) Prefs.http(b.http)
        }.also { it.start() }

        findViewById<Button>(R.id.pair).setOnClickListener {
            val base = url.text.toString().trim().ifBlank { Prefs.http() }
            val pairing = code.text.toString()
            if (base.isBlank() || pairing.isBlank()) {
                Toast.makeText(this, "Need URL or beacon, and a pairing code", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            Thread {
                try {
                    val health = api.health(base)
                    val result = api.pair(base, pairing, name.text.toString().ifBlank { Prefs.deviceName() })
                    Prefs.http(result.optString("http", base))
                    Prefs.cmsId(result.optString("cms_id", health.optString("cms_id")))
                    Prefs.token(result.getString("device_token"))
                    Prefs.deviceId(result.optLong("device_id"))
                    Prefs.deviceName(name.text.toString())
                    Prefs.etag("")
                    main.post { showPlayer() }
                } catch (e: Exception) {
                    main.post { Toast.makeText(this, e.message, Toast.LENGTH_LONG).show() }
                }
            }.start()
        }
    }

    private fun showPlayer() {
        beacon?.stop()
        setContentView(R.layout.activity_player)
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
        view.useController = false
        view.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        engine?.release()
        val eng = PlayerEngine(
            this,
            onEnded = { session?.onEnded() },
            onReady = { session?.onReady() },
        )
        engine = eng
        view.player = eng.player
        bus?.stop()
        val playBus = PlayBus(this) { msg -> main.post { session?.onPlayMsg(msg) } }
        playBus.start()
        bus = playBus
        val cache = MediaCache(this)
        session = WallSession(eng, clock, playBus, api, cache) { line ->
            main.post { hud.text = listOf(line, updateHint).filter { it.isNotBlank() }.joinToString("\n") }
        }
        beacon = BeaconListener { b ->
            if (Prefs.cmsId().isNotBlank() && b.cmsId != Prefs.cmsId()) return@BeaconListener
            if (!httpOk || Prefs.http() != b.http) {
                Prefs.http(b.http)
                clock.setPort(b.clockPort)
            }
        }.also { it.start() }
        startLoops()
    }

    private fun startLoops() {
        running = false
        worker?.join(200)
        running = true
        worker = Thread {
            var n = 0
            var lastClock = 0L
            while (running) {
                val now = System.currentTimeMillis()
                try {
                    val playing = session?.playing == true
                    val clockEveryMs = if (playing) 5000L else 1000L
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
                            val man = api.manifest(Prefs.http(), Prefs.token(), clock.offsetMs, clock.rttMs)
                            httpOk = true
                            if (man != null) {
                                if (man.cmsId.isNotBlank() && man.cmsId != Prefs.cmsId()) {
                                    httpOk = false
                                } else {
                                    session?.applyManifest(man)
                                    session?.ensureDownloads()
                                }
                            }
                        } catch (_: Exception) {
                            httpOk = false
                        }
                    }
                    main.post { session?.tickPlayBus() }
                    if (n % 16 == 0) {
                        session?.tickCms()
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

    override fun onDestroy() {
        running = false
        worker?.interrupt()
        beacon?.stop()
        bus?.stop()
        engine?.release()
        super.onDestroy()
    }
}
