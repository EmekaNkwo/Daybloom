package com.daybloom.domain.services

import com.daybloom.domain.models.Priority
import com.daybloom.domain.models.RecurrenceFrequency
import com.daybloom.domain.models.RecurrenceRule
import com.daybloom.domain.models.TaskCategory
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.number

enum class CaptureKind { TASK, APPOINTMENT }

data class ParsedCapture(
    val title: String,
    val date: LocalDate? = null,
    val time: LocalTime? = null,
    val durationMinutes: Int? = null,
    val recurrence: RecurrenceRule? = null,
    val priority: Priority? = null,
    val category: TaskCategory? = null,
    val tags: List<String> = emptyList(),
    val kind: CaptureKind = CaptureKind.TASK,
    /** Human-readable notes about assumptions; when non-empty the UI asks the user to confirm. */
    val ambiguities: List<String> = emptyList(),
    /** Fragments of the input that were understood, for highlighting. */
    val recognized: List<String> = emptyList(),
) {
    val needsConfirmation: Boolean get() = ambiguities.isNotEmpty()
    val isValid: Boolean get() = title.isNotBlank()
}

/**
 * Deterministic natural-language parser for quick capture. Supports:
 * - dates: today, tonight, tomorrow, day after tomorrow, [this|next|on] <weekday>, in N days/weeks,
 *   next week, "Oct 30", "30 October [2027]", numeric 10/30 (flagged when day/month order is ambiguous)
 * - times: at 9, at 9am, 9:30 pm, 14:00, noon, midnight, morning/afternoon/evening/tonight
 * - durations: for 30 minutes, for 1.5 hours, for an hour, for half an hour
 * - recurrence: daily, every day, every weekday, every Monday [and Thursday], weekly, monthly, yearly
 * - priority: !, !!, !!!, "urgent", "high priority", "low priority"; tags and categories via #hashtags
 *
 * Anything the parser had to assume is reported in [ParsedCapture.ambiguities].
 */
class QuickCaptureParser(private val monthDayOrderIsDayFirst: Boolean = true) {

    fun parse(input: String, now: LocalDateTime): ParsedCapture {
        val state = ParseState(input)
        val today = now.date

        parseRecurrence(state)
        parseDuration(state)
        parsePriority(state)
        parseTags(state)
        val partOfDay = parsePartOfDay(state, today)
        parseExplicitTime(state)
        parseDate(state, today)

        if (state.time == null && partOfDay != null) state.time = partOfDay
        resolveMissingDate(state, now)

        val kind = if (APPOINTMENT_WORDS.containsMatchIn(input) && state.time != null) {
            CaptureKind.APPOINTMENT
        } else {
            CaptureKind.TASK
        }

        return ParsedCapture(
            title = state.cleanTitle(),
            date = state.date,
            time = state.time,
            durationMinutes = state.duration,
            recurrence = state.recurrence,
            priority = state.priority,
            category = state.category,
            tags = state.tags.distinct(),
            kind = kind,
            ambiguities = state.ambiguities.distinct(),
            recognized = state.recognized,
        )
    }

    private class ParseState(val original: String) {
        /** Working copy where consumed spans are blanked out with spaces (keeps indices aligned). */
        var text: String = original
        var date: LocalDate? = null
        var time: LocalTime? = null
        var duration: Int? = null
        var recurrence: RecurrenceRule? = null
        var priority: Priority? = null
        var category: TaskCategory? = null
        val tags = mutableListOf<String>()
        val ambiguities = mutableListOf<String>()
        val recognized = mutableListOf<String>()

        fun find(regex: Regex): MatchResult? = regex.find(text)

        fun consume(match: MatchResult) {
            recognized += original.substring(match.range).trim()
            val blank = " ".repeat(match.range.last - match.range.first + 1)
            text = text.replaceRange(match.range, blank)
        }

        fun cleanTitle(): String {
            var t = text.replace(Regex("\\s+"), " ").trim()
            repeat(3) {
                t = t.replace(TRAILING_FILLER, "").replace(LEADING_FILLER, "").trim()
            }
            return t.trim(',', '.', ';', '-', ' ')
        }
    }

    // region recurrence

