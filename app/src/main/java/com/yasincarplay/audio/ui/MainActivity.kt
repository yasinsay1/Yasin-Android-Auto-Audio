package com.yasincarplay.audio.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import com.yasincarplay.audio.R
import com.yasincarplay.audio.data.Podcast
import com.yasincarplay.audio.data.Prefs
import com.yasincarplay.audio.data.Station
import com.yasincarplay.audio.databinding.ActivityMainBinding
import com.yasincarplay.audio.service.AudioService
import com.yasincarplay.audio.service.MediaIds
import com.yasincarplay.audio.ui.dashboard.CarbonFiberDrawable
import com.yasincarplay.audio.ui.dashboard.LocationTracker
import com.yasincarplay.audio.ui.dashboard.StatusMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.Calendar

/**
 * Telefondaki AGAMA tarzı gösterge paneli.
 *
 * - Sol menü: RADYO, NAVİ, MÜZİK
 * - Sağ menü: TELEFON, UYGULAMALAR (İNTERNET menüsü yok)
 * - Merkez: gerçek GPS hız göstergesi (sahte hız gösterilmez)
 * - Alt: çalan medya + önceki/oynat-duraklat/sonraki
 *
 * Android Auto tarafı bu ekrandan bağımsızdır: araç ekranında AudioService'in
 * standart medya arayüzü kullanılır, bu panel Android Auto'da çalışmaz.
 */
class MainActivity : AppCompatActivity() {

    private enum class Panel { NONE, RADIO, MUSIC }

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs
    private lateinit var browser: MediaBrowserCompat
    private var controller: MediaControllerCompat? = null

    private var panel: Panel = Panel.NONE
    private var showFavorites = false
    private var showSubs = true

    private var subscribedId: String? = null
    private var lastParentId: String? = null
    private val backStack = ArrayDeque<String>()

    private val adapter = MediaAdapter(
        onClick = ::onItemClick,
        onAction = ::onActionClick,
        actionLabel = ::actionLabelFor,
    )

