package com.daybloom.domain

import com.daybloom.domain.models.DayClassification
import com.daybloom.domain.models.Priority
import com.daybloom.domain.models.ScoreDimension
import com.daybloom.domain.models.TaskStatus
import com.daybloom.domain.services.DayScoreInput
import com.daybloom.domain.services.ProductivityScoreCalculator
import com.daybloom.domain.services.ScoreExplainer
import com.daybloom.domain.services.ScoredTask
import kotlinx.datetime.LocalDate
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class ProductivityScoreCalculatorTest {
    private val calc = ProductivityScoreCalculator()
    private val all = ScoreDimension.entries.toSet()
    private val at = Instant.fromEpochMilliseconds(0)
    private val date = LocalDate(2026, 10, 22)

    private fun task(id: String, p: Priority = Priority.NORMAL, done: Boolean = false, top: Boolean = false, status: TaskStatus = if (done) TaskStatus.COMPLETED else TaskStatus.OPEN) =
        ScoredTask(id, p, status, top, done)

    private fun input(
        tasks: List<ScoredTask> = emptyList(),
        hasPlan: Boolean = false,
        rest: Boolean = false,
        focus: Int = 0,
        focusTracked: Boolean = false,
        habitsScheduled: Int = 0,
        habitsDone: Int = 0,
        tops: Int = tasks.count { it.isTopPriority },
        planned: Int = 0,
        available: Int? = null,
        activity: Boolean = false,
    ) = DayScoreInput(date, rest, hasPlan, tasks, focus, 60, focusTracked, habitsScheduled, habitsDone, tops, planned, available, false, activity)

    @Test
    fun `overall equals the weighted mean of the displayed dimension scores`() {
        val score = calc.calculate(
            input(
                tasks = listOf(task("a", Priority.HIGH, done = true, top = true), task("b"), task("c", done = true)),
                hasPlan = true, focus = 42, focusTracked = true, habitsScheduled = 4, habitsDone = 3, planned = 300, available = 360,
            ),
            all, at,
        )
        val overall = assertNotNull(score.overall)
        val recomputed = score.dimensions.sumOf { (it.score ?: 0) * it.effectiveWeight }.roundToInt()
        assertEquals(recomputed, overall)
        assertEquals(1.0, score.dimensions.sumOf { it.effectiveWeight }, 1e-9)
    }

    @Test
    fun `same input gives the same result`() {
        val i = input(tasks = listOf(task("a", done = true), task("b")), hasPlan = true)
        assertEquals(calc.calculate(i, all, at), calc.calculate(i, all, at))
    }

    @Test
    fun `missing dimensions are renormalized rather than counted as zero`() {
        val score = calc.calculate(input(tasks = listOf(task("a", done = true))), all, at)
        assertEquals(100, score.overall)
        val counted = score.dimensions.filter { it.score != null }.map { it.dimension }
        assertEquals(listOf(ScoreDimension.TASK_COMPLETION), counted)
    }

    @Test
    fun `disabled dimensions are excluded`() {
        val i = input(tasks = listOf(task("a", done = true)), focus = 0, focusTracked = true)
        val withFocus = calc.calculate(i, all, at).overall
        val withoutFocus = calc.calculate(i, all - ScoreDimension.FOCUS_TIME, at).overall
        assertTrue(withFocus!! < withoutFocus!!)
        assertEquals(100, withoutFocus)
    }

    @Test
    fun `rest days and empty days are not scored`() {
        val rest = calc.calculate(input(rest = true, tasks = listOf(task("a"))), all, at)
        assertEquals(DayClassification.REST, rest.classification)
        assertNull(rest.overall)
        val empty = calc.calculate(input(), all, at)
        assertEquals(DayClassification.NO_DATA, empty.classification)
        assertNull(empty.overall)
    }

    @Test
    fun `cancelled tasks are neither successes nor failures`() {
        val base = calc.calculate(input(tasks = listOf(task("a", done = true))), all, at)
        val withCancelled = calc.calculate(input(tasks = listOf(task("a", done = true), task("b", status = TaskStatus.CANCELLED))), all, at)
        assertEquals(base.overall, withCancelled.overall)
    }

    @Test
    fun `adding many trivial completed tasks cannot outweigh an unfinished important one`() {
        val important = task("i", Priority.HIGH, done = false)
        val few = calc.calculate(input(tasks = listOf(important, task("t1", Priority.LOW, done = true))), all, at).overall!!
        val many = calc.calculate(input(tasks = listOf(important) + (1..20).map { task("t$it", Priority.LOW, done = true) }), all, at).overall!!
        assertEquals(few, many, "quantity of trivial tasks must not change the score")
        val importantDone = calc.calculate(input(tasks = listOf(task("i", Priority.HIGH, done = true))), all, at).overall!!
        assertTrue(importantDone > many)
    }

    @Test
    fun `top priorities count more than other important tasks`() {
        val topDone = calc.calculate(input(tasks = listOf(task("a", Priority.HIGH, done = true, top = true), task("b", Priority.HIGH))), all, at)
        val otherDone = calc.calculate(input(tasks = listOf(task("a", Priority.HIGH, top = true), task("b", Priority.HIGH, done = true))), all, at)
        val imp = { s: com.daybloom.domain.models.DailyScore -> s.dimensions.first { it.dimension == ScoreDimension.IMPORTANT_TASKS }.score!! }
        assertTrue(imp(topDone) > imp(otherDone))
    }

    @Test
    fun `focus is only scored once the user uses focus sessions`() {
        val untracked = calc.calculate(input(tasks = listOf(task("a", done = true))), all, at)
        assertNull(untracked.dimensions.first { it.dimension == ScoreDimension.FOCUS_TIME }.score)
        val tracked = calc.calculate(input(tasks = listOf(task("a", done = true)), focus = 30, focusTracked = true), all, at)
        assertEquals(50, tracked.dimensions.first { it.dimension == ScoreDimension.FOCUS_TIME }.score)
    }

    @Test
    fun `planning rewards realistic plans and follow-through`() {
        val realistic = calc.calculate(input(tasks = listOf(task("a", done = true, top = true)), hasPlan = true, planned = 120, available = 240), all, at)
        val overloaded = calc.calculate(input(tasks = listOf(task("a", done = true, top = true)), hasPlan = true, planned = 600, available = 240), all, at)
        val plan = { s: com.daybloom.domain.models.DailyScore -> s.dimensions.first { it.dimension == ScoreDimension.PLANNING_QUALITY }.score!! }
        assertEquals(100, plan(realistic))
        assertTrue(plan(overloaded) < plan(realistic))
    }

    @Test
    fun `activity without a plan is scored as an unplanned day`() {
        val s = calc.calculate(input(habitsScheduled = 2, habitsDone = 1, activity = true), all, at)
        assertEquals(DayClassification.ACTIVITY_ONLY, s.classification)
        assertEquals(50, s.overall)
    }

    @Test
    fun `explanations are encouraging and describe the change`() {
        val before = calc.calculate(input(tasks = listOf(task("a"), task("b", done = true))), all, at)
        val after = calc.calculate(input(tasks = listOf(task("a", done = true), task("b", done = true))), all, at)
        val change = assertNotNull(ScoreExplainer.change(before, after))
        assertTrue(change.contains("rose"))
        assertTrue(ScoreExplainer.summary(calc.calculate(input(rest = true), all, at)).contains("rest", ignoreCase = true))
    }

    @Test
    fun `formula lists each counted dimension with its weight and is kept out of the summary`() {
        val s = calc.calculate(input(tasks = listOf(task("a", done = true), task("b"))), all, at)
        val formula = assertNotNull(ScoreExplainer.formula(s))
        s.dimensions.filter { it.score != null }.forEach { d ->
            assertTrue(formula.contains("${d.dimension.label} ${d.score} × ${(d.effectiveWeight * 100).roundToInt()}%"), formula)
        }
        assertTrue("×" !in ScoreExplainer.summary(s))
        assertNull(ScoreExplainer.formula(calc.calculate(input(rest = true), all, at)))
    }
}
