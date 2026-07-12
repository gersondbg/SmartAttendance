package com.example.smartattendance.ui.attendance

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.smartattendance.R
import com.example.smartattendance.data.remote.SessionStore

class GenericSelectionAdapter(
    private var items: List<SelectionItem>,
    private val onItemClick: (SelectionItem) -> Unit
) : RecyclerView.Adapter<GenericSelectionAdapter.ViewHolder>() {

    data class SelectionItem(val id: Int, val title: String, val subtitle: String = "")

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.tvTitle)
        val subtitle: TextView = view.findViewById(R.id.tvSubtitle)
        val status: TextView = view.findViewById(R.id.tvStatus)
        val hint: TextView = view.findViewById(R.id.tvHint)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_selection_session, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val isActiveSession = SessionStore.activeClassRunning && SessionStore.activeSessionId == item.id
        val isLastSelectedSession = !SessionStore.activeClassRunning && SessionStore.activeSessionId == item.id

        holder.title.text = item.title
        holder.subtitle.text = item.subtitle
        holder.status.text = if (isActiveSession) "EN CURSO" else "ULTIMA"
        holder.status.setTextColor(Color.parseColor(if (isActiveSession) "#166534" else "#475569"))
        holder.status.setBackgroundColor(Color.parseColor(if (isActiveSession) "#DCFCE7" else "#E2E8F0"))
        holder.status.visibility = if (isActiveSession || isLastSelectedSession) View.VISIBLE else View.GONE
        holder.hint.text = if (isActiveSession) "Toca para retomar la clase activa" else "Ultima sesion abierta"
        holder.hint.setTextColor(Color.parseColor(if (isActiveSession) "#15803D" else "#64748B"))
        holder.hint.visibility = if (isActiveSession || isLastSelectedSession) View.VISIBLE else View.GONE
        holder.itemView.setBackgroundColor(
            when {
                isActiveSession -> Color.parseColor("#ECFDF5")
                isLastSelectedSession -> Color.parseColor("#F8FAFC")
                else -> Color.WHITE
            }
        )
        holder.itemView.setOnClickListener { onItemClick(item) }
    }

    override fun getItemCount() = items.size

    fun updateData(newItems: List<SelectionItem>) {
        items = newItems
        notifyDataSetChanged()
    }
}