    private fun parseRecurrence(state: ParseState) {
        state.find(EVERY_WEEKDAYS)?.let { m ->
            val days = WEEKDAY_TOKEN.findAll(m.value).mapNotNull { weekdayFrom(it.value) }.toSet()
            if (days.isNotEmpty()) {
                state.recurrence = RecurrenceRule(RecurrenceFrequency.WEEKLY, daysOfWeek = days)
                state.consume(m)
                return
            }
        }
        state.find(EVERY_INTERVAL)?.let { m ->
            val n = m.groupValues[1].toIntOrNull() ?: 1
            val freq = frequencyFrom(m.groupValues[2]) ?: return@let
            state.recurrence = RecurrenceRule(freq, interval = n.coerceAtLeast(1))
            state.consume(m)
            return
        }
        state.find(EVERY_SIMPLE)?.let { m ->
            val word = m.groupValues[1].lowercase()
            state.recurrence = when (word) {
                "weekday" -> RecurrenceRule(RecurrenceFrequency.WEEKLY, daysOfWeek = RecurrenceRule.WEEKDAYS)
                "morning", "evening", "night", "day" -> RecurrenceRule.daily()
                else -> frequencyFrom(word)?.let { RecurrenceRule(it) }
            }
            if (word == "morning" && state.time == null) state.time = LocalTime(9, 0)
            if ((word == "evening" || word == "night") && state.time == null) state.time = LocalTime(19, 0)
            state.consume(m)
            return
        }
        state.find(ADVERB_RECURRENCE)?.let { m ->
            state.recurrence = when (m.value.lowercase()) {
                "daily" -> RecurrenceRule.daily()
                "weekly" -> RecurrenceRule(RecurrenceFrequency.WEEKLY)
                "monthly" -> RecurrenceRule.monthly()
                else -> RecurrenceRule(RecurrenceFrequency.YEARLY)
            }
            state.consume(m)
        }
    }

    private fun frequencyFrom(word: String): RecurrenceFrequency? = when (word.lowercase().removeSuffix("s")) {
        "day" -> RecurrenceFrequency.DAILY
        "week" -> RecurrenceFrequency.WEEKLY
        "month" -> RecurrenceFrequency.MONTHLY
        "year" -> RecurrenceFrequency.YEARLY
        else -> null
    }

    // endregion

    // region duration, priority, tags

    private fun parseDuration(state: ParseState) {
        state.find(DURATION_HALF_HOUR)?.let { m ->
            state.duration = 30
            state.consume(m)
            return
        }
        state.find(DURATION_AN_HOUR)?.let { m ->
            state.duration = 60
            state.consume(m)
            return
        }
        state.find(DURATION)?.let { m ->
            val amount = m.groupValues[1].toDoubleOrNull() ?: return
            val unit = m.groupValues[2].lowercase()
            val minutes = if (unit.startsWith("h")) amount * 60 else amount
            val rounded = minutes.toInt()
            if (rounded in 1..(24 * 60)) {
                state.duration = rounded
                state.consume(m)
            }
        }
    }

    private fun parsePriority(state: ParseState) {
        state.find(BANG_PRIORITY)?.let { m ->
            state.priority = when (m.groupValues[1].length) {
                1 -> Priority.HIGH
                else -> Priority.URGENT
            }
            state.consume(m)
            return
        }
        state.find(WORD_PRIORITY)?.let { m ->
            val word = m.value.lowercase()
            state.priority = when {
                word.startsWith("urgent") || word.startsWith("asap") -> Priority.URGENT
                word.startsWith("high") || word.startsWith("important") -> Priority.HIGH
                word.startsWith("low") -> Priority.LOW
                else -> Priority.NORMAL
            }
            state.consume(m)
        }
    }

    private fun parseTags(state: ParseState) {
        HASHTAG.findAll(state.text).toList().forEach { m ->
            val tag = m.groupValues[1]
            val category = TaskCategory.entries.firstOrNull { it.name.equals(tag, ignoreCase = true) }
            if (category != null && state.category == null) state.category = category else state.tags += tag.lowercase()
            state.consume(m)
        }
    }

    // endregion

    // region time

    private fun parsePartOfDay(state: ParseState, today: LocalDate): LocalTime? {
        val m = state.find(PART_OF_DAY) ?: return null
        val word = m.groupValues[2].lowercase()
        val time = when (word) {
            "morning" -> LocalTime(9, 0)
            "afternoon" -> LocalTime(14, 0)
            "evening" -> LocalTime(18, 0)
            "tonight", "night" -> LocalTime(20, 0)
            else -> return null
        }
        if (word == "tonight" || m.groupValues[1].isNotBlank()) state.date = state.date ?: today
        state.consume(m)
        return time
    }

