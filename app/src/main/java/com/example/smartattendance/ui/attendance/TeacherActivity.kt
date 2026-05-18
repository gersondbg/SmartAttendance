package com.example.smartattendance.ui.attendance

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.smartattendance.R
import com.example.smartattendance.data.remote.SessionStore
import com.example.smartattendance.databinding.ActivityTeacherBinding
import com.example.smartattendance.domain.model.StudentSummary
import com.example.smartattendance.ui.login.LoginActivity
import kotlinx.coroutines.launch
import java.io.Serializable
import java.util.UUID

class TeacherActivity : AppCompatActivity() {

    private companion object {
        private val SMART_UUID = ParcelUuid(UUID.fromString("0000b81d-0000-1000-8000-00805f9b34fb"))
    }

    private lateinit var binding: ActivityTeacherBinding
    private val viewModel: TeacherViewModel by viewModels()
    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private lateinit var adapter: StudentAdapter
    
    private val scanHandler = Handler(Looper.getMainLooper())
    private val tickHandler = Handler(Looper.getMainLooper())

    private val autoScanRunnable = object : Runnable {
        override fun run() {
            if (viewModel.state.value.isClassActive && !viewModel.state.value.isPaused) refreshBleScan()
            scanHandler.postDelayed(this, 5_000L)
        }
    }

    private val tickRunnable = object : Runnable {
        override fun run() {
            viewModel.onIntent(TeacherIntent.Tick)
            tickHandler.postDelayed(this, 1000L)
        }
    }

