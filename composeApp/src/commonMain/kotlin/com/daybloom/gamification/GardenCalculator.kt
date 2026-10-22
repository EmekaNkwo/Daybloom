package com.daybloom.gamification

/** Cumulative, persisted progress that grows the garden. Nothing here decays with time. */
data class GardenInputs(
    val tasksCompleted: Int,
    val topPrioritiesCompleted: Int,
    val questsCompleted: Int,
    val focusSessionsCompleted: Int,
    /** ISO weeks with at least [GardenCalculator.ACTIVE_DAYS_FOR_WEEK] active days. */
    val progressWeeks: Int,
    /** Calendar months with at least [GardenCalculator.ACTIVE_DAYS_FOR_MONTH] active days. */
    val goalMonths: Int,
)

enum class PlantSpecies(val label: String) {
    DAISY("Daisy"), TULIP("Tulip"), SUNFLOWER("Sunflower"), LAVENDER("Lavender"),
    FERN("Fern"), ROSE("Rose"), LILY("Lily"), BONSAI("Bonsai"),
}

enum class Decoration(val label: String, val emoji: String) {
    STEPPING_STONES("Stepping stones", "🪨"),
    LANTERN("Garden lantern", "🏮"),
    BENCH("Quiet bench", "🪑"),
    BIRD_BATH("Bird bath", "🐦"),
    WIND_CHIME("Wind chime", "🎐"),
    BUTTERFLIES("Butterflies", "🦋"),
    FAIRY_LIGHTS("Fairy lights", "✨"),
    WATERING_CAN("Watering can", "🪣"),
}

enum class GardenArea(val label: String, val monthsRequired: Int) {
    MEADOW("Meadow", 0),
    POND("Lily pond", 1),
    ORCHARD("Orchard", 2),
    GREENHOUSE("Greenhouse", 4),
}

data class Plant(val index: Int, val species: PlantSpecies, val stage: Int) {
    val isMature: Boolean get() = stage >= GardenCalculator.MAX_STAGE
}

data class GardenState(
    val growthPoints: Int,
    val plants: List<Plant>,
    val flowers: Int,
    val decorations: List<Decoration>,
    val areas: List<GardenArea>,
    val unlockedSpecies: List<PlantSpecies>,
    val pointsToNextStage: Int,
    val nextMilestone: String,
)

/**
 * Derives the Bloom Garden entirely from persisted progress. Growth only ever pauses — a quiet day, a missed
 * habit or a rest week never removes anything.
 */
object GardenCalculator {
    const val MAX_STAGE = 4
    const val POINTS_PER_STAGE = 5
    const val SESSIONS_PER_DECORATION = 3
    const val ACTIVE_DAYS_FOR_WEEK = 3
    const val ACTIVE_DAYS_FOR_MONTH = 10
    private const val MAX_VISIBLE_FLOWERS = 36

    fun growthPoints(inputs: GardenInputs): Int = inputs.tasksCompleted + 2 * inputs.topPrioritiesCompleted

    fun calculate(inputs: GardenInputs): GardenState {
        val points = growthPoints(inputs)
        val pointsPerPlant = POINTS_PER_STAGE * MAX_STAGE
        val maturePlants = points / pointsPerPlant
        val growingStage = (points % pointsPerPlant) / POINTS_PER_STAGE

        // One extra species unlocks per progress week, starting with two.
        val species = PlantSpecies.entries.take((2 + inputs.progressWeeks).coerceAtMost(PlantSpecies.entries.size))
        val plants = (0..maturePlants).map { i ->
            Plant(
                index = i,
                species = species[i % species.size],
                stage = if (i < maturePlants) MAX_STAGE else growingStage,
            )
        }
        val decorations = Decoration.entries.take(inputs.focusSessionsCompleted / SESSIONS_PER_DECORATION)
        val areas = GardenArea.entries.filter { inputs.goalMonths >= it.monthsRequired }
        val toNextStage = POINTS_PER_STAGE - (points % POINTS_PER_STAGE)

        return GardenState(
            growthPoints = points,
            plants = plants,
            flowers = inputs.questsCompleted.coerceAtMost(MAX_VISIBLE_FLOWERS),
            decorations = decorations,
            areas = areas,
            unlockedSpecies = species,
            pointsToNextStage = toNextStage,
            nextMilestone = nextMilestone(inputs, toNextStage),
        )
    }

    private fun nextMilestone(inputs: GardenInputs, toNextStage: Int): String {
        val sessionsLeft = SESSIONS_PER_DECORATION - inputs.focusSessionsCompleted % SESSIONS_PER_DECORATION
        val nextDecoration = Decoration.entries.getOrNull(inputs.focusSessionsCompleted / SESSIONS_PER_DECORATION)
        return buildString {
            append("$toNextStage more growth to the next stage")
            if (nextDecoration != null) {
                append(" · $sessionsLeft focus session${if (sessionsLeft == 1) "" else "s"} to unlock ${nextDecoration.label.lowercase()}")
            }
        }
    }
}