    private fun parseExplicitTime(state: ParseState) {
        state.find(NOON_MIDNIGHT)?.let { m ->
            state.time = if (m.groupValues[1].lowercase() == "midnight") LocalTime(0, 0) else LocalTime(12, 0)
            state.consume(m)
            return
        }
        val match = state.find(TIME_WITH_MERIDIEM) ?: state.find(TIME_24H) ?: state.find(TIME_AT_BARE) ?: return
        val hour = match.named("h")?.toIntOrNull() ?: return
        val minute = match.named("m")?.toIntOrNull() ?: 0
        val meridiem = match.named("ap")?.lowercase()?.replace(".", "")
        if (minute !in 0..59) return

        val resolvedHour = when {
            meridiem == "am" -> if (hour == 12) 0 else hour
            meridiem == "pm" -> if (hour == 12) 12 else hour + 12
            hour in 13..23 || hour == 0 -> hour
            hour == 12 -> 12
            hour in 1..6 -> {
                state.ambiguities += "Assumed ${hour}${minuteSuffix(minute)} PM — add \"am\" if you meant the morning."
                hour + 12
            }
            hour in 7..11 -> {
                state.ambiguities += "Assumed ${hour}${minuteSuffix(minute)} AM — add \"pm\" if you meant the evening."
                hour
            }
            else -> return
        }
        if (resolvedHour !in 0..23 || (meridiem != null && hour !in 1..12)) return
        state.time = LocalTime(resolvedHour, minute)
        state.consume(match)
    }

    private fun minuteSuffix(minute: Int) = if (minute == 0) "" else ":" + minute.toString().padStart(2, '0')

    // endregion

    // region date

    private fun parseDate(state: ParseState, today: LocalDate) {
        state.find(DAY_AFTER_TOMORROW)?.let { m ->
            state.date = DateMath.plusDays(today, 2); state.consume(m); return
        }
        state.find(TODAY_TOMORROW)?.let { m ->
            state.date = if (m.value.lowercase().startsWith("tom")) DateMath.plusDays(today, 1) else today
            state.consume(m)
            return
        }
        state.find(IN_N_UNITS)?.let { m ->
            val n = wordNumber(m.groupValues[1]) ?: return@let
            val unit = m.groupValues[2].lowercase()
            state.date = when {
                unit.startsWith("day") -> DateMath.plusDays(today, n)
                unit.startsWith("week") -> DateMath.plusDays(today, 7 * n)
                else -> DateMath.plusMonths(today, n)
            }
            state.consume(m)
            return
        }
        state.find(NEXT_WEEK)?.let { m ->
            val monday = DateMath.plusDays(DateMath.startOfWeek(today), 7)
            state.date = monday
            state.ambiguities += "\"Next week\" was set to Monday ${formatShort(monday)}."
            state.consume(m)
            return
        }
        state.find(WEEKDAY_PHRASE)?.let { m ->
            val qualifier = m.named("q")?.lowercase()?.trim()
            val day = m.named("d")?.let(::weekdayFrom) ?: return@let
            state.date = resolveWeekday(today, day, qualifier, state)
            state.consume(m)
            return
        }
        state.find(MONTH_DAY)?.let { m ->
            val month = m.named("mon")?.let(::monthFrom) ?: return@let
            val day = m.named("day")?.toIntOrNull() ?: return@let
            val year = m.named("yr")?.toIntOrNull()
            buildDate(today, year, month, day, state)?.let { state.date = it; state.consume(m); return }
        }
        state.find(DAY_MONTH)?.let { m ->
            val month = m.named("mon")?.let(::monthFrom) ?: return@let
            val day = m.named("day")?.toIntOrNull() ?: return@let
            val year = m.named("yr")?.toIntOrNull()
            buildDate(today, year, month, day, state)?.let { state.date = it; state.consume(m); return }
        }
        state.find(NUMERIC_DATE)?.let { m ->
            val a = m.groupValues[1].toInt()
            val b = m.groupValues[2].toInt()
            val year = m.groupValues[3].takeIf { it.isNotEmpty() }?.toInt()?.let { if (it < 100) 2000 + it else it }
            val (day, month) = when {
                a > 12 -> a to b
                b > 12 -> b to a
                monthDayOrderIsDayFirst -> a to b
                else -> b to a
            }
            if (a <= 12 && b <= 12 && a != b) {
                state.ambiguities += "Read ${m.value.trim()} as day $day of month $month."
            }
            buildDate(today, year, month, day, state)?.let { state.date = it; state.consume(m) }
        }
    }

