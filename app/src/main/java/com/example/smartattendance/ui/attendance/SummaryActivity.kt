package com.example.smartattendance.ui.attendance

import com.example.smartattendance.R
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.os.Bundle
import android.os.Environment
import java.util.Locale
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
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
    private val viewModel: SummaryViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySummaryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val serializableList = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getSerializableExtra("SUMMARY_LIST", ArrayList::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getSerializableExtra("SUMMARY_LIST")
        }
        
        @Suppress("UNCHECKED_CAST")
        val summaryList = (serializableList as? List<StudentSummary>) ?: emptyList()
        val courseName = intent.getStringExtra("COURSE_NAME") ?: getString(R.string.unspecified)
        val totalTime = intent.getIntExtra("TOTAL_TIME", 0)

        viewModel.onIntent(SummaryIntent.Init(summaryList, courseName, totalTime))

        setupUI()
        observeViewModel()
    }

    private fun setupUI() {
        binding.btnExportPdf.setOnClickListener {
            generateAndSharePdf()
        }

        binding.btnSyncMoodle.setOnClickListener {
            viewModel.onIntent(SummaryIntent.SyncToMoodle)
        }

        binding.btnBack.setOnClickListener {
            finish()
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.state.collect { state ->
                val minutes = state.totalTime / 60
                val seconds = state.totalTime % 60
                binding.tvClassStats.text = "Curso: ${state.courseName}\nDuracion efectiva: ${String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)}\n${state.summaryList.size} alumnos evaluados"

                val attended = state.summaryList.count { it.status == "P" }
                val averageConc = if (state.summaryList.isNotEmpty()) state.summaryList.map { it.concentration }.average().toInt() else 0
                val totalCuts = state.summaryList.sumOf { it.disconnections }
                val synced = state.summaryList.count { it.moodleSynced }

                binding.tvAttendanceMetric.text = "Asistencia\n$attended/${state.summaryList.size}"
                binding.tvConcentrationMetric.text = "Concentracion\n$averageConc%"
                binding.tvMoodleMetric.text = "Moodle: $synced/${state.summaryList.size} alumnos sincronizados | Cortes totales: $totalCuts"

                binding.tvSummaryStudents.text = if (state.summaryList.isEmpty()) {
                    getString(R.string.no_session_data)
                } else {
                    state.summaryList.joinToString("\n\n") { student ->
                        val attendance = if (student.status == "P") "ASISTIO" else "NO ASISTIO"
                        val time = formatSeconds(student.presentSeconds)
                        val moodle = if (student.moodleSynced) "OK" else "PENDIENTE"
                        "${student.fullName} (${student.username})\n" +
                            "Asistencia Moodle: $attendance (${student.status})\n" +
                            "Presencia final: ${student.presenceFinal}\n" +
                            "Concentracion: ${student.concentration}% | Tiempo presente: $time\n" +
                            "Cortes de senal: ${student.disconnections} | Moodle: $moodle"
                    }
                }

                binding.btnSyncMoodle.isEnabled = !state.isSyncing && !state.syncSuccess
                if (state.isSyncing) {
                    binding.btnSyncMoodle.text = getString(R.string.syncing_progress, state.syncProgress, state.syncTotal)
                } else if (state.syncSuccess) {
                    binding.btnSyncMoodle.text = getString(R.string.synced_success)
                    binding.btnSyncMoodle.setBackgroundColor(android.graphics.Color.GRAY)
                } else {
                    binding.btnSyncMoodle.text = getString(R.string.retry_sync_moodle)
                }
            }
        }

        lifecycleScope.launch {
            viewModel.effect.collect { effect ->
                when (effect) {
                    is SummaryEffect.ShowMessage -> Toast.makeText(this@SummaryActivity, effect.message, Toast.LENGTH_SHORT).show()
                    is SummaryEffect.ShowError -> Toast.makeText(this@SummaryActivity, getString(effect.messageRes), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    private fun formatSeconds(seconds: Int): String {
        val minutes = seconds / 60
        val rest = seconds % 60
        return String.format(Locale.getDefault(), "%02d:%02d", minutes, rest)
    }
    private fun generateAndSharePdf() {
        val state = viewModel.state.value
        val summaryList = state.summaryList
        if (summaryList.isEmpty()) {
            Toast.makeText(this, getString(R.string.no_data_export), Toast.LENGTH_SHORT).show()
            return
        }

        val pdfDocument = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create()
        val page = pdfDocument.startPage(pageInfo)
        val canvas = page.canvas
        val linePaint = Paint().apply { color = android.graphics.Color.rgb(203, 213, 225) }
        val titlePaint = Paint().apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = 18f
            color = android.graphics.Color.rgb(30, 58, 138)
        }
        val subtitlePaint = Paint().apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = 11f
            color = android.graphics.Color.rgb(15, 23, 42)
        }
        val textPaint = Paint().apply {
            textSize = 9.5f
            color = android.graphics.Color.rgb(51, 65, 85)
        }

        val attended = summaryList.count { it.status == "P" }
        val averageConc = summaryList.map { it.concentration }.average().toInt()
        val totalCuts = summaryList.sumOf { it.disconnections }
        val synced = summaryList.count { it.moodleSynced }

        var y = 38f
        canvas.drawText("INFORME FINAL - SMART ATTENDANCE", 40f, y, titlePaint)
        y += 22f
        canvas.drawText("Curso: ${state.courseName}", 40f, y, textPaint)
        y += 15f
        canvas.drawText("Fecha: ${SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date())}", 40f, y, textPaint)
        y += 15f
        canvas.drawText("Duracion efectiva: ${formatSeconds(state.totalTime)} | Alumnos: ${summaryList.size}", 40f, y, textPaint)
        y += 18f
        canvas.drawText("Asistencia: $attended/${summaryList.size} | Concentracion promedio: $averageConc% | Cortes: $totalCuts | Moodle: $synced/${summaryList.size}", 40f, y, subtitlePaint)
        y += 18f
        canvas.drawText("Criterio: un alumno asiste si fue detectado por Bluetooth BLE durante la clase activa.", 40f, y, textPaint)
        y += 13f
        canvas.drawText("La concentracion mide permanencia estable; los cortes indican perdidas de senal.", 40f, y, textPaint)
        y += 20f
        canvas.drawLine(40f, y, 555f, y, linePaint)
        y += 18f

        canvas.drawText("Alumno", 40f, y, subtitlePaint)
        canvas.drawText("Asist.", 190f, y, subtitlePaint)
        canvas.drawText("Presencia final", 250f, y, subtitlePaint)
        canvas.drawText("Conc.", 370f, y, subtitlePaint)
        canvas.drawText("Tiempo", 420f, y, subtitlePaint)
        canvas.drawText("Cortes", 475f, y, subtitlePaint)
        canvas.drawText("Moodle", 525f, y, subtitlePaint)
        y += 12f
        canvas.drawLine(40f, y, 555f, y, linePaint)
        y += 16f

        summaryList.forEach { student ->
            if (y > 810f) return@forEach
            val name = student.fullName.take(25)
            val attendance = if (student.status == "P") "SI" else "NO"
            val moodle = if (student.moodleSynced) "OK" else "PEND"
            canvas.drawText(name, 40f, y, textPaint)
            canvas.drawText(attendance, 190f, y, textPaint)
            canvas.drawText(student.presenceFinal.take(16), 250f, y, textPaint)
            canvas.drawText("${student.concentration}%", 370f, y, textPaint)
            canvas.drawText(formatSeconds(student.presentSeconds), 420f, y, textPaint)
            canvas.drawText(student.disconnections.toString(), 485f, y, textPaint)
            canvas.drawText(moodle, 525f, y, textPaint)
            y += 18f
        }

        pdfDocument.finishPage(page)

        val fileName = "Asistencia_${state.courseName.replace(" ", "_")}_${System.currentTimeMillis()}.pdf"
        val file = File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), fileName)

        try {
            pdfDocument.writeTo(FileOutputStream(file))
            Toast.makeText(this, getString(R.string.pdf_success), Toast.LENGTH_SHORT).show()
            shareFile(file)
        } catch (e: IOException) {
            e.printStackTrace()
            Toast.makeText(this, getString(R.string.pdf_error, e.message ?: ""), Toast.LENGTH_LONG).show()
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
        startActivity(Intent.createChooser(intent, getString(R.string.share_report)))
    }
}
