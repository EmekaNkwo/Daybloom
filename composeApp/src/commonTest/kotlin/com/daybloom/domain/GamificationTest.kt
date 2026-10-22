package com.daybloom.domain

import com.daybloom.domain.models.Habit
import com.daybloom.domain.models.QuestStatus
import com.daybloom.domain.models.QuestType
import com.daybloom.domain.services.StreakCalculator
import com.daybloom.gamification.AchievementCatalog
import com.daybloom.gamification.GardenCalculator
import com.daybloom.gamification.GardenInputs
import com.daybloom.gamification.LevelSystem
import com.daybloom.gamification.ProgressStats
import com.daybloom.gamification.QuestCatalog
import com.daybloom.gamification.QuestContext
import com.daybloom.gamification.QuestDayProgress
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class GamificationTest {
    private val at = Instant.fromEpochMilliseconds(0)

    @Test
    fun `levels progress monotonically`() {
        assertEquals(1, LevelSystem.progressFor(0).level.number)
        assertEquals(2, LevelSystem.progressFor(100).level.number)
        assertEquals(7, LevelSystem.progressFor(3_200).level.title.let { LevelSystem.levels.first { l -> l.title == it }.number })
        assertEquals(0.5f, LevelSystem.progressFor(200).fraction)
        assertEquals(1f, LevelSystem.progressFor(1_000_000).fraction)
        assertEquals(1, LevelSystem.progressFor(-5).level.number)
        val levels = (0..12_000 step 50).map { LevelSystem.progressFor(it).level.number }
        assertEquals(levels.sorted(), levels)
    }

    @Test
    fun `quests are deterministic per date and adapt to context`() {
        val date = LocalDate(2026, 10, 22)
        val ctx = QuestContext(hasHabits = false, hasPostponedTasks = false, hasOpenTasks = true)
        val a = QuestCatalog.generate(date, ctx)
        assertEquals(a, QuestCatalog.generate(date, ctx))
        assertEquals(QuestCatalog.QUESTS_PER_DAY, a.size)
        assertEquals(QuestType.COMPLETE_TOP_PRIORITY, a.first().type)
        assertTrue(a.none { it.type == QuestType.COMPLETE_ONE_HABIT || it.type == QuestType.FINISH_POSTPONED_TASK })
        assertEquals(a.size, a.map { it.id }.toSet().size)
    }

    @Test
    fun `quest progress completes once and terminal quests never change`() {
        val quest = QuestCatalog.generate(LocalDate(2026, 10, 22), QuestContext(true, true, true)).first { it.type == QuestType.FINISH_THREE_TASKS }
        val progress = QuestDayProgress(0, 2, 0, 0, false, 0, 0)
        val partial = QuestCatalog.advance(quest, progress, at)
        assertEquals(2, partial.progress)
        assertEquals(QuestStatus.ACTIVE, partial.status)
        val done = QuestCatalog.advance(partial, progress.copy(tasksCompleted = 5), at)
        assertEquals(QuestStatus.COMPLETED, done.status)
        assertEquals(3, done.progress)
        assertEquals(done, QuestCatalog.advance(done, progress.copy(tasksCompleted = 0), at))
        val skipped = quest.copy(status = QuestStatus.SKIPPED)
        assertEquals(skipped, QuestCatalog.advance(skipped, progress.copy(tasksCompleted = 9), at))
    }

    @Test
    fun `achievements unlock from central criteria and only once`() {
        val stats = ProgressStats(tasksCompleted = 1, reflectionsWritten = 1)
        val first = AchievementCatalog.newlyUnlocked(stats, emptySet()).map { it.id }.toSet()
        assertEquals(setOf("first_task", "first_reflection"), first)
        assertTrue(AchievementCatalog.newlyUnlocked(stats, first).isEmpty())
        assertTrue(AchievementCatalog.all.map { it.id }.toSet().size == AchievementCatalog.all.size, "ids are unique")
    }

    @Test
    fun `garden only grows with progress and never with time alone`() {
        val small = GardenCalculator.calculate(GardenInputs(3, 1, 2, 2, 0, 0))
        val bigger = GardenCalculator.calculate(GardenInputs(30, 5, 10, 9, 3, 1))
        assertTrue(bigger.growthPoints > small.growthPoints)
        assertTrue(bigger.plants.size >= small.plants.size)
        assertTrue(bigger.decorations.size >= small.decorations.size)
        assertEquals(3, bigger.decorations.size)
        assertEquals(2, bigger.areas.size)
        assertEquals(small, GardenCalculator.calculate(GardenInputs(3, 1, 2, 2, 0, 0)), "deterministic; nothing decays")
        val empty = GardenCalculator.calculate(GardenInputs(0, 0, 0, 0, 0, 0))
        assertEquals(1, empty.plants.size)
        assertEquals(0, empty.plants.single().stage)
    }

    private fun habit(days: Set<DayOfWeek> = DayOfWeek.entries.toSet(), target: Int = days.size, paused: LocalDate? = null) =
        Habit("h", "Read", scheduledDays = days, weeklyTarget = target, pausedSince = paused, createdAt = at)

    private fun d(day: Int) = LocalDate(2026, 10, day)

    @Test
    fun `a single missed day is forgiven two are not`() {
        val done = ((1..10) - 5).map(::d).toSet()
        val s = StreakCalculator.habitStreak(habit(), done, emptySet(), d(10), d(1))
        assertEquals(9, s.current)
        val twoMisses = ((1..10) - setOf(4, 6)).map(::d).toSet()
        assertEquals(4, StreakCalculator.habitStreak(habit(), twoMisses, emptySet(), d(10), d(1)).current)
    }

    @Test
    fun `today being unfinished never breaks the streak`() {
        val done = (1..9).map(::d).toSet()
        assertEquals(9, StreakCalculator.habitStreak(habit(), done, emptySet(), d(10), d(1)).current)
    }

    @Test
    fun `unscheduled rest and paused days are skipped`() {
        // Weekdays only; 2026-10-03/04 and 10/11 are weekends.
        val weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
        val done = (1..9).map(::d).filter { it.dayOfWeek in weekdays }.toSet() - d(7)
        val s = StreakCalculator.habitStreak(habit(weekdays), done, restDays = setOf(d(7)), today = d(9), startDate = d(1))
        assertEquals(6, s.current)
        val paused = StreakCalculator.habitStreak(habit(paused = d(5)), (1..4).map(::d).toSet(), emptySet(), d(20), d(1))
        assertEquals(4, paused.current, "pausing freezes the streak instead of breaking it")
    }

    @Test
    fun `weekly target streaks`() {
        // Weeks starting 5, 12, 19 Oct 2026 (Mondays): 3, 3, 1-so-far completions with target 3.
        val done = listOf(5, 6, 7, 12, 14, 16, 19).map(::d).toSet()
        val s = StreakCalculator.habitStreak(habit(target = 3), done, emptySet(), d(20), d(5))
        assertEquals(2, s.currentWeeks)
        assertEquals(1, s.completedThisWeek)
    }

    @Test
    fun `comebacks and activity streaks ignore rest days`() {
        val active = listOf(1, 2, 3, 8, 9).map(::d).toSet()
        assertEquals(1, StreakCalculator.comebacks(active, emptySet()))
        assertEquals(0, StreakCalculator.comebacks(active, (4..7).map(::d).toSet()))
        assertEquals(5, StreakCalculator.bestActivityStreak(active, (4..7).map(::d).toSet()))
        assertEquals(2, StreakCalculator.activityStreak(active, emptySet(), d(9)))
    }
}
