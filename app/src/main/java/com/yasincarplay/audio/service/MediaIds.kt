package com.yasincarplay.audio.service

/** Medya tarayıcı ağacındaki düğüm kimlikleri ve öğe ek bilgi (extras) anahtarları. */
object MediaIds {
    const val ROOT = "yasin_root"

    const val RADIO = "radio"
    const val RADIO_FAVORITES = "radio_favorites"
    const val RADIO_TOP = "radio_top"

    const val PODCASTS = "podcasts"
    const val PODCAST_SUBS = "podcast_subs"
    const val PODCAST_DISCOVER = "podcast_discover"

    /** Çalınabilir radyo: "station:<id>" */
    const val PREFIX_STATION = "station:"

    /** Gezilebilir podcast: "podcast:<feedUrl>" (çocukları bölümlerdir) */
    const val PREFIX_PODCAST = "podcast:"

    /** Çalınabilir bölüm: "episode:<id>" */
    const val PREFIX_EPISODE = "episode:"

    const val EXTRA_KIND = "yasin.kind"
    const val EXTRA_STATION_ID = "yasin.station_id"
    const val EXTRA_URL = "yasin.url"
    const val EXTRA_COUNTRY = "yasin.country"
    const val EXTRA_TAGS = "yasin.tags"
    const val EXTRA_FEED = "yasin.feed"
    const val EXTRA_AUTHOR = "yasin.author"
    const val EXTRA_ARTWORK = "yasin.artwork"

    const val KIND_STATION = "station"
    const val KIND_PODCAST = "podcast"
    const val KIND_EPISODE = "episode"

    // Android Auto'nun liste/ızgara görünümü ipuçları
    const val CONTENT_STYLE_SUPPORTED = "android.media.browse.CONTENT_STYLE_SUPPORTED"
    const val CONTENT_STYLE_BROWSABLE_HINT = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"
    const val CONTENT_STYLE_PLAYABLE_HINT = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"
    const val CONTENT_STYLE_LIST = 1
}
