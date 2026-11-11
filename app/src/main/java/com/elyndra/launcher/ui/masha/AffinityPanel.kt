package com.elyndra.launcher.ui.masha

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.elyndra.launcher.R
import com.elyndra.launcher.domain.relationship.AffinityGain
import com.elyndra.launcher.domain.relationship.AffinityLevel
import com.elyndra.launcher.domain.relationship.AffinityProgress
import com.elyndra.launcher.domain.relationship.GainReason
import com.elyndra.launcher.ui.components.ElyText
import com.elyndra.launcher.ui.components.tracking
import com.elyndra.launcher.ui.theme.LocalReducedMotion
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Lo que cambia en cada nivel, en su voz (una línea). */
fun AffinityLevel.perkRes(): Int = when (this) {
    AffinityLevel.Distant -> R.string.lucy_level_perk_distant
    AffinityLevel.Curious -> R.string.lucy_level_perk_curious
    AffinityLevel.Friendly -> R.string.lucy_level_perk_friendly
    AffinityLevel.Close -> R.string.lucy_level_perk_close
    AffinityLevel.Devoted -> R.string.lucy_level_perk_devoted
}

/** Su reacción al llegar a un nivel por primera vez. */
fun AffinityLevel.levelUpLineRes(): Int = when (this) {
    AffinityLevel.Distant, AffinityLevel.Curious -> R.string.lucy_levelup_line_curious
    AffinityLevel.Friendly -> R.string.lucy_levelup_line_friendly
    AffinityLevel.Close -> R.string.lucy_levelup_line_close
    AffinityLevel.Devoted -> R.string.lucy_levelup_line_devoted
}

fun GainReason.labelRes(): Int = when (this) {
    GainReason.Compliment -> R.string.lucy_gain_compliment
    GainReason.Kindness -> R.string.lucy_gain_kindness
    GainReason.CuriousAboutHer -> R.string.lucy_gain_curiousabouther
    GainReason.Sharing -> R.string.lucy_gain_sharing
    GainReason.Humour -> R.string.lucy_gain_humour
    GainReason.Greeting -> R.string.lucy_gain_greeting
    GainReason.Chat -> R.string.lucy_gain_chat
}

/** El nombre del nivel dentro de una frase ("Lucy ahora se siente cercana contigo"). */
@Composable
fun AffinityLevel.inSentence(): String = stringResource(labelRes()).lowercase()

/** "12 puntos para Cercana" o, en el último nivel, que no se puede estar más cerca. */
@Composable
fun progressLine(progress: AffinityProgress): String {
    val next = progress.next ?: return stringResource(R.string.lucy_bond_max)
    return stringResource(R.string.lucy_bond_points_to, progress.pointsToNext, stringResource(next.labelRes()))
}

/** La barra hacia el siguiente nivel. */
@Composable
fun AffinityBar(progress: AffinityProgress, glow: Color, track: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(track)
            .drawBehind { drawRect(glow, size = size.copy(width = size.width * progress.fraction.coerceIn(0f, 1f))) },
    )
}

/**
 * El panel "Lucy y tú": el nivel con su corazón, cuánto falta para el
 * siguiente, qué cambia en cada nivel (los que faltan, atenuados pero con su
 * nombre) y las últimas subidas. Las bajadas no se enumeran: solo cuentan en
 * el total.
 */
