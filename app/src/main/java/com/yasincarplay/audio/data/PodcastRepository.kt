package com.yasincarplay.audio.data

import android.util.Xml
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.io.StringReader
import java.net.URLEncoder

/**
 * Podcast arama: Apple iTunes Search API. Bölümler: podcast'in RSS beslemesinden okunur.
 */
object PodcastRepository {

    @Throws(IOException::class)
    fun search(text: String, limit: Int = 30, country: String? = null): List<Podcast> {
        val encoded = URLEncoder.encode(text, "UTF-8")
        val countryPart = if (country != null) "&country=$country" else ""
        val body = Http.getString(
            "https://itunes.apple.com/search?media=podcast&entity=podcast&limit=$limit&term=$encoded$countryPart"
        )
        val results = JSONObject(body).optJSONArray("results") ?: return emptyList()
        val out = ArrayList<Podcast>(results.length())
        for (i in 0 until results.length()) {
            val o = results.getJSONObject(i)
            val feed = o.optString("feedUrl").trim()
            val title = o.optString("collectionName").trim()
            if (feed.isEmpty() || title.isEmpty()) continue
            out.add(
                Podcast(
                    feedUrl = feed,
                    title = title,
                    author = o.optString("artistName"),
                    artworkUrl = o.optString("artworkUrl600").ifBlank { o.optString("artworkUrl100") },
                )
            )
        }
        return out
    }

    @Throws(IOException::class)
    fun episodes(feedUrl: String, limit: Int = 50): List<Episode> {
        val xml = Http.getString(feedUrl)
        return try {
            parseFeed(xml, limit)
        } catch (e: Exception) {
            throw IOException("Podcast beslemesi okunamadı", e)
        }
    }

    private fun parseFeed(xml: String, limit: Int): List<Episode> {
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(xml))
        val out = ArrayList<Episode>()
        var channelTitle = ""
        var inItem = false
        var title = ""
        var audio = ""
        var durationMs = 0L
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT && out.size < limit) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "item" -> {
                        inItem = true
                        title = ""
                        audio = ""
                        durationMs = 0L
                    }
                    "title" -> {
                        val text = parser.nextText().trim()
                        if (inItem) title = text else if (channelTitle.isEmpty()) channelTitle = text
                    }
                    "enclosure" -> if (inItem) audio = parser.getAttributeValue(null, "url").orEmpty()
                    "itunes:duration" -> if (inItem) durationMs = parseDurationMs(parser.nextText())
                }
            } else if (event == XmlPullParser.END_TAG && parser.name == "item") {
                inItem = false
                if (audio.isNotBlank()) {
                    out.add(
                        Episode(
                            id = Integer.toHexString(audio.hashCode()),
                            title = title.ifBlank { channelTitle },
                            audioUrl = audio,
                            podcastTitle = channelTitle,
                            durationMs = durationMs,
                        )
                    )
                }
            }
            event = parser.next()
        }
        return out
    }

    /** "HH:MM:SS", "MM:SS" veya düz saniye değerini milisaniyeye çevirir; anlaşılmazsa 0. */
    internal fun parseDurationMs(raw: String): Long {
        val parts = raw.trim().split(":").map { it.trim().toLongOrNull() ?: return 0L }
        val seconds = when (parts.size) {
            1 -> parts[0]
            2 -> parts[0] * 60 + parts[1]
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            else -> 0L
        }
        return seconds * 1000L
    }
}