    private fun resolveWeekday(today: LocalDate, day: DayOfWeek, qualifier: String?, state: ParseState): LocalDate {
        val ahead = (DateMath.dayOfWeekIndex(day) - DateMath.dayOfWeekIndex(today.dayOfWeek) + 7) % 7
        return when (qualifier) {
            "next" -> {
                val nextWeekStart = DateMath.plusDays(DateMath.startOfWeek(today), 7)
                val date = DateMath.plusDays(nextWeekStart, DateMath.dayOfWeekIndex(day))
                val soonest = DateMath.plusDays(today, if (ahead == 0) 7 else ahead)
                if (soonest != date) {
                    state.ambiguities += "\"Next ${day.name.lowercase()}\" could mean ${formatShort(soonest)} " +
                        "or ${formatShort(date)}. Using ${formatShort(date)}."
                } else {
                    state.ambiguities += "Detected ${formatShort(date)}."
                }
                date
            }
            else -> {
                if (ahead == 0 && qualifier != "this") {
                    state.ambiguities += "Today is ${day.name.lowercase()}; using today, not next week."
                }
                DateMath.plusDays(today, ahead)
            }
        }
    }

    private fun buildDate(today: LocalDate, year: Int?, month: Int, day: Int, state: ParseState): LocalDate? {
        if (month !in 1..12) return null
        val y = year ?: today.year
        if (day !in 1..DateMath.lengthOfMonth(y, month)) return null
        val candidate = LocalDate(y, month, day)
        if (year == null && candidate < today) {
            val nextYear = y + 1
            if (day > DateMath.lengthOfMonth(nextYear, month)) return null
            state.ambiguities += "That date has passed this year, so it was set to $nextYear."
            return LocalDate(nextYear, month, day)
        }
        return candidate
    }

    private fun resolveMissingDate(state: ParseState, now: LocalDateTime) {
        val time = state.time
        if (state.date == null && time != null) {
            if (time > now.time) {
                state.date = now.date
            } else {
                state.date = DateMath.plusDays(now.date, 1)
                state.ambiguities += "That time has already passed today, so it was set for tomorrow."
            }
        }
        val recurrence = state.recurrence
        if (state.date == null && recurrence != null) {
            state.date = firstRecurrenceDate(recurrence, now.date)
        }
        val date = state.date
        if (date != null && time != null && date == now.date && time <= now.time &&
            state.ambiguities.none { it.startsWith("That time") }
        ) {
            state.ambiguities += "That time has already passed today."
        }
    }

    private fun firstRecurrenceDate(rule: RecurrenceRule, today: LocalDate): LocalDate =
        if (rule.frequency == RecurrenceFrequency.WEEKLY && rule.daysOfWeek.isNotEmpty()) {
            (0..6).map { DateMath.plusDays(today, it) }.first { it.dayOfWeek in rule.daysOfWeek }
        } else {
            today
        }

