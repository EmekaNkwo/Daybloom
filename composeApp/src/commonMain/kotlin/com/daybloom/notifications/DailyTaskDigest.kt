package com.daybloom.notifications

import com.daybloom.domain.models.NotificationCategory
import com.daybloom.domain.models.ReminderOccurrence
import com.daybloom.domain.models.Task
import com.daybloom.domain.services.DateMath
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.time.Instant

/**
 * The two daily task digests: a morning heads-up listing the tasks that were set ahead for that day, and an
 * evening nudge listing what's still open before the day ends.
 *
 * Digests aren't stored as reminders. They're derived from the open tasks on every reconcile, so completing,
 * moving or adding a task updates the pending digest (via its content hash) or withdraws it.
 */
object DailyTaskDigest {
    const val MORNING_ID = "digest:morning"
    const val EVENING_ID = "digest:evening"

    /** How many days ahead digests are handed to the OS; keeps them from crowding iOS's 64-notification cap. */
    const val DAYS_AHEAD = 7

    private const val MAX_LISTED = 3

    val ids: Set<String> = setOf(MORNING_ID, EVENING_ID)

    fun occurrences(
        openTasks: List<Task>,
        morningTime: LocalTime?,
        eveningTime: LocalTime?,
        now: Instant,
        zone: TimeZone,
    ): List<ReminderOccurrence> {
        if (morningTime == null && eveningTime == null) return emptyList()
        val today = DateMath.localDate(now, zone)
        val lastDay = DateMath.plusDays(today, DAYS_AHEAD)
        return openTasks
            .filter { it.isOpen }
            .mapNotNull { task -> task.dueDate?.takeIf { it in today..lastDay }?.let { it to task } }
            .groupBy({ it.first }, { it.second })
            .flatMap { (date, tasks) ->
                val ordered = tasks.sortedWith(compareBy(nullsLast()) { t: Task -> t.dueTime }.thenBy { it.title.lowercase() })
                listOfNotNull(
                    morningTime?.let { morning(date, ordered, it, zone) },
                    eveningTime?.let { evening(date, ordered, it, zone) },
                )
            }
            .filter { it.fireAt > now }
            .sortedBy { it.fireAt }
    }

    /** Only tasks set before the day itself: the point is to remind the user of what they planned ahead. */
    private fun morning(date: LocalDate, tasks: List<Task>, time: LocalTime, zone: TimeZone): ReminderOccurrence? {
        val plannedAhead = tasks.filter { DateMath.localDate(it.createdAt, zone) < date }
        if (plannedAhead.isEmpty()) return null
        return ReminderOccurrence(
            reminderId = MORNING_ID,
            fireAt = DateMath.toInstant(date, time, zone),
            title = morningTitle(plannedAhead.size),
            body = summarise(plannedAhead.map { it.title }),
            category = NotificationCategory.DIGEST,
        )
    }

    private fun evening(date: LocalDate, tasks: List<Task>, time: LocalTime, zone: TimeZone) = ReminderOccurrence(
        reminderId = EVENING_ID,
        fireAt = DateMath.toInstant(date, time, zone),
        title = eveningTitle(tasks.size),
        body = "${summarise(tasks.map { it.title })} · Finish up or move them to tomorrow.",
        category = NotificationCategory.DIGEST,
    )

    fun morningTitle(count: Int): String = "${plural(count)} planned for today"

    fun eveningTitle(count: Int): String = "${plural(count)} still open today"

    /** "A", "A and B", "A, B and C", "A, B, C and 2 more". */
    fun summarise(titles: List<String>): String {
        val shown = titles.take(MAX_LISTED)
        val rest = titles.size - shown.size
        return when {
            rest > 0 -> "${shown.joinToString(", ")} and $rest more"
            shown.size <= 1 -> shown.joinToString()
            else -> "${shown.dropLast(1).joinToString(", ")} and ${shown.last()}"
        }
    }

    private fun plural(count: Int) = if (count == 1) "1 task" else "$count tasks"
}
