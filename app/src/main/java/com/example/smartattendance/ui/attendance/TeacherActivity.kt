package com.example.smartattendance.ui.attendance

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.smartattendance.data.remote.SessionStore
import com.example.smartattendance.data.repository.AttendanceRepositoryImpl
import com.example.smartattendance.databinding.ActivityTeacherBinding
import com.example.smartattendance.domain.model.StudentSummary
import com.example.smartattendance.ui.login.LoginActivity
import kotlinx.coroutines.launch
import java.io.Serializable
import java.util.UUID

class TeacherActivity : AppCompatActivity() {

    private companion object {
        private const val SEEN_CONSIDER_AWAY_MS = 30_000L
        private val SMART_UUID = ParcelUuid(UUID.fromString("0000b81d-0000-1000-8000-00805f9b34fb"))
    }

    private data class StudentStats(
        val id: Int,
        val username: String,
        val fullName: String,
        val email: String,
        var presentSeconds: Int = 0,
        var lastSeenAtMillis: Long = 0L,
        var connected: Boolean = false,
        var isMoving: Boolean = false,
        var manualStatus: String? = null
    )

    private lateinit var binding: ActivityTeacherBinding
    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private val repository = AttendanceRepositoryImpl()
    private val studentStats = linkedMapOf<Int, StudentStats>()
    private lateinit var adapter: StudentAdapter
    
    private var isClassActive = false
    private var isPaused = false
    private var elapsedSeconds = 0
    private var timer: CountDownTimer? = null
    private val scanHandler = Handler(Looper.getMainLooper())
    private var isScanning = false

    private val autoScanRunnable = object : Runnable {
        override fun run() {
            if (isClassActive && !isPaused) refreshBleScan()
            scanHandler.postDelayed(this, 5_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.WHITE))
        binding = ActivityTeacherBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setupRecyclerView()
        setupUI()
        requestBluetoothSetup()
        loadInitialData()
    }

    private fun setupRecyclerView() {
        adapter = StudentAdapter(emptyList()) { student, newStatus -> handleManualStatusChange(student, newStatus) }
        binding.rvStudents.layoutManager = LinearLayoutManager(this)
        binding.rvStudents.adapter = adapter
    }

    private fun setupUI() {
        val teacherName = intent.getStringExtra("USER_NAME") ?: "Profesor"
        binding.tvWelcome.text = "Sesión de: $teacherName"
        
        // Mostrar contexto real de Moodle
        val course = SessionStore.activeCourseName ?: "Curso"
        val sessionDesc = SessionStore.activeAttendanceName ?: "Asistencia"
        binding.tvCourseName.text = "$course | $sessionDesc"

        binding.btnStart.setOnClickListener { startClass() }
        binding.btnPause.setOnClickListener { togglePause() }
        binding.btnFinish.setOnClickListener { finishClass() }
        binding.btnLogout.setOnClickListener { backToCourses() }
        binding.btnScan.setOnClickListener { scanNow() }
        binding.btnSummary.setOnClickListener { openSummary() }
        
        // Inicialmente el botón de reporte (RES) puede estar oculto o deshabilitado hasta que termine la clase si prefieres, 
        // pero lo dejaremos habilitado para ver progreso.
    }

