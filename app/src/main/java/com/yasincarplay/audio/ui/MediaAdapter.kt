package com.yasincarplay.audio.ui

import android.annotation.SuppressLint
import android.support.v4.media.MediaBrowserCompat
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.yasincarplay.audio.databinding.ItemMediaBinding

class MediaAdapter(
    private val onClick: (MediaBrowserCompat.MediaItem) -> Unit,
    private val onAction: (MediaBrowserCompat.MediaItem) -> Unit,
    /** Öğe için eylem düğmesinin etiketi (favori / abone); null ise düğme gizlenir. */
    private val actionLabel: (MediaBrowserCompat.MediaItem) -> String?,
) : RecyclerView.Adapter<MediaAdapter.ViewHolder>() {

    private var items: List<MediaBrowserCompat.MediaItem> = emptyList()

    class ViewHolder(val binding: ItemMediaBinding) : RecyclerView.ViewHolder(binding.root)

    @SuppressLint("NotifyDataSetChanged")
    fun submit(newItems: List<MediaBrowserCompat.MediaItem>) {
        items = newItems.toList()
        notifyDataSetChanged()
    }

    @SuppressLint("NotifyDataSetChanged")
    fun refresh() = notifyDataSetChanged()

    /** Mevcut liste (önceki/sonraki için bağlam aktarmada kullanılır). */
    fun items(): List<MediaBrowserCompat.MediaItem> = items

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(ItemMediaBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val b = holder.binding
        b.itemTitle.text = item.description.title
        b.itemSubtitle.text = item.description.subtitle
        b.itemSubtitle.isVisible = !item.description.subtitle.isNullOrBlank()
        val label = actionLabel(item)
        b.itemAction.isVisible = label != null
        b.itemAction.text = label
        b.itemAction.setOnClickListener { onAction(item) }
        b.root.setOnClickListener { onClick(item) }
    }
}
