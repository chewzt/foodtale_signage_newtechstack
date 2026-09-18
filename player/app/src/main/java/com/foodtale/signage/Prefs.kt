package com.foodtale.signage

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings

object Prefs {
    private lateinit var p: SharedPreferences

    fun init(ctx: Context) {
        p = ctx.applicationContext.getSharedPreferences("foodtale", Context.MODE_PRIVATE)
        if (deviceName().isBlank()) {
            val model = Build.MODEL ?: "Android"
            deviceName(model)
        }
    }

    fun token(): String = p.getString("token", "") ?: ""
    fun token(v: String) { p.edit().putString("token", v).apply() }

    fun cmsId(): String = p.getString("cmsId", "") ?: ""
    fun cmsId(v: String) { p.edit().putString("cmsId", v).apply() }

    fun http(): String = p.getString("http", "") ?: ""
    fun http(v: String) { p.edit().putString("http", v.trimEnd('/')).apply() }

    fun deviceId(): Long = p.getLong("deviceId", 0)
    fun deviceId(v: Long) { p.edit().putLong("deviceId", v).apply() }

    fun deviceName(): String = p.getString("deviceName", "") ?: ""
    fun deviceName(v: String) { p.edit().putString("deviceName", v).apply() }

    fun speedCatchup(): Boolean = p.getBoolean("speedCatchup", false)
    fun speedCatchup(v: Boolean) { p.edit().putBoolean("speedCatchup", v).apply() }

    fun etag(): String = p.getString("etag", "") ?: ""
    fun etag(v: String) { p.edit().putString("etag", v).apply() }

    fun paired(): Boolean = token().isNotBlank() && cmsId().isNotBlank()

    fun clearPair() {
        p.edit().remove("token").remove("deviceId").apply()
    }

    fun androidId(ctx: Context): String =
        Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
}
