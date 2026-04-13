package com.cash.dash

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class SearchAdapter(private val items: List<SearchListItem>) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_TRANSACTION = 1
    }

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is SearchListItem.Header -> TYPE_HEADER
            is SearchListItem.Transaction -> TYPE_TRANSACTION
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderHolder(inflater.inflate(R.layout.item_search_header, parent, false))
            else -> TransactionHolder(inflater.inflate(R.layout.item_search_result, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        if (holder is HeaderHolder && item is SearchListItem.Header) {
            holder.title.text = item.title
        } else if (holder is TransactionHolder && item is SearchListItem.Transaction) {
            val trans = item.item
            holder.title.text = trans.title
            holder.date.text = trans.date
            holder.category.text = "(${trans.category.uppercase()})"
            holder.amount.text = "-₹${trans.amount}"
        }
    }

    override fun getItemCount() = items.size

    class HeaderHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.tvHeaderTitle)
    }

    class TransactionHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.txtTransTitle)
        val date: TextView = view.findViewById(R.id.txtTransDate)
        val category: TextView = view.findViewById(R.id.txtTransCategory)
        val amount: TextView = view.findViewById(R.id.txtTransAmount)
    }
}
