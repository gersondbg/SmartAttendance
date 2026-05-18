package com.example.smartattendance.ui.attendance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.smartattendance.data.repository.AttendanceRepositoryImpl
import com.example.smartattendance.domain.model.Course
import com.example.smartattendance.domain.repository.AttendanceRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed class CourseSelectionIntent {
    object LoadCourses : CourseSelectionIntent()
    data class SelectCourse(val course: Course) : CourseSelectionIntent()
}

data class CourseSelectionState(
    val isLoading: Boolean = false,
    val courses: List<Course> = emptyList(),
    val error: String? = null
)

sealed class CourseSelectionEffect {
    data class NavigateToSessions(val course: Course) : CourseSelectionEffect()
    data class ShowError(val message: String) : CourseSelectionEffect()
}

class CourseSelectionViewModel(
    private val repository: AttendanceRepository = AttendanceRepositoryImpl()
) : ViewModel() {

    private val _state = MutableStateFlow(CourseSelectionState())
    val state: StateFlow<CourseSelectionState> = _state

    private val _effect = MutableSharedFlow<CourseSelectionEffect>()
    val effect: SharedFlow<CourseSelectionEffect> = _effect

    fun onIntent(intent: CourseSelectionIntent) {
        when (intent) {
            is CourseSelectionIntent.LoadCourses -> loadCourses()
            is CourseSelectionIntent.SelectCourse -> {
                viewModelScope.launch { _effect.emit(CourseSelectionEffect.NavigateToSessions(intent.course)) }
            }
        }
    }

    private fun loadCourses() {
        _state.value = _state.value.copy(isLoading = true, error = null)
        viewModelScope.launch {
            repository.getCurrentUserCourses()
                .onSuccess { courses ->
                    _state.value = _state.value.copy(isLoading = false, courses = courses)
                    if (courses.size == 1) {
                        _effect.emit(CourseSelectionEffect.NavigateToSessions(courses.first()))
                    }
                }
                .onFailure { error ->
                    _state.value = _state.value.copy(isLoading = false, error = error.message)
                    _effect.emit(CourseSelectionEffect.ShowError(error.message ?: "Error desconocido"))
                }
        }
    }
}
