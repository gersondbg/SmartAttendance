package com.example.smartattendance.ui.login

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.smartattendance.databinding.ActivityLoginBinding
import com.example.smartattendance.domain.model.User
import com.example.smartattendance.ui.attendance.StudentActivity
import com.example.smartattendance.ui.attendance.TeacherActivity
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private val viewModel: LoginViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupListeners()
        observeViewModel()
    }

    private fun setupListeners() {
        binding.btnLogin.setOnClickListener {
            val email = binding.etEmail.text.toString().trim()
            val password = binding.etPassword.text.toString().trim()

            if (email.isNotEmpty() && password.isNotEmpty()) {
                viewModel.login(email, password)
            } else if (email.equals("profe", ignoreCase = true) || email.equals("alumno", ignoreCase = true)) {
                viewModel.login(email)
            } else {
                Toast.makeText(this, "Ingresa usuario y contraseña", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.loginState.collect { state ->
                when (state) {
                    is LoginViewModel.LoginState.Loading -> {
                        binding.progressBar.visibility = View.VISIBLE
                        binding.btnLogin.isEnabled = false
                    }

                    is LoginViewModel.LoginState.Success -> {
                        binding.progressBar.visibility = View.GONE
                        binding.btnLogin.isEnabled = true
                        navigateToHome(state.user)
                    }

                    is LoginViewModel.LoginState.Error -> {
                        binding.progressBar.visibility = View.GONE
                        binding.btnLogin.isEnabled = true
                        Toast.makeText(this@LoginActivity, state.message, Toast.LENGTH_SHORT).show()
                    }

                    else -> binding.progressBar.visibility = View.GONE
                }
            }
        }
    }

    private fun navigateToHome(user: User) {
        val intent = if (user.role == "teacher") {
            Intent(this, TeacherActivity::class.java)
        } else {
            Intent(this, StudentActivity::class.java)
        }

        intent.putExtra("USER_NAME", user.fullname)
        intent.putExtra("USER_USERNAME", user.username)
        intent.putExtra("USER_ID", user.id)
        startActivity(intent)
        finish()
    }
}