    private val serviceDetectionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != TeacherClassSessionService.ACTION_STUDENT_DETECTED) return
            val username = intent.getStringExtra(TeacherClassSessionService.EXTRA_USERNAME).orEmpty()
            if (username.isBlank()) return
            val isMoving = intent.getBooleanExtra(TeacherClassSessionService.EXTRA_MOVING, false)
            val isRestart = intent.getBooleanExtra(TeacherClassSessionService.EXTRA_RESTART, false)
            viewModel.onIntent(TeacherIntent.OnStudentDiscovered(username, isMoving, isRestart))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.WHITE))
        binding = ActivityTeacherBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setupRecyclerView()
        setupUI()
        observeViewModel()
        requestBluetoothSetup()
        registerReceiverCompat()
        
        viewModel.onIntent(TeacherIntent.LoadStudents)
    }

    private fun registerReceiverCompat() {
        val filter = IntentFilter(TeacherClassSessionService.ACTION_STUDENT_DETECTED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(serviceDetectionReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(serviceDetectionReceiver, filter)
        }
    }

    private fun setupRecyclerView() {
        adapter = StudentAdapter(emptyList()) { student, newStatus -> 
            viewModel.onIntent(TeacherIntent.UpdateStudentStatus(student.id, newStatus))
        }
        binding.rvStudents.layoutManager = LinearLayoutManager(this)
        binding.rvStudents.adapter = adapter
    }

    private fun setupUI() {
        val teacherName = intent.getStringExtra("USER_NAME") ?: "Profesor"
        binding.tvWelcome.text = getString(R.string.hello_user, teacherName)
        
        binding.btnStart.setOnClickListener { 
            val duration = binding.etDuration.text.toString().toIntOrNull() ?: 60
            viewModel.onIntent(TeacherIntent.StartClass(duration))
            TeacherClassSessionService.start(this)
            tickHandler.post(tickRunnable)
            scanHandler.post(autoScanRunnable)
        }
        
        binding.btnPause.setOnClickListener { viewModel.onIntent(TeacherIntent.TogglePause) }
        binding.btnFinish.setOnClickListener { viewModel.onIntent(TeacherIntent.FinishClass) }
        binding.btnLogout.setOnClickListener { if (viewModel.state.value.isClassActive) viewModel.onIntent(TeacherIntent.FinishClass); finish() }
        binding.btnScan.setOnClickListener { refreshBleScan() }
        binding.btnSummary.setOnClickListener { viewModel.onIntent(TeacherIntent.FinishClass) }
        
        binding.tvPresentCount.setOnLongClickListener {
            viewModel.onIntent(TeacherIntent.ResetAttendance)
            Toast.makeText(this, "Asistencia reiniciada", Toast.LENGTH_SHORT).show()
            true
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.state.collect { state ->
                binding.tvCourseName.text = "${state.courseName} | ${state.sessionName}"
                binding.btnStart.isEnabled = !state.isClassActive
                binding.etDuration.isEnabled = !state.isClassActive
                binding.btnPause.isEnabled = state.isClassActive
                binding.btnFinish.isEnabled = state.isClassActive
                binding.btnPause.text = if (state.isPaused) getString(R.string.btn_resume) else getString(R.string.btn_pause)
                
                val remaining = state.remainingSeconds
                binding.tvTimer.text = if (state.isClassActive) 
                    getString(R.string.elapsed_time, remaining / 60, remaining % 60) else "00:00"

                val displayList = state.students.values.map {
                    val concentration = if (state.elapsedSeconds > 0) (it.presentSeconds * 100 / state.elapsedSeconds).coerceIn(0, 100) else 0
                    StudentDisplay(
                        id = it.id,
                        username = it.username,
                        fullName = it.fullName,
                        status = it.manualStatus ?: if (it.attended) "P" else "A",
                        attendanceText = if (it.attended) "Asistencia: ASISTIO" else "Asistencia: NO",
                        presenceText = presenceLabel(it.presenceState),
                        concentration = concentration,
                        presenceState = it.presenceState,
                        lastSeenMillis = it.lastSeenAtMillis,
                        disconnections = it.disconnections
                    )
                }
                adapter.updateData(displayList)
                binding.tvPresentCount.text = getString(R.string.students_present, displayList.count { it.status == "P" })
                binding.progressBar.visibility = if (state.isLoading) View.VISIBLE else View.GONE
                
                // Update Notifications and ensure robustness
                val avgConcentration = if (displayList.isNotEmpty()) displayList.map { it.concentration }.average().toInt() else 0
                
                TeacherClassNotificationState.remainingSeconds = state.remainingSeconds
                TeacherClassNotificationState.paused = state.isPaused
                TeacherClassNotificationState.presentCount = displayList.count { it.status == "P" }
                TeacherClassNotificationState.totalStudents = displayList.size
                TeacherClassNotificationState.courseTitle = state.courseName
                TeacherClassNotificationState.averageConcentrationPercent = avgConcentration
            }
        }

        lifecycleScope.launch {
            viewModel.effect.collect { effect ->
                when (effect) {
                    is TeacherEffect.ShowMessage -> Toast.makeText(this@TeacherActivity, getString(effect.messageRes), Toast.LENGTH_SHORT).show()
                    is TeacherEffect.NavigateToSummary -> {
                        TeacherClassSessionService.stop(this@TeacherActivity)
                        stopBleScan()
                        tickHandler.removeCallbacks(tickRunnable)
                        scanHandler.removeCallbacks(autoScanRunnable)
                        
                        val intent = Intent(this@TeacherActivity, SummaryActivity::class.java)
                        intent.putExtra("SUMMARY_LIST", effect.summary as Serializable)
                        intent.putExtra("COURSE_NAME", SessionStore.activeCourseName)
                        intent.putExtra("TOTAL_TIME", effect.totalTime)
                        startActivity(intent)
                    }
                }
            }
        }
    }

    private fun refreshBleScan() {
        try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) return
            val scanner = bluetoothAdapter?.bluetoothLeScanner
            if (scanner == null) {
                Toast.makeText(this, "Bluetooth no disponible o apagado", Toast.LENGTH_SHORT).show()
                return
            }
            scanner.stopScan(leScanCallback)
            val settings = android.bluetooth.le.ScanSettings.Builder()
                .setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            val filters = listOf(android.bluetooth.le.ScanFilter.Builder().setServiceUuid(SMART_UUID).build())
            scanner.startScan(filters, settings, leScanCallback)
            scanHandler.postDelayed({ stopBleScan() }, 4_500L)
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Error al iniciar escaneo: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopBleScan() {
        try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
                bluetoothAdapter?.bluetoothLeScanner?.stopScan(leScanCallback)
            }
        } catch (_: Exception) {}
    }

    private val leScanCallback = object : android.bluetooth.le.ScanCallback() {
        override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult?) {
            try {
                result?.scanRecord?.getServiceData(SMART_UUID)?.let { data ->
                    val deviceName = String(data, Charsets.UTF_8)
                    val username = deviceName.substringAfter("-").trim()
                    val isMoving = deviceName.contains("MV-")
                    val isMaintenanceRestart = deviceName.contains("RS-")
                    viewModel.onIntent(TeacherIntent.OnStudentDiscovered(username, isMoving, isMaintenanceRestart))
                }
            } catch (e: Exception) {
                // Evitar crash por datos corruptos en el aire
                e.printStackTrace()
            }
        }
    }

    private fun requestBluetoothSetup() {
        val perms = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }.toTypedArray()
        val missing = perms.filter { ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1001)
    }

    private fun presenceLabel(state: PresenceState): String {
        return when (state) {
            PresenceState.NotSeen -> "NO VISTO"
            PresenceState.InClass -> "EN AULA"
            PresenceState.Moving -> "MOVIMIENTO"
            PresenceState.Restarting -> "REINICIO APP"
            PresenceState.SignalLost -> "SIN SENAL"
            PresenceState.Disconnected -> "DESCONECTADO"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        tickHandler.removeCallbacks(tickRunnable)
        scanHandler.removeCallbacks(autoScanRunnable)
        stopBleScan()
        try {
            unregisterReceiver(serviceDetectionReceiver)
        } catch (_: Exception) {
        }
    }
}