    // Gösterge paneli
    private lateinit var locationTracker: LocationTracker
    private lateinit var statusMonitor: StatusMonitor
    private var clockHandler: Handler? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val locationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) locationTracker.start()
        }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* sonuç gerekmez */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        binding.root.background = CarbonFiberDrawable()

        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter

        clockHandler = Handler(Looper.getMainLooper())

        // Menü düğmeleri
        binding.menuRadio.setOnClickListener { openPanel(Panel.RADIO) }
        binding.menuNavi.setOnClickListener { openNavi() }
        binding.menuMusic.setOnClickListener { openPanel(Panel.MUSIC) }
        binding.menuPhone.setOnClickListener { openPhone() }
        binding.menuApps.setOnClickListener { openApps() }
        binding.backButton.setOnClickListener { closePanel() }

        binding.favoritesToggle.setOnClickListener {
            showFavorites = !showFavorites
            updatePanelTitle()
            if (browser.isConnected) showParent(rootForCurrent())
        }

        binding.podcastToggle.setOnClickListener {
            showSubs = !showSubs
            updatePanelTitle()
            if (browser.isConnected) showParent(rootForCurrent())
        }

        binding.searchButton.setOnClickListener { doSearch() }
        binding.searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                doSearch()
                true
            } else {
                false
            }
        }

        binding.ytSearchButton.setOnClickListener { openYouTubeMusic(binding.ytQuery.text.toString().trim()) }
        binding.ytOpenButton.setOnClickListener { openYouTubeMusic("") }
        binding.ytQuery.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                openYouTubeMusic(binding.ytQuery.text.toString().trim())
                true
            } else {
                false
            }
        }

        // Alt medya kontrolleri
        binding.playPause.setOnClickListener {
            val c = controller ?: return@setOnClickListener
            val state = c.playbackState?.state
            if (state == PlaybackStateCompat.STATE_PLAYING || state == PlaybackStateCompat.STATE_BUFFERING) {
                c.transportControls.pause()
            } else {
                c.transportControls.play()
            }
        }
        binding.prevButton.setOnClickListener { controller?.transportControls?.skipToPrevious() }
        binding.nextButton.setOnClickListener { controller?.transportControls?.skipToNext() }

        // Geri tuşu: önce içerik panelindeki alt listelerden, sonra panelden çık
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    panel != Panel.NONE && backStack.isNotEmpty() -> showParent(backStack.removeLast())
                    panel != Panel.NONE -> closePanel()
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })

        browser = MediaBrowserCompat(
            this,
            ComponentName(this, AudioService::class.java),
            connectionCallback,
            null,
        )

        // GPS hız takibi: izin yoksa istenir, izinsiz/sahte hız gösterilmez
        locationTracker = LocationTracker(this)
        locationTracker.onSpeedUpdate = { speed, hasData ->
            binding.speedometer.speedKmh = speed
            binding.speedometer.hasGpsData = hasData
            binding.gpsIcon.alpha = if (hasData) 1f else 0.35f
        }
        if (locationTracker.hasPermission()) {
            locationTracker.start()
        } else {
            locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        statusMonitor = StatusMonitor(
            context = this,
            scope = scope,
            connectionIcon = binding.connIcon,
            connectionLabel = binding.connLabel,
            weatherIcon = binding.weatherIcon,
            weatherLabel = binding.weatherLabel,
            bluetoothIcon = binding.bluetoothIcon,
            locationTracker = locationTracker,
        )

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onStart() {
        super.onStart()
        browser.connect()
        locationTracker.start()
        statusMonitor.start()
        updateClock()
    }

    override fun onStop() {
        super.onStop()
        controller?.unregisterCallback(controllerCallback)
        subscribedId?.let { browser.unsubscribe(it, subscriptionCallback) }
        subscribedId = null
        if (browser.isConnected) browser.disconnect()
        locationTracker.stop()
        statusMonitor.stop()
        clockHandler?.removeCallbacksAndMessages(null)
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    // --- Saat ----------------------------------------------------------------

    private fun updateClock() {
        val now = Calendar.getInstance()
        val hh = now.get(Calendar.HOUR_OF_DAY).toString().padStart(2, '0')
        val mm = now.get(Calendar.MINUTE).toString().padStart(2, '0')
        binding.clock.text = "$hh:$mm"
        clockHandler?.postDelayed({ updateClock() }, 1000L)
    }

    // --- İçerik paneli ---------------------------------------------------------

    private fun openPanel(p: Panel) {
        panel = p
        backStack.clear()
        binding.contentPanel.isVisible = true
        binding.ytPanel.isVisible = p == Panel.MUSIC
        binding.favoritesToggle.isVisible = p == Panel.RADIO
        binding.podcastToggle.isVisible = p == Panel.MUSIC
        binding.searchInput.isVisible = p == Panel.RADIO
        binding.searchButton.isVisible = p == Panel.RADIO
        binding.searchInput.setText("")
        updatePanelTitle()
        if (browser.isConnected) showParent(rootForCurrent())
    }

    private fun closePanel() {
        panel = Panel.NONE
        backStack.clear()
        subscribedId?.let { browser.unsubscribe(it, subscriptionCallback) }
        subscribedId = null
        binding.contentPanel.isVisible = false
    }

    private fun rootForCurrent(): String = when {
        panel == Panel.MUSIC -> if (showSubs) MediaIds.PODCAST_SUBS else MediaIds.PODCAST_DISCOVER
        showFavorites -> MediaIds.RADIO_FAVORITES
        else -> MediaIds.RADIO_TOP
    }

    private fun updatePanelTitle() {
        binding.panelTitle.text = getString(
            when {
                panel == Panel.MUSIC -> if (showSubs) R.string.menu_music else R.string.discover
                showFavorites -> R.string.favorites
                else -> R.string.menu_radio
            }
        )
    }

    private fun showParent(parentId: String) {
        subscribedId?.let { browser.unsubscribe(it, subscriptionCallback) }
        subscribedId = parentId
        lastParentId = parentId
        adapter.submit(emptyList())
        binding.emptyText.isVisible = false
        browser.subscribe(parentId, subscriptionCallback)
    }

    private fun doSearch() {
        val query = binding.searchInput.text.toString().trim()
        if (!browser.isConnected) return
        if (query.isEmpty()) {
            backStack.clear()
            showParent(rootForCurrent())
            return
        }
        subscribedId?.let { browser.unsubscribe(it, subscriptionCallback) }
        subscribedId = null
        backStack.clear()
        adapter.submit(emptyList())
        binding.emptyText.isVisible = false
        browser.search(query, null, searchCallback)
    }

    // --- Servis bağlantısı -----------------------------------------------------

    private val connectionCallback = object : MediaBrowserCompat.ConnectionCallback() {
        override fun onConnected() {
            val c = MediaControllerCompat(this@MainActivity, browser.sessionToken)
            c.registerCallback(controllerCallback)
            controller = c
            MediaControllerCompat.setMediaController(this@MainActivity, c)
            updateNowPlaying(c.metadata)
            updatePlayButton(c.playbackState)
            if (panel != Panel.NONE) showParent(lastParentId ?: rootForCurrent())
        }
    }

    private val controllerCallback = object : MediaControllerCompat.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackStateCompat?) = updatePlayButton(state)
        override fun onMetadataChanged(metadata: MediaMetadataCompat?) = updateNowPlaying(metadata)
    }

    private val subscriptionCallback = object : MediaBrowserCompat.SubscriptionCallback() {
        override fun onChildrenLoaded(parentId: String, children: MutableList<MediaBrowserCompat.MediaItem>) {
            if (parentId != subscribedId) return
            if (children.isEmpty() && parentId == MediaIds.PODCAST_SUBS) {
                // Abonelik yoksa önerilenleri göster
                showSubs = false
                updatePanelTitle()
                showParent(MediaIds.PODCAST_DISCOVER)
                return
            }
            adapter.submit(children)
            binding.emptyText.isVisible = children.isEmpty()
        }

        override fun onError(parentId: String) {
            if (parentId != subscribedId) return
            adapter.submit(emptyList())
            binding.emptyText.isVisible = true
        }
    }

    private val searchCallback = object : MediaBrowserCompat.SearchCallback() {
        override fun onSearchResult(query: String, extras: Bundle?, items: MutableList<MediaBrowserCompat.MediaItem>) {
            adapter.submit(items)
            binding.emptyText.isVisible = items.isEmpty()
        }

        override fun onError(query: String, extras: Bundle?) {
            adapter.submit(emptyList())
            binding.emptyText.isVisible = true
        }
    }

    // --- Liste etkileşimi ---------------------------------------------------------

    private fun onItemClick(item: MediaBrowserCompat.MediaItem) {
        val id = item.mediaId ?: return
        if (item.isBrowsable) {
            backStack.addLast(subscribedId ?: rootForCurrent())
            showParent(id)
        } else {
            // Önceki/sonraki için mevcut liste bağlamını servise ilet
            val extras = Bundle().apply {
                item.description.extras?.let { putAll(it) }
                val listIds = adapter.items().filter { !it.isBrowsable }.mapNotNull { it.mediaId }
                if (listIds.isNotEmpty()) putStringArray(MediaIds.EXTRA_LIST_IDS, listIds.toTypedArray())
            }
            controller?.transportControls?.playFromMediaId(id, extras)
        }
    }

    private fun actionLabelFor(item: MediaBrowserCompat.MediaItem): String? {
        val extras = item.description.extras ?: return null
        return when (extras.getString(MediaIds.EXTRA_KIND)) {
            MediaIds.KIND_STATION -> getString(
                if (prefs.isFavorite(extras.getString(MediaIds.EXTRA_STATION_ID).orEmpty())) {
                    R.string.favorite_remove
                } else {
                    R.string.favorite_add
                }
            )
            MediaIds.KIND_PODCAST -> getString(
                if (prefs.isSubscribed(extras.getString(MediaIds.EXTRA_FEED).orEmpty())) {
                    R.string.unsubscribe
                } else {
                    R.string.subscribe
                }
            )
            else -> null
        }
    }

    private fun onActionClick(item: MediaBrowserCompat.MediaItem) {
        val extras = item.description.extras ?: return
        val title = item.description.title?.toString().orEmpty()
        when (extras.getString(MediaIds.EXTRA_KIND)) {
            MediaIds.KIND_STATION -> prefs.toggleFavorite(
                Station(
                    id = extras.getString(MediaIds.EXTRA_STATION_ID).orEmpty(),
                    name = title,
                    streamUrl = extras.getString(MediaIds.EXTRA_URL).orEmpty(),
                    country = extras.getString(MediaIds.EXTRA_COUNTRY).orEmpty(),
                    tags = extras.getString(MediaIds.EXTRA_TAGS).orEmpty(),
                )
            )
            MediaIds.KIND_PODCAST -> prefs.toggleSubscription(
                Podcast(
                    feedUrl = extras.getString(MediaIds.EXTRA_FEED).orEmpty(),
                    title = title,
                    author = extras.getString(MediaIds.EXTRA_AUTHOR).orEmpty(),
                    artworkUrl = extras.getString(MediaIds.EXTRA_ARTWORK).orEmpty(),
                )
            )
            else -> return
        }
        val current = subscribedId
        if (current == MediaIds.RADIO_FAVORITES || current == MediaIds.PODCAST_SUBS) {
            showParent(current) // listeyi yenile
        } else {
            adapter.refresh()
        }
    }

    // --- Şu an çalan / oynat düğmesi ---------------------------------------------------

    private fun updateNowPlaying(metadata: MediaMetadataCompat?) {
        val title = metadata?.getString(MediaMetadataCompat.METADATA_KEY_TITLE)
        binding.nowTitle.text = if (title.isNullOrBlank()) getString(R.string.nothing_playing) else title
        binding.nowSubtitle.text = metadata?.getString(MediaMetadataCompat.METADATA_KEY_ARTIST).orEmpty()
    }

    private fun updatePlayButton(state: PlaybackStateCompat?) {
        val playing = state?.state == PlaybackStateCompat.STATE_PLAYING ||
            state?.state == PlaybackStateCompat.STATE_BUFFERING
        binding.playPause.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        binding.playPause.contentDescription = getString(if (playing) R.string.pause else R.string.play)
    }

    // --- NAVİ / TELEFON / UYGULAMALAR ---------------------------------------------------

    private fun openNavi() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0"))
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.navi_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    private fun openPhone() {
        val intent = Intent(Intent.ACTION_DIAL)
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.phone_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    private fun openApps() {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.apps_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    // --- YouTube Music -----------------------------------------------------------------

    /**
     * YouTube Music, ses akışını üçüncü taraf uygulamalara açmaz; bu yüzden entegrasyon,
     * resmi YouTube Music uygulamasını (yoksa tarayıcıyı) aramayla birlikte açmaktan ibarettir.
     */
    private fun openYouTubeMusic(query: String) {
        val url = if (query.isEmpty()) "https://music.youtube.com/" else "https://music.youtube.com/search?q=" + Uri.encode(query)
        val appIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(YT_MUSIC_PACKAGE)
        try {
            startActivity(appIntent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.yt_not_installed, Toast.LENGTH_SHORT).show()
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (e2: ActivityNotFoundException) {
                // Tarayıcı da yok; yapılacak bir şey yok.
            }
        }
    }

    private companion object {
        const val YT_MUSIC_PACKAGE = "com.google.android.apps.youtube.music"
    }
}
