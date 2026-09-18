package com.foodtale.signage

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class CmsApi {
    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun health(base: String): JSONObject {
        val req = Request.Builder().url("${base.trimEnd('/')}/api/health").get().build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("health ${resp.code}")
            return JSONObject(body)
        }
    }

    fun pair(base: String, code: String, name: String): JSONObject {
        val payload = JSONObject()
            .put("pairing_code", code.trim().uppercase())
            .put("device_name", name)
            .toString()
        val req = Request.Builder()
            .url("${base.trimEnd('/')}/api/pair")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error(body.ifBlank { "pair ${resp.code}" })
            return JSONObject(body)
        }
    }

    fun manifest(base: String, token: String, offsetMs: Long, rttMs: Long): Manifest? {
        val url = "${base.trimEnd('/')}/api/device/manifest?clock_offset_ms=$offsetMs&clock_rtt_ms=$rttMs"
        val reqBuilder = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .get()
        val etag = Prefs.etag()
        if (etag.isNotBlank()) {
            reqBuilder.header("If-None-Match", etag)
        }
        http.newCall(reqBuilder.build()).execute().use { resp ->
            if (resp.code == 304) return null
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error(body.ifBlank { "manifest ${resp.code}" })
            resp.header("ETag")?.let { Prefs.etag(it) }
            return parseManifest(JSONObject(body))
        }
    }

    fun heartbeat(base: String, token: String, payload: JSONObject) {
        val req = Request.Builder()
            .url("${base.trimEnd('/')}/api/device/heartbeat")
            .header("Authorization", "Bearer $token")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { }
    }

    fun appVersion(base: String): JSONObject {
        val req = Request.Builder().url("${base.trimEnd('/')}/api/app/version").get().build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("version ${resp.code}")
            return JSONObject(body)
        }
    }

    fun download(url: String, dest: File) {
        dest.parentFile?.mkdirs()
        val tmp = File(dest.absolutePath + ".part")
        val req = Request.Builder().url(url).get().build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("download ${resp.code}")
            resp.body?.byteStream()?.use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            } ?: error("empty body")
        }
        if (!tmp.renameTo(dest)) {
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }
    }

    private fun parseManifest(j: JSONObject): Manifest {
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
                    )
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
