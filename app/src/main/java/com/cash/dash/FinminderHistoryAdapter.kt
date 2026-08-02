package com.cash.dash

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

data class HistoryItem(
    val sNo: Int,
    val dateStr: String, // e.g. "15/06/2026"
    val status: String // "Completed", "Upcoming", "Not completed"
)

class FinminderHistoryAdapter(private val onCompleteClicked: (String) -> Unit) : RecyclerView.Adapter<FinminderHistoryAdapter.ViewHolder>() {

    private var items = listOf<HistoryItem>()

    fun submitList(newItems: List<HistoryItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvSNo: TextView = view.findViewById(R.id.tvSNo)
        val tvDate: TextView = view.findViewById(R.id.tvDate)
        val tvStatus: TextView = view.findViewById(R.id.tvStatus)
        val btnComplete: Button = view.findViewById(R.id.btnComplete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_finminder_history_row, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.tvSNo.text = item.sNo.toString()
        holder.tvDate.text = item.dateStr
        
        when (item.status) {
            "Completed" -> {
                holder.tvStatus.visibility = View.VISIBLE
                holder.tvStatus.text = "Completed"
                holder.tvStatus.setTextColor(android.graphics.Color.parseColor("#4CAF50"))
                holder.btnComplete.visibility = View.GONE
            }
            "Upcoming" -> {
                holder.tvStatus.visibility = View.VISIBLE
                holder.tvStatus.text = "Upcoming"
                holder.tvStatus.setTextColor(android.graphics.Color.parseColor("#888888"))
                holder.btnComplete.visibility = View.GONE
            }
            "Not completed" -> {
                holder.tvStatus.visibility = View.GONE
                holder.btnComplete.visibility = View.VISIBLE
                holder.btnComplete.text = "Mark completed"
                holder.btnComplete.backgroundTintList = android.content.res.ColorStateList.valueOf(
                    android.graphics.Color.parseColor("#4CAF50")
                )
                holder.btnComplete.setTextColor(android.graphics.Color.WHITE)
                holder.btnComplete.setOnClickListener {
                    onCompleteClicked(item.dateStr)
                }
            }
        }
    }

    override fun getItemCount() = items.size
}
