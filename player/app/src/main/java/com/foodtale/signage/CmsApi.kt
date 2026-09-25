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

    fun manifest(base: String, token: String, offsetMs: Long, rttMs: Long, status: String = ""): Manifest? {
        val query = buildString {
            append("clock_offset_ms=$offsetMs&clock_rtt_ms=$rttMs")
            if (status.isNotBlank()) append("&").append(status)
        }
        val url = "${base.trimEnd('/')}/api/device/manifest?$query"
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
            return Manifest.fromJson(JSONObject(body))
        }
    }

    fun parseCached(raw: String): Manifest? {
        if (raw.isBlank()) return null
        return try {
            Manifest.fromJson(JSONObject(raw))
        } catch (_: Exception) {
            null
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
}
