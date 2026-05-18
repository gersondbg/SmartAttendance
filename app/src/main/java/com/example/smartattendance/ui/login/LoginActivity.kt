package com.example.smartattendance.ui.login

import com.example.smartattendance.R
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.smartattendance.data.remote.SessionStore
import com.example.smartattendance.databinding.ActivityLoginBinding
import com.example.smartattendance.domain.model.User
import com.example.smartattendance.ui.attendance.CourseSelectionActivity
import com.example.smartattendance.ui.attendance.StudentActivity
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private val viewModel: LoginViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setupUI()
        setupListeners()
        observeViewModel()
    }

    private fun setupUI() {
        binding.btnLogin.text = getString(R.string.login_button)
        // El secreto es poner el hint en el contenedor (til), NO en el edit text (et)
        binding.tilEmail.hint = getString(R.string.email_hint)
        binding.tilPassword.hint = getString(R.string.password_hint)
    }

    private fun setupListeners() {
        binding.btnLogin.setOnClickListener {
            val email = binding.etEmail.text.toString().trim()
            val password = binding.etPassword.text.toString().trim()
            
            if (email.lowercase() == "demo") {
                viewModel.onIntent(LoginIntent.DemoLogin)
            } else {
                viewModel.onIntent(LoginIntent.LoginUser(email, password))
            }
        }
        
        binding.etPassword.setOnEditorActionListener { _, _, _ ->
            binding.btnLogin.performClick()
            true
        }
    }

    private fun observeViewModel() {
        // Observar el ESTADO (Arquitectura basada en eventos/MVI)
        lifecycleScope.launch {
            viewModel.state.collect { state ->
                binding.progressBar.visibility = if (state.isLoading) View.VISIBLE else View.GONE
                binding.btnLogin.isEnabled = !state.isLoading
            }
        }

        // Observar EFECTOS (Side effects: Navegación, Toasts)
        lifecycleScope.launch {
            viewModel.effect.collect { effect ->
                when (effect) {
                    is LoginEffect.NavigateToHome -> navigateToHome(effect.user)
                    is LoginEffect.ShowToast -> Toast.makeText(this@LoginActivity, getString(effect.messageRes), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun navigateToHome(user: User) {
        // Guardar en SessionStore (esto podría ser parte de un UseCase, pero lo mantenemos aquí por simplicidad)
        SessionStore.currentUserId = user.id
        SessionStore.currentUserRole = user.role

        val intent = if (user.role == "teacher") {
            Intent(this, CourseSelectionActivity::class.java)
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
