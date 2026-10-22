package com.daybloom.domain.models

enum class Priority(val label: String, val weight: Double, val rank: Int) {
    URGENT("Urgent", 3.0, 0),
    HIGH("High", 2.0, 1),
    NORMAL("Normal", 1.0, 2),
    LOW("Low", 0.5, 3);

    val isImportant: Boolean get() = this == URGENT || this == HIGH
}

enum class TaskCategory(val label: String, val emoji: String) {
    WORK("Work", "💼"),
    PERSONAL("Personal", "🌿"),
    HEALTH("Health", "💪"),
    FINANCE("Finance", "💳"),
    FAMILY("Family", "🏡"),
    LEARNING("Learning", "📚"),
    ERRANDS("Errands", "🛒"),
    OTHER("Other", "✨"),
}

enum class TaskStatus { OPEN, COMPLETED, CANCELLED }

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class EnergyLevel(val label: String) { LOW("Low"), MEDIUM("Steady"), HIGH("High") }

/** How a day felt. Recorded for reflection only — never an input to productivity scoring. */
enum class Mood(val label: String, val emoji: String) {
    DRAINED("Drained", "😮‍💨"),
    MEH("Meh", "😐"),
    OKAY("Okay", "🙂"),
    GOOD("Good", "😊"),
    GREAT("Great", "🤩"),
}

/** What happens to unfinished tasks at the end of the day. Never moves anything without a policy or confirmation. */
enum class UnfinishedTaskPolicy { ASK, KEEP_ON_ORIGINAL_DATE, MOVE_FLEXIBLE_TO_TOMORROW }
