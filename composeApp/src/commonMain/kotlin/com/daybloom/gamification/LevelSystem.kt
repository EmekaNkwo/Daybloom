package com.daybloom.gamification

import com.daybloom.domain.models.Level
import com.daybloom.domain.models.LevelProgress

object LevelSystem {
    val levels: List<Level> = listOf(
        Level(1, "Beginner", 0),
        Level(2, "Getting Started", 100),
        Level(3, "Builder", 300),
        Level(4, "Focused", 650),
        Level(5, "Achiever", 1_200),
        Level(6, "Strategist", 2_000),
        Level(7, "Momentum Master", 3_200),
        Level(8, "Bloom Keeper", 5_000),
        Level(9, "Evergreen", 7_500),
        Level(10, "Garden Sage", 10_000),
    )

    fun progressFor(totalXp: Int): LevelProgress {
        val xp = totalXp.coerceAtLeast(0)
        val index = levels.indexOfLast { xp >= it.minXp }.coerceAtLeast(0)
        return LevelProgress(xp, levels[index], levels.getOrNull(index + 1))
    }
}
