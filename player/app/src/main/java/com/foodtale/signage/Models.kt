package com.foodtale.signage

import org.json.JSONArray
import org.json.JSONObject

data class Beacon(
    val v: Int,
    val service: String,
    val cmsId: String,
    val http: String,
    val clockPort: Int,
)

data class Peer(val id: Long, val name: String, val ip: String = "")

data class MediaItem(
    val id: Long,
    val type: String,
    val url: String,
    val sha256: String,
    val durationMs: Int,
    val fit: String,
    val fileDurationMs: Int,
)

data class Manifest(
    val playlistName: String,
    val playlistId: Long,
    val deviceId: Long,
    val cmsId: String,
    val kind: String,
    val panelCount: Int,
    val panelIndex: Int,
    val peerCount: Int,
    val peers: List<Peer>,
    val startAt: String,
    val startMasterMs: Long,
    val syncGeneration: Long,
    val items: List<MediaItem>,
) {
    val group: String get() = "p$playlistId|$syncGeneration"
    val leaderId: Long get() = peers.minOfOrNull { it.id } ?: deviceId
    val isLeader: Boolean get() = deviceId == leaderId

    fun toJson(): JSONObject {
        val peerArr = JSONArray()
        for (peer in peers) {
            peerArr.put(
                JSONObject()
                    .put("id", peer.id)
                    .put("name", peer.name)
                    .put("ip", peer.ip),
            )
        }
        val itemArr = JSONArray()
        for (item in items) {
            itemArr.put(
                JSONObject()
                    .put("id", item.id)
                    .put("type", item.type)
                    .put("url", item.url)
                    .put("sha256", item.sha256)
                    .put("duration_ms", item.durationMs)
                    .put("fit", item.fit)
                    .put("file_duration_ms", item.fileDurationMs),
            )
        }
        return JSONObject()
            .put("playlist_name", playlistName)
            .put("playlist_id", playlistId)
            .put("device_id", deviceId)
            .put("cms_id", cmsId)
            .put("kind", kind)
            .put("panel_count", panelCount)
            .put("panel_index", panelIndex)
            .put("peer_count", peerCount)
            .put("peers", peerArr)
            .put("start_at", startAt)
            .put("start_master_ms", startMasterMs)
            .put("sync_generation", syncGeneration)
            .put("items", itemArr)
    }

    companion object {
        fun fromJson(j: JSONObject): Manifest {
            val peers = j.optJSONArray("peers")
            val peerList = mutableListOf<Peer>()
            if (peers != null) {
                for (i in 0 until peers.length()) {
                    val p = peers.getJSONObject(i)
                    peerList.add(Peer(p.optLong("id"), p.optString("name"), p.optString("ip")))
                }
            }
            val items = j.optJSONArray("items")
            val itemList = mutableListOf<MediaItem>()
            if (items != null) {
                for (i in 0 until items.length()) {
                    val it = items.getJSONObject(i)
                    itemList.add(
                        MediaItem(
                            id = it.optLong("id"),
                            type = it.optString("type"),
                            url = it.optString("url"),
                            sha256 = it.optString("sha256"),
                            durationMs = it.optInt("duration_ms", 10_000),
                            fit = it.optString("fit", "cut"),
                            fileDurationMs = it.optInt("file_duration_ms", it.optInt("duration_ms", 10_000)),
                        ),
                    )
                }
            }
            return Manifest(
                playlistName = j.optString("playlist_name"),
                playlistId = j.optLong("playlist_id"),
                deviceId = j.optLong("device_id"),
                cmsId = j.optString("cms_id"),
                kind = j.optString("kind", "playlist"),
                panelCount = j.optInt("panel_count", 1),
                panelIndex = j.optInt("panel_index", 0),
                peerCount = j.optInt("peer_count", 1),
                peers = peerList,
                startAt = j.optString("start_at"),
                startMasterMs = j.optLong("start_master_ms"),
                syncGeneration = j.optLong("sync_generation"),
                items = itemList,
            )
        }
    }
}

data class PlayMsg(
    val v: Int,
    val action: String,
    val group: String,
    val itemId: String,
    val index: Int,
    val targetEpoch: Long,
    val posMs: Long,
    val sendTime: Long,
)
