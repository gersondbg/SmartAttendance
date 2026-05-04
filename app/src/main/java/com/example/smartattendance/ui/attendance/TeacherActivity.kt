package com.example.smartattendance.ui.attendance

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.ParcelUuid
import java.util.UUID
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.smartattendance.data.remote.SessionStore
import com.example.smartattendance.data.repository.AttendanceRepositoryImpl
import com.example.smartattendance.databinding.ActivityTeacherBinding
import com.example.smartattendance.domain.model.User
import com.example.smartattendance.ui.login.LoginActivity
import kotlinx.coroutines.launch

class TeacherActivity : AppCompatActivity() {

    private companion object {
        const val MOODLE_SYNC_INTERVAL_MS = 45_000L

        /**
         * Ahora con BLE el escaneo es casi instantaneo.
         * Si no lo vemos en 10s está estable, si pasan 20s sin verlo se considera ausente/corte.
         */
        private const val SEEN_STILL_PRESENT_MS = 10_000L
        private const val SEEN_CONSIDER_AWAY_MS = 20_000L
        private const val PRECLASS_BT_HINT_MS = 30_000L
    }

    private data class StudentStats(
        val username: String,
        val fullName: String,
        val email: String,
        var presentSeconds: Int = 0,
        var disconnections: Int = 0,
        var lastSeenAtMillis: Long = 0L,
        var connected: Boolean = false,
        var isMoving: Boolean = false,
        var attended: Boolean = false,
        var presentAtEndOfClass: Boolean = false
    )

    private data class StudentRow(
        val text: String,
        val connected: Boolean,
        val isMoving: Boolean,
        val hasPreClassSignal: Boolean
    )

    private lateinit var binding: ActivityTeacherBinding
    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private val repository = AttendanceRepositoryImpl()
    private val studentStats = linkedMapOf<String, StudentStats>()
    private var enrolledStudents: List<User> = emptyList()
    private var isClassActive = false
    private var isPaused = false
    private var totalSeconds = 0
    private var elapsedSeconds = 0
    private var timer: CountDownTimer? = null
    private var receiverRegistered = false
    private var lastReportedRemainingSeconds = 0
    private val scanHandler = Handler(Looper.getMainLooper())
    private val autoScanRunnable = object : Runnable {
        override fun run() {
            if (isClassActive && !isPaused) {
                stopBleScan()
                startBleScan()
            }
            scanHandler.postDelayed(this, 4_000L) // Forzar refresco cada 4s para evitar caché de Android
        }
    }

