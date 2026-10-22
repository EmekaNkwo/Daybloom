package com.daybloom.testing

import com.daybloom.domain.services.ProductivityScoreCalculator
import com.daybloom.domain.usecases.AppStartupUseCase
import com.daybloom.domain.usecases.ApplyUnfinishedTaskPolicyUseCase
import com.daybloom.domain.usecases.AwardExperiencePointsUseCase
import com.daybloom.domain.usecases.BuildDayScoreInputUseCase
import com.daybloom.domain.usecases.CalculateDailyProductivityScoreUseCase
import com.daybloom.domain.usecases.CalculatePlanCapacityUseCase
import com.daybloom.domain.usecases.CancelTaskUseCase
import com.daybloom.domain.usecases.CollectProgressStatsUseCase
import com.daybloom.domain.usecases.CompleteDailyQuestUseCase
import com.daybloom.domain.usecases.CompleteTaskUseCase
import com.daybloom.domain.usecases.CreateTaskUseCase
import com.daybloom.domain.usecases.DeleteTaskUseCase
import com.daybloom.domain.usecases.EnsureDailyQuestsUseCase
import com.daybloom.domain.usecases.EvaluateAchievementUseCase
import com.daybloom.domain.usecases.FocusSessionUseCases
import com.daybloom.domain.usecases.GenerateMonthlyInsightsUseCase
import com.daybloom.domain.usecases.GenerateMonthlyRecapUseCase
import com.daybloom.domain.usecases.GetGardenStateUseCase
import com.daybloom.domain.usecases.GetSuggestionsUseCase
import com.daybloom.domain.usecases.HandleReminderActionUseCase
import com.daybloom.domain.usecases.LevelUpUseCase
import com.daybloom.domain.usecases.LogBreakUseCase
import com.daybloom.domain.usecases.ProcessProgressUseCase
import com.daybloom.domain.usecases.ReopenTaskUseCase
import com.daybloom.domain.usecases.RescheduleTaskUseCase
import com.daybloom.domain.usecases.ResetAllDataUseCase
import com.daybloom.domain.usecases.CancelAppointmentUseCase
import com.daybloom.domain.usecases.SaveAppointmentUseCase
import com.daybloom.domain.usecases.SaveDailyPlanUseCase
import com.daybloom.domain.usecases.ArchiveHabitUseCase
import com.daybloom.domain.usecases.SaveHabitUseCase
import com.daybloom.domain.usecases.SaveReflectionUseCase
import com.daybloom.domain.usecases.DeleteReminderUseCase
import com.daybloom.domain.usecases.ScheduleReminderUseCase
import com.daybloom.domain.usecases.SeedDemoDataUseCase
import com.daybloom.domain.usecases.SetAppointmentAttendanceUseCase
import com.daybloom.domain.usecases.SetRestDayUseCase
import com.daybloom.domain.usecases.SyncSourceRemindersUseCase
import com.daybloom.domain.usecases.ToggleHabitUseCase
import com.daybloom.domain.usecases.UpdateTaskUseCase
import com.daybloom.notifications.ReminderReconciler

/**
 * Wires the real use cases to in-memory repositories and fake platform adapters, mirroring the Koin graph.
 * Creating a second graph over the same [store] simulates an app restart with persisted data.
 */
