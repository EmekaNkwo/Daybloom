package com.daybloom.di

import com.daybloom.data.database.AppDatabase
import com.daybloom.data.database.RoomTransactionRunner
import com.daybloom.data.database.TransactionRunner
import com.daybloom.data.repositories.DataStorePreferencesRepository
import com.daybloom.data.repositories.RoomAchievementRepository
import com.daybloom.data.repositories.RoomAppointmentRepository
import com.daybloom.data.repositories.RoomDataResetRepository
import com.daybloom.data.repositories.RoomFocusRepository
import com.daybloom.data.repositories.RoomHabitRepository
import com.daybloom.data.repositories.RoomJournalRepository
import com.daybloom.data.repositories.RoomPlanRepository
import com.daybloom.data.repositories.RoomQuestRepository
import com.daybloom.data.repositories.RoomReminderRepository
import com.daybloom.data.repositories.RoomScoreRepository
import com.daybloom.data.repositories.RoomSuggestionDismissalStore
import com.daybloom.data.repositories.RoomTaskRepository
import com.daybloom.data.repositories.RoomXpRepository
import com.daybloom.domain.repositories.AchievementRepository
import com.daybloom.domain.repositories.AppointmentRepository
import com.daybloom.domain.repositories.DataResetRepository
import com.daybloom.domain.repositories.FocusRepository
import com.daybloom.domain.repositories.HabitRepository
import com.daybloom.domain.repositories.JournalRepository
import com.daybloom.domain.repositories.PlanRepository
import com.daybloom.domain.repositories.PreferencesRepository
import com.daybloom.domain.repositories.QuestRepository
import com.daybloom.domain.repositories.ReminderRepository
import com.daybloom.domain.repositories.ScoreRepository
import com.daybloom.domain.repositories.TaskRepository
import com.daybloom.domain.repositories.XpRepository
import com.daybloom.domain.services.ProductivityScoreCalculator
import com.daybloom.domain.services.QuickCaptureParser
import com.daybloom.domain.usecases.AppStartupUseCase
import com.daybloom.domain.usecases.ApplyUnfinishedTaskPolicyUseCase
import com.daybloom.domain.usecases.ArchiveHabitUseCase
import com.daybloom.domain.usecases.AwardExperiencePointsUseCase
import com.daybloom.domain.usecases.BuildDayScoreInputUseCase
import com.daybloom.domain.usecases.CalculateDailyProductivityScoreUseCase
import com.daybloom.domain.usecases.CalculatePlanCapacityUseCase
import com.daybloom.domain.usecases.CancelAppointmentUseCase
import com.daybloom.domain.usecases.CancelTaskUseCase
import com.daybloom.domain.usecases.CollectProgressStatsUseCase
import com.daybloom.domain.usecases.CompleteDailyQuestUseCase
import com.daybloom.domain.usecases.CompleteTaskUseCase
import com.daybloom.domain.usecases.CreateTaskUseCase
import com.daybloom.domain.usecases.DeleteAppointmentUseCase
import com.daybloom.domain.usecases.DeleteReflectionUseCase
import com.daybloom.domain.usecases.DeleteReminderUseCase
import com.daybloom.domain.usecases.DeleteTaskUseCase
import com.daybloom.domain.usecases.DuplicateTaskUseCase
import com.daybloom.domain.usecases.EnsureDailyQuestsUseCase
import com.daybloom.domain.usecases.EvaluateAchievementUseCase
import com.daybloom.domain.usecases.FocusSessionUseCases
import com.daybloom.domain.usecases.GenerateMonthlyInsightsUseCase
import com.daybloom.domain.usecases.GenerateMonthlyRecapUseCase
import com.daybloom.domain.usecases.GetDailyScoreUseCase
import com.daybloom.domain.usecases.GetGardenStateUseCase
import com.daybloom.domain.usecases.GetSuggestionsUseCase
import com.daybloom.domain.usecases.HandleReminderActionUseCase
import com.daybloom.domain.usecases.LevelUpUseCase
import com.daybloom.domain.usecases.LogBreakUseCase
import com.daybloom.domain.usecases.ManualWinUseCases
import com.daybloom.domain.usecases.PauseHabitUseCase
import com.daybloom.domain.usecases.ProcessProgressUseCase
import com.daybloom.domain.usecases.ReopenTaskUseCase
import com.daybloom.domain.usecases.RescheduleReminderUseCase
import com.daybloom.domain.usecases.RescheduleTaskUseCase
import com.daybloom.domain.usecases.ResetAllDataUseCase
import com.daybloom.domain.usecases.SaveAppointmentUseCase
import com.daybloom.domain.usecases.SaveDailyPlanUseCase
import com.daybloom.domain.usecases.SaveHabitUseCase
import com.daybloom.domain.usecases.SaveReflectionUseCase
import com.daybloom.domain.usecases.ScheduleReminderUseCase
import com.daybloom.domain.usecases.SeedDemoDataUseCase
import com.daybloom.domain.usecases.SetAppointmentAttendanceUseCase
import com.daybloom.domain.usecases.SetRestDayUseCase
import com.daybloom.domain.usecases.SuggestionDismissalStore
import com.daybloom.domain.usecases.SyncSourceRemindersUseCase
import com.daybloom.domain.usecases.ToggleHabitUseCase
import com.daybloom.domain.usecases.ToggleSubtaskUseCase
import com.daybloom.domain.usecases.UpdateTaskUseCase
import com.daybloom.notifications.ReminderReconciler
import com.daybloom.platform.IdGenerator
import org.koin.core.module.Module
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

