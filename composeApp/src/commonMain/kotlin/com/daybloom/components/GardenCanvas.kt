package com.daybloom.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.daybloom.design.BloomColors
import com.daybloom.design.BloomTheme
import com.daybloom.domain.models.GardenTheme
import com.daybloom.gamification.GardenArea
import com.daybloom.gamification.GardenCalculator
import com.daybloom.gamification.GardenState
import com.daybloom.gamification.PlantSpecies
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.ui.draw.clip
import androidx.compose.material3.MaterialTheme

/**
 * The Bloom Garden, drawn entirely with Canvas so it renders identically on Android and iOS. Plants sway gently
 * unless reduced motion is on. Everything shown is derived from [GardenState]; nothing here can wilt.
 */
@Composable
fun GardenCanvas(
    state: GardenState,
    theme: GardenTheme,
    modifier: Modifier = Modifier,
    height: Dp = 220.dp,
    maxPlants: Int = 12,
) {
    val colors = BloomTheme.colors
    val reduced = BloomTheme.reducedMotion
    val sway = if (reduced) {
        0f
    } else {
        val transition = rememberInfiniteTransition(label = "sway")
        val value by transition.animateFloat(-2.5f, 2.5f, infiniteRepeatable(tween(2600), RepeatMode.Reverse), label = "swayAngle")
        value
    }
    val description = "Bloom Garden with ${state.plants.count { it.isMature }} grown plants, " +
        "${state.flowers} flowers and ${state.decorations.size} decorations"
    Canvas(modifier.fillMaxWidth().height(height).clip(MaterialTheme.shapes.medium).semantics { contentDescription = description }) {
        drawSky(colors, theme)
        val ground = size.height * 0.72f
        drawRect(colors.gardenGrass, Offset(0f, ground), Size(size.width, size.height - ground))
        drawRect(colors.gardenSoil.copy(alpha = 0.35f), Offset(0f, ground), Size(size.width, 6.dp.toPx()))
        if (GardenArea.POND in state.areas) drawPond(ground)
        drawFlowers(state.flowers, ground)
        val plants = state.plants.takeLast(maxPlants)
        val spacing = size.width / (plants.size + 1)
        plants.forEachIndexed { i, plant ->
            val x = spacing * (i + 1)
            rotate(sway * (if (i % 2 == 0) 1 else -1), pivot = Offset(x, ground)) {
                drawPlant(Offset(x, ground), plant.species, plant.stage, colors)
            }
        }
        drawDecorations(state.decorations.size, ground)
    }
}

private fun DrawScope.drawSky(colors: BloomColors, theme: GardenTheme) {
    val top = when (theme) {
        GardenTheme.MEADOW -> colors.gardenSky
        GardenTheme.ZEN -> Color(0xFFE8EFE6)
        GardenTheme.MOONLIT -> Color(0xFF2A2340)
    }
    drawRect(Brush.verticalGradient(listOf(top, colors.gardenSkyLow)))
    if (theme == GardenTheme.MOONLIT) {
        drawCircle(Color(0xFFF6F0D8), 14.dp.toPx(), Offset(size.width * 0.82f, size.height * 0.2f))
    } else {
        drawCircle(Color(0xFFFFD27A).copy(alpha = 0.9f), 16.dp.toPx(), Offset(size.width * 0.84f, size.height * 0.18f))
    }
}

private fun DrawScope.drawPond(ground: Float) {
    val w = size.width * 0.26f
    drawOval(Color(0xFF8CC7D8).copy(alpha = 0.85f), Offset(size.width * 0.04f, ground + 10.dp.toPx()), Size(w, 22.dp.toPx()))
}

private fun DrawScope.drawFlowers(count: Int, ground: Float) {
    val palette = listOf(Color(0xFFF28FAD), Color(0xFFFFD166), Color(0xFFB39DDB), Color(0xFFFFFFFF))
    repeat(count) { i ->
        val x = ((i * 53 + 17) % 100) / 100f * size.width
        val y = ground + ((i * 29) % 60) / 100f * (size.height - ground) + 6.dp.toPx()
        drawCircle(palette[i % palette.size], 3.dp.toPx(), Offset(x, y))
    }
}

