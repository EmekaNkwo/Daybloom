package com.daybloom.navigation

import kotlinx.serialization.Serializable

/** Type-safe destinations. Dates travel as ISO strings to keep routes serializable on every platform. */
sealed interface Route {
    @Serializable data object Today : Route
    @Serializable data object Tasks : Route
    @Serializable data object Calendar : Route
    @Serializable data object Habits : Route
    @Serializable data object Bloom : Route

    @Serializable data class TaskEditor(val taskId: String? = null, val date: String? = null, val appointmentId: String? = null, val prefill: Boolean = false) : Route
    @Serializable data class AppointmentEditor(val appointmentId: String? = null, val date: String? = null, val prefill: Boolean = false) : Route
    @Serializable data class Planner(val date: String) : Route
    @Serializable data class Focus(val taskId: String? = null, val minutes: Int? = null) : Route
    @Serializable data object Insights : Route
    @Serializable data class Recap(val month: String) : Route
    @Serializable data object Reminders : Route
    @Serializable data class ReminderEditor(val reminderId: String? = null) : Route
    @Serializable data class DoneList(val date: String) : Route
    @Serializable data class HabitEditor(val habitId: String? = null) : Route
    @Serializable data object Settings : Route
}
