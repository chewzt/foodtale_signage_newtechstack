package com.foodtale.signage

import android.content.Context
import android.util.Log
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Starts the in-process shop server while this device is Head or Temp Head.
 * Followers still call 127.0.0.1:8080 on the host and the LAN address from the beacon.
 */
object CmsHost {
    private const val TAG = "CmsHost"
    private val gate = Any()
    @Volatile private var want = false
    private var shop: ShopServer? = null
    private var thread: Thread? = null
    private var app: Context? = null

    fun start(context: Context) {
        synchronized(gate) {
            app = context.applicationContext
            want = true
            if (thread?.isAlive == true) return
            thread = Thread {
                while (true) {
                    val ctx = app ?: break
                    try {
                        if (!want) {
                            stopChild()
                        } else if (shop == null && !portOpen()) {
                            launch(ctx)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "cms start failed", e)
                    }
                    Thread.sleep(1_000)
                }
            }.also { it.isDaemon = true; it.start() }
        }
    }

    fun stop() {
        want = false
        stopChild()
    }

    fun wanted(): Boolean = want

    fun ready(): Boolean = want && portOpen()

    fun dmsPublicUrl(): String {
        val baked = BuildConfig.DMS_PUBLIC_URL.trim().trimEnd('/')
        if (baked.isNotEmpty()) return baked
        return readEnvFile(File("/data/local/tmp/foodtale/cms.env"))["DMS_PUBLIC_URL"]?.trim()?.trimEnd('/') ?: ""
    }

    private fun stopChild() {
        val running = shop
        shop = null
        running?.stop()
    }

    private fun launch(context: Context) {
        if (portOpen()) return
        val server = ShopServer(context.applicationContext)
        server.start()
        shop = server
        Log.i(TAG, "shop server started")
    }

    private fun portOpen(): Boolean {
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress("127.0.0.1", 8080), 200)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun readEnvFile(file: File): Map<String, String> {
        if (!file.canRead()) return emptyMap()
        val out = mutableMapOf<String, String>()
        file.readLines().forEach { line ->
            val raw = line.trim()
            if (raw.isEmpty() || raw.startsWith("#")) return@forEach
            val eq = raw.indexOf('=')
            if (eq <= 0) return@forEach
            val key = raw.substring(0, eq).trim()
            var value = raw.substring(eq + 1).trim()
            if (value.length >= 2 && value.first() == '"' && value.last() == '"') {
                value = value.substring(1, value.length - 1)
            }
            out[key] = value
        }
        return out
    }
}
