package com.foodtale.signage

import android.os.SystemClock
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.charset.StandardCharsets

class ClockClient(
    private val hostProvider: () -> String,
    private var port: Int = 8123,
) {
    @Volatile var offsetMs: Long = 0
        private set
    @Volatile var rttMs: Long = 0
        private set
    @Volatile var lastOkAt: Long = 0
        private set

    fun syncedNow(): Long = SystemClock.elapsedRealtime() + offsetMs

    fun elapsed(): Long = SystemClock.elapsedRealtime()

    fun setPort(p: Int) {
        if (p > 0) port = p
    }

    fun sample() {
        val host = hostFromUrl(hostProvider()) ?: return
        val t1 = SystemClock.elapsedRealtime()
        val req = JSONObject().put("v", 1).put("t1", t1).toString().toByteArray(StandardCharsets.UTF_8)
        DatagramSocket().use { sock ->
            sock.soTimeout = 800
            sock.send(DatagramPacket(req, req.size, InetAddress.getByName(host), port))
            val buf = ByteArray(2048)
            val pkt = DatagramPacket(buf, buf.size)
            sock.receive(pkt)
            val t3 = SystemClock.elapsedRealtime()
            val json = JSONObject(String(pkt.data, 0, pkt.length, StandardCharsets.UTF_8))
            val tMaster = json.optLong("tMaster")
            val t2send = json.optLong("t2send")
            offsetMs = ((tMaster - t1) + (t2send - t3)) / 2
            rttMs = t3 - t1
            lastOkAt = t3
        }
    }

    companion object {
        fun hostFromUrl(http: String): String? {
            val t = http.trim().removePrefix("http://").removePrefix("https://")
            if (t.isBlank()) return null
            return t.substringBefore("/").substringBefore(":")
        }
    }
}
