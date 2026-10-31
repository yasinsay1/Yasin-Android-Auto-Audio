package com.yasincarplay.audio.data

/** İnternet radyosu istasyonu. */
data class Station(
    val id: String,
    val name: String,
    val streamUrl: String,
    val country: String = "",
    val tags: String = "",
)

/** Abone olunabilen podcast (RSS beslemesi). */
data class Podcast(
    val feedUrl: String,
    val title: String,
    val author: String = "",
    val artworkUrl: String = "",
)

/** Bir podcast bölümü. */
data class Episode(
    val id: String,
    val title: String,
    val audioUrl: String,
    val podcastTitle: String,
    val durationMs: Long = 0L,
)
