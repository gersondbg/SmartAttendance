package com.example.smartattendance.ui.attendance

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.smartattendance.R
import com.example.smartattendance.domain.model.Course

class CourseAdapter(
    private var courses: List<Course>,
    private val onCourseSelected: (Course) -> Unit
) : RecyclerView.Adapter<CourseAdapter.CourseViewHolder>() {

    class CourseViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tvCourseName)
        val tvShortName: TextView = view.findViewById(R.id.tvCourseShortName)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CourseViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_course, parent, false)
        return CourseViewHolder(view)
    }

    override fun onBindViewHolder(holder: CourseViewHolder, position: Int) {
        val course = courses[position]
        holder.tvName.text = course.fullname
        holder.tvShortName.text = course.shortname
        holder.itemView.setOnClickListener { onCourseSelected(course) }
    }

    override fun getItemCount() = courses.size

    fun updateData(newCourses: List<Course>) {
        this.courses = newCourses
        notifyDataSetChanged()
    }
}
