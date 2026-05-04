package com.example.smartattendance.ui.attendance

/**
 * Estado de la sesión leído por [TeacherClassSessionService] para la notificación persistente.
 * La actividad del profesor lo actualiza cada segundo (y al detectar BT).
 */
object TeacherClassNotificationState {
    @Volatile var paused: Boolean = false
    @Volatile var remainingSeconds: Int = 0
    @Volatile var presentCount: Int = 0
    @Volatile var totalStudents: Int = 0
    @Volatile var averageConcentrationPercent: Int = 0
    @Volatile var courseTitle: String = ""

    fun clear() {
        paused = false
        remainingSeconds = 0
        presentCount = 0
        totalStudents = 0
        averageConcentrationPercent = 0
        courseTitle = ""
    }
}
