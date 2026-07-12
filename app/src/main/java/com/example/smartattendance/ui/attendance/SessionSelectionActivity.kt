package com.example.smartattendance.ui.attendance

import com.example.smartattendance.R
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.smartattendance.data.remote.SessionStore
import com.example.smartattendance.databinding.ActivitySessionSelectionBinding
import kotlinx.coroutines.launch

class SessionSelectionActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_OPEN_ACTIVE_ATTENDANCE = "OPEN_ACTIVE_ATTENDANCE"
    }

    private lateinit var binding: ActivitySessionSelectionBinding
    private val viewModel: SessionSelectionViewModel by viewModels()
    private lateinit var adapter: GenericSelectionAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySessionSelectionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        observeViewModel()
        
        val courseId = SessionStore.activeCourseId ?: 0
        val activeAttendanceId = SessionStore.activeAttendanceId
        val shouldOpenActiveAttendance = intent.getBooleanExtra(EXTRA_OPEN_ACTIVE_ATTENDANCE, false)
        if ((SessionStore.activeClassRunning || shouldOpenActiveAttendance) && activeAttendanceId != null) {
            viewModel.onIntent(SessionSelectionIntent.LoadSessions(activeAttendanceId))
        } else {
            viewModel.onIntent(SessionSelectionIntent.LoadModules(courseId))
        }
    }

    private fun setupUI() {
        adapter = GenericSelectionAdapter(emptyList()) { item ->
            if (viewModel.state.value.isSelectingModule) {
                SessionStore.activeAttendanceId = item.id
                SessionStore.activeAttendanceName = item.title
                viewModel.onIntent(SessionSelectionIntent.SelectModule(item.id, item.title))
            } else {
                SessionStore.activeSessionId = item.id
                viewModel.onIntent(SessionSelectionIntent.SelectSession(item.id))
            }
        }

        binding.rvSelection.layoutManager = LinearLayoutManager(this)
        binding.rvSelection.adapter = adapter
        
        binding.btnCreateSession.setOnClickListener {
            viewModel.onIntent(SessionSelectionIntent.CreateSession)
        }

        binding.btnBack.setOnClickListener {
            if (!viewModel.state.value.isSelectingModule) {
                val courseId = SessionStore.activeCourseId ?: 0
                viewModel.onIntent(SessionSelectionIntent.LoadModules(courseId))
            } else {
                startActivity(Intent(this, CourseSelectionActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                })
            }
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.state.collect { state ->
                binding.pbLoading.visibility = if (state.isLoading) View.VISIBLE else View.GONE
                binding.tvSelectionContext.text = getString(state.titleRes)
                binding.btnCreateSession.visibility = if (state.showCreateButton) View.VISIBLE else View.GONE
                adapter.updateData(state.items)
                
                if (!state.isLoading && state.items.isEmpty()) {
                    binding.tvEmptyState.visibility = View.VISIBLE
                    binding.rvSelection.visibility = View.GONE
                    binding.tvEmptyState.text = if (state.isSelectingModule) {
                        "No se encontraron módulos de Asistencia en este curso."
                    } else {
                        "No hay sesiones programadas en este módulo."
                    }
                } else {
                    binding.tvEmptyState.visibility = View.GONE
                    binding.rvSelection.visibility = View.VISIBLE
                }
            }
        }

        lifecycleScope.launch {
            viewModel.effect.collect { effect ->
                when (effect) {
                    is SessionSelectionEffect.NavigateToTeacher -> {
                        val intent = Intent(this@SessionSelectionActivity, TeacherActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        }
                        intent.putExtra("USER_NAME", getIntent().getStringExtra("USER_NAME"))
                        startActivity(intent)
                    }
                    is SessionSelectionEffect.NavigateToSummaryPreview -> {
                        val intent = Intent(this@SessionSelectionActivity, SummaryActivity::class.java).apply {
                            putExtra("COURSE_NAME", SessionStore.activeCourseName ?: "Curso")
                            putExtra("TOTAL_TIME", 0)
                            putExtra("SUMMARY_PREVIEW_MODE", true)
                            putExtra("SESSION_TITLE", effect.sessionTitle)
                            putExtra("SESSION_DESCRIPTION", effect.sessionDescription)
                        }
                        startActivity(intent)
                    }
                    is SessionSelectionEffect.Exit -> finish()
                    is SessionSelectionEffect.ShowError -> {
                        Toast.makeText(this@SessionSelectionActivity, effect.message, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
}