    private fun formatShort(date: LocalDate): String {
        val dow = date.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }
        val mon = MONTH_NAMES[date.month.number - 1].replaceFirstChar { it.uppercase() }
        return "$dow, $mon ${date.day}"
    }

    // endregion

    private companion object {
        val MONTH_NAMES = listOf(
            "january", "february", "march", "april", "may", "june",
            "july", "august", "september", "october", "november", "december",
        )
        const val WEEKDAY = "(?:mon(?:day)?|tue(?:s(?:day)?)?|wed(?:nesday)?|thu(?:r(?:s(?:day)?)?)?|fri(?:day)?|sat(?:urday)?|sun(?:day)?)"
        const val MONTH = "(?:jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)"
        val I = setOf(RegexOption.IGNORE_CASE)

        val WEEKDAY_TOKEN = Regex("\\b$WEEKDAY\\b", I)
        val EVERY_WEEKDAYS = Regex("\\bevery\\s+$WEEKDAY(?:\\s*(?:,|and|&)\\s*$WEEKDAY)*\\b", I)
        val EVERY_INTERVAL = Regex("\\bevery\\s+(\\d+)\\s+(days?|weeks?|months?|years?)\\b", I)
        val EVERY_SIMPLE = Regex("\\bevery\\s+(day|weekday|week|month|year|morning|evening|night)\\b", I)
        val ADVERB_RECURRENCE = Regex("\\b(daily|weekly|monthly|yearly|annually)\\b", I)

        val DURATION_HALF_HOUR = Regex("\\bfor\\s+half\\s+an?\\s+hour\\b", I)
        val DURATION_AN_HOUR = Regex("\\bfor\\s+an\\s+hour\\b", I)
        val DURATION = Regex("\\bfor\\s+(\\d+(?:\\.\\d+)?)\\s*(minutes?|mins?|m|hours?|hrs?|h)\\b", I)

        val BANG_PRIORITY = Regex("(?:^|\\s)(!{1,3})(?=\\s|$)")
        val WORD_PRIORITY = Regex("\\b(urgent(?:ly)?|asap|high priority|important|low priority)\\b", I)
        val HASHTAG = Regex("(?:^|\\s)#([\\p{L}0-9_-]+)")

        val PART_OF_DAY = Regex("\\b(this\\s+)?(morning|afternoon|evening|tonight)\\b", I)
        val NOON_MIDNIGHT = Regex("\\b(?:at\\s+)?(noon|midday|midnight)\\b", I)
        val TIME_WITH_MERIDIEM = Regex(
            "\\b(?:at\\s+)?(?<h>\\d{1,2})(?::(?<m>\\d{2}))?\\s*(?<ap>a\\.?m\\.?|p\\.?m\\.?)(?=\\s|$|[,.;!?])", I,
        )
        val TIME_24H = Regex("\\b(?:at\\s+)?(?<h>[01]?\\d|2[0-3]):(?<m>[0-5]\\d)\\b", I)
        val TIME_AT_BARE = Regex("\\bat\\s+(?<h>\\d{1,2})\\b(?![/:.]\\d)", I)

        val DAY_AFTER_TOMORROW = Regex("\\b(?:the\\s+)?day\\s+after\\s+tomorrow\\b", I)
        val TODAY_TOMORROW = Regex("\\b(today|tomorrow|tmrw|tmr)\\b", I)
        val IN_N_UNITS = Regex("\\bin\\s+(\\d+|a|an|one|two|three|four|five|six|seven)\\s+(days?|weeks?|months?)\\b", I)
        val NEXT_WEEK = Regex("\\bnext\\s+week\\b", I)
        val WEEKDAY_PHRASE = Regex("\\b(?:on\\s+)?(?:(?<q>this|next|coming)\\s+)?(?<d>$WEEKDAY)\\b", I)
        val MONTH_DAY = Regex(
            "\\b(?:on\\s+)?(?<mon>$MONTH)\\.?\\s+(?<day>\\d{1,2})(?:st|nd|rd|th)?(?:,?\\s+(?<yr>\\d{4}))?\\b", I,
        )
        val DAY_MONTH = Regex(
            "\\b(?:on\\s+)?(?:the\\s+)?(?<day>\\d{1,2})(?:st|nd|rd|th)?\\s+(?:of\\s+)?(?<mon>$MONTH)\\b(?:,?\\s+(?<yr>\\d{4}))?", I,
        )
        val NUMERIC_DATE = Regex("\\b(?:on\\s+)?(\\d{1,2})/(\\d{1,2})(?:/(\\d{2,4}))?\\b", I)

        val APPOINTMENT_WORDS = Regex(
            "\\b(appointment|appt|meeting|interview|class|lecture|service|doctor|dentist|clinic|checkup|check-up)\\b", I,
        )
        val TRAILING_FILLER = Regex("\\s+(?:on|at|by|for|in|from|the|this|next)$", I)
        val LEADING_FILLER = Regex("^(?:remind me to|remember to|i need to|need to|todo:?)\\s+", I)

        /** Named group value, or null when the group didn't match or doesn't exist in this pattern (JVM throws). */
        fun MatchResult.named(name: String): String? =
            try {
                groups[name]?.value
            } catch (e: IllegalArgumentException) {
                null
            }

        fun weekdayFrom(token: String): DayOfWeek? = when (token.lowercase().take(3)) {
            "mon" -> DayOfWeek.MONDAY
            "tue" -> DayOfWeek.TUESDAY
            "wed" -> DayOfWeek.WEDNESDAY
            "thu" -> DayOfWeek.THURSDAY
            "fri" -> DayOfWeek.FRIDAY
            "sat" -> DayOfWeek.SATURDAY
            "sun" -> DayOfWeek.SUNDAY
            else -> null
        }

        fun monthFrom(token: String): Int? {
            val t = token.lowercase().take(3)
            return MONTH_NAMES.indexOfFirst { it.startsWith(t) }.takeIf { it >= 0 }?.plus(1)
        }

        fun wordNumber(token: String): Int? = when (token.lowercase()) {
            "a", "an", "one" -> 1
            "two" -> 2
            "three" -> 3
            "four" -> 4
            "five" -> 5
            "six" -> 6
            "seven" -> 7
            else -> token.toIntOrNull()
        }
    }
}
