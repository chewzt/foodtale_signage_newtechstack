package com.foodtale.signage

import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

class BeaconListener(
    private val port: Int = 48720,
    private val onBeacon: (Beacon) -> Unit,
) {
    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread {
            DatagramSocket(null).use { sock ->
                sock.reuseAddress = true
                sock.bind(InetSocketAddress(port))
                sock.soTimeout = 1500
                val buf = ByteArray(2048)
                while (running) {
                    try {
                        val pkt = DatagramPacket(buf, buf.size)
                        sock.receive(pkt)
                        val json = JSONObject(String(pkt.data, 0, pkt.length, StandardCharsets.UTF_8))
                        if (json.optString("service") != "foodtale-cms") continue
                        onBeacon(
                            Beacon(
                                v = json.optInt("v", 1),
                                service = json.optString("service"),
                                cmsId = json.optString("cmsId"),
                                http = json.optString("http").trimEnd('/'),
                                clockPort = json.optInt("clockPort", 8123),
                            )
                        )
                    } catch (_: Exception) {
                    }
                }
            }
        }.also { it.isDaemon = true; it.start() }
    }

    fun stop() {
        running = false
        thread?.interrupt()
    }
}
