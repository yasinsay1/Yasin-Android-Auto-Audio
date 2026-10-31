package com.yasincarplay.audio.data

import org.json.JSONArray
import java.io.IOException
import java.net.URLEncoder

/**
 * İnternet radyosu istasyonlarını herkese açık Radio Browser API'sinden (radio-browser.info) alır.
 * Sunucu adreslerinden biri yanıt vermezse sıradakine geçilir.
 */
object RadioRepository {
    private val servers = listOf(
        "https://de1.api.radio-browser.info",
        "https://nl1.api.radio-browser.info",
        "https://at1.api.radio-browser.info",
    )

    @Throws(IOException::class)
    fun top(countryCode: String = "TR", limit: Int = 50): List<Station> =
        query("countrycode=$countryCode&order=votes&reverse=true&limit=$limit&hidebroken=true")

    @Throws(IOException::class)
    fun search(text: String, limit: Int = 50): List<Station> {
        val encoded = URLEncoder.encode(text, "UTF-8")
        return query("name=$encoded&order=votes&reverse=true&limit=$limit&hidebroken=true")
    }

    private fun query(params: String): List<Station> {
        var lastError: Exception? = null
        for (server in servers) {
            try {
                return parse(Http.getString("$server/json/stations/search?$params"))
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw IOException("Radyo sunucularına ulaşılamadı", lastError)
    }

    private fun parse(body: String): List<Station> {
        val array = JSONArray(body)
        val out = ArrayList<Station>(array.length())
        for (i in 0 until array.length()) {
            val o = array.getJSONObject(i)
            val name = o.optString("name").trim()
            val url = o.optString("url_resolved").ifBlank { o.optString("url") }.trim()
            if (name.isEmpty() || url.isEmpty()) continue
            out.add(
                Station(
                    id = o.optString("stationuuid").ifBlank { url.hashCode().toString(16) },
                    name = name,
                    streamUrl = url,
                    country = o.optString("countrycode"),
                    tags = o.optString("tags"),
                )
            )
        }
        return out
    }
}
