package com.example.smartattendance.ui.attendance

import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.smartattendance.databinding.ActivitySummaryBinding

class SummaryActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySummaryBinding
    private var summaryData: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySummaryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        summaryData = intent.getStringArrayExtra("SUMMARY_DATA")?.toList() ?: demoSummary()
        binding.tvClassStats.text = "Resumen: ${summaryData.size} alumnos evaluados"
        binding.lvSummaryStudents.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, summaryData)

        binding.btnExportPdf.setOnClickListener {
            shareReport()
        }

        binding.btnBack.setOnClickListener {
            finish()
        }
    }

    private fun shareReport() {
        val reportText = buildString {
            appendLine("Smart Attendance - Informe final")
            appendLine("Curso: Programación 1")
            appendLine()
            summaryData.forEach { appendLine(it) }
        }

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Informe Smart Attendance")
            putExtra(Intent.EXTRA_TEXT, reportText)
        }
        Toast.makeText(this, "Reporte listo para compartir", Toast.LENGTH_SHORT).show()
        startActivity(Intent.createChooser(intent, "Compartir informe"))
    }

    private fun demoSummary(): List<String> = listOf(
        "Alumno 1 - 95% concentración - ASISTIÓ",
        "Alumno 2 - 88% concentración - ASISTIÓ"
    )
}
