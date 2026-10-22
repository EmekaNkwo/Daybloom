package com.daybloom.features.garden

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.daybloom.components.AchievementBadge
import com.daybloom.components.BloomCard
import com.daybloom.components.ChoiceChips
import com.daybloom.components.EmptyState
import com.daybloom.components.GardenCanvas
import com.daybloom.components.LevelSummary
import com.daybloom.components.LoadingState
import com.daybloom.components.ScreenHeader
import com.daybloom.components.SectionHeader
import com.daybloom.components.XpPill
import com.daybloom.design.LocalGamificationEnabled
import com.daybloom.design.Spacing
import com.daybloom.components.FeaturedCard
import com.daybloom.domain.models.AchievementCategory
import com.daybloom.domain.models.GardenTheme
import com.daybloom.domain.models.LevelProgress
import com.daybloom.domain.models.XpEntry
import com.daybloom.domain.repositories.AchievementRepository
import com.daybloom.domain.repositories.PreferencesRepository
import com.daybloom.domain.repositories.XpRepository
import com.daybloom.domain.usecases.CollectProgressStatsUseCase
import com.daybloom.features.common.AppMessages
import com.daybloom.features.common.runAction
import com.daybloom.gamification.AchievementCatalog
import com.daybloom.gamification.AchievementDefinition
import com.daybloom.gamification.GardenCalculator
import com.daybloom.gamification.GardenState
import com.daybloom.gamification.LevelSystem
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import org.koin.compose.viewmodel.koinViewModel

data class AchievementView(val definition: AchievementDefinition, val unlocked: Boolean, val progress: Int)

data class BloomState(
    val loading: Boolean = true,
    val garden: GardenState? = null,
    val theme: GardenTheme = GardenTheme.MEADOW,
    val level: LevelProgress = LevelSystem.progressFor(0),
    val achievements: Map<AchievementCategory, List<AchievementView>> = emptyMap(),
    val unlockedCount: Int = 0,
    val recentXp: List<XpEntry> = emptyList(),
)

class BloomViewModel(
    xp: XpRepository,
    achievements: AchievementRepository,
    private val preferences: PreferencesRepository,
    private val collectStats: CollectProgressStatsUseCase,
    private val messages: AppMessages,
) : ViewModel() {
    private val stats = xp.observeTotal().mapLatest { total -> total to collectStats() }

    val state: StateFlow<BloomState> = combine(stats, achievements.observeUnlocks(), preferences.preferences, xp.observeRecent(15)) { (total, facts), unlocks, prefs, recent ->
        val unlockedIds = unlocks.map { it.achievementId }.toSet()
        val views = AchievementCatalog.all.map { AchievementView(it, it.id in unlockedIds, it.progress(facts.stats)) }
        BloomState(
            loading = false,
            garden = GardenCalculator.calculate(facts.garden),
            theme = prefs.gardenTheme,
            level = LevelSystem.progressFor(total),
            achievements = views.groupBy { it.definition.category },
            unlockedCount = unlockedIds.size,
            recentXp = recent,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BloomState())

    fun setTheme(theme: GardenTheme) = runAction(messages) { preferences.update { it.copy(gardenTheme = theme) } }
}

@Composable
fun BloomScreen(contentPadding: PaddingValues, onOpenSettings: () -> Unit, viewModel: BloomViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    if (!LocalGamificationEnabled.current) {
        EmptyState(
            "🌿", "Gamification is off",
            "Tasks, reminders and insights work exactly the same. Turn it back on any time in Settings.",
            Modifier.padding(contentPadding), "Open settings", onOpenSettings,
        )
        return
    }
    val garden = state.garden
    if (state.loading || garden == null) {
        LoadingState(Modifier.padding(contentPadding))
        return
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Spacing.screenGutter, end = Spacing.screenGutter,
            top = contentPadding.calculateTopPadding(), bottom = contentPadding.calculateBottomPadding() + Spacing.xl,
        ),
        verticalArrangement = Arrangement.spacedBy(Spacing.itemGap),
    ) {
        item {
            ScreenHeader("Bloom Garden")
        }
        item {
            BloomCard {
                GardenCanvas(garden, state.theme, height = 240.dp)
                Text(
                    "${garden.plants.count { it.isMature }} grown · ${garden.flowers} flowers · ${garden.decorations.size} decorations · ${garden.areas.size} area${if (garden.areas.size == 1) "" else "s"}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                    Text(garden.nextMilestone, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Your garden only grows. Quiet days just mean it's resting too.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                ChoiceChips(GardenTheme.entries, state.theme, { it.label }, viewModel::setTheme, Modifier.padding(top = Spacing.xs))
            }
        }
        item { FeaturedCard { LevelSummary(state.level, onFeatured = true) } }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                SectionHeader("How your garden grows")
                listOf(
                    "🌱 Each task adds growth; top priorities add double",
                    "🌸 Every daily quest plants a flower",
                    "🏮 Every ${GardenCalculator.SESSIONS_PER_DECORATION} focus sessions unlock a decoration",
                    "🌻 Weeks with ${GardenCalculator.ACTIVE_DAYS_FOR_WEEK}+ active days unlock new plants",
                    "🪷 Months with ${GardenCalculator.ACTIVE_DAYS_FOR_MONTH}+ active days open new areas",
                ).forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
        }
        item { SectionHeader("Achievements · ${state.unlockedCount}/${AchievementCatalog.all.size}") }
        state.achievements.forEach { (category, list) ->
            item(key = category.name) {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text(category.label, style = MaterialTheme.typography.titleSmall)
                    list.chunked(3).forEach { row ->
                        Row(Modifier.fillMaxWidth()) {
                            row.forEach { a ->
                                AchievementBadge(
                                    a.definition.emoji, a.definition.title, a.unlocked,
                                    "${a.progress}/${a.definition.target}", Modifier.weight(1f).padding(Spacing.xs),
                                )
                            }
                            repeat(3 - row.size) { Column(Modifier.weight(1f)) {} }
                        }
                    }
                }
            }
        }
        if (state.recentXp.isNotEmpty()) {
            item { SectionHeader("Recent XP") }
            state.recentXp.forEach { entry ->
                item(key = entry.eventKey) {
                    Row(Modifier.heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(entry.reason.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        if (entry.amount > 0) XpPill(entry.amount) else Text("daily cap reached", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}
