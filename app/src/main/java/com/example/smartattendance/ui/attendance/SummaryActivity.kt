package com.example.smartattendance.ui.attendance

import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.os.Bundle
import android.os.Environment
import java.util.Locale
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.example.smartattendance.data.remote.SessionStore
import com.example.smartattendance.data.repository.AttendanceRepositoryImpl
import com.example.smartattendance.databinding.ActivitySummaryBinding
import com.example.smartattendance.domain.model.StudentSummary
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.Serializable
import java.text.SimpleDateFormat
import java.util.Date

class SummaryActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySummaryBinding
    private val repository = AttendanceRepositoryImpl()
    private var summaryList: List<StudentSummary> = emptyList()
    private var courseName: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySummaryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Recuperar datos reales de la sesión
        summaryList = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getSerializableExtra("SUMMARY_LIST", ArrayList::class.java) as? List<StudentSummary> ?: emptyList()
        } else {
            @Suppress("DEPRECATION")
            intent.getSerializableExtra("SUMMARY_LIST") as? List<StudentSummary> ?: emptyList()
        }
        courseName = intent.getStringExtra("COURSE_NAME") ?: "Sin especificar"

        setupUI()
    }

    private fun setupUI() {
        val totalSec = intent.getIntExtra("TOTAL_TIME", 0)
        val minutes = totalSec / 60
        val seconds = totalSec % 60
        
        binding.tvClassStats.text = String.format(Locale.getDefault(), 
            "Curso: %s\nDuración efectiva: %02d:%02d\n%d alumnos evaluados", 
            courseName, minutes, seconds, summaryList.size)
        
        if (summaryList.isEmpty()) {
            binding.lvSummaryStudents.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, listOf("No hay datos de sesión"))
        } else {
            val displayStrings = summaryList.map { 
                "${it.fullName} (${it.username})\nEstado: ${it.status} | Conc: ${it.concentration}%"
            }
            val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, displayStrings)
            binding.lvSummaryStudents.adapter = adapter
        }

        binding.btnExportPdf.setOnClickListener {
            generateAndSharePdf()
        }

        binding.btnSyncMoodle.setOnClickListener {
            syncToMoodle()
        }

        binding.btnBack.setOnClickListener {
            finish()
        }
    }

    private fun syncToMoodle() {
        val sessionId = SessionStore.activeSessionId
        if (sessionId == null) {
            Toast.makeText(this, "Error: No hay una sesión activa de Moodle", Toast.LENGTH_SHORT).show()
            return
        }

        binding.btnSyncMoodle.isEnabled = false
        binding.btnSyncMoodle.text = "SINCRONIZANDO..."

        lifecycleScope.launch {
            var successCount = 0
            summaryList.forEach { student ->
                repository.markAttendance(sessionId, student.id, student.status).onSuccess {
                    successCount++
                }
            }
            
            Toast.makeText(this@SummaryActivity, "Sincronizados $successCount de ${summaryList.size} alumnos", Toast.LENGTH_LONG).show()
            binding.btnSyncMoodle.isEnabled = true
            binding.btnSyncMoodle.text = "SUBIR A MOODLE CLOUD"
            
            if (successCount == summaryList.size) {
                binding.btnSyncMoodle.setBackgroundColor(android.graphics.Color.GRAY)
                binding.btnSyncMoodle.text = "¡SINCRONIZADO!"
                binding.btnSyncMoodle.isEnabled = false
            }
        }
    }

    private fun generateAndSharePdf() {
        if (summaryList.isEmpty()) {
            Toast.makeText(this, "No hay datos para exportar", Toast.LENGTH_SHORT).show()
            return
        }

        val pdfDocument = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create() // A4 size
        val page = pdfDocument.startPage(pageInfo)
        val canvas = page.canvas
        val paint = Paint()
        val titlePaint = Paint().apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = 18f
        }
        val textPaint = Paint().apply {
            textSize = 12f
        }

        var y = 40f
        canvas.drawText("INFORME DE ASISTENCIA - SMART ATTENDANCE", 50f, y, titlePaint)
        y += 30f
        canvas.drawText("Curso: $courseName", 50f, y, textPaint)
        y += 20f
        canvas.drawText("Fecha: ${SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date())}", 50f, y, textPaint)
        y += 10f
        canvas.drawLine(50f, y, 545f, y, paint)
        y += 30f

        // Table Header
        canvas.drawText("Estudiante", 50f, y, titlePaint)
        canvas.drawText("Estado", 350f, y, titlePaint)
        canvas.drawText("Conc %", 450f, y, titlePaint)
        y += 20f

        summaryList.forEach { student ->
            if (y > 800) { // Simple page break handling (just one page for now or cut off)
                // In a real app, you'd start a new page here
            }
            canvas.drawText(student.fullName, 50f, y, textPaint)
            canvas.drawText(student.status, 350f, y, textPaint)
            canvas.drawText("${student.concentration}%", 450f, y, textPaint)
            y += 20f
        }

        pdfDocument.finishPage(page)

        val fileName = "Asistencia_${courseName.replace(" ", "_")}_${System.currentTimeMillis()}.pdf"
        val file = File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), fileName)

        try {
            pdfDocument.writeTo(FileOutputStream(file))
            Toast.makeText(this, "PDF generado con éxito", Toast.LENGTH_SHORT).show()
            shareFile(file)
        } catch (e: IOException) {
            e.printStackTrace()
            Toast.makeText(this, "Error al generar PDF: ${e.message}", Toast.LENGTH_LONG).show()
        } finally {
            pdfDocument.close()
        }
    }

    private fun shareFile(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Compartir Reporte PDF"))
    }
}
