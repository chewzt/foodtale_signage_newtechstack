package com.foodtale.signage

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import java.io.File
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Starts the Go CMS only while this device is the branch head.
 * A second start does nothing while 8080 is already open.
 */
object CmsHost {
    private const val TAG = "CmsHost"
    private val gate = Any()
    @Volatile private var want = false
    private var child: Process? = null
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
                        } else if (child?.isAlive != true && !portOpen()) {
                            child = launch(ctx)
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

    fun dmsPublicUrl(): String =
        readEnvFile(File("/data/local/tmp/foodtale/cms.env"))["DMS_PUBLIC_URL"]?.trim()?.trimEnd('/') ?: ""

    private fun stopChild() {
        val proc = child
        child = null
        proc?.destroy()
        if (proc?.isAlive == true) proc.destroyForcibly()
    }

    private fun launch(context: Context): Process? {
        if (portOpen()) return null
        val lib = File(context.applicationInfo.nativeLibraryDir, "libfoodtale_cms.so")
        if (!lib.exists()) {
            Log.e(TAG, "missing ${lib.absolutePath}")
            return null
        }
        val dir = File(context.filesDir, "cms")
        dir.mkdirs()
        val preset = Prefs.installationId()
        if (preset.isNotBlank()) writeIdIfMissing(dir, preset)
        File(dir, "media").mkdirs()
        File(dir, "public").mkdirs()
        adoptShellData(dir)
        val env = mutableMapOf<String, String>()
        env["DATA_DIR"] = dir.absolutePath
        env["HTTP_PORT"] = "8080"
        env["CLOCK_PORT"] = "8123"
        env["BEACON_PORT"] = "48720"
        val saved = readEnvFile(File("/data/local/tmp/foodtale/cms.env"))
        saved["CMS_ID"]?.let { writeIdIfMissing(dir, it) }
        saved["ADMIN_PASSWORD"]?.let { env["ADMIN_PASSWORD"] = it }
        saved["DMS_PUBLIC_URL"]?.let { env["DMS_PUBLIC_URL"] = it }
        advertiseHttp(context)?.let { env["HTTP_ADVERTISE"] = it }
        readId(dir)?.let { env["CMS_ID"] = it }
        val pb = ProcessBuilder(lib.absolutePath)
        pb.environment().putAll(env)
        pb.redirectErrorStream(true)
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(File(dir, "cms.log")))
        val child = pb.start()
        Log.i(TAG, "cms started data=${dir.absolutePath}")
        return child
    }

    private fun advertiseHttp(context: Context): String? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val props = cm.getLinkProperties(cm.activeNetwork ?: return null) ?: return null
        val ip = props.linkAddresses.firstOrNull { link ->
            val addr = link.address
            addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress
        }?.address?.hostAddress ?: return null
        return "http://$ip:8080"
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

    private fun adoptShellData(dir: File) {
        if (File(dir, "cms.db").exists()) return
        val src = File("/data/local/tmp/foodtale")
        val db = File(src, "cms.db")
        if (!db.canRead()) return
        db.copyTo(File(dir, "cms.db"), overwrite = false)
        val id = File(src, "cms.id")
        if (id.canRead()) id.copyTo(File(dir, "cms.id"), overwrite = false)
        val media = File(src, "media")
        media.listFiles()?.forEach { f ->
            if (f.isFile && f.canRead()) f.copyTo(File(dir, "media/${f.name}"), overwrite = false)
        }
    }

    private fun readId(dir: File): String? {
        val f = File(dir, "cms.id")
        if (!f.canRead()) return null
        val id = f.readText().trim()
        return id.ifBlank { null }
    }

    private fun writeIdIfMissing(dir: File, id: String) {
        val f = File(dir, "cms.id")
        if (f.exists() || id.isBlank()) return
        f.writeText(id.trim() + "\n")
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
