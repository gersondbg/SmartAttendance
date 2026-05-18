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
import com.example.smartattendance.databinding.ActivityCourseSelectionBinding
import com.example.smartattendance.ui.login.LoginActivity
import kotlinx.coroutines.launch

class CourseSelectionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCourseSelectionBinding
    private val repository = AttendanceRepositoryImpl()
    private lateinit var adapter: CourseAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCourseSelectionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        loadCourses()
    }

    private fun setupUI() {
        val teacherName = intent.getStringExtra("USER_NAME") ?: "Profesor"
        binding.tvWelcomeTeacher.text = "Bienvenido, $teacherName"

        adapter = CourseAdapter(emptyList()) { course ->
            SessionStore.activeCourseId = course.id
            SessionStore.activeCourseName = course.fullname
            
            val intent = Intent(this, SessionSelectionActivity::class.java)
            intent.putExtra("USER_NAME", teacherName)
            startActivity(intent)
        }

        binding.rvCourses.layoutManager = LinearLayoutManager(this)
        binding.rvCourses.adapter = adapter

        binding.btnLogout.setOnClickListener {
            SessionStore.clear()
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }
    }

    private fun loadCourses() {
        binding.pbLoadingCourses.visibility = View.VISIBLE
        lifecycleScope.launch {
            repository.getCurrentUserCourses()
                .onSuccess { courses ->
                    binding.pbLoadingCourses.visibility = View.GONE
                    if (courses.isEmpty()) {
                        Toast.makeText(this@CourseSelectionActivity, "No se encontraron cursos activos", Toast.LENGTH_LONG).show()
                    }
                    adapter.updateData(courses)
                }
                .onFailure { error ->
                    binding.pbLoadingCourses.visibility = View.GONE
                    Toast.makeText(this@CourseSelectionActivity, "Error al cargar cursos: ${error.message}", Toast.LENGTH_LONG).show()
                }
        }
    }
}
