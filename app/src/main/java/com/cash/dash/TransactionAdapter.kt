package com.cash.dash

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale

data class TransactionItem(val title: String, val category: String, val amount: Int, val rawEntry: String)

class TransactionAdapter(
    private val items: List<TransactionItem>,
    private val showTimestamp: Boolean = false,
    private val onItemLongClick: ((TransactionItem) -> Unit)? = null
) : RecyclerView.Adapter<TransactionAdapter.Holder>() {

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.txtTransTitle)
        val category: TextView = view.findViewById(R.id.txtTransCategory)
        val amount: TextView = view.findViewById(R.id.txtTransAmount)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_transaction, parent, false)
        return Holder(v)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        
        fun toTitleCase(s: String): String {
            if (s.isEmpty()) return s
            if (s.equals("no choice", ignoreCase = true)) return "No Allocation"
            return s.split(" ").joinToString(" ") { word ->
                word.lowercase().replaceFirstChar { if (it.isLowerCase()) it.titlecase(java.util.Locale.getDefault()) else it.toString() }
            }
        }

        holder.title.text = toTitleCase(item.title)
        
        if (showTimestamp) {
            val p = item.rawEntry.split("|")
            val ts = if (p.size >= 2) p[1].toLongOrNull() ?: 0L else 0L
            val dateStr = if (ts > 0) {
                java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.getDefault()).format(java.util.Date(ts))
            } else ""
            
            holder.category.text = "${toTitleCase(item.category)} | $dateStr"
        } else {
            holder.category.text = toTitleCase(item.category)
        }
        
        holder.amount.text = "-₹${item.amount}"

        holder.itemView.setOnLongClickListener {
            onItemLongClick?.invoke(item)
            true
        }
    }

    override fun getItemCount() = items.size
}
