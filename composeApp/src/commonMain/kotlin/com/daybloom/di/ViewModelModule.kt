package com.daybloom.di

import com.daybloom.app.AppViewModel
import com.daybloom.features.appointments.AppointmentEditorArgs
import com.daybloom.features.appointments.AppointmentEditorViewModel
import com.daybloom.features.appointments.CalendarViewModel
import com.daybloom.features.common.AppMessages
import com.daybloom.features.common.DraftHandoff
import com.daybloom.features.dashboard.DashboardViewModel
import com.daybloom.features.dashboard.DoneListViewModel
import com.daybloom.features.focus.FocusArgs
import com.daybloom.features.focus.FocusViewModel
import com.daybloom.features.garden.BloomViewModel
import com.daybloom.features.habits.HabitEditorViewModel
import com.daybloom.features.habits.HabitsViewModel
import com.daybloom.features.insights.InsightsViewModel
import com.daybloom.features.insights.RecapViewModel
import com.daybloom.features.planner.PlannerViewModel
import com.daybloom.features.reminders.ReminderEditorViewModel
import com.daybloom.features.reminders.RemindersViewModel
import com.daybloom.features.settings.SettingsViewModel
import com.daybloom.features.tasks.QuickCaptureViewModel
import com.daybloom.features.tasks.TaskEditorArgs
import com.daybloom.features.tasks.TaskEditorViewModel
import com.daybloom.features.tasks.TasksViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

/** One ViewModel per feature; screens obtain them with koinViewModel(), parameterized ones via parametersOf. */
val viewModelModule = module {
    single { AppMessages() }
    single { DraftHandoff() }

    viewModelOf(::AppViewModel)
    viewModelOf(::DashboardViewModel)
    viewModelOf(::TasksViewModel)
    viewModelOf(::QuickCaptureViewModel)
    viewModelOf(::CalendarViewModel)
    viewModelOf(::HabitsViewModel)
    viewModelOf(::BloomViewModel)
    viewModelOf(::InsightsViewModel)
    viewModelOf(::RemindersViewModel)
    viewModelOf(::SettingsViewModel)

    viewModel { (args: TaskEditorArgs) -> TaskEditorViewModel(args, get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    viewModel { (args: AppointmentEditorArgs) -> AppointmentEditorViewModel(args, get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    viewModel { (date: String) -> PlannerViewModel(date, get(), get(), get(), get(), get(), get(), get(), get()) }
    viewModel { (args: FocusArgs) -> FocusViewModel(args, get(), get(), get(), get(), get(), get()) }
    viewModel { (month: String) -> RecapViewModel(month, get(), get(), get(), get(), get()) }
    viewModel { (date: String) -> DoneListViewModel(date, get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    viewModel { params -> HabitEditorViewModel(params.getOrNull(), get(), get(), get()) }
    viewModel { params -> ReminderEditorViewModel(params.getOrNull(), get(), get(), get(), get()) }
}
