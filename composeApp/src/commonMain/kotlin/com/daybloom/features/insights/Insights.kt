package com.daybloom.features.insights

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.daybloom.components.BarDatum
import com.daybloom.components.BarChart
import com.daybloom.components.BloomCard
import com.daybloom.components.BloomTopBar
import com.daybloom.components.EmptyState
import com.daybloom.components.ErrorState
import com.daybloom.components.Format
import com.daybloom.components.LoadingState
import com.daybloom.components.ScoreTrendChart
import com.daybloom.components.SectionHeader
import com.daybloom.components.ShareBars
import com.daybloom.components.categoryColor
import com.daybloom.design.Spacing
import com.daybloom.design.BloomTheme
import com.daybloom.components.FeaturedCard
import androidx.compose.foundation.layout.offset
import com.daybloom.domain.models.DayClassification
import com.daybloom.domain.repositories.ScoreRepository
import com.daybloom.domain.repositories.TaskRepository
import com.daybloom.domain.services.DateMath
import com.daybloom.domain.services.MonthlyInsights
import com.daybloom.domain.services.MonthlyRecap
import com.daybloom.domain.services.MonthlyRecapGenerator
import com.daybloom.domain.usecases.GenerateMonthlyInsightsUseCase
import com.daybloom.domain.usecases.GenerateMonthlyRecapUseCase
import com.daybloom.features.common.AppMessages
import com.daybloom.features.common.runAction
import com.daybloom.platform.ExportResult
import com.daybloom.platform.PlatformDateTimeService
import com.daybloom.platform.PlatformFileExporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import com.daybloom.components.BloomButtons
import com.daybloom.components.SwitchRow

sealed interface InsightsUi {
    data object Loading : InsightsUi
    data class Ready(val month: LocalDate, val insights: MonthlyInsights, val isCurrentMonth: Boolean) : InsightsUi
    data class Failed(val message: String) : InsightsUi
}

class InsightsViewModel(
    private val generate: GenerateMonthlyInsightsUseCase,
    scores: ScoreRepository,
    tasks: TaskRepository,
    private val clock: PlatformDateTimeService,
) : ViewModel() {
    private val month = MutableStateFlow(DateMath.firstOfMonth(clock.today()))
    private val refresh = MutableStateFlow(0)

    /** Recomputed off the main thread whenever the month changes or its stored history changes. */
    val state: StateFlow<InsightsUi> = combine(month, refresh) { m, _ -> m }.flatMapLatest { m ->
        combine(scores.observeBetween(m, DateMath.lastOfMonth(m)), tasks.observeCompletedBetween(m, DateMath.lastOfMonth(m))) { _, _ -> m }
            .debounce(300)
            .flatMapLatest { target ->
                flow<InsightsUi> {
                    emit(InsightsUi.Loading)
                    try {
                        emit(InsightsUi.Ready(target, generate(target), target == DateMath.firstOfMonth(clock.today())))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        emit(InsightsUi.Failed("Insights couldn't be calculated right now."))
                    }
                }
            }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InsightsUi.Loading)

    fun shift(forward: Boolean) {
        val next = DateMath.plusMonths(month.value, if (forward) 1 else -1)
        if (next <= DateMath.firstOfMonth(clock.today())) month.value = next
    }

    fun retry() {
        refresh.value++
    }
}

