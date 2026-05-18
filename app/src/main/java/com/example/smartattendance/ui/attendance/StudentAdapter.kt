package com.example.smartattendance.ui.attendance

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.smartattendance.databinding.ItemStudentCardBinding

data class StudentDisplay(
    val id: Int,
    val username: String,
    val fullName: String,
    val status: String, // P, L, E, A
    val concentration: Int,
    val isConnected: Boolean,
    val isMoving: Boolean,
    val lastSeenMillis: Long,
    val disconnections: Int
)

class StudentAdapter(
    private var students: List<StudentDisplay>,
    private val onStatusChange: (StudentDisplay, String) -> Unit
) : RecyclerView.Adapter<StudentAdapter.ViewHolder>() {

    inner class ViewHolder(val binding: ItemStudentCardBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemStudentCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val student = students[position]
        with(holder.binding) {
            tvStudentName.text = student.fullName
            
            val connectionText = if (student.isConnected) {
                if (student.isMoving) "¡EN MOVIMIENTO!" else "Conectado"
            } else {
                "Desconectado"
            }
            
            tvStudentDetails.text = "$connectionText | Conc: ${student.concentration}% | Cortes: ${student.disconnections}"
            tvCurrentStatus.text = when(student.status) {
                "P" -> "PRESENTE"
                "L" -> "RETRASO"
                "E" -> "JUSTIFICADO"
                "A" -> "AUSENTE"
                else -> "AUSENTE"
            }

            // Colores del indicador
            val statusColor = when {
                student.isConnected && !student.isMoving -> Color.parseColor("#10B981") // Verde
                student.isConnected && student.isMoving -> Color.parseColor("#F59E0B") // Naranja
                student.status == "E" -> Color.parseColor("#3B82F6") // Azul
                else -> Color.parseColor("#94A3B8") // Gris
            }
            viewStatusIndicator.backgroundTintList = ColorStateList.valueOf(statusColor)

            // Configurar botones
            setupButton(btnP, "P", student.status == "P")
            setupButton(btnL, "L", student.status == "L")
            setupButton(btnE, "E", student.status == "E")
            setupButton(btnA, "A", student.status == "A")

            btnP.setOnClickListener { onStatusChange(student, "P") }
            btnL.setOnClickListener { onStatusChange(student, "L") }
            btnE.setOnClickListener { onStatusChange(student, "E") }
            btnA.setOnClickListener { onStatusChange(student, "A") }
        }
    }

    private fun setupButton(button: com.google.android.material.button.MaterialButton, type: String, isSelected: Boolean) {
        if (isSelected) {
            button.setBackgroundColor(when(type) {
                "P" -> Color.parseColor("#DCFCE7")
                "L" -> Color.parseColor("#FEF3C7")
                "E" -> Color.parseColor("#DBEAFE")
                "A" -> Color.parseColor("#FEE2E2")
                else -> Color.LTGRAY
            })
            button.setTextColor(when(type) {
                "P" -> Color.parseColor("#166534")
                "L" -> Color.parseColor("#92400E")
                "E" -> Color.parseColor("#1E40AF")
                "A" -> Color.parseColor("#991B1B")
                else -> Color.BLACK
            })
            button.strokeWidth = 2
        } else {
            button.setBackgroundColor(Color.TRANSPARENT)
            button.setTextColor(Color.parseColor("#64748B"))
            button.strokeWidth = 1
        }
    }

    override fun getItemCount() = students.size

    fun updateData(newStudents: List<StudentDisplay>) {
        students = newStudents
        notifyDataSetChanged()
    }
}
