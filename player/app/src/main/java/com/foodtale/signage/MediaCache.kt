package com.foodtale.signage

import android.content.Context
import java.io.File

class MediaCache(ctx: Context) {
    private val dir = File(ctx.filesDir, "media").apply { mkdirs() }

    fun fileFor(sha: String): File = File(dir, "$sha.mp4")

    fun has(sha: String): Boolean = fileFor(sha).isFile && fileFor(sha).length() > 32
}