@Composable
internal fun AffinityPanel(
    progress: AffinityProgress,
    gains: List<AffinityGain>,
    glow: Color,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .widthIn(max = 340.dp)
            .holoPanel(RoundedCornerShape(18.dp), glow, alpha = 0.94f)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ElyText(stringResource(R.string.lucy_bond_title), size = 13f, weight = FontWeight.Bold, color = Holo.text, letterSpacing = tracking(0.08f), modifier = Modifier.weight(1f))
            HoloButton(stringResource(R.string.lucy_bond_close), glow, onClose)
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            HeartGlyph(fill = (progress.level.ordinal + 1) / AffinityLevel.entries.size.toFloat(), glow = glow, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                ElyText(stringResource(progress.level.labelRes()), size = 15f, weight = FontWeight.Bold, color = Holo.text)
                ElyText(progressLine(progress), size = 10f, color = Holo.dim)
            }
        }
        Spacer(Modifier.height(10.dp))
        AffinityBar(progress, glow, Holo.line.copy(alpha = 0.25f), Modifier.fillMaxWidth())
        Spacer(Modifier.height(14.dp))
        AffinityLevel.entries.forEach { level ->
            val reached = level <= progress.level
            val alpha = if (reached) 1f else 0.4f
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).graphicsLayer { this.alpha = alpha }, verticalAlignment = Alignment.Top) {
                HeartGlyph(fill = if (reached) 1f else 0f, glow = glow, modifier = Modifier.padding(top = 2.dp).size(10.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    ElyText(
                        stringResource(level.labelRes()) + if (reached) "" else " · " + stringResource(R.string.lucy_bond_locked),
                        size = 10.5f,
                        weight = if (level == progress.level) FontWeight.Bold else FontWeight.SemiBold,
                        color = Holo.text,
                    )
                    ElyText(stringResource(level.perkRes()), size = 9.5f, color = Holo.dim, lineHeightRatio = 1.3f)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        ElyText(stringResource(R.string.lucy_bond_recent), size = 9f, weight = FontWeight.SemiBold, color = Holo.dim, uppercase = true, letterSpacing = tracking(0.18f))
        Spacer(Modifier.height(4.dp))
        if (gains.isEmpty()) {
            ElyText(stringResource(R.string.lucy_bond_none), size = 10f, color = Holo.dim)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                gains.forEach { g ->
                    ElyText(stringResource(R.string.lucy_gain_line, g.points, stringResource(g.reason.labelRes())), size = 10f, color = Holo.text)
                }
            }
        }
    }
}

/**
 * El "+N" con un corazón que sube junto a ella y se desvanece (~1 s) cada vez
 * que la relación suma ([seq] cambia). Con movimiento reducido, solo aparece
 * y se va.
 */
@Composable
internal fun FloatingGain(seq: Int, points: Int, glow: Color, modifier: Modifier = Modifier) {
    val reduced = LocalReducedMotion.current
    val rise = remember { Animatable(0f) }
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(seq) {
        if (seq == 0 || points <= 0) return@LaunchedEffect
        rise.snapTo(0f)
        alpha.snapTo(1f)
        launch { if (!reduced) rise.animateTo(1f, tween(1_000, easing = FastOutSlowInEasing)) }
        delay(550)
        alpha.animateTo(0f, tween(450))
    }
    if (seq == 0) return
    val description = stringResource(R.string.lucy_gain_float_cd, points)
    Row(
        modifier
            .graphicsLayer {
                this.alpha = alpha.value
                translationY = -40.dp.toPx() * rise.value
            }
            .semantics { contentDescription = description; liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HeartGlyph(fill = 1f, glow = glow, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        ElyText("+$points", size = 13f, weight = FontWeight.Bold, color = Holo.text)
    }
}

/**
 * La primera vez que se llega a un nivel: una tarjeta breve con su reacción.
 * Se cierra con A/B, tocándola o sola a los [AUTO_DISMISS_MS].
 */
@Composable
internal fun LevelUpCard(level: AffinityLevel, glow: Color, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    LaunchedEffect(level) {
        delay(AUTO_DISMISS_MS)
        onDismiss()
    }
    Column(
        modifier
            .widthIn(max = 320.dp)
            .holoPanel(RoundedCornerShape(18.dp), glow, alpha = 0.95f)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
            .padding(horizontal = 18.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        HeartGlyph(fill = 1f, glow = glow, modifier = Modifier.size(30.dp))
        Spacer(Modifier.height(8.dp))
        ElyText(stringResource(R.string.lucy_levelup_title, level.inSentence()), size = 14f, weight = FontWeight.Bold, color = Holo.text)
        Spacer(Modifier.height(6.dp))
        ElyText("“" + stringResource(level.levelUpLineRes()) + "”", size = 11f, color = Holo.text, lineHeightRatio = 1.35f)
        Spacer(Modifier.height(12.dp))
        HoloButton(stringResource(R.string.lucy_bond_close), glow, onDismiss)
    }
}

private const val AUTO_DISMISS_MS = 6_000L
