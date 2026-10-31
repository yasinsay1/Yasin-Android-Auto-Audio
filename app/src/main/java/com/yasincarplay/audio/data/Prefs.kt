package com.yasincarplay.audio.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Favori radyolar ve podcast abonelikleri (SharedPreferences içinde JSON olarak saklanır). */
class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("yasin_audio", Context.MODE_PRIVATE)

    // --- Favori radyolar -------------------------------------------------

    fun favorites(): List<Station> = readArray(KEY_FAVORITES).map {
        Station(
            id = it.optString("id"),
            name = it.optString("name"),
            streamUrl = it.optString("url"),
            country = it.optString("country"),
            tags = it.optString("tags"),
        )
    }

    fun isFavorite(stationId: String): Boolean = favorites().any { it.id == stationId }

    /** Favori durumunu tersine çevirir; yeni durumu (favori mi) döndürür. */
    fun toggleFavorite(station: Station): Boolean {
        val current = favorites().toMutableList()
        val existing = current.indexOfFirst { it.id == station.id }
        val nowFavorite = existing < 0
        if (nowFavorite) current.add(station) else current.removeAt(existing)
        writeArray(
            KEY_FAVORITES,
            current.map {
                JSONObject()
                    .put("id", it.id)
                    .put("name", it.name)
                    .put("url", it.streamUrl)
                    .put("country", it.country)
                    .put("tags", it.tags)
            },
        )
        return nowFavorite
    }

    // --- Podcast abonelikleri --------------------------------------------

    fun subscriptions(): List<Podcast> = readArray(KEY_SUBSCRIPTIONS).map {
        Podcast(
            feedUrl = it.optString("feed"),
            title = it.optString("title"),
            author = it.optString("author"),
            artworkUrl = it.optString("artwork"),
        )
    }

    fun isSubscribed(feedUrl: String): Boolean = subscriptions().any { it.feedUrl == feedUrl }

    /** Aboneliği tersine çevirir; yeni durumu (abone mi) döndürür. */
    fun toggleSubscription(podcast: Podcast): Boolean {
        val current = subscriptions().toMutableList()
        val existing = current.indexOfFirst { it.feedUrl == podcast.feedUrl }
        val nowSubscribed = existing < 0
        if (nowSubscribed) current.add(podcast) else current.removeAt(existing)
        writeArray(
            KEY_SUBSCRIPTIONS,
            current.map {
                JSONObject()
                    .put("feed", it.feedUrl)
                    .put("title", it.title)
                    .put("author", it.author)
                    .put("artwork", it.artworkUrl)
            },
        )
        return nowSubscribed
    }

    // --- Yardımcılar -------------------------------------------------------

    private fun readArray(key: String): List<JSONObject> {
        val raw = sp.getString(key, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map { array.getJSONObject(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun writeArray(key: String, items: List<JSONObject>) {
        val array = JSONArray()
        items.forEach { array.put(it) }
        sp.edit().putString(key, array.toString()).apply()
    }

    private companion object {
        const val KEY_FAVORITES = "favorites"
        const val KEY_SUBSCRIPTIONS = "subscriptions"
    }
}
