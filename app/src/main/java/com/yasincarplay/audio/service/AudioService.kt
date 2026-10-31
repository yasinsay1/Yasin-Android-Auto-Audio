package com.yasincarplay.audio.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media.MediaBrowserServiceCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.yasincarplay.audio.R
import com.yasincarplay.audio.data.Episode
import com.yasincarplay.audio.data.Http
import com.yasincarplay.audio.data.Podcast
import com.yasincarplay.audio.data.PodcastRepository
import com.yasincarplay.audio.data.Prefs
import com.yasincarplay.audio.data.RadioRepository
import com.yasincarplay.audio.data.Station
import com.yasincarplay.audio.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Hem telefondaki uygulama hem de Android Auto için medya servisi:
 * - MediaBrowserService: radyo / podcast ağacını sunar (Android Auto bunu tarar)
 * - MediaSession: oynat/duraklat/ara gibi komutları alır
 * - ExoPlayer: sesi çalar
 */
class AudioService : MediaBrowserServiceCompat() {

    private data class PlayableInfo(
        val mediaId: String,
        val title: String,
        val subtitle: String,
        val url: String,
        val artworkUrl: String?,
        val isLive: Boolean,
        val durationMs: Long,
    )

    private lateinit var prefs: Prefs
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaSessionCompat

    /** Önceki/sonraki için çalma sırası; gezinme ağacında gösterilen listelerden beslenir. */
    private val queue = PlayQueue()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val playables = ConcurrentHashMap<String, PlayableInfo>()
    private var current: PlayableInfo? = null
    private var liveTitle: String? = null
    private var isForeground = false

