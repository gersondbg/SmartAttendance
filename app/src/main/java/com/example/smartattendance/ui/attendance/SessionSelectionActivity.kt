package com.example.smartattendance.ui.attendance

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.smartattendance.data.remote.SessionStore
import com.example.smartattendance.data.repository.AttendanceRepositoryImpl
import com.example.smartattendance.databinding.ActivitySessionSelectionBinding
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class SessionSelectionActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySessionSelectionBinding
    private val repository = AttendanceRepositoryImpl()
    private lateinit var adapter: GenericSelectionAdapter
    
    private var isSelectingModule = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySessionSelectionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        loadModules()
    }

    private fun setupUI() {
        adapter = GenericSelectionAdapter(emptyList()) { item ->
            if (isSelectingModule) {
                SessionStore.activeAttendanceId = item.id
                SessionStore.activeAttendanceName = item.title
                loadSessions(item.id)
            } else {
                SessionStore.activeSessionId = item.id
                val intent = Intent(this, TeacherActivity::class.java)
                intent.putExtra("USER_NAME", getIntent().getStringExtra("USER_NAME"))
                startActivity(intent)
                finish()
            }
        }

        binding.rvSelection.layoutManager = LinearLayoutManager(this)
        binding.rvSelection.adapter = adapter
        
        binding.btnBack.setOnClickListener {
            if (!isSelectingModule) {
                loadModules()
            } else {
                finish()
            }
        }
    }

    private fun loadModules() {
        isSelectingModule = true
        binding.tvSelectionContext.text = "Selecciona el módulo de asistencia"
        binding.pbLoading.visibility = View.VISIBLE
        
        lifecycleScope.launch {
            repository.getAttendanceModules(SessionStore.activeCourseId ?: 0)
                .onSuccess { modules ->
                    binding.pbLoading.visibility = View.GONE
                    val items = modules.map { 
                        GenericSelectionAdapter.SelectionItem(it.id, it.name, "Módulo de Asistencia")
                    }
                    adapter.updateData(items)
                    if (items.isEmpty()) {
                        Toast.makeText(this@SessionSelectionActivity, "No hay módulos de asistencia en este curso", Toast.LENGTH_SHORT).show()
                    }
                }
                .onFailure {
                    binding.pbLoading.visibility = View.GONE
                    Toast.makeText(this@SessionSelectionActivity, "Error: ${it.message}", Toast.LENGTH_SHORT).show()
                }
        }
    }

    private fun loadSessions(attendanceId: Int) {
        isSelectingModule = false
        binding.tvSelectionContext.text = "Selecciona la sesión de Moodle"
        binding.pbLoading.visibility = View.VISIBLE
        
        val dateFormat = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())

        lifecycleScope.launch {
            repository.getSessions(attendanceId)
                .onSuccess { sessions ->
                    binding.pbLoading.visibility = View.GONE
                    val items = sessions.map { 
                        val dateStr = dateFormat.format(Date(it.date * 1000))
                        GenericSelectionAdapter.SelectionItem(it.id, dateStr, it.description)
                    }
                    adapter.updateData(items)
                    if (items.isEmpty()) {
                        Toast.makeText(this@SessionSelectionActivity, "No hay sesiones programadas", Toast.LENGTH_SHORT).show()
                    }
                }
                .onFailure {
                    binding.pbLoading.visibility = View.GONE
                    Toast.makeText(this@SessionSelectionActivity, "Error: ${it.message}", Toast.LENGTH_SHORT).show()
                }
        }
    }
}
