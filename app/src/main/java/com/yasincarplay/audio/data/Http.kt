package com.yasincarplay.audio.data

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Uygulama genelinde paylaşılan HTTP istemcisi (API istekleri ve ExoPlayer için). */
object Http {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    @Throws(IOException::class)
    fun getString(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "YasinAndroidAutoAudio/1.0")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $url")
            return response.body?.string() ?: throw IOException("Boş yanıt: $url")
        }
    }
}
