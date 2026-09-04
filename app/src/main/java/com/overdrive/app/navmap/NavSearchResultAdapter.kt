package com.overdrive.app.navmap

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.overdrive.app.R
import com.overdrive.app.navmap.nav.SearchResult

/**
 * Adapter for the Google-Maps-style autocomplete dropdown under the RoadSense
 * map search bar. Renders each [SearchResult] as a single
 * `item_nav_search_result` row: a leading pin glyph, a primary title, and an
 * optional muted subtitle.
 *
 * <p>The label produced by ForwardGeocoder is a comma-joined string
 * ("name, city, country"); we split it so the first segment is the title and
 * the remainder is the subtitle — a lightweight Gmap-style two-line treatment.
 * No network or disk work happens here (unlike RecordingAdapter's thumbnail
 * loader); rows are pure text, so binding is trivial.
 *
 * <p>Mirrors the app's house adapter pattern ([com.overdrive.app.ui.adapter.RecordingAdapter]):
 * [ListAdapter] + [DiffUtil] + an inner [RecyclerView.ViewHolder].
 */
class NavSearchResultAdapter(
    private val onResultTap: (SearchResult) -> Unit,
    private val onResultLongPress: ((SearchResult) -> Unit)? = null,
    private val onResultRemove: ((SearchResult) -> Unit)? = null
) : ListAdapter<SearchResult, NavSearchResultAdapter.ResultViewHolder>(DiffCallback()) {

    /**
     * Whether the current list is the RECENT-searches set (true) vs live autocomplete
     * results (false). When true the trailing remove (✕) is shown so a single recent
     * destination can be deleted in place; live results never show it. Set via
     * [submitRecents] / [submitResults] so the row binding stays trivial.
     */
    private var removable = false

    /** Submit the RECENT-searches list (rows get a trailing ✕ remove button). */
    fun submitRecents(items: List<SearchResult>) {
        removable = true
        submitList(items) { notifyDataSetChanged() } // re-bind so the ✕ reflects the new mode
    }

    /** Submit LIVE autocomplete results (no remove button). */
    fun submitResults(items: List<SearchResult>) {
        removable = false
        submitList(items) { notifyDataSetChanged() }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ResultViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_nav_search_result, parent, false)
        return ResultViewHolder(view)
    }

    override fun onBindViewHolder(holder: ResultViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ResultViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvTitle: TextView = itemView.findViewById(R.id.tvResultTitle)
        private val tvSubtitle: TextView = itemView.findViewById(R.id.tvResultSubtitle)
        private val btnRemove: ImageButton = itemView.findViewById(R.id.btnResultRemove)

        fun bind(result: SearchResult) {
            // Split "name, rest…" so the first segment is the bold title and the
            // remainder is the muted secondary line. Falls back to a single line
            // when the label has no comma.
            val comma = result.label.indexOf(',')
            if (comma > 0 && comma < result.label.length - 1) {
                tvTitle.text = result.label.substring(0, comma).trim()
                tvSubtitle.text = result.label.substring(comma + 1).trim()
                tvSubtitle.visibility = View.VISIBLE
            } else {
                tvTitle.text = result.label
                tvSubtitle.visibility = View.GONE
            }
            itemView.setOnClickListener { onResultTap(result) }
            // Long-press a result → save it (Home / Work / favourite) without
            // having to compute a route first. No-op when no handler is wired.
            itemView.setOnLongClickListener {
                onResultLongPress?.invoke(result)
                onResultLongPress != null
            }
            // Trailing ✕ — only on recent rows (removable) with a handler wired.
            // Deletes just this recent entry; never fires the row tap.
            if (removable && onResultRemove != null) {
                btnRemove.visibility = View.VISIBLE
                btnRemove.setOnClickListener { onResultRemove.invoke(result) }
            } else {
                btnRemove.visibility = View.GONE
                btnRemove.setOnClickListener(null)
            }
        }
    }

    private class DiffCallback : DiffUtil.ItemCallback<SearchResult>() {
        override fun areItemsTheSame(oldItem: SearchResult, newItem: SearchResult): Boolean =
            oldItem.lat == newItem.lat &&
                oldItem.lng == newItem.lng &&
                oldItem.label == newItem.label

        override fun areContentsTheSame(oldItem: SearchResult, newItem: SearchResult): Boolean =
            oldItem == newItem
    }
}
