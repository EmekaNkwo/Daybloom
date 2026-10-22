package com.daybloom.usecases

import com.daybloom.domain.models.AppointmentStatus
import com.daybloom.domain.models.DayClassification
import com.daybloom.domain.models.FocusStatus
import com.daybloom.domain.models.Priority
import com.daybloom.domain.models.Quest
import com.daybloom.domain.models.QuestStatus
import com.daybloom.domain.models.QuestType
import com.daybloom.domain.models.UnfinishedTaskPolicy
import com.daybloom.domain.usecases.AppointmentDraft
import com.daybloom.domain.usecases.HabitDraft
import com.daybloom.domain.usecases.PlanDraft
import com.daybloom.domain.usecases.SaveAppointmentUseCase
import com.daybloom.domain.usecases.SaveHabitUseCase
import com.daybloom.domain.usecases.SavePlanResult
import com.daybloom.domain.usecases.TaskDraft
import com.daybloom.domain.usecases.TaskResult
import com.daybloom.gamification.QuestCatalog
import com.daybloom.notifications.ReconcileReason
import com.daybloom.testing.TestGraph
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

/**
 * The end-to-end workflow from the spec, run through the real use cases:
 * create a task with a reminder → complete it → score updates → XP once → Done List → analytics → restart.
 */
class EndToEndWorkflowTest {
    private val today = LocalDate(2026, 10, 22)

    @Test
    fun `create remind complete score xp done list analytics restart`() = runTest {
        val g = TestGraph()
        // 1. Create a task and schedule a reminder.
        val task = assertIs<TaskResult.Success>(
            g.createTask(TaskDraft("Prepare presentation", priority = Priority.HIGH, dueDate = today, dueTime = LocalTime(15, 0), reminderOffsetsMinutes = listOf(30))),
        ).task
        val other = assertIs<TaskResult.Success>(g.createTask(TaskDraft("Read for 20 minutes", dueDate = today))).task
        assertEquals(1, g.scheduler.pending.size)
        val before = assertNotNull(g.scores.get(today))
        assertEquals(0, before.overall)

        // 2. Complete the task.
        val outcome = assertNotNull(g.completeTask(task.id))

        // 3. The productivity score updates.
        val after = assertNotNull(g.scores.get(today))
        assertTrue(after.overall!! > before.overall!!)
        assertEquals(DayClassification.PLANNED, after.classification)

        // 4. XP is awarded once.
        assertEquals(10, outcome.xpAwarded)
        g.completeTask(task.id)
        assertEquals(1, g.store.xp.value.keys.count { it == "task:${task.id}" })

        // 5. The Done List (completed-on-date query) updates; reminder no longer pending.
        assertEquals(listOf(task.id), g.tasks.getCompletedBetween(today, today).map { it.id })
        assertTrue(g.scheduler.pending.isEmpty())

        // 6. Daily and monthly analytics reflect it.
        val month = g.insights(today)
        assertEquals(1, month.tasksCompleted)
        assertEquals(1, month.scoredDays)
        assertEquals(after.overall, month.averageScore)

        // 7–8. Restart the app: data and reminders stay consistent, nothing is duplicated.
        g.completeTask(other.id)
        val restarted = g.restart()
        val scheduledBefore = g.scheduler.scheduleCalls.size
        restarted.startup(ReconcileReason.APP_START)
        assertEquals(scheduledBefore, g.scheduler.scheduleCalls.size)
        assertEquals(100, restarted.scores.get(today)?.overall)
        assertEquals(g.store.xp.value, restarted.store.xp.value)
        assertEquals(2, restarted.tasks.getCompletedBetween(today, today).size)
    }

    @Test
    fun `appointments are never marked attended just because time passed`() = runTest {
        val g = TestGraph()
        val appt = assertIs<SaveAppointmentUseCase.Result.Saved>(
            g.saveAppointment(AppointmentDraft("Doctor", date = today, startTime = LocalTime(10, 0), endTime = LocalTime(10, 30), newPreparationTasks = listOf("Bring forms"))),
        ).appointment
        g.clock.advance(3.days)
        assertNull(g.store.occurrenceStatuses.value["${appt.id}@$today"])
        assertEquals(1, g.tasks.getForAppointment(appt.id).size, "prep task created")
        val score = g.calculateScore(today).current
        assertTrue(score.dimensions.none { it.detail.contains("Doctor") })
        g.setAttendance(appt.id, today, AppointmentStatus.ATTENDED)
        assertEquals(AppointmentStatus.ATTENDED, g.store.occurrenceStatuses.value["${appt.id}@$today"])
    }

    @Test
    fun `over-capacity plans warn but the user decides`() = runTest {
        val g = TestGraph()
        val big = assertIs<TaskResult.Success>(g.createTask(TaskDraft("Big job", dueDate = today, estimatedMinutes = 7 * 60))).task
        val draft = PlanDraft(today, listOf(big.id), listOf(big.id), availableMinutes = 6 * 60, breakMinutes = 30)
        val warned = assertIs<SavePlanResult.OverCapacity>(g.savePlan(draft))
        assertEquals(90, warned.capacity.overByMinutes)
        assertNull(g.plans.get(today), "nothing saved until the user chooses")
        val kept = assertIs<SavePlanResult.Saved>(g.savePlan(draft, keepDespiteOverCapacity = true))
        assertTrue(kept.plan.acceptedOverCapacity)
    }