class TestGraph(
    val store: InMemoryStore = InMemoryStore(),
    val clock: FakeClock = FakeClock(),
    val scheduler: FakeNotificationScheduler = FakeNotificationScheduler(),
    val permissions: FakePermissionManager = FakePermissionManager(),
    val ids: SequentialIds = SequentialIds(),
) {
    val tasks = InMemoryTaskRepository(store)
    val appointments = InMemoryAppointmentRepository(store)
    val reminders = InMemoryReminderRepository(store)
    val plans = InMemoryPlanRepository(store)
    val habits = InMemoryHabitRepository(store)
    val focus = InMemoryFocusRepository(store)
    val scores = InMemoryScoreRepository(store)
    val xp = InMemoryXpRepository(store)
    val achievements = InMemoryAchievementRepository(store)
    val quests = InMemoryQuestRepository(store)
    val journal = InMemoryJournalRepository(store)
    val preferences = InMemoryPreferencesRepository(store)
    val dismissals = InMemoryDismissals(store)

    val reconciler = ReminderReconciler(reminders, scheduler, permissions, preferences, clock)
    val awardXp = AwardExperiencePointsUseCase(xp, clock)
    val buildInput = BuildDayScoreInputUseCase(tasks, plans, focus, habits, appointments, preferences, clock)
    val calculateScore = CalculateDailyProductivityScoreUseCase(buildInput, scores, preferences, clock, ProductivityScoreCalculator())
    val collectStats = CollectProgressStatsUseCase(tasks, plans, focus, habits, appointments, journal, quests, xp, clock)
    val evaluateAchievements = EvaluateAchievementUseCase(achievements, awardXp, clock)
    val ensureQuests = EnsureDailyQuestsUseCase(quests, tasks, habits)
    val questUseCase = CompleteDailyQuestUseCase(quests, tasks, plans, focus, habits, journal, awardXp, clock)
    val process = ProcessProgressUseCase(calculateScore, ensureQuests, questUseCase, collectStats, evaluateAchievements, clock)
    val garden = GetGardenStateUseCase(collectStats)
    val logBreak = LogBreakUseCase(journal, ids, clock, process)

    val reminderSync = SyncSourceRemindersUseCase(reminders, reconciler, clock)
    val scheduleReminder = ScheduleReminderUseCase(reminders, reconciler, ids, clock)
    val deleteReminder = DeleteReminderUseCase(reminders, reconciler)

    val createTask = CreateTaskUseCase(tasks, reminderSync, process, ids, clock)
    val updateTask = UpdateTaskUseCase(tasks, reminderSync, process, ids, clock)
    val completeTask = CompleteTaskUseCase(tasks, plans, awardXp, reminderSync, process, ids, clock)
    val reopenTask = ReopenTaskUseCase(tasks, awardXp, reminderSync, process, clock)
    val cancelTask = CancelTaskUseCase(tasks, awardXp, reminderSync, process, clock)
    val deleteTask = DeleteTaskUseCase(tasks, awardXp, reminderSync, process)
    val reschedule = RescheduleTaskUseCase(tasks, reminderSync, process, clock)

    val capacity = CalculatePlanCapacityUseCase(tasks, appointments)
    val savePlan = SaveDailyPlanUseCase(plans, capacity, awardXp, process, clock)
    val setRestDay = SetRestDayUseCase(plans, process, clock)
    val saveAppointment = SaveAppointmentUseCase(appointments, createTask, reminderSync, process, ids, clock)
    val cancelAppointment = CancelAppointmentUseCase(appointments, reminderSync, process, clock)
    val setAttendance = SetAppointmentAttendanceUseCase(appointments, process, clock)

    val saveHabit = SaveHabitUseCase(habits, reminderSync, process, ids, clock)
    val toggleHabit = ToggleHabitUseCase(habits, awardXp, reminderSync, process, clock)
    val archiveHabit = ArchiveHabitUseCase(habits, reminderSync, process, clock)
    val focusSessions = FocusSessionUseCases(focus, tasks, awardXp, process, ids, clock)
    val handleReminderAction = HandleReminderActionUseCase(reminders, tasks, completeTask, toggleHabit, reconciler, clock)

    val insights = GenerateMonthlyInsightsUseCase(scores, calculateScore, tasks, plans, focus, habits, appointments, clock)
    val recap = GenerateMonthlyRecapUseCase(insights, achievements, clock)
    val saveReflection = SaveReflectionUseCase(journal, awardXp, process, clock)
    val suggestions = GetSuggestionsUseCase(tasks, plans, appointments, preferences, dismissals, clock)

    val applyPolicy = ApplyUnfinishedTaskPolicyUseCase(tasks, preferences, reschedule, clock)
    val startup = AppStartupUseCase(reconciler, focusSessions, process, applyPolicy)
    val reset = ResetAllDataUseCase(InMemoryDataReset(store), preferences, reconciler)
    val levelUp = LevelUpUseCase(xp, preferences)
    val seed = SeedDemoDataUseCase(createTask, saveAppointment, saveHabit, preferences, clock)

    /** A fresh graph over the same persisted store and OS scheduler state — like relaunching the app. */
    fun restart(): TestGraph = TestGraph(store, clock, scheduler, permissions, SequentialIds("r"))
}