    private fun loadInitialData() {
        val courseId = SessionStore.activeCourseId ?: return
        binding.progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            repository.getAttendanceSessions(courseId).onSuccess { id -> SessionStore.activeSessionId = id }
            repository.getEnrolledStudents(courseId).onSuccess { list ->
                studentStats.clear()
                list.forEach { studentStats[it.id] = StudentStats(it.id, it.username, it.fullname, it.email) }
                updateUIList()
            }
            binding.progressBar.visibility = View.GONE
        }
    }

    private fun startClass() {
        val durationMin = binding.etDuration.text.toString().toIntOrNull() ?: 60
        val durationSec = durationMin * 60
        
        isClassActive = true
        isPaused = false
        binding.btnStart.isEnabled = false
        binding.etDuration.isEnabled = false
        
        startTimer(durationSec)
        startAutoScanner()
        TeacherClassSessionService.start(this)
        logAction("CLASE INICIADA ($durationMin min)")
    }

    private fun startTimer(seconds: Int) {
        timer?.cancel()
        timer = object : CountDownTimer((seconds * 1000).toLong(), 1000) {
            override fun onTick(millisUntilFinished: Long) {
                if (!isPaused) {
                    elapsedSeconds++
                    val remaining = millisUntilFinished / 1000
                    binding.tvTimer.text = String.format("%02d:%02d", remaining / 60, remaining % 60)
                    studentStats.values.forEach { if (it.connected && !it.isMoving) it.presentSeconds++ }
                    
                    // Actualizar el estado de la notificación
                    TeacherClassNotificationState.remainingSeconds = remaining.toInt()
                    TeacherClassNotificationState.paused = isPaused

                    updateUIList()
                }
            }
            override fun onFinish() { finishClass() }
        }.start()
    }

    private fun handleManualStatusChange(student: StudentDisplay, newStatus: String) {
        val stats = studentStats[student.id] ?: return
        stats.manualStatus = newStatus
        SessionStore.activeSessionId?.let { sid -> lifecycleScope.launch { repository.markAttendance(sid, student.id, newStatus) } }
        updateUIList()
    }

    private fun updateUIList() {
        val now = System.currentTimeMillis()
        val displayList = studentStats.values.map {
            if (it.lastSeenAtMillis > 0 && (now - it.lastSeenAtMillis) > SEEN_CONSIDER_AWAY_MS) it.connected = false
            val concentration = if (elapsedSeconds > 0) (it.presentSeconds * 100 / elapsedSeconds).coerceIn(0, 100) else 0
            StudentDisplay(it.id, it.username, it.fullName, it.manualStatus ?: "A", concentration, it.connected, it.isMoving, it.lastSeenAtMillis)
        }
        adapter.updateData(displayList)
        binding.tvPresentCount.text = displayList.count { it.status == "P" }.toString()
        
        // Actualizar estado para la notificación en segundo plano
        TeacherClassNotificationState.presentCount = displayList.count { it.status == "P" }
        TeacherClassNotificationState.totalStudents = displayList.size
        TeacherClassNotificationState.averageConcentrationPercent = if (displayList.isNotEmpty()) {
            displayList.sumOf { it.concentration } / displayList.size
        } else 0
        TeacherClassNotificationState.courseTitle = SessionStore.activeCourseName ?: "Clase"
    }

    private fun scanNow() { refreshBleScan(); logAction("Escaneando...") }

    private fun refreshBleScan() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) return
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return
        val settings = android.bluetooth.le.ScanSettings.Builder().setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        val filters = listOf(android.bluetooth.le.ScanFilter.Builder().setServiceUuid(SMART_UUID).build())
        scanner.startScan(filters, settings, leScanCallback)
        isScanning = true
    }

    private val leScanCallback = object : android.bluetooth.le.ScanCallback() {
        override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult?) {
            result?.scanRecord?.getServiceData(SMART_UUID)?.let { data ->
                val deviceName = String(data, Charsets.UTF_8)
                val username = deviceName.substringAfter("-").trim()
                studentStats.values.firstOrNull { it.username.equals(username, true) }?.let {
                    it.lastSeenAtMillis = System.currentTimeMillis()
                    it.connected = true
                    it.isMoving = deviceName.contains("MV-")
                    if (it.manualStatus == null || it.manualStatus == "A") it.manualStatus = "P"
                    updateUIList()
                }
            }
        }
    }

    private fun startAutoScanner() { scanHandler.post(autoScanRunnable) }

    private fun togglePause() { 
        isPaused = !isPaused
        binding.btnPause.text = if (isPaused) "REANUDAR" else "PAUSA"
        TeacherClassNotificationState.paused = isPaused
    }

    private fun finishClass() {
        isClassActive = false
        timer?.cancel()
        scanHandler.removeCallbacks(autoScanRunnable)
        TeacherClassSessionService.stop(this)
        TeacherClassNotificationState.clear()
        binding.tvTimer.text = "FIN"
        
        // Sincronismo Real con Moodle Cloud
        val sessionId = SessionStore.activeSessionId
        if (sessionId != null) {
            logAction("Sincronizando con Moodle...")
            lifecycleScope.launch {
                studentStats.forEach { (userId, stats) ->
                    // Si no tiene estado manual (P,L,E,A), determinamos uno por su concentración (ej > 50% = Presente)
                    // En un entorno profesional, esto se configura según reglas de negocio.
                    val finalStatus = stats.manualStatus ?: if (elapsedSeconds > 0 && (stats.presentSeconds * 100 / elapsedSeconds) > 50) "P" else "A"
                    repository.markAttendance(sessionId, userId, finalStatus)
                }
                logAction("¡Moodle Cloud Actualizado!")
                Toast.makeText(this@TeacherActivity, "Asistencia subida exitosamente a Moodle", Toast.LENGTH_LONG).show()
            }
        } else {
            logAction("Error: No hay SessionID vinculado")
        }

        logAction("CLASE FINALIZADA")
    }

    private fun backToCourses() { if (isClassActive) finishClass(); finish() }

    private fun openSummary() {
        val summaryList = studentStats.values.map { 
            val conc = if (elapsedSeconds > 0) (it.presentSeconds * 100 / elapsedSeconds).coerceIn(0, 100) else 0
            StudentSummary(
                id = it.id,
                fullName = it.fullName,
                username = it.username,
                status = it.manualStatus ?: if (it.connected) "P" else "A",
                concentration = conc,
                presentSeconds = it.presentSeconds
            )
        }
        
        val intent = Intent(this, SummaryActivity::class.java)
        intent.putExtra("SUMMARY_LIST", summaryList as Serializable)
        intent.putExtra("COURSE_NAME", SessionStore.activeCourseName)
        intent.putExtra("TOTAL_TIME", elapsedSeconds)
        startActivity(intent)
    }

    private fun logAction(msg: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        binding.tvLog.text = "[$time] $msg\n${binding.tvLog.text}".take(500)
    }

    private fun requestBluetoothSetup() {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_FINE_LOCATION)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        val missing = perms.filter { ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1001)
    }
}
