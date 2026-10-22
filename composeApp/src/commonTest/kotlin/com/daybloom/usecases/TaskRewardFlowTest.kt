package com.daybloom.usecases

import com.daybloom.domain.models.Priority
import com.daybloom.domain.models.RecurrenceRule
import com.daybloom.domain.models.TaskStatus
import com.daybloom.domain.models.XpReason
import com.daybloom.domain.usecases.PlanDraft
import com.daybloom.domain.usecases.TaskDraft
import com.daybloom.domain.usecases.TaskResult
import com.daybloom.gamification.RewardConfig
import com.daybloom.testing.TestGraph
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TaskRewardFlowTest {
    private val today = LocalDate(2026, 10, 22)

    private suspend fun TestGraph.newTask(title: String = "Write report", priority: Priority = Priority.NORMAL, due: LocalDate? = today) =
        assertIs<TaskResult.Success>(createTask(TaskDraft(title, priority = priority, dueDate = due))).task

    private suspend fun TestGraph.taskXp() = store.xp.value.values.filter { it.reason == XpReason.TASK_COMPLETED || it.reason == XpReason.TOP_PRIORITY_COMPLETED }.sumOf { it.amount }

    @Test
    fun `completing a task awards XP exactly once`() = runTest {
        val g = TestGraph()
        val task = g.newTask()
        val outcome = assertNotNull(g.completeTask(task.id))
        assertEquals(RewardConfig.TASK_COMPLETED, outcome.xpAwarded)
        assertNull(g.completeTask(task.id), "second completion is a no-op")
        assertEquals(RewardConfig.TASK_COMPLETED, g.taskXp())
    }

    @Test
    fun `toggling complete and reopen never accumulates XP`() = runTest {
        val g = TestGraph()
        val task = g.newTask()
        repeat(5) {
            g.completeTask(task.id)
            g.reopenTask(task.id)
        }
        assertEquals(0, g.taskXp(), "reopening revokes the award")
        g.completeTask(task.id)
        assertEquals(RewardConfig.TASK_COMPLETED, g.taskXp())
    }

    @Test
    fun `top priorities earn the top-priority reward`() = runTest {
        val g = TestGraph()
        val task = g.newTask(priority = Priority.HIGH)
        g.savePlan(PlanDraft(today, listOf(task.id), emptyList(), availableMinutes = 240))
        assertEquals(RewardConfig.TOP_PRIORITY_COMPLETED, g.completeTask(task.id)?.xpAwarded)
    }

    @Test
    fun `daily cap stops XP farming with trivial tasks but tasks still complete`() = runTest {
        val g = TestGraph()
        val tasks = (1..RewardConfig.MAX_REWARDED_TASKS_PER_DAY + 5).map { g.newTask("Trivial $it", Priority.LOW) }
        tasks.forEach { g.completeTask(it.id) }
        assertEquals(RewardConfig.MAX_REWARDED_TASKS_PER_DAY * RewardConfig.TASK_COMPLETED, g.taskXp())
        assertTrue(tasks.all { g.tasks.get(it.id)?.status == TaskStatus.COMPLETED })
    }

    @Test
    fun `completing a recurring task creates the next occurrence once`() = runTest {
        val g = TestGraph()
        val task = assertIs<TaskResult.Success>(
            g.createTask(TaskDraft("Water plants", dueDate = today, recurrence = RecurrenceRule.weekly(DayOfWeek.THURSDAY, DayOfWeek.SUNDAY))),
        ).task
        val outcome = assertNotNull(g.completeTask(task.id))
        val next = assertNotNull(outcome.nextOccurrence)
        assertEquals(LocalDate(2026, 10, 25), next.dueDate)
        assertEquals(task.seriesId, next.seriesId)
        g.reopenTask(task.id)
        g.completeTask(task.id)
        assertEquals(2, g.store.tasks.value.values.count { it.seriesId == task.seriesId }, "re-completing doesn't duplicate the next occurrence")
    }

    @Test
    fun `monthly series anchored on the 31st does not drift`() = runTest {
        val g = TestGraph()
        val jan31 = LocalDate(2027, 1, 31)
        val first = assertIs<TaskResult.Success>(g.createTask(TaskDraft("Rent", dueDate = jan31, recurrence = RecurrenceRule.monthly()))).task
        val feb = assertNotNull(g.completeTask(first.id)?.nextOccurrence)
        assertEquals(LocalDate(2027, 2, 28), feb.dueDate)
        val mar = assertNotNull(g.completeTask(feb.id)?.nextOccurrence)
        assertEquals(LocalDate(2027, 3, 31), mar.dueDate)
    }

    @Test
    fun `cancelled tasks don't hurt the score and revoke earlier XP`() = runTest {
        val g = TestGraph()
        val a = g.newTask("A")
        val b = g.newTask("B")
        g.completeTask(a.id)
        val before = g.scores.get(today)?.overall
        g.cancelTask(b.id)
        assertEquals(100, g.scores.get(today)?.overall)
        assertTrue((before ?: 0) < 100)
        g.cancelTask(a.id)
        assertEquals(0, g.taskXp())
    }

    @Test
    fun `invalid drafts are rejected with field errors`() = runTest {
        val g = TestGraph()
        val result = g.createTask(TaskDraft("  ", reminderOffsetsMinutes = listOf(15)))
        val invalid = assertIs<TaskResult.Invalid>(result)
        assertTrue("title" in invalid.errors && "reminder" in invalid.errors)
        assertTrue(g.store.tasks.value.isEmpty())
    }

    @Test
    fun `postponing increments the postpone count`() = runTest {
        val g = TestGraph()
        val t = g.newTask()
        g.reschedule(listOf(t.id), LocalDate(2026, 10, 25))
        g.reschedule(listOf(t.id), LocalDate(2026, 10, 24))
        assertEquals(1, g.tasks.get(t.id)?.postponeCount, "moving earlier isn't a postponement")
    }
}
