package com.yasincarplay.audio.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
import com.google.android.material.tabs.TabLayout
import com.yasincarplay.audio.R
import com.yasincarplay.audio.data.Podcast
import com.yasincarplay.audio.data.Prefs
import com.yasincarplay.audio.data.Station
import com.yasincarplay.audio.databinding.ActivityMainBinding
import com.yasincarplay.audio.service.AudioService
import com.yasincarplay.audio.service.MediaIds

/**
 * Telefondaki ana ekran. Servisin medya tarayıcı ağacını (Android Auto ile aynı ağaç) listeler,
 * seçilen öğeyi MediaController üzerinden çaldırır.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs
    private lateinit var browser: MediaBrowserCompat
    private var controller: MediaControllerCompat? = null

    private var tabPosition = TAB_RADIO
    private var subscribedId: String? = null
    private var lastParentId: String? = null
    private val backStack = ArrayDeque<String>()

    private val adapter = MediaAdapter(
        onClick = ::onItemClick,
        onAction = ::onActionClick,
        actionLabel = ::actionLabelFor,
    )

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* sonuç gerekmez */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter

        listOf(R.string.tab_radio, R.string.tab_favorites, R.string.tab_podcasts, R.string.tab_youtube)
            .forEach { binding.tabs.addTab(binding.tabs.newTab().setText(it)) }
        binding.tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = selectTab(tab.position)
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) = selectTab(tab.position)
        })

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

        binding.playPause.setOnClickListener {
            val c = controller ?: return@setOnClickListener
            val state = c.playbackState?.state
            if (state == PlaybackStateCompat.STATE_PLAYING || state == PlaybackStateCompat.STATE_BUFFERING) {
                c.transportControls.pause()
            } else {
                c.transportControls.play()
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!goBack()) {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        browser = MediaBrowserCompat(
            this,
            ComponentName(this, AudioService::class.java),
            connectionCallback,
            null,
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
    }

    override fun onStop() {
        super.onStop()
        controller?.unregisterCallback(controllerCallback)
        subscribedId?.let { browser.unsubscribe(it, subscriptionCallback) }
        subscribedId = null
        if (browser.isConnected) browser.disconnect()
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
            if (tabPosition != TAB_YOUTUBE) showParent(lastParentId ?: rootFor(tabPosition))
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

    // --- Gezinme -----------------------------------------------------------------

    private fun rootFor(tab: Int): String = when (tab) {
        TAB_RADIO -> MediaIds.RADIO_TOP
        TAB_FAVORITES -> MediaIds.RADIO_FAVORITES
        else -> MediaIds.PODCAST_SUBS
    }

    private fun selectTab(position: Int) {
        tabPosition = position
        backStack.clear()
        val isYoutube = position == TAB_YOUTUBE
        binding.ytPanel.isVisible = isYoutube
        binding.list.isVisible = !isYoutube
        binding.searchRow.isVisible = !isYoutube
        binding.emptyText.isVisible = false
        if (!isYoutube) {
            binding.searchInput.setText("")
            if (browser.isConnected) showParent(rootFor(position))
        }
    }

    private fun showParent(parentId: String) {
        subscribedId?.let { browser.unsubscribe(it, subscriptionCallback) }
        subscribedId = parentId
        lastParentId = parentId
        adapter.submit(emptyList())
        binding.emptyText.isVisible = false
        browser.subscribe(parentId, subscriptionCallback)
    }

    /** Geri tuşu: önce podcast bölümlerinden / arama sonuçlarından çık. Eylem yapıldıysa true. */
    private fun goBack(): Boolean {
        if (backStack.isNotEmpty()) {
            showParent(backStack.removeLast())
            return true
        }
        if (subscribedId == null && tabPosition != TAB_YOUTUBE && browser.isConnected) {
            // Arama sonuçları gösteriliyordu
            binding.searchInput.setText("")
            showParent(rootFor(tabPosition))
            return true
        }
        return false
    }

    private fun doSearch() {
        val query = binding.searchInput.text.toString().trim()
        if (!browser.isConnected) return
        if (query.isEmpty()) {
            backStack.clear()
            showParent(rootFor(tabPosition))
            return
        }
        subscribedId?.let { browser.unsubscribe(it, subscriptionCallback) }
        subscribedId = null
        backStack.clear()
        adapter.submit(emptyList())
        binding.emptyText.isVisible = false
        browser.search(query, null, searchCallback)
    }

    // --- Liste etkileşimi ---------------------------------------------------------

    private fun onItemClick(item: MediaBrowserCompat.MediaItem) {
        val id = item.mediaId ?: return
        if (item.isBrowsable) {
            backStack.addLast(subscribedId ?: rootFor(tabPosition))
            showParent(id)
        } else {
            controller?.transportControls?.playFromMediaId(id, item.description.extras)
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
        const val TAB_RADIO = 0
        const val TAB_FAVORITES = 1
        const val TAB_PODCASTS = 2
        const val TAB_YOUTUBE = 3
        const val YT_MUSIC_PACKAGE = "com.google.android.apps.youtube.music"
    }
}
