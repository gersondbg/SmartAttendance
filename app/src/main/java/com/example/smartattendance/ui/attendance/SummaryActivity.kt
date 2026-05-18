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
import android.widget.ArrayAdapter
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
                binding.tvClassStats.text = getString(R.string.summary_stats, state.courseName, minutes, seconds, state.summaryList.size)
                
                if (state.summaryList.isEmpty()) {
                    binding.lvSummaryStudents.adapter = ArrayAdapter(this@SummaryActivity, android.R.layout.simple_list_item_1, listOf(getString(R.string.no_session_data)))
                } else {
                    val displayStrings = state.summaryList.map { 
                        getString(R.string.student_summary_item, it.fullName, it.username, it.status, it.concentration)
                    }
                    val adapter = ArrayAdapter(this@SummaryActivity, android.R.layout.simple_list_item_1, displayStrings)
                    binding.lvSummaryStudents.adapter = adapter
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
        val paint = Paint()
        val titlePaint = Paint().apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = 18f
        }
        val textPaint = Paint().apply {
            textSize = 12f
        }

        var y = 40f
        canvas.drawText(getString(R.string.report_title), 50f, y, titlePaint)
        y += 30f
        canvas.drawText(getString(R.string.report_course, state.courseName), 50f, y, textPaint)
        y += 20f
        canvas.drawText(getString(R.string.report_date, SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date())), 50f, y, textPaint)
        y += 10f
        canvas.drawLine(50f, y, 545f, y, paint)
        y += 30f

        // Table Header
        canvas.drawText(getString(R.string.header_student), 50f, y, titlePaint)
        canvas.drawText(getString(R.string.header_status), 350f, y, titlePaint)
        canvas.drawText(getString(R.string.header_concentration).replace("%%", "%"), 450f, y, titlePaint)
        y += 20f

        summaryList.forEach { student ->
            canvas.drawText(student.fullName, 50f, y, textPaint)
            canvas.drawText(student.status, 350f, y, textPaint)
            canvas.drawText("${student.concentration}%", 450f, y, textPaint)
            y += 20f
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
