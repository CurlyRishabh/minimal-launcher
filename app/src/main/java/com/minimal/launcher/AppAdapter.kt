package com.minimal.launcher

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * Plain text rows. [items] is the activity's reusable results list; the adapter never copies it.
 * Look (size/gravity) is fixed per adapter instance; the activity recreates the adapter
 * when those settings change, so onBindViewHolder only sets text and alpha.
 *
 * When [showFooter] is true (non-empty query) a last "Search files" row is appended.
 */
class AppAdapter(
    private val inflater: LayoutInflater,
    private val items: List<AppEntry>,
    private val listener: Listener,
    private val gravity: Int,
    private val textSizeSp: Float,
) : RecyclerView.Adapter<AppAdapter.Holder>() {

    interface Listener {
        fun onAppClick(entry: AppEntry)
        fun onAppLongClick(entry: AppEntry)
        fun onSearchFilesClick()
    }

    /** Set before notifyDataSetChanged(). */
    var showFooter = false

    init {
        setHasStableIds(true)
    }

    class Holder(@JvmField val text: TextView) : RecyclerView.ViewHolder(text)

    override fun getItemCount(): Int = items.size + if (showFooter) 1 else 0

    override fun getItemViewType(position: Int): Int = if (position < items.size) TYPE_APP else TYPE_FOOTER

    override fun getItemId(position: Int): Long =
        if (position < items.size) items[position].id else FOOTER_ID

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val tv = inflater.inflate(R.layout.item_app, parent, false) as TextView
        tv.gravity = gravity
        tv.textSize = textSizeSp
        val holder = Holder(tv)
        if (viewType == TYPE_FOOTER) {
            // Static text: no per-keystroke string building.
            tv.setText(R.string.search_files)
            tv.alpha = 0.6f
            tv.setOnClickListener { listener.onSearchFilesClick() }
            return holder
        }
        // Listeners are created once per holder, never per bind.
        tv.setOnClickListener {
            val p = holder.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION && p < items.size) listener.onAppClick(items[p])
        }
        tv.setOnLongClickListener {
            val p = holder.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION && p < items.size) listener.onAppLongClick(items[p])
            true
        }
        return holder
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        if (position >= items.size) return // Footer: fully set up at creation.
        val e = items[position]
        holder.text.text = e.display
        holder.text.alpha = if (e.hidden) 0.5f else 1f
    }

    private companion object {
        const val TYPE_APP = 0
        const val TYPE_FOOTER = 1
        const val FOOTER_ID = Long.MIN_VALUE
    }
}
