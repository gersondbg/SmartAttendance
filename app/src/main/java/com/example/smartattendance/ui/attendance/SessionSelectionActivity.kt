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
        viewModel.onIntent(SessionSelectionIntent.LoadModules(courseId))
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
                finish()
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
            }
        }

        lifecycleScope.launch {
            viewModel.effect.collect { effect ->
                when (effect) {
                    is SessionSelectionEffect.NavigateToTeacher -> {
                        val intent = Intent(this@SessionSelectionActivity, TeacherActivity::class.java)
                        intent.putExtra("USER_NAME", getIntent().getStringExtra("USER_NAME"))
                        startActivity(intent)
                        finish()
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