    @Test
    fun `rest days are excluded and unfinished tasks only move by policy`() = runTest {
        val g = TestGraph()
        assertIs<TaskResult.Success>(g.createTask(TaskDraft("Flexible", dueDate = today)))
        g.setRestDay(today, true)
        assertNull(g.scores.get(today)?.overall)
        g.clock.advance(1.days)
        g.startup()
        assertEquals(today, g.store.tasks.value.values.single().dueDate, "default policy never moves tasks on its own")
        g.preferences.update { it.copy(unfinishedTaskPolicy = UnfinishedTaskPolicy.MOVE_FLEXIBLE_TO_TOMORROW) }
        g.startup()
        assertEquals(LocalDate(2026, 10, 23), g.store.tasks.value.values.single().dueDate)
    }

    @Test
    fun `quests stay stable across launches and pay once`() = runTest {
        val g = TestGraph()
        repeat(3) { assertIs<TaskResult.Success>(g.createTask(TaskDraft("Task $it", dueDate = today))) }
        val quests = g.ensureQuests(today)
        val restarted = g.restart()
        assertEquals(quests, restarted.ensureQuests(today))
        // Make sure a "finish three tasks" quest exists today regardless of the daily rotation.
        val threeId = QuestCatalog.questId(today, QuestType.FINISH_THREE_TASKS)
        if (g.store.quests.value[threeId] == null) {
            val template = QuestCatalog.templates.getValue(QuestType.FINISH_THREE_TASKS)
            g.quests.insertIfAbsent(listOf(Quest(threeId, today, template.type, template.title, template.description, template.target, rewardXp = template.rewardXp)))
        }
        g.store.tasks.value.values.forEach { g.completeTask(it.id) }
        assertEquals(QuestStatus.COMPLETED, g.store.quests.value.getValue(threeId).status)
        assertEquals(1, g.store.xp.value.keys.count { it == "quest:$threeId" })
        val questXp = g.store.xp.value.values.filter { it.reason == com.daybloom.domain.models.XpReason.DAILY_QUEST }
        g.process(today)
        assertEquals(questXp, g.store.xp.value.values.filter { it.reason == com.daybloom.domain.models.XpReason.DAILY_QUEST })
    }

    @Test
    fun `habits focus and reflection feed progress without double rewards`() = runTest {
        val g = TestGraph()
        val habit = assertIs<SaveHabitUseCase.Result.Saved>(g.saveHabit(HabitDraft("Read"))).habit
        g.toggleHabit(habit.id, today, true)
        g.toggleHabit(habit.id, today, true)
        assertEquals(1, g.store.xp.value.keys.count { it.startsWith("habit:") })
        g.toggleHabit(habit.id, today, false)
        assertEquals(0, g.store.xp.value.keys.count { it.startsWith("habit:") })
        g.toggleHabit(habit.id, LocalDate(2026, 10, 30), true)
        assertTrue(g.store.habitCompletions.value.none { it.date > today }, "future completions are refused")

        val session = g.focusSessions.start(25, null)
        g.clock.advance(26.minutes)
        val finished = assertNotNull(g.focusSessions.reconcileActive())
        assertEquals(FocusStatus.COMPLETED, finished.status)
        assertEquals(1, g.store.xp.value.keys.count { it == "focus:${session.id}" })

        g.saveReflection(today, "A calm day", "", "", null, "")
        g.saveReflection(today, "A calm day, edited", "", "", null, "")
        assertEquals(1, g.store.xp.value.keys.count { it.startsWith("reflection:") })
        assertNotNull(g.store.unlocks.value["first_reflection"])
        assertNotNull(g.store.unlocks.value["first_focus"])
    }

    @Test
    fun `sample data loads and reset clears everything`() = runTest {
        val g = TestGraph()
        g.clock.setLocal(LocalDateTime(today, LocalTime(8, 0)))
        g.seed()
        assertTrue(g.store.tasks.value.isNotEmpty())
        assertTrue(g.store.habits.value.isNotEmpty())
        assertTrue(g.scheduler.pending.isNotEmpty())
        g.reset()
        assertTrue(g.store.tasks.value.isEmpty() && g.store.xp.value.isEmpty() && g.store.reminders.value.isEmpty())
        assertTrue(g.scheduler.pending.isEmpty())
    }

    @Test
    fun `level up is celebrated once`() = runTest {
        val g = TestGraph()
        repeat(12) { i -> assertIs<TaskResult.Success>(g.createTask(TaskDraft("T$i", dueDate = today))).task.let { g.completeTask(it.id) } }
        val pending = assertNotNull(g.levelUp.pendingCelebration())
        g.levelUp.markCelebrated(pending.level.number)
        assertNull(g.levelUp.pendingCelebration())
    }

    @Test
    fun `archiving a habit hides it and restoring brings it back with its reminder`() = runTest {
        val g = TestGraph()
        val habit = assertIs<SaveHabitUseCase.Result.Saved>(
            g.saveHabit(HabitDraft("Drink water", reminderTime = kotlinx.datetime.LocalTime(20, 0))),
        ).habit
        // A daily reminder schedules a rolling window of occurrences.
        val scheduled = g.scheduler.pending.size
        assertTrue(scheduled > 0)

        g.archiveHabit(habit.id)
        assertTrue(g.habits.getHabits().none { it.id == habit.id && !it.archived }, "archived habits leave the list")
        assertTrue(g.scheduler.pending.isEmpty(), "archived habits don't remind")

        g.archiveHabit.restore(habit.id)
        assertEquals(false, g.habits.get(habit.id)?.archived)
        assertEquals(scheduled, g.scheduler.pending.size, "restoring brings the reminders back")
    }
}