/**
 * Platform bindings. Each platform provides: [AppDatabase] (one instance per process),
 * DataStore<Preferences>, PlatformDateTimeService, NotificationScheduler, NotificationPermissionManager,
 * PlatformFileExporter, AppLifecycleService and PlatformInfo.
 */
expect fun platformModule(): Module

val dataModule = module {
    single { get<AppDatabase>().taskDao() }
    single { get<AppDatabase>().appointmentDao() }
    single { get<AppDatabase>().dailyPlanDao() }
    single { get<AppDatabase>().reminderDao() }
    single { get<AppDatabase>().habitDao() }
    single { get<AppDatabase>().focusDao() }
    single { get<AppDatabase>().scoreDao() }
    single { get<AppDatabase>().xpDao() }
    single { get<AppDatabase>().achievementDao() }
    single { get<AppDatabase>().questDao() }
    single { get<AppDatabase>().journalDao() }
    single { get<AppDatabase>().suggestionDao() }
    single<TransactionRunner> { RoomTransactionRunner(get()) }

    singleOf(::RoomTaskRepository) bind TaskRepository::class
    singleOf(::RoomAppointmentRepository) bind AppointmentRepository::class
    singleOf(::RoomReminderRepository) bind ReminderRepository::class
    singleOf(::RoomPlanRepository) bind PlanRepository::class
    singleOf(::RoomHabitRepository) bind HabitRepository::class
    singleOf(::RoomFocusRepository) bind FocusRepository::class
    singleOf(::RoomScoreRepository) bind ScoreRepository::class
    singleOf(::RoomXpRepository) bind XpRepository::class
    singleOf(::RoomAchievementRepository) bind AchievementRepository::class
    singleOf(::RoomQuestRepository) bind QuestRepository::class
    singleOf(::RoomJournalRepository) bind JournalRepository::class
    singleOf(::RoomSuggestionDismissalStore) bind SuggestionDismissalStore::class
    singleOf(::RoomDataResetRepository) bind DataResetRepository::class
    singleOf(::DataStorePreferencesRepository) bind PreferencesRepository::class
}

val domainModule = module {
    single { IdGenerator.Random }
    single { ProductivityScoreCalculator() }
    single { QuickCaptureParser() }
    singleOf(::ReminderReconciler)

    singleOf(::AwardExperiencePointsUseCase)
    singleOf(::BuildDayScoreInputUseCase)
    single { CalculateDailyProductivityScoreUseCase(get(), get(), get(), get(), get()) }
    singleOf(::GetDailyScoreUseCase)
    singleOf(::CollectProgressStatsUseCase)
    singleOf(::EvaluateAchievementUseCase)
    singleOf(::EnsureDailyQuestsUseCase)
    singleOf(::CompleteDailyQuestUseCase)
    singleOf(::ProcessProgressUseCase)
    singleOf(::GetGardenStateUseCase)
    singleOf(::LogBreakUseCase)

    singleOf(::SyncSourceRemindersUseCase)
    singleOf(::ScheduleReminderUseCase)
    singleOf(::RescheduleReminderUseCase)
    singleOf(::HandleReminderActionUseCase)
    singleOf(::DeleteReminderUseCase)

    singleOf(::CreateTaskUseCase)
    singleOf(::UpdateTaskUseCase)
    singleOf(::CompleteTaskUseCase)
    singleOf(::ReopenTaskUseCase)
    singleOf(::CancelTaskUseCase)
    singleOf(::DeleteTaskUseCase)
    singleOf(::DuplicateTaskUseCase)
    singleOf(::RescheduleTaskUseCase)
    singleOf(::ToggleSubtaskUseCase)

    singleOf(::CalculatePlanCapacityUseCase)
    singleOf(::SaveDailyPlanUseCase)
    singleOf(::SetRestDayUseCase)
    singleOf(::SaveAppointmentUseCase)
    singleOf(::SetAppointmentAttendanceUseCase)
    singleOf(::CancelAppointmentUseCase)
    singleOf(::DeleteAppointmentUseCase)

    singleOf(::SaveHabitUseCase)
    singleOf(::ToggleHabitUseCase)
    singleOf(::PauseHabitUseCase)
    singleOf(::ArchiveHabitUseCase)
    singleOf(::FocusSessionUseCases)

    singleOf(::GenerateMonthlyInsightsUseCase)
    singleOf(::GenerateMonthlyRecapUseCase)
    singleOf(::SaveReflectionUseCase)
    singleOf(::DeleteReflectionUseCase)
    singleOf(::ManualWinUseCases)
    singleOf(::GetSuggestionsUseCase)

    singleOf(::AppStartupUseCase)
    singleOf(::ApplyUnfinishedTaskPolicyUseCase)
    singleOf(::ResetAllDataUseCase)
    singleOf(::LevelUpUseCase)
    singleOf(::SeedDemoDataUseCase)
}

fun sharedModules(): List<Module> = listOf(platformModule(), dataModule, domainModule, viewModelModule)