    private val notificationManager: NotificationManager
        get() = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        createNotificationChannel()

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(Http.client)))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player.addListener(playerListener)

        session = MediaSessionCompat(this, "YasinAudioSession").apply {
            setCallback(sessionCallback)
            setSessionActivity(
                PendingIntent.getActivity(
                    this@AudioService,
                    0,
                    Intent(this@AudioService, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                )
            )
        }
        sessionToken = session.sessionToken
        publishPlaybackState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> togglePlayPause()
            ACTION_STOP -> stopPlayback()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        session.isActive = false
        session.release()
        player.release()
        super.onDestroy()
    }

    // ------------------------------------------------------------------------
    // Medya tarayıcı (Android Auto ve telefon arayüzü bu ağacı gezer)
    // ------------------------------------------------------------------------

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot? {
        val extras = Bundle().apply {
            putBoolean(MediaIds.CONTENT_STYLE_SUPPORTED, true)
            putInt(MediaIds.CONTENT_STYLE_BROWSABLE_HINT, MediaIds.CONTENT_STYLE_LIST)
            putInt(MediaIds.CONTENT_STYLE_PLAYABLE_HINT, MediaIds.CONTENT_STYLE_LIST)
        }
        return BrowserRoot(MediaIds.ROOT, extras)
    }

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>,
    ) {
        result.detach()
        scope.launch {
            val items = withContext(Dispatchers.IO) {
                runCatching { loadChildren(parentId) }.getOrDefault(emptyList())
            }
            rememberPlayables(parentId, items)
            result.sendResult(items.toMutableList())
        }
    }

    override fun onSearch(
        query: String,
        extras: Bundle?,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>,
    ) {
        result.detach()
        scope.launch {
            val items = withContext(Dispatchers.IO) {
                val stations = runCatching { RadioRepository.search(query, 20) }.getOrDefault(emptyList())
                val podcasts = runCatching { PodcastRepository.search(query, 15) }.getOrDefault(emptyList())
                stations.map { stationItem(it) } + podcasts.map { podcastItem(it) }
            }
            rememberPlayables(MediaIds.SEARCH_KEY_PREFIX + query, items)
            result.sendResult(items.toMutableList())
        }
    }

    /** Gösterilen listedeki çalınabilir öğeleri, önceki/sonraki için çalma sırası adayı olarak kaydeder. */
    private fun rememberPlayables(key: String, items: List<MediaBrowserCompat.MediaItem>) {
        val ids = items.filter { it.isPlayable }.mapNotNull { it.mediaId }
        if (ids.isNotEmpty()) queue.remember(key, ids)
    }

    private fun loadChildren(parentId: String): List<MediaBrowserCompat.MediaItem> = when {
        parentId == MediaIds.ROOT -> listOf(
            browsableItem(MediaIds.RADIO, getString(R.string.root_radio)),
            browsableItem(MediaIds.PODCASTS, getString(R.string.root_podcasts)),
        )
        parentId == MediaIds.RADIO -> listOf(
            browsableItem(MediaIds.RADIO_FAVORITES, getString(R.string.root_radio_favorites)),
            browsableItem(MediaIds.RADIO_TOP, getString(R.string.root_radio_top)),
        )
        parentId == MediaIds.RADIO_FAVORITES -> prefs.favorites().map { stationItem(it) }
        parentId == MediaIds.RADIO_TOP -> RadioRepository.top().map { stationItem(it) }
        parentId == MediaIds.PODCASTS -> listOf(
            browsableItem(MediaIds.PODCAST_SUBS, getString(R.string.root_podcast_subs)),
            browsableItem(MediaIds.PODCAST_DISCOVER, getString(R.string.root_podcast_top)),
        )
        parentId == MediaIds.PODCAST_SUBS -> prefs.subscriptions().map { podcastItem(it) }
        parentId == MediaIds.PODCAST_DISCOVER ->
            PodcastRepository.search("haber", 25, "TR").map { podcastItem(it) }
        parentId.startsWith(MediaIds.PREFIX_PODCAST) ->
            PodcastRepository.episodes(parentId.removePrefix(MediaIds.PREFIX_PODCAST)).map { episodeItem(it) }
        else -> emptyList()
    }

    private fun browsableItem(id: String, title: String): MediaBrowserCompat.MediaItem {
        val description = MediaDescriptionCompat.Builder()
            .setMediaId(id)
            .setTitle(title)
            .build()
        return MediaBrowserCompat.MediaItem(description, MediaBrowserCompat.MediaItem.FLAG_BROWSABLE)
    }

    private fun stationSubtitle(station: Station): String {
        val tags = station.tags.split(",").map { it.trim() }.filter { it.isNotEmpty() }.take(2)
        return (listOf(station.country).filter { it.isNotEmpty() } + tags).joinToString(" • ")
    }

    private fun stationItem(station: Station): MediaBrowserCompat.MediaItem {
        val id = MediaIds.PREFIX_STATION + station.id
        val subtitle = stationSubtitle(station)
        playables[id] = PlayableInfo(id, station.name, subtitle, station.streamUrl, null, true, 0L)
        val extras = Bundle().apply {
            putString(MediaIds.EXTRA_KIND, MediaIds.KIND_STATION)
            putString(MediaIds.EXTRA_STATION_ID, station.id)
            putString(MediaIds.EXTRA_URL, station.streamUrl)
            putString(MediaIds.EXTRA_COUNTRY, station.country)
            putString(MediaIds.EXTRA_TAGS, station.tags)
        }
        val description = MediaDescriptionCompat.Builder()
            .setMediaId(id)
            .setTitle(station.name)
            .setSubtitle(subtitle)
            .setExtras(extras)
            .build()
        return MediaBrowserCompat.MediaItem(description, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE)
    }

    private fun podcastItem(podcast: Podcast): MediaBrowserCompat.MediaItem {
        val extras = Bundle().apply {
            putString(MediaIds.EXTRA_KIND, MediaIds.KIND_PODCAST)
            putString(MediaIds.EXTRA_FEED, podcast.feedUrl)
            putString(MediaIds.EXTRA_AUTHOR, podcast.author)
            putString(MediaIds.EXTRA_ARTWORK, podcast.artworkUrl)
        }
        val builder = MediaDescriptionCompat.Builder()
            .setMediaId(MediaIds.PREFIX_PODCAST + podcast.feedUrl)
            .setTitle(podcast.title)
            .setSubtitle(podcast.author)
            .setExtras(extras)
        if (podcast.artworkUrl.isNotBlank()) builder.setIconUri(Uri.parse(podcast.artworkUrl))
        return MediaBrowserCompat.MediaItem(builder.build(), MediaBrowserCompat.MediaItem.FLAG_BROWSABLE)
    }

    private fun episodeItem(episode: Episode): MediaBrowserCompat.MediaItem {
        val id = MediaIds.PREFIX_EPISODE + episode.id
        playables[id] = PlayableInfo(
            id, episode.title, episode.podcastTitle, episode.audioUrl, null, false, episode.durationMs,
        )
        val extras = Bundle().apply {
            putString(MediaIds.EXTRA_KIND, MediaIds.KIND_EPISODE)
            putString(MediaIds.EXTRA_URL, episode.audioUrl)
        }
        val description = MediaDescriptionCompat.Builder()
            .setMediaId(id)
            .setTitle(episode.title)
            .setSubtitle(episode.podcastTitle)
            .setExtras(extras)
            .build()
        return MediaBrowserCompat.MediaItem(description, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE)
    }

    // ------------------------------------------------------------------------
    // MediaSession komutları
    // ------------------------------------------------------------------------

    private val sessionCallback = object : MediaSessionCompat.Callback() {
        override fun onPlay() {
            if (current == null) return
            if (player.playerError != null || player.playbackState == Player.STATE_IDLE) player.prepare()
            player.play()
            session.isActive = true
        }

        override fun onPause() = player.pause()

        override fun onStop() = stopPlayback()

        override fun onSeekTo(pos: Long) = player.seekTo(pos)

        override fun onSkipToNext() = skip(forward = true)

        override fun onSkipToPrevious() {
            val info = current
            // Kayıtlı bir bölümde 3 saniyeden fazla ilerlenmişse "önceki" başa sarar (standart davranış).
            if (info != null && !info.isLive && player.currentPosition > RESTART_THRESHOLD_MS) {
                player.seekTo(0L)
                return
            }
            skip(forward = false)
        }

        override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
            if (mediaId == null) return
            val extraUrl = extras?.getString(MediaIds.EXTRA_URL)
            val extraKind = extras?.getString(MediaIds.EXTRA_KIND)
            val info = playables[mediaId]
                ?: prefs.favorites()
                    .firstOrNull { MediaIds.PREFIX_STATION + it.id == mediaId }
                    ?.let { PlayableInfo(mediaId, it.name, stationSubtitle(it), it.streamUrl, null, true, 0L) }
                ?: extraUrl?.let { url ->
                    PlayableInfo(mediaId, mediaId, "", url, null, extraKind == MediaIds.KIND_STATION, 0L)
                }
            if (info != null) {
                playables[mediaId] = info
                queue.select(mediaId, extras?.getString(MediaIds.EXTRA_LIST_KEY))
                playInfo(info)
            } else {
                publishError()
            }
        }

        override fun onPlayFromSearch(query: String?, extras: Bundle?) {
            playFromSearch(query)
        }
    }

    private fun playFromSearch(query: String?) {
        scope.launch {
            val info = withContext(Dispatchers.IO) {
                runCatching {
                    if (query.isNullOrBlank()) {
                        val station = prefs.favorites().firstOrNull() ?: RadioRepository.top(limit = 1).firstOrNull()
                        station?.let { stationInfo(it) }
                    } else {
                        val station = RadioRepository.search(query, 1).firstOrNull()
                        if (station != null) {
                            stationInfo(station)
                        } else {
                            val podcast = PodcastRepository.search(query, 1).firstOrNull()
                            val episode = podcast?.let { PodcastRepository.episodes(it.feedUrl, 1).firstOrNull() }
                            episode?.let { episodeInfo(it) }
                        }
                    }
                }.getOrNull()
            }
            if (info != null) {
                playables[info.mediaId] = info
                queue.select(info.mediaId)
                playInfo(info)
            } else {
                publishError()
            }
        }
    }

    private fun stationInfo(station: Station) = PlayableInfo(
        MediaIds.PREFIX_STATION + station.id, station.name, stationSubtitle(station), station.streamUrl, null, true, 0L,
    )

    private fun episodeInfo(episode: Episode) = PlayableInfo(
        MediaIds.PREFIX_EPISODE + episode.id, episode.title, episode.podcastTitle, episode.audioUrl, null, false, episode.durationMs,
    )

    private fun playInfo(info: PlayableInfo) {
        current = info
        liveTitle = null
        player.setMediaItem(MediaItem.fromUri(info.url))
        player.prepare()
        player.play()
        session.isActive = true
        publishMetadata()
        onPlayerStateUpdated()
    }

    private fun togglePlayPause() {
        if (current == null) return
        if (player.playWhenReady && player.playbackState != Player.STATE_ENDED) {
            player.pause()
        } else {
            sessionCallback.onPlay()
        }
    }

    /** Çalma sırasındaki sonraki/önceki öğeye geçer; sıra tek öğeliyse hiçbir şey yapmaz. */
    private fun skip(forward: Boolean) {
        val id = (if (forward) queue.next() else queue.previous()) ?: return
        val info = playables[id]
        if (info == null) {
            publishError()
            return
        }
        playInfo(info)
    }

    private fun stopPlayback() {
        player.stop()
        player.clearMediaItems()
        queue.clear()
        current = null
        liveTitle = null
        session.isActive = false
        session.setMetadata(null)
        publishPlaybackState()
        isForeground = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        notificationManager.cancel(NOTIF_ID)
        stopSelf()
    }

    // ------------------------------------------------------------------------
    // Oynatıcı olayları -> MediaSession durumu + bildirim
    // ------------------------------------------------------------------------

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) = onPlayerStateUpdated()

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = onPlayerStateUpdated()

        override fun onPlayerError(error: PlaybackException) = onPlayerStateUpdated()

        override fun onMediaMetadataChanged(mediaMetadata: androidx.media3.common.MediaMetadata) {
            // Radyo yayınlarındaki ICY "şu an çalan" bilgisi
            val title = mediaMetadata.title?.toString()
            if (!title.isNullOrBlank() && current?.isLive == true) {
                liveTitle = title
                publishMetadata()
                refreshNotification()
            }
        }
    }

    private fun onPlayerStateUpdated() {
        publishPlaybackState()
        refreshNotification()
    }

    private fun publishError() {
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(BASE_ACTIONS)
                .setState(PlaybackStateCompat.STATE_ERROR, 0L, 0f)
                .setErrorMessage(PlaybackStateCompat.ERROR_CODE_APP_ERROR, getString(R.string.playback_error))
                .build()
        )
    }

    private fun publishPlaybackState() {
        val info = current
        val error = player.playerError
        val state = when {
            error != null -> PlaybackStateCompat.STATE_ERROR
            info == null -> PlaybackStateCompat.STATE_NONE
            else -> when (player.playbackState) {
                Player.STATE_BUFFERING -> PlaybackStateCompat.STATE_BUFFERING
                Player.STATE_READY ->
                    if (player.playWhenReady) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
                Player.STATE_ENDED -> PlaybackStateCompat.STATE_STOPPED
                else ->
                    if (player.playWhenReady) PlaybackStateCompat.STATE_BUFFERING else PlaybackStateCompat.STATE_PAUSED
            }
        }
        var actions = BASE_ACTIONS
        if (info != null && !info.isLive) actions = actions or PlaybackStateCompat.ACTION_SEEK_TO
        // Önceki/sonraki yalnızca sırada gerçekten başka öğe varken duyurulur (araç ekranı ve direksiyon tuşları buna bakar).
        if (info != null && queue.hasMultiple) {
            actions = actions or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
        }
        val position =
            if (info == null || info.isLive) PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN else player.currentPosition
        val builder = PlaybackStateCompat.Builder()
            .setActions(actions)
            .setState(state, position, if (state == PlaybackStateCompat.STATE_PLAYING) 1f else 0f)
        if (error != null) {
            builder.setErrorMessage(PlaybackStateCompat.ERROR_CODE_APP_ERROR, getString(R.string.playback_error))
        }
        session.setPlaybackState(builder.build())
    }

    private fun publishMetadata() {
        val info = current
        if (info == null) {
            session.setMetadata(null)
            return
        }
        val artist = if (info.isLive) (liveTitle ?: info.subtitle) else info.subtitle
        val builder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, info.mediaId)
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, info.title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
            .putLong(
                MediaMetadataCompat.METADATA_KEY_DURATION,
                if (info.isLive || info.durationMs <= 0L) -1L else info.durationMs,
            )
        info.artworkUrl?.let { builder.putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, it) }
        session.setMetadata(builder.build())
    }

    // ------------------------------------------------------------------------
    // Bildirim ve ön plan servisi
    // ------------------------------------------------------------------------

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        notificationManager.createNotificationChannel(channel)
    }

    private fun refreshNotification() {
        val info = current ?: return
        val playing = player.playWhenReady &&
            player.playbackState != Player.STATE_IDLE &&
            player.playbackState != Player.STATE_ENDED
        val notification = buildNotification(info, playing)
        if (playing) {
            if (!isForeground) {
                ContextCompat.startForegroundService(this, Intent(this, AudioService::class.java))
                ServiceCompat.startForeground(
                    this,
                    NOTIF_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                )
                isForeground = true
            } else {
                notificationManager.notify(NOTIF_ID, notification)
            }
        } else {
            if (isForeground) {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
                isForeground = false
            }
            notificationManager.notify(NOTIF_ID, notification)
        }
    }

    private fun buildNotification(info: PlayableInfo, playing: Boolean): android.app.Notification {
        val toggle = PendingIntent.getService(
            this, 1, Intent(this, AudioService::class.java).setAction(ACTION_TOGGLE), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 2, Intent(this, AudioService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val subtitle = if (info.isLive) (liveTitle ?: info.subtitle) else info.subtitle
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(info.title)
            .setContentText(subtitle)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(playing)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(
                if (playing) R.drawable.ic_pause else R.drawable.ic_play,
                getString(if (playing) R.string.pause else R.string.play),
                toggle,
            )
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stop)
            .setStyle(
                MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1)
            )
            .build()
    }

    private companion object {
        const val CHANNEL_ID = "yasin_audio_playback"
        const val NOTIF_ID = 4201
        const val RESTART_THRESHOLD_MS = 3_000L
        const val ACTION_TOGGLE = "com.yasincarplay.audio.TOGGLE"
        const val ACTION_STOP = "com.yasincarplay.audio.STOP"
        const val BASE_ACTIONS = PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or
            PlaybackStateCompat.ACTION_STOP or
            PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID or
            PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH
    }
}
