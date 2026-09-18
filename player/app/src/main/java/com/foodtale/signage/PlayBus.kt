package com.foodtale.signage

import android.content.Context
import android.net.wifi.WifiManager
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

class PlayBus(
    ctx: Context,
    private val port: Int = 48721,
    private val onMsg: (PlayMsg) -> Unit,
) {
    private val app = ctx.applicationContext
    @Volatile private var running = false
    @Volatile var recvCount: Long = 0
        private set
    private var thread: Thread? = null
    private var sendSock: DatagramSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    fun start() {
        if (running) return
        running = true
        acquireMulticast()
        sendSock = DatagramSocket().apply { broadcast = true }
        thread = Thread {
            DatagramSocket(null).use { sock ->
                sock.reuseAddress = true
                sock.broadcast = true
                sock.bind(InetSocketAddress(port))
                sock.soTimeout = 1000
                val buf = ByteArray(4096)
                while (running) {
                    try {
                        val pkt = DatagramPacket(buf, buf.size)
                        sock.receive(pkt)
                        val json = JSONObject(String(pkt.data, 0, pkt.length, StandardCharsets.UTF_8))
                        recvCount += 1
                        onMsg(
                            PlayMsg(
                                v = json.optInt("v", 1),
                                action = json.optString("action"),
                                group = json.optString("group"),
                                itemId = json.optString("itemId"),
                                index = json.optInt("index", 0),
                                targetEpoch = json.optLong("targetEpoch"),
                                posMs = json.optLong("posMs"),
                                sendTime = json.optLong("sendTime"),
                            )
                        )
                    } catch (_: Exception) {
                    }
                }
            }
        }.also { it.isDaemon = true; it.start() }
    }

    fun send(msg: PlayMsg, extraHosts: Collection<String> = emptyList()) {
        val json = JSONObject()
            .put("v", msg.v)
            .put("action", msg.action)
            .put("group", msg.group)
            .put("itemId", msg.itemId)
            .put("index", msg.index)
            .put("targetEpoch", msg.targetEpoch)
            .put("posMs", msg.posMs)
            .put("sendTime", msg.sendTime)
            .toString()
            .toByteArray(StandardCharsets.UTF_8)
        val dests = LinkedHashSet<InetAddress>()
        try {
            dests.add(InetAddress.getByName("255.255.255.255"))
        } catch (_: Exception) {
        }
        subnetBroadcast()?.let { dests.add(it) }
        for (host in extraHosts) {
            val h = host.trim()
            if (h.isBlank() || h == "127.0.0.1" || h == "::1") continue
            try {
                dests.add(InetAddress.getByName(h))
            } catch (_: Exception) {
            }
        }
        for (addr in dests) {
            try {
                sendSock?.send(DatagramPacket(json, json.size, addr, port))
            } catch (_: Exception) {
            }
        }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        sendSock?.close()
        sendSock = null
        try {
            multicastLock?.release()
        } catch (_: Exception) {
        }
        multicastLock = null
    }

    private fun acquireMulticast() {
        try {
            val wifi = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val lock = wifi.createMulticastLock("foodtale-playbus")
            lock.setReferenceCounted(false)
            lock.acquire()
            multicastLock = lock
        } catch (_: Exception) {
        }
    }

    @Suppress("DEPRECATION")
    private fun subnetBroadcast(): InetAddress? {
        return try {
            val wifi = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val dhcp = wifi.dhcpInfo ?: return null
            if (dhcp.ipAddress == 0) return null
            val mask = if (dhcp.netmask != 0) dhcp.netmask else 0x00FFFFFF
            val bcast = (dhcp.ipAddress and mask) or mask.inv()
            val bytes = byteArrayOf(
                (bcast and 0xff).toByte(),
                (bcast shr 8 and 0xff).toByte(),
                (bcast shr 16 and 0xff).toByte(),
                (bcast shr 24 and 0xff).toByte(),
            )
            InetAddress.getByAddress(bytes)
        } catch (_: Exception) {
            null
        }
    }
}
