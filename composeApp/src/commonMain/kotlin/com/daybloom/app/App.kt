package com.daybloom.app

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.daybloom.design.Spacing
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import com.daybloom.design.BloomTheme
import com.daybloom.design.Elevation
import com.daybloom.components.softShadow
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.LocalFlorist
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.daybloom.components.LevelUpDialog
import com.daybloom.components.LocalHapticsEnabled
import com.daybloom.components.LocalPlatformInfo
import com.daybloom.design.DayBloomTheme
import com.daybloom.features.appointments.AppointmentEditorArgs
import com.daybloom.features.appointments.AppointmentEditorScreen
import com.daybloom.features.appointments.CalendarScreen
import com.daybloom.features.dashboard.DashboardNavigation
import com.daybloom.features.dashboard.DashboardScreen
import com.daybloom.features.dashboard.DoneListScreen
import com.daybloom.features.focus.FocusArgs
import com.daybloom.features.focus.FocusScreen
import com.daybloom.features.garden.BloomScreen
import com.daybloom.features.habits.HabitEditorScreen
import com.daybloom.features.habits.HabitsScreen
import com.daybloom.features.insights.InsightsScreen
import com.daybloom.features.insights.RecapScreen
import com.daybloom.features.planner.PlannerScreen
import com.daybloom.features.reminders.ReminderEditorScreen
import com.daybloom.features.reminders.RemindersScreen
import com.daybloom.features.settings.OnboardingScreen
import com.daybloom.features.settings.SettingsScreen
import com.daybloom.features.tasks.QuickCaptureSheet
import com.daybloom.features.tasks.TaskEditorArgs
import com.daybloom.features.tasks.TaskEditorScreen
import com.daybloom.features.tasks.TasksScreen
import com.daybloom.navigation.Route
import com.daybloom.platform.PlatformInfo
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import kotlin.reflect.KClass

private data class Tab(val route: Route, val type: KClass<out Route>, val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab(Route.Today, Route.Today::class, "Today", Icons.Outlined.WbSunny),
    Tab(Route.Tasks, Route.Tasks::class, "Tasks", Icons.Outlined.Checklist),
    Tab(Route.Calendar, Route.Calendar::class, "Calendar", Icons.Outlined.CalendarMonth),
    Tab(Route.Habits, Route.Habits::class, "Habits", Icons.Outlined.Repeat),
    Tab(Route.Bloom, Route.Bloom::class, "Bloom", Icons.Outlined.LocalFlorist),
)

@Composable
fun App() {
    val appViewModel = koinViewModel<AppViewModel>()
    val state by appViewModel.state.collectAsStateWithLifecycle()
    val platform = koinInject<PlatformInfo>()
    val prefs = state.preferences
    DayBloomTheme(prefs.themeMode, prefs.reducedMotion, prefs.gamificationEnabled) {
        CompositionLocalProvider(
            LocalPlatformInfo provides platform,
            LocalHapticsEnabled provides prefs.hapticsEnabled,
        ) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                when {
                    !state.loaded -> Box(Modifier.fillMaxSize())
                    !prefs.onboardingComplete -> ContentWidth { OnboardingScreen(appViewModel::completeOnboarding) }
                    else -> MainScaffold(appViewModel)
                }
                state.celebration?.let { LevelUpDialog(it, appViewModel::dismissCelebration) }
            }
        }
    }
}