private fun DrawScope.drawDecorations(count: Int, ground: Float) {
    if (count >= 1) {
        repeat(4) { i -> drawOval(Color(0xFFBDB2A7), Offset(size.width * (0.35f + i * 0.08f), ground + 26.dp.toPx()), Size(14.dp.toPx(), 6.dp.toPx())) }
    }
    if (count >= 2) {
        val x = size.width * 0.93f
        drawLine(Color(0xFF6D5F55), Offset(x, ground), Offset(x, ground - 40.dp.toPx()), 3.dp.toPx())
        drawCircle(Color(0xFFFFC46B), 6.dp.toPx(), Offset(x, ground - 44.dp.toPx()))
    }
    if (count >= 6) {
        drawCircle(Color(0xFFFFB74D), 3.dp.toPx(), Offset(size.width * 0.55f, size.height * 0.3f))
        drawCircle(Color(0xFF9575CD), 3.dp.toPx(), Offset(size.width * 0.62f, size.height * 0.25f))
    }
}

private fun DrawScope.drawPlant(base: Offset, species: PlantSpecies, stage: Int, colors: BloomColors) {
    val unit = 10.dp.toPx()
    if (stage == 0) {
        drawCircle(colors.gardenStem, unit * 0.35f, Offset(base.x, base.y - unit * 0.2f))
        return
    }
    val stemHeight = unit * (1.2f + stage * 1.1f)
    val top = Offset(base.x, base.y - stemHeight)
    drawLine(colors.gardenStem, base, top, strokeWidth = unit * 0.28f)
    drawLeaf(Offset(base.x, base.y - stemHeight * 0.4f), unit, colors.gardenStem, left = true)
    if (stage >= 2) drawLeaf(Offset(base.x, base.y - stemHeight * 0.65f), unit, colors.gardenStem, left = false)
    if (stage >= GardenCalculator.MAX_STAGE - 1) {
        val petalColor = petalColorFor(species)
        val bloomRadius = unit * (if (stage >= GardenCalculator.MAX_STAGE) 0.9f else 0.55f)
        val petals = if (species == PlantSpecies.SUNFLOWER) 10 else 6
        repeat(petals) { i ->
            val angle = 2 * PI * i / petals
            drawCircle(petalColor, bloomRadius * 0.55f, Offset(top.x + cos(angle).toFloat() * bloomRadius, top.y + sin(angle).toFloat() * bloomRadius))
        }
        drawCircle(if (species == PlantSpecies.SUNFLOWER) Color(0xFF6D4C41) else Color(0xFFFFD54F), bloomRadius * 0.55f, top)
    } else {
        drawCircle(colors.gardenStem, unit * 0.35f, top)
    }
}

private fun DrawScope.drawLeaf(at: Offset, unit: Float, color: Color, left: Boolean) {
    val dx = if (left) -unit * 0.8f else unit * 0.8f
    drawOval(color, Offset(at.x + (if (left) dx else 0f), at.y - unit * 0.25f), Size(unit * 0.8f, unit * 0.45f))
}

private fun petalColorFor(species: PlantSpecies): Color = when (species) {
    PlantSpecies.DAISY -> Color(0xFFFFFFFF)
    PlantSpecies.TULIP -> Color(0xFFEF6F8E)
    PlantSpecies.SUNFLOWER -> Color(0xFFFFC93C)
    PlantSpecies.LAVENDER -> Color(0xFFB39DDB)
    PlantSpecies.FERN -> Color(0xFF81C784)
    PlantSpecies.ROSE -> Color(0xFFE5737B)
    PlantSpecies.LILY -> Color(0xFFFFE0B2)
    PlantSpecies.BONSAI -> Color(0xFFA5D6A7)
}