    /** Lista de matriculados desde Moodle: al volver a la pantalla y cada [MOODLE_SYNC_INTERVAL_MS]. */
    private val moodleSyncHandler = Handler(Looper.getMainLooper())
    private val moodleSyncRunnable = object : Runnable {
        override fun run() {
            lifecycleScope.launch { runMoodleStudentSync(silent = true) }
            moodleSyncHandler.postDelayed(this, MOODLE_SYNC_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTeacherBinding.inflate(layoutInflater)
        setContentView(binding.root)
        requestBluetoothSetup()
        checkBluetoothStatus()
        setupUI()
        loadStudents()
        scanNow(showMessage = false)
    }

    override fun onResume() {
        super.onResume()
        if (::binding.isInitialized) {
            checkBluetoothStatus()
            startAutoScanner()
            lifecycleScope.launch { runMoodleStudentSync(silent = true) }
            startMoodleRosterPolling()
        }
    }

    override fun onPause() {
        super.onPause()
        if (!isClassActive) {
            stopAutoScanner()
            stopMoodleRosterPolling()
        }
    }

    override fun onBackPressed() {
        moveTaskToBack(true)
    }

    private fun checkBluetoothStatus() {
        if (bluetoothAdapter == null) {
            binding.tvStatus.text = "Bluetooth no disponible"
            binding.tvStatus.setTextColor(android.graphics.Color.RED)
            return
        }

        if (!bluetoothAdapter.isEnabled) {
            binding.tvStatus.text = "Bluetooth: DESACTIVADO"
            binding.tvStatus.setTextColor(android.graphics.Color.RED)
            Toast.makeText(this, "Activa Bluetooth para iniciar la asistencia", Toast.LENGTH_LONG).show()
            requestEnableBluetooth()
        } else {
            binding.tvStatus.text = "Bluetooth: ACTIVO"
            binding.tvStatus.setTextColor(android.graphics.Color.rgb(22, 163, 74))
        }
    }

    private fun requestPostNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                2005
            )
        }
    }

    private fun requestBluetoothSetup() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        val missingPermissions = permissions.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), 2001)
            Toast.makeText(this, "Permite Bluetooth y ubicacion para detectar alumnos.", Toast.LENGTH_LONG).show()
        }
    }

    private fun requestEnableBluetooth() {
        if (bluetoothAdapter == null || bluetoothAdapter.isEnabled) return
        if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        ) {
            startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        }
    }

    /** API 31+: BLUETOOTH_SCAN; API 26-30: ubicacion fina (requisito historico para discovery). */
    private fun hasBluetoothDiscoveryPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun setupUI() {
        val teacherName = intent.getStringExtra("USER_NAME") ?: "Panel del Profesor"
        val courseName = SessionStore.activeCourseName ?: "Curso detectado automaticamente"
        binding.tvWelcome.text = "$teacherName\n$courseName"
        binding.btnStart.setOnClickListener { startClass() }
        binding.btnScan.setOnClickListener {
            triggerDiscoveryBurst()
            scanNow(showMessage = true)
        }
        binding.btnPause.setOnClickListener { togglePause() }
        binding.btnFinish.setOnClickListener { finishClass() }
        binding.btnLogout.setOnClickListener { logout() }
        binding.btnSummary.setOnClickListener { openSummary() }
    }

    private fun loadStudents() {
        lifecycleScope.launch { runMoodleStudentSync(silent = false) }
    }

    private suspend fun runMoodleStudentSync(silent: Boolean) {
        val courseResult = repository.getCurrentUserCourses()
        val course = courseResult.getOrDefault(emptyList()).firstOrNull()
        if (course != null) {
            SessionStore.activeCourseId = course.id
            SessionStore.activeCourseName = course.fullname
            val teacherName = intent.getStringExtra("USER_NAME") ?: "Panel del Profesor"
            binding.tvWelcome.text = "$teacherName\n${course.fullname}"
        }

        val courseId = SessionStore.activeCourseId ?: 2
        repository.getEnrolledStudents(courseId = courseId).fold(
            onSuccess = { list ->
                mergeMoodleStudentsIntoStats(list)
                if (list.isEmpty() && !silent) {
                    Toast.makeText(
                        this@TeacherActivity,
                        "Moodle devolvio 0 estudiantes en este curso. Revisa matricula, curso activo o permisos del servicio web.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            },
            onFailure = { e ->
                if (!silent) {
                    Toast.makeText(
                        this@TeacherActivity,
                        "No se pudieron cargar alumnos: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        )
        updateListView()
        scanNow(showMessage = false)
    }

    /**
     * Incorpora nuevos matriculados de Moodle sin borrar tiempos de asistencia ya acumulados.
     * Quita de la lista solo alumnos que ya no figuran en Moodle (no borra filas solo-BT).
     */
    private fun mergeMoodleStudentsIntoStats(list: List<User>) {
        val moodleKeys = list.map { student ->
            student.username.ifBlank { student.email.substringBefore("@") }
        }.toSet()

        studentStats.keys.toList().forEach { key ->
            val st = studentStats[key] ?: return@forEach
            val isBluetoothOnly = st.fullName.contains("(detectado no matriculado)")
            if (!isBluetoothOnly && key !in moodleKeys) {
                studentStats.remove(key)
            }
        }

        list.forEach { student ->
            val username = student.username.ifBlank { student.email.substringBefore("@") }
            val prev = studentStats[username]
            studentStats[username] = if (prev != null) {
                prev.copy(fullName = student.fullname, email = student.email)
            } else {
                StudentStats(username, student.fullname, student.email)
            }
        }
        enrolledStudents = list
    }

    private fun startMoodleRosterPolling() {
        stopMoodleRosterPolling()
        moodleSyncHandler.postDelayed(moodleSyncRunnable, MOODLE_SYNC_INTERVAL_MS)
    }

    private fun stopMoodleRosterPolling() {
        moodleSyncHandler.removeCallbacks(moodleSyncRunnable)
    }

    private fun startClass() {
        if (bluetoothAdapter?.isEnabled != true) {
            Toast.makeText(this, "Primero activa Bluetooth para iniciar la clase.", Toast.LENGTH_LONG).show()
            requestEnableBluetooth()
            return
        }

        val minutes = binding.etDuration.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: 1
        totalSeconds = minutes * 60
        elapsedSeconds = 0
        isClassActive = true
        isPaused = false

        requestPostNotificationsIfNeeded()
        TeacherClassSessionService.start(this)

        val now = System.currentTimeMillis()
        studentStats.values.forEach {
            it.presentSeconds = 0
            it.disconnections = 0
            it.presentAtEndOfClass = false
            it.connected = isEffectivelyPresent(it, now)
            it.attended = false
        }
        // "Vino a clase": si ya habia deteccion BT valida al pulsar INICIAR, cuenta como primera vez.
        studentStats.values.forEach { if (it.connected) it.attended = true }

        binding.btnStart.isEnabled = false
        binding.btnPause.isEnabled = true
        binding.btnFinish.isEnabled = true
        binding.etDuration.isEnabled = false
        binding.progressBar.visibility = View.VISIBLE
        binding.progressBar.max = totalSeconds
        binding.progressBar.progress = 0

        startTimer(totalSeconds)
        startAutoScanner()
        triggerDiscoveryBurst()
        lastReportedRemainingSeconds = totalSeconds
        pushClassSessionToNotification(lastReportedRemainingSeconds)
        updateListView()
    }

    /** Varios intentos seguidos para pillar equipos nuevos al iniciar clase o al pulsar escanear. */
    private fun triggerDiscoveryBurst() {
        scanHandler.post { scanNow(showMessage = false) }
        scanHandler.postDelayed({ scanNow(showMessage = false) }, 450L)
        scanHandler.postDelayed({ scanNow(showMessage = false) }, 1200L)
        scanHandler.postDelayed({ scanNow(showMessage = false) }, 2400L)
    }

    private fun pushClassSessionToNotification(remainingSeconds: Int) {
        if (!isClassActive) return
        val denom = elapsedSeconds.coerceAtLeast(1)
        val avg = if (studentStats.isEmpty()) {
            0
        } else {
            studentStats.values.sumOf { ((it.presentSeconds * 100) / denom).coerceIn(0, 100) } / studentStats.size
        }
        TeacherClassNotificationState.paused = isPaused
        TeacherClassNotificationState.remainingSeconds = remainingSeconds
        TeacherClassNotificationState.presentCount = studentStats.values.count { it.connected }
        TeacherClassNotificationState.totalStudents = studentStats.size
        TeacherClassNotificationState.averageConcentrationPercent = avg
        TeacherClassNotificationState.courseTitle =
            SessionStore.activeCourseName ?: (binding.tvWelcome.text?.toString() ?: "")
    }

    private fun startAutoScanner() {
        scanHandler.removeCallbacks(autoScanRunnable)
        scanHandler.post(autoScanRunnable)
    }

    private fun stopAutoScanner() {
        scanHandler.removeCallbacks(autoScanRunnable)
    }

    private fun scanNow(showMessage: Boolean) {
        if (bluetoothAdapter?.isEnabled != true) {
            if (showMessage) Toast.makeText(this, "Activa Bluetooth para escanear alumnos.", Toast.LENGTH_LONG).show()
            requestEnableBluetooth()
            return
        }

        if (!hasBluetoothDiscoveryPermission()) {
            requestBluetoothSetup()
            if (showMessage) Toast.makeText(this, "Permite Bluetooth y ubicacion y vuelve a escanear.", Toast.LENGTH_LONG).show()
            return
        }

        stopBleScan()
        startBleScan()
        if (showMessage) Toast.makeText(this, "Escaneando alumnos (BLE)...", Toast.LENGTH_SHORT).show()
    }

    private fun startTimer(seconds: Int) {
        timer?.cancel()
        timer = object : CountDownTimer((seconds * 1000).toLong(), 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val remaining = millisUntilFinished / 1000
                binding.tvTimer.text = String.format("%02d:%02d", remaining / 60, remaining % 60)
                if (!isPaused) {
                    elapsedSeconds = totalSeconds - (millisUntilFinished / 1000).toInt()
                    binding.progressBar.progress = elapsedSeconds
                    refreshConnectionStates()
                    studentStats.values.filter { it.connected }.forEach {
                        if (!it.isMoving) {
                            it.presentSeconds += 1
                        } else {
                            if (it.presentSeconds > 0) it.presentSeconds -= 1
                        }
                    }
                    updateListView()
                }
                lastReportedRemainingSeconds = remaining.toInt()
                pushClassSessionToNotification(lastReportedRemainingSeconds)
            }

            override fun onFinish() {
                finishClass()
            }
        }.start()
    }

    private fun togglePause() {
        isPaused = !isPaused
        binding.btnPause.text = if (isPaused) "REANUDAR" else "RECESO"
        Toast.makeText(this, if (isPaused) "Receso iniciado" else "Clase reanudada", Toast.LENGTH_SHORT).show()
        pushClassSessionToNotification(lastReportedRemainingSeconds)
    }

    private val SMART_UUID = ParcelUuid(UUID.fromString("0000b81d-0000-1000-8000-00805f9b34fb"))
    private var isScanning = false
    
    private val leScanCallback = object : android.bluetooth.le.ScanCallback() {
        override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult?) {
            super.onScanResult(callbackType, result)
            result?.scanRecord?.getServiceData(SMART_UUID)?.let { data ->
                val deviceName = String(data, Charsets.UTF_8)
                if (deviceName.isNotBlank()) {
                    markSeen(deviceName)
                }
            }
        }
    }

    private fun startBleScan() {
        if (bluetoothAdapter?.isEnabled != true || !hasBluetoothDiscoveryPermission()) return
        val scanner = bluetoothAdapter.bluetoothLeScanner ?: return

        try {
            val filters = listOf(android.bluetooth.le.ScanFilter.Builder().setServiceUuid(SMART_UUID).build())
            val settings = android.bluetooth.le.ScanSettings.Builder()
                .setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            scanner.startScan(filters, settings, leScanCallback)
            isScanning = true
        } catch (_: Exception) {}
    }

    private fun stopBleScan() {
        if (!isScanning) return
        try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                bluetoothAdapter?.bluetoothLeScanner?.stopScan(leScanCallback)
            }
        } catch (_: Exception) {}
        isScanning = false
    }

    private fun markSeen(deviceName: String) {
        val isMoving = deviceName.contains("MV-", ignoreCase = true)
        val isSmart = deviceName.contains("SA-", ignoreCase = true)
        if (!isSmart && !isMoving) return

        val matched = studentStats.values.firstOrNull {
            deviceName.contains(it.username, ignoreCase = true) ||
                (it.email.isNotBlank() && deviceName.contains(it.email.substringBefore("@"), ignoreCase = true)) ||
                deviceName.contains(it.fullName, ignoreCase = true)
        } ?: createDetectedStudent(deviceName)

        if (matched != null) {
            matched.lastSeenAtMillis = System.currentTimeMillis()
            if (isClassActive) {
                matched.connected = true
                matched.attended = true
                matched.isMoving = isMoving
            } else {
                matched.connected = false
                matched.isMoving = false
            }
            refreshConnectionStates()
            updateListView()
            if (isClassActive) {
                pushClassSessionToNotification(lastReportedRemainingSeconds)
            }
        }
    }

    private fun createDetectedStudent(deviceName: String): StudentStats? {
        val isSmart = deviceName.contains("SA-", ignoreCase = true)
        val isMoving = deviceName.contains("MV-", ignoreCase = true)
        if (!isSmart && !isMoving) return null

        val prefix = if (isSmart) "SA-" else "MV-"
        val username = deviceName.substringAfter(prefix, "").trim()
        if (username.isBlank()) return null

        val existing = studentStats[username]
        if (existing != null) return existing

        val fullName = username.replaceFirstChar { it.uppercase() }
        val stats = StudentStats(username = username, fullName = "$fullName (detectado no matriculado)", email = "")
        studentStats[username] = stats
        return stats
    }

    private fun refreshConnectionStates() {
        val now = System.currentTimeMillis()
        studentStats.values.forEach { stats ->
            val wasConnected = stats.connected
            val isStillConnected = isEffectivelyPresent(stats, now)
            stats.connected = isStillConnected
            if (wasConnected && !isStillConnected && isClassActive) {
                stats.disconnections += 1
            }
        }
    }

    /**
     * Solo aplica **con clase iniciada**. Antes de INICIAR no hay "en sala" para concentracion ni lista verde.
     * [SEEN_STILL_PRESENT_MS] o menos desde la ultima detección → presente.
     * [SEEN_CONSIDER_AWAY_MS] o más → ausente (salio de rango o apago BT).
     * Entre ambos → se conserva el estado anterior (no parpadea por huecos del escaneo).
     */
    private fun isEffectivelyPresent(stats: StudentStats, now: Long): Boolean {
        if (!isClassActive) return false
        if (stats.lastSeenAtMillis <= 0L) return false
        val sinceSeen = now - stats.lastSeenAtMillis
        if (sinceSeen <= SEEN_STILL_PRESENT_MS) return true
        if (sinceSeen >= SEEN_CONSIDER_AWAY_MS) return false
        return stats.connected
    }

    private fun updateListView() {
        binding.lvStudents.adapter = object : ArrayAdapter<StudentRow>(
            this,
            android.R.layout.simple_list_item_1,
            buildLiveRows()
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                val row = getItem(position)
                val textView = view.findViewById<TextView>(android.R.id.text1)
                textView.text = row?.text.orEmpty()
                
                if (row?.connected == true) {
                    if (row.isMoving) {
                        textView.setTextColor(Color.rgb(202, 138, 4)) // Naranja/Amarillo oscuro
                    } else {
                        textView.setTextColor(Color.rgb(22, 163, 74)) // Verde
                    }
                } else if (row?.hasPreClassSignal == true) {
                    textView.setTextColor(Color.rgb(37, 99, 235)) // Azul (Listo/BT Activo)
                } else {
                    textView.setTextColor(Color.rgb(220, 38, 38)) // Rojo
                }
                
                textView.textSize = 15f
                textView.setPadding(8, 12, 8, 12)
                return view
            }
        }
    }

    private fun buildLiveRows(): List<StudentRow> {
        val denominator = elapsedSeconds.coerceAtLeast(1)
        if (studentStats.isEmpty()) {
            return listOf(
                StudentRow("Sin alumnos sincronizados. Revisa matricula en Moodle o espera deteccion Bluetooth.", false, false, false)
            )
        }
        val now = System.currentTimeMillis()
        return studentStats.values.map { stats ->
            // Si la clase no ha iniciado (elapsed == 0), % es 0. Si ya termino, usa totalSeconds
            val currentDenominator = if (isClassActive) denominator else if (elapsedSeconds > 0) totalSeconds.coerceAtLeast(1) else 1
            val percent = if (elapsedSeconds > 0 || isClassActive) {
                ((stats.presentSeconds * 100) / currentDenominator).coerceIn(0, 100)
            } else {
                0
            }
            
            val vino = if (stats.attended) "SI" else "NO"
            
            val reciente = stats.lastSeenAtMillis > 0L && (now - stats.lastSeenAtMillis) <= PRECLASS_BT_HINT_MS
            
            val enSala = if (isClassActive) {
                if (stats.connected) {
                    if (stats.isMoving) "SI (Moviendose!)" else "SI"
                } else "NO"
            } else {
                if (reciente) "Esperando... (Señal BT OK)" else "Esperando señal..."
            }
            
            val rowConnected = isClassActive && stats.connected
            val hasPreClassSignal = !isClassActive && reciente
            
            StudentRow(
                "${stats.fullName}\nVino: $vino | En salon: $enSala | Concentracion: $percent% | Cortes: ${stats.disconnections}",
                rowConnected,
                stats.isMoving,
                hasPreClassSignal
            )
        }
    }

    private fun buildReportRows(): List<String> {
        val denominator = totalSeconds.coerceAtLeast(1)
        if (studentStats.isEmpty()) {
            return listOf("Sin alumnos sincronizados desde Moodle.")
        }
        return studentStats.values.map { stats ->
            val percent = ((stats.presentSeconds * 100) / denominator).coerceIn(0, 100)
            val vino = if (stats.attended) "SI" else "NO"
            val (enSalaLabel, enSalaVal) = if (isClassActive) {
                "En salon ahora" to if (stats.connected) "SI" else "NO"
            } else {
                "En salon al final" to if (stats.presentAtEndOfClass) "SI" else "NO"
            }
            "${stats.fullName} | Vino a clase: $vino | $enSalaLabel: $enSalaVal | Concentracion: $percent% | Cortes: ${stats.disconnections}"
        }
    }

    private fun openSummary() {
        refreshConnectionStates()
        val intent = Intent(this, SummaryActivity::class.java)
        intent.putExtra("SUMMARY_DATA", buildReportRows().toTypedArray())
        startActivity(intent)
    }

    private fun finishClass() {
        timer?.cancel()
        refreshConnectionStates()
        studentStats.values.forEach { it.presentAtEndOfClass = it.connected }
        isClassActive = false
        TeacherClassNotificationState.clear()
        TeacherClassSessionService.stop(this)
        refreshConnectionStates()
        binding.tvTimer.text = "FIN"
        binding.btnStart.isEnabled = true
        binding.btnPause.isEnabled = false
        binding.btnFinish.isEnabled = false
        binding.etDuration.isEnabled = true
        Toast.makeText(this, "Clase terminada. Revisa el informe final.", Toast.LENGTH_LONG).show()
    }

    private fun logout() {
        isClassActive = false
        TeacherClassNotificationState.clear()
        TeacherClassSessionService.stop(this)
        timer?.cancel()
        SessionStore.clear()
        val intent = Intent(this, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        timer?.cancel()
        stopAutoScanner()
        stopBleScan()
        stopMoodleRosterPolling()
        TeacherClassNotificationState.clear()
        TeacherClassSessionService.stop(this)
    }
}