@Composable
private fun MainScaffold(appViewModel: AppViewModel) {
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var quickCapture by remember { mutableStateOf(false) }
    val backStack by nav.currentBackStackEntryAsState()
    val destination = backStack?.destination
    val currentTab = TABS.firstOrNull { tab -> destination?.hasRoute(tab.type) == true }

    LaunchedEffect(Unit) {
        appViewModel.messages.collect { message ->
            scope.launch {
                val result = snackbar.showSnackbar(
                    message.text,
                    actionLabel = message.actionLabel,
                    withDismissAction = message.actionLabel == null,
                    duration = if (message.actionLabel != null) SnackbarDuration.Long else SnackbarDuration.Short,
                )
                if (result == SnackbarResult.ActionPerformed) message.onAction?.invoke()
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (currentTab != null) {
                // White bar lifted by a soft shadow; the current tab gets an indigo icon on a lavender pill.
                NavigationBar(
                    modifier = Modifier.softShadow(RectangleShape, Elevation.navigation),
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                    tonalElevation = 0.dp,
                ) {
                    val itemColors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TABS.forEach { tab ->
                        NavigationBarItem(
                            selected = tab == currentTab,
                            onClick = { nav.switchTab(tab.route) },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            // Never wraps or truncates: at large font sizes the label may run slightly into the item's padding.
                            label = { Text(tab.label, maxLines = 1, softWrap = false, overflow = TextOverflow.Visible) },
                            colors = itemColors,
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            if (currentTab != null && currentTab.route != Route.Bloom) {
                ExtendedFloatingActionButton(
                    onClick = { quickCapture = true },
                    // The extended FAB doesn't expose its label to accessibility services, so name it explicitly.
                    modifier = Modifier.semantics { contentDescription = "Add" },
                    containerColor = BloomTheme.colors.action,
                    contentColor = BloomTheme.colors.onAction,
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("Add") },
                )
            }
        },
    ) { padding ->
        // Tab screens have no top bar, so the status-bar inset is applied outside their scrolling lists:
        // content is clipped below the status bar instead of scrolling underneath the clock and icons.
        val topInset = if (currentTab != null) padding.calculateTopPadding() else 0.dp
        ContentWidth(Modifier.padding(top = topInset)) {
            AppNavHost(nav, PaddingValues(bottom = padding.calculateBottomPadding()))
        }
    }

    if (quickCapture) {
        QuickCaptureSheet(
            onDismiss = { quickCapture = false },
            onOpenTaskEditor = { quickCapture = false; nav.navigate(Route.TaskEditor(prefill = true)) },
            onOpenAppointmentEditor = { quickCapture = false; nav.navigate(Route.AppointmentEditor(prefill = true)) },
        )
    }
}

/**
 * Caps screen content at a readable width on tablets and in landscape; phones are unaffected.
 * The navigation bar stays full width.
 */
@Composable
private fun ContentWidth(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = Spacing.maxContentWidth).fillMaxSize()) { content() }
    }
}

private fun NavHostController.switchTab(route: Route) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun AppNavHost(nav: NavHostController, padding: PaddingValues) {
    val back: () -> Unit = { nav.popBackStack() }
    NavHost(
        navController = nav,
        startDestination = Route.Today,
        enterTransition = { fadeIn() },
        exitTransition = { fadeOut() },
        popEnterTransition = { EnterTransition.None + fadeIn() },
        popExitTransition = { ExitTransition.None + fadeOut() },
    ) {
        composable<Route.Today> {
            DashboardScreen(
                nav = DashboardNavigation(
                    openTask = { nav.navigate(Route.TaskEditor(taskId = it)) },
                    openTasks = { nav.switchTab(Route.Tasks) },
                    openPlanner = { nav.navigate(Route.Planner(it)) },
                    openAppointment = { id, date -> nav.navigate(Route.AppointmentEditor(id, date)) },
                    openFocus = { nav.navigate(Route.Focus(minutes = it)) },
                    openInsights = { nav.navigate(Route.Insights) },
                    openReminders = { nav.navigate(Route.Reminders) },
                    openDoneList = { nav.navigate(Route.DoneList(it)) },
                    openGarden = { nav.switchTab(Route.Bloom) },
                    openHabits = { nav.switchTab(Route.Habits) },
                    openSettings = { nav.navigate(Route.Settings) },
                ),
                contentPadding = padding,
            )
        }
        composable<Route.Tasks> { TasksScreen(padding, onOpenTask = { nav.navigate(Route.TaskEditor(taskId = it)) }) }
        composable<Route.Calendar> {
            CalendarScreen(
                padding,
                onOpenAppointment = { id, date -> nav.navigate(Route.AppointmentEditor(id, date)) },
                onNewAppointment = { nav.navigate(Route.AppointmentEditor(date = it)) },
                onOpenTask = { nav.navigate(Route.TaskEditor(taskId = it)) },
            )
        }
        composable<Route.Habits> {
            HabitsScreen(padding, onNewHabit = { nav.navigate(Route.HabitEditor()) }, onEditHabit = { nav.navigate(Route.HabitEditor(it)) })
        }
        composable<Route.Bloom> { BloomScreen(padding, onOpenSettings = { nav.navigate(Route.Settings) }) }

        composable<Route.TaskEditor> { entry ->
            val r = entry.toRoute<Route.TaskEditor>()
            TaskEditorScreen(
                TaskEditorArgs(r.taskId, r.date, r.appointmentId, r.prefill),
                onClose = back,
                onStartFocus = { nav.navigate(Route.Focus(taskId = it)) },
            )
        }
        composable<Route.AppointmentEditor> { entry ->
            val r = entry.toRoute<Route.AppointmentEditor>()
            AppointmentEditorScreen(AppointmentEditorArgs(r.appointmentId, r.date, r.prefill), onClose = back, onOpenTask = { nav.navigate(Route.TaskEditor(taskId = it)) })
        }
        composable<Route.Planner> { entry -> PlannerScreen(entry.toRoute<Route.Planner>().date, onClose = back) }
        composable<Route.Focus> { entry ->
            val r = entry.toRoute<Route.Focus>()
            FocusScreen(FocusArgs(r.taskId, r.minutes), onClose = back)
        }
        composable<Route.Insights> { InsightsScreen(onBack = back, onOpenRecap = { nav.navigate(Route.Recap(it)) }) }
        composable<Route.Recap> { entry -> RecapScreen(entry.toRoute<Route.Recap>().month, onBack = back) }
        composable<Route.Reminders> {
            RemindersScreen(onBack = back, onNew = { nav.navigate(Route.ReminderEditor()) }, onEdit = { nav.navigate(Route.ReminderEditor(it)) })
        }
        composable<Route.ReminderEditor> { entry -> ReminderEditorScreen(entry.toRoute<Route.ReminderEditor>().reminderId, onClose = back) }
        composable<Route.DoneList> { entry -> DoneListScreen(entry.toRoute<Route.DoneList>().date, onBack = back) }
        composable<Route.HabitEditor> { entry -> HabitEditorScreen(entry.toRoute<Route.HabitEditor>().habitId, onClose = back) }
        composable<Route.Settings> { SettingsScreen(onBack = back, onOpenReminders = { nav.navigate(Route.Reminders) }) }
    }
}