@Composable
fun InsightsScreen(onBack: () -> Unit, onOpenRecap: (String) -> Unit, viewModel: InsightsViewModel = koinViewModel()) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    var showMethod by rememberSaveable { mutableStateOf(false) }
    Scaffold(topBar = { BloomTopBar("Insights", onBack = onBack) }) { padding ->
        when (val s = ui) {
            InsightsUi.Loading -> LoadingState(Modifier.padding(padding), "Calculating insights")
            is InsightsUi.Failed -> ErrorState(s.message, Modifier.padding(padding), viewModel::retry)
            is InsightsUi.Ready -> {
                val i = s.insights
                Column(
                    Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = Spacing.screenGutter),
                    verticalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { viewModel.shift(false) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous month") }
                        Text("${Format.monthYear(s.month).uppercase()} PRODUCTIVITY", Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleSmall)
                        IconButton(onClick = { viewModel.shift(true) }, enabled = !s.isCurrentMonth) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next month") }
                    }
                    if (i.scoredDays == 0 && i.tasksCompleted == 0 && i.focusMinutes == 0) {
                        EmptyState("📊", "No history yet", "Insights appear as you plan days and complete tasks. Untracked days never count as zero.")
                    }
                    BloomCard {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(i.averageScore?.toString() ?: "–", style = MaterialTheme.typography.displayMedium)
                            Text("  average score", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(bottom = Spacing.sm))
                        }
                        Text(
                            "Based on ${i.scoredDays} scored day${if (i.scoredDays == 1) "" else "s"} of ${i.eligibleDays} available · consistency ${i.consistencyPercent}%" +
                                (i.scoreSpread?.let { " · spread ±$it" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "${i.restDays} rest day${if (i.restDays == 1) "" else "s"} and ${i.noDataDays} untracked day${if (i.noDataDays == 1) "" else "s"} are left out of the average, not counted as zero.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = { showMethod = !showMethod }, modifier = Modifier.offset(x = -Spacing.md)) { Text(if (showMethod) "Hide method" else "How is this calculated?") }
                        AnimatedVisibility(showMethod) {
                            Text(
                                "The monthly average is the mean of daily scores on scored days: days you planned or had tasks due, " +
                                    "plus unplanned days with recorded activity. Low days are always included. Rest days and days with " +
                                    "nothing planned or recorded are excluded and shown separately. Consistency = scored days ÷ " +
                                    "(days so far − rest days).",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    SectionHeader("Daily score trend")
                    ScoreTrendChart(
                        points = i.trend.map { it.score },
                        restIndices = i.trend.withIndex().filter { it.value.classification == DayClassification.REST }.map { it.index }.toSet(),
                        summary = "Daily scores for ${Format.monthName(s.month)}; gaps are rest or untracked days.",
                    )
                    if (i.weeks.isNotEmpty()) {
                        SectionHeader("Weekly comparison")
                        BarChart(
                            i.weeks.map { w -> BarDatum("Wk ${Format.shortMonth(w.weekStart)} ${w.weekStart.day}", (w.averageScore ?: 0).toFloat(), w.averageScore?.toString() ?: "–") },
                            maxValue = 100f,
                        )
                    }
                    SectionHeader("This month")
                    MetricGrid(
                        listOf(
                            "Tasks completed" to i.tasksCompleted.toString(),
                            "Important tasks" to (i.importantCompletionPercent?.let { "$it%" } ?: "–"),
                            "Focus time" to MonthlyRecapGenerator.formatHours(i.focusMinutes),
                            "Habits completed" to (i.habitCompletionPercent?.let { "$it%" } ?: "–"),
                            "Planning consistency" to "${i.planningConsistencyPercent}%",
                            "Appointments attended" to (i.appointmentAttendancePercent?.let { "$it% of ${i.appointmentsRecorded}" } ?: "None recorded"),
                            "Best streak" to "${i.bestStreak} day${if (i.bestStreak == 1) "" else "s"}",
                            "Best day" to (i.bestDay?.takeIf { i.hasEnoughDataForTrends }?.let { "${Format.shortMonth(it)} ${it.day}" } ?: "–"),
                        ),
                    )
                    if (!i.hasEnoughDataForTrends) {
                        Text("Patterns like best day and strongest category appear after a few more scored days.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (i.categoryDistribution.isNotEmpty()) {
                        SectionHeader("Completed by category" + (i.strongestCategory?.takeIf { i.hasEnoughDataForTrends }?.let { " · strongest: ${it.label}" } ?: ""))
                        ShareBars(i.categoryDistribution.entries.sortedByDescending { it.value }.map { (c, n) -> BarDatum("${c.emoji} ${c.label}", n.toFloat(), n.toString(), categoryColor(c)) })
                    }
                    if (i.weeks.any { it.overdueTasks > 0 }) {
                        SectionHeader("Tasks that slipped past their date")
                        BarChart(i.weeks.map { w -> BarDatum("${Format.shortMonth(w.weekStart)} ${w.weekStart.day}", w.overdueTasks.toFloat(), w.overdueTasks.toString()) })
                    }
                    Button(colors = BloomButtons.primary(), onClick = { onOpenRecap(s.month.toString()) }, modifier = Modifier.fillMaxWidth()) { Text("${Format.monthName(s.month)} recap") }
                    Spacer(Modifier.height(Spacing.xl))
                }
            }
        }
    }
}

@Composable
private fun MetricGrid(metrics: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        metrics.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                row.forEach { (label, value) ->
                    BloomCard(Modifier.weight(1f)) {
                        Text(value, style = MaterialTheme.typography.titleLarge)
                        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

data class RecapState(
    val loading: Boolean = true,
    val recap: MonthlyRecap? = null,
    val insights: MonthlyInsights? = null,
    val includePrivate: Boolean = false,
    val suggestionDismissed: Boolean = false,
    val error: String? = null,
)

class RecapViewModel(
    private val monthArg: String,
    private val generate: GenerateMonthlyRecapUseCase,
    private val tasks: TaskRepository,
    private val exporter: PlatformFileExporter,
    private val messages: AppMessages,
    private val clock: PlatformDateTimeService,
) : ViewModel() {
    private val _state = MutableStateFlow(RecapState())
    val state: StateFlow<RecapState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val month = runCatching { LocalDate.parse(monthArg) }.getOrNull() ?: clock.today()
            try {
                val result = generate(month)
                _state.update { it.copy(loading = false, recap = result.recap, insights = result.insights) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = "The recap couldn't be generated.") }
            }
        }
    }

    fun setIncludePrivate(v: Boolean) = _state.update { it.copy(includePrivate = v) }
    fun dismissSuggestion() = _state.update { it.copy(suggestionDismissed = true) }

    fun share() = runAction(messages, "Couldn't share the recap.") {
        val s = _state.value
        val recap = s.recap ?: return@runAction
        val insights = s.insights ?: return@runAction
        val shown = if (s.suggestionDismissed) recap.copy(suggestion = null) else recap
        val privateLines = if (s.includePrivate) {
            tasks.getCompletedBetween(recap.month, DateMath.lastOfMonth(recap.month)).take(20).map { "Completed: ${it.title}" }
        } else {
            emptyList()
        }
        val text = MonthlyRecapGenerator.shareableText(shown, insights, s.includePrivate, privateLines)
        when (val result = exporter.shareText("daybloom-recap-${recap.month.year}-${recap.month.month.ordinal + 1}.txt", "text/plain", text)) {
            is ExportResult.Shared -> Unit
            is ExportResult.Failed -> messages.post(result.reason)
        }
    }
}

@Composable
fun RecapScreen(month: String, onBack: () -> Unit, viewModel: RecapViewModel = koinViewModel(key = "recap-$month") { parametersOf(month) }) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(topBar = { BloomTopBar("Monthly recap", onBack = onBack) }) { padding ->
        val recap = state.recap
        when {
            state.loading -> LoadingState(Modifier.padding(padding))
            state.error != null || recap == null -> ErrorState(state.error ?: "No recap available.", Modifier.padding(padding))
            else -> Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = Spacing.screenGutter),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                // The recap is what gets shared, so it's this screen's featured surface.
                FeaturedCard(Modifier.padding(top = Spacing.sm)) {
                    Text(
                        "${MonthlyRecapGenerator.monthName(recap.month)} ${recap.month.year}".uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = BloomTheme.colors.onFeaturedMuted,
                    )
                    Text(recap.headline, style = MaterialTheme.typography.headlineSmall)
                    Column(Modifier.padding(top = Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        recap.highlights.forEach { Text("• $it", style = MaterialTheme.typography.bodyLarge, color = BloomTheme.colors.onFeaturedMuted) }
                    }
                }
                if (recap.achievements.isNotEmpty()) {
                    SectionHeader("Achievements")
                    Text(recap.achievements.joinToString("   ") { "${it.emoji} ${it.title}" }, style = MaterialTheme.typography.bodyMedium)
                }
                recap.dataNote?.let { BloomCard(tonal = true) { Text(it, style = MaterialTheme.typography.bodyMedium) } }
                if (recap.suggestion != null && !state.suggestionDismissed) {
                    BloomCard(tonal = true) {
                        Text("One idea for next month", style = MaterialTheme.typography.titleSmall)
                        Text(recap.suggestion, style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = viewModel::dismissSuggestion) { Text("Dismiss") }
                    }
                }
                SwitchRow("Include task names", state.includePrivate, viewModel::setIncludePrivate, subtitle = "Off by default. Reflections are never shared.")
                Button(colors = BloomButtons.primary(), onClick = { viewModel.share() }, modifier = Modifier.fillMaxWidth()) { Text("Share recap") }
                Spacer(Modifier.height(Spacing.xl))
            }
        }
    }
}
