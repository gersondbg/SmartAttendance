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
import com.example.smartattendance.databinding.ActivityCourseSelectionBinding
import com.example.smartattendance.ui.login.LoginActivity
import kotlinx.coroutines.launch

class CourseSelectionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCourseSelectionBinding
    private val viewModel: CourseSelectionViewModel by viewModels()
    private lateinit var adapter: CourseAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCourseSelectionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        observeViewModel()
        
        viewModel.onIntent(CourseSelectionIntent.LoadCourses)
    }

    private fun setupUI() {
        val userName = intent.getStringExtra("USER_NAME") ?: getString(R.string.unspecified)
        val userRole = SessionStore.currentUserRole ?: "student"
        val roleName = if (userRole == "teacher") "Profesor" else "Alumno"
        binding.tvWelcomeTeacher.text = "Hola, $roleName $userName"

        adapter = CourseAdapter(emptyList()) { course ->
            viewModel.onIntent(CourseSelectionIntent.SelectCourse(course))
        }

        binding.rvCourses.layoutManager = LinearLayoutManager(this)
        binding.rvCourses.adapter = adapter

        binding.btnLogout.setOnClickListener {
            SessionStore.clear()
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.state.collect { state ->
                binding.pbLoadingCourses.visibility = if (state.isLoading) View.VISIBLE else View.GONE
                adapter.updateData(state.courses)
            }
        }

        lifecycleScope.launch {
            viewModel.effect.collect { effect ->
                when (effect) {
                    is CourseSelectionEffect.NavigateToSessions -> {
                        SessionStore.activeCourseId = effect.course.id
                        SessionStore.activeCourseName = effect.course.fullname
                        
                        val userName = intent.getStringExtra("USER_NAME") ?: "Usuario"
                        val userUsername = intent.getStringExtra("USER_USERNAME") ?: userName
                        
                        val userRole = SessionStore.currentUserRole ?: "student"
                        if (userRole == "teacher") {
                            val intent = Intent(this@CourseSelectionActivity, SessionSelectionActivity::class.java)
                            intent.putExtra("USER_NAME", userName)
                            startActivity(intent)
                        } else {
                            val intent = Intent(this@CourseSelectionActivity, StudentActivity::class.java)
                            intent.putExtra("USER_NAME", userName)
                            intent.putExtra("USER_USERNAME", userUsername)
                            startActivity(intent)
                        }
                    }
                    is CourseSelectionEffect.ShowError -> {
                        Toast.makeText(this@CourseSelectionActivity, effect.message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }
}
