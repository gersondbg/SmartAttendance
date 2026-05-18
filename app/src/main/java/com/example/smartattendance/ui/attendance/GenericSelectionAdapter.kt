package com.example.smartattendance.ui.attendance

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import android.R

class GenericSelectionAdapter(
    private var items: List<SelectionItem>,
    private val onItemClick: (SelectionItem) -> Unit
) : RecyclerView.Adapter<GenericSelectionAdapter.ViewHolder>() {

    data class SelectionItem(val id: Int, val title: String, val subtitle: String = "")

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val text1: TextView = view.findViewById(R.id.text1)
        val text2: TextView = view.findViewById(R.id.text2)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.simple_list_item_2, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.text1.text = item.title
        holder.text2.text = item.subtitle
        holder.itemView.setOnClickListener { onItemClick(item) }
    }

    override fun getItemCount() = items.size

    fun updateData(newItems: List<SelectionItem>) {
        items = newItems
        notifyDataSetChanged()
    }
}
