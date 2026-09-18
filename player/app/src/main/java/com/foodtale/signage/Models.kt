package com.foodtale.signage

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
