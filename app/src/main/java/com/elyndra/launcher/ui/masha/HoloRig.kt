package com.elyndra.launcher.ui.masha

import com.google.android.filament.Engine
import com.google.android.filament.MaterialInstance
import io.github.sceneview.node.ModelNode
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * Masha viva, fotograma a fotograma, sobre el modelo de `build_masha.py`.
 *
 * - **Cuerpo:** una máquina de estados con fundido entre clips (Idle, Talk,
 *   Listen) y gestos puntuales encima (Wave, Explain, Think). Filament aplica
 *   el fundido con `applyCrossFade`.
 * - **Cara:** morph targets mezclados en vivo — visemas de la voz, parpadeo
 *   con doble parpadeo ocasional, sonrisa y cejas según el ánimo y pequeñas
 *   microexpresiones al azar.
 * - **Holograma:** el color de la piel y del brillo siguen al ánimo (azul frío
 *   al analizar, lavanda al bromear o emocionarse), el código de la piel fluye
 *   (matriz UV de la emisión), el brillo late con la voz y el micrófono, y de
 *   vez en cuando hay un "glitch" de señal.
 * - **Holotanque:** los anillos giran; el haz se aviva con la energía.
 *
 * Con el modelo v2 (morphs ARKit/visemas) los materiales de Masha pasan a ser
 * los de [HoloShader] (que el rig libera en [destroy]); los huesos blandos y
 * de giro, si el modelo los trae, los mueve [SpringBonesFilament]. Con el
 * modelo antiguo todo sigue como estaba.
 *
 * Todo corre en el hilo principal dentro del `onFrame` de SceneView, sin
 * crear objetos por fotograma.
 */
internal class HoloRig(
    private val node: ModelNode,
    private val presence: MashaPresence,
    engine: Engine,
    private val shader: HoloShader? = null,
) {

    private val animator = node.animator
    private val clips: Map<String, Int> = (0 until animator.animationCount).associateBy { animator.getAnimationName(it) }
    private val idle = clips["Idle"] ?: 0
    private val rings = clips.filterKeys { it.startsWith("RingSpin") }.values.toIntArray()

    /* ── cara ─────────────────────────────────────────────────── */

    private val face = node.renderableNodes.firstOrNull { it.name == HEAD }
    private val morphs = FaceMorphs.forNames(face?.morphTargetNames.orEmpty())
    private val weights = FloatArray(morphs.names.size)
    private val channels = FaceChannels()
    private val mouth = LipSync.Weights()

    /* ── huesos procedurales (muelles y giros; el modelo v1 no trae ninguno) ── */

    private val springs = runCatching {
        SpringBonesFilament(engine, node.modelInstance.asset, node.modelInstance).takeIf { it.active }
    }.getOrNull()

    /* ── materiales ───────────────────────────────────────────── */

    private enum class Kind(val alpha: Float, val emit: Float, val uvScale: FloatArray?, val scroll: FloatArray) {
        // La emisión de piel, cara y manos es moderada: así la luz (y los
        // mapas de normales y AO horneados) dibujan los rasgos bajo el brillo.
        Skin(0.80f, 1.9f, floatArrayOf(1f, 1f), floatArrayOf(0f, 0.035f)),
        Face(0.96f, 0.55f, floatArrayOf(1f, 1f), floatArrayOf(0f, 0.035f)),
        Hands(0.94f, 1.3f, floatArrayOf(1f, 1f), floatArrayOf(0f, 0.035f)),
        Hair(0.88f, 2.4f, floatArrayOf(2f, 1.5f), floatArrayOf(0f, 0.02f)),
        Rim(0.30f, 4.0f, null, floatArrayOf(0f, 0f)),
        Eyes(1f, 6.0f, null, floatArrayOf(0f, 0f)),
        Beam(0.10f, 1.4f, floatArrayOf(3f, 1f), floatArrayOf(0f, 0.12f)),
        Ring(0.55f, 4.0f, floatArrayOf(6f, 0.5f), floatArrayOf(0.03f, 0f)),
        Glow(1f, 6.0f, null, floatArrayOf(0f, 0f)),
    }

    private class Slot(val mi: MaterialInstance, val kind: Kind) {
        val hasBase = mi.material.hasParameter("baseColorFactor")
        val hasEmit = mi.material.hasParameter("emissiveFactor")
        val hasStrength = mi.material.hasParameter("emissiveStrength")
        val hasUv = mi.material.hasParameter("emissiveUvMatrix")
    }

    // Las primitivas que pasan a HoloShader ya no se animan aquí (su material
    // original no se dibuja); el resto (holotanque, tarjetas sin textura) sí.
    private val slots: List<Slot> = node.renderableNodes.flatMap { r ->
        val name = r.name.orEmpty()
        r.materialInstances.mapIndexedNotNull { i, mi ->
            val shaded = shader != null && HoloShader.kindOf(name, i, mi.name)?.let { shader.apply(r.entity, i, it) } == true
            if (shaded) null else kindOf(name, i, mi.name)?.let { Slot(mi, it) }
        }
    }

    private fun kindOf(nodeName: String, index: Int, materialName: String?): Kind? {
        when (materialName) {
            "Holo_Skin" -> return Kind.Skin
            "Holo_Face" -> return Kind.Face
            "Holo_Hands" -> return Kind.Hands
            "Holo_Hair" -> return Kind.Hair
            "Holo_Rim" -> return Kind.Rim
            "Holo_Eyes" -> return Kind.Eyes
            "Tank_Beam" -> return Kind.Beam
            "Tank_Ring" -> return Kind.Ring
            "Tank_Glow" -> return Kind.Glow
        }
        // Por si el cargador no conserva los nombres de material: por nodo.
        return when {
            nodeName == "Masha_Body" -> Kind.Skin
            nodeName == "Masha_BodyRim" -> Kind.Rim
            nodeName == "Masha_Hair" -> Kind.Hair
            nodeName == "Masha_Head" -> if (index == 0) Kind.Face else Kind.Eyes
            nodeName == "Masha_Hands" -> Kind.Hands
            nodeName.startsWith("Tank_Beam") -> Kind.Beam
            nodeName.startsWith("Tank_Orbit") -> Kind.Ring
            nodeName.startsWith("Tank_GlowRing") -> Kind.Glow
            else -> null
        }
    }

    /* ── estado ───────────────────────────────────────────────── */

    private var start = -1L
    private var last = 0L

    private var current = idle
    private var currentAt = 0f
    private var previous = -1
    private var previousAt = 0f
    private var fadeAt = 0f
    private var oneShot = -1
    private var cueSeen = 0
    private var wasThinking = false

    private val skin = FloatArray(3)
    private val glow = FloatArray(3)
    private var pulse = 0f

    private val rnd = Random(7)
    private val blink = Blink { rnd.nextFloat() }
    private var nextGlitch = 5f
    private var glitchUntil = -1f
    private var nextMicro = 3f
    private var microUntil = -1f
    private var microKind = 0

    private val uv = FloatArray(9)

    init {
        linear(presence.visibleMood.skin, skin)
        linear(presence.visibleMood.glow, glow)
        // Orden de dibujo (0 primero): el cuerpo y la cara escriben profundidad,
        // así el casco de silueta, que va después, solo asoma por los bordes.
        for (r in node.renderableNodes) {
            val name = r.name.orEmpty()
            r.setPriority(
                when {
                    // La cabeza, la primera: su profundidad tapa el cuello del
                    // cuerpo, que sube por dentro de ella (si no, asoma bajo la barbilla).
                    name == HEAD || name == "Masha_Eyes" -> 0
                    name == "Masha_Body" || name == "Masha_Hands" -> 1
                    name == "Masha_Hair" -> 2
                    name == "Masha_BodyRim" -> 5
                    name.startsWith("Tank_Beam") -> 7
                    else -> 4
                },
            )
        }
        // Piel, cara y manos escriben profundidad: casi opacas, que a través
        // de la cara no se vea la nuca ni el pelo de detrás.
        slots.filter { it.kind == Kind.Skin || it.kind == Kind.Face || it.kind == Kind.Hands }.forEach { it.mi.setDepthWrite(true) }
    }

    /**
     * Deja de animar y libera los materiales propios (devolviendo los de
     * gltfio). Sin fotogramas en curso y con el asset aún vivo.
     */
    fun destroy(@Suppress("UNUSED_PARAMETER") engine: Engine) {
        stopped = true
        shader?.destroy()
    }

    private var stopped = false

    /** Deja de tocar el modelo (se va a liberar). */
    fun stop() {
        stopped = true
    }

    fun frame(frameTimeNanos: Long) {
        if (stopped) return
        if (start < 0) {
            start = frameTimeNanos
            last = frameTimeNanos
            cueSeen = presence.cueSeq
        }
        val t = (frameTimeNanos - start) / 1e9f
        val dt = ((frameTimeNanos - last) / 1e9f).coerceIn(0f, 0.1f)
        last = frameTimeNanos

        body(t, frameTimeNanos)
        faceFrame(t, dt, frameTimeNanos)
        hologram(t, dt)
    }

    /* ── cuerpo ───────────────────────────────────────────────── */

    private fun base(): Int = when {
        presence.speaking -> clips["Talk"]
        presence.listening -> clips["Listen"]
        else -> null
    } ?: idle

    private fun switchTo(clip: Int, t: Float) {
        if (clip == current) return
        previous = current
        previousAt = currentAt
        fadeAt = t
        current = clip
        currentAt = t
    }

    private fun body(t: Float, now: Long) {
        // Gestos: los pedidos por la interfaz y "pensar" al empezar a esperar.
        if (presence.cueSeq != cueSeen) {
            cueSeen = presence.cueSeq
            val clip = when (presence.cue) {
                MashaPresence.Cue.Wave -> clips["Wave"]
                MashaPresence.Cue.Explain -> clips["Explain"]
                MashaPresence.Cue.None -> null
            }
            if (clip != null) {
                oneShot = clip
                switchTo(clip, t)
            }
        }
        val thinking = presence.thinking
        if (thinking && !wasThinking && oneShot < 0) {
            clips["Think"]?.let {
                oneShot = it
                switchTo(it, t)
            }
        }
        wasThinking = thinking

        if (oneShot >= 0) {
            val d = animator.getAnimationDuration(oneShot)
            if (t - currentAt >= d - FADE) {
                oneShot = -1
                switchTo(base(), t)
            }
        } else {
            switchTo(base(), t)
        }

        animator.applyAnimation(current, clipTime(current, t - currentAt, loop = oneShot < 0))
        val fade = (t - fadeAt) / FADE
        if (previous >= 0 && fade < 1f) {
            animator.applyCrossFade(previous, clipTime(previous, t - previousAt, loop = true), smooth(fade))
        }
        for (r in rings) animator.applyAnimation(r, t % animator.getAnimationDuration(r))
        // Giros y muelles leen lo que acaban de escribir los clips; después, las matrices.
        springs?.frame(now)
        animator.updateBoneMatrices()
    }

    private fun clipTime(clip: Int, elapsed: Float, loop: Boolean): Float {
        val d = animator.getAnimationDuration(clip)
        if (d <= 0f) return 0f
        return if (loop) elapsed % d else min(elapsed, d)
    }

    /* ── cara ─────────────────────────────────────────────────── */

    private fun faceFrame(t: Float, dt: Float, now: Long) {
        if (face == null || !morphs.usable) return
        val c = channels
        val mood = presence.visibleMood
        presence.lipSync.sample(now, mouth)

        // La boca sigue a los visemas con algo de inercia (no salta).
        val k = 1f - exp(-dt * 28f)
        for (i in c.mouth.indices) c.mouth[i] += (mouth.v[i] - c.mouth[i]) * k

        // Microexpresiones: una ceja que sube, un amago de sonrisa, entrecerrar.
        if (t > nextMicro) {
            microKind = rnd.nextInt(3)
            microUntil = t + 0.45f + rnd.nextFloat() * 0.4f
            nextMicro = t + 3f + rnd.nextFloat() * 5f
        }
        val micro = if (t < microUntil) sin(((microUntil - t) / 0.8f).coerceIn(0f, 1f) * PI.toFloat()) else 0f

        val slow = 1f - exp(-dt * 4f)
        c.smile += (mood.smile + (if (microKind == 1) 0.18f * micro else 0f) - c.smile) * slow
        c.browUp += ((if (microKind == 0) 0.35f * micro else 0f) - c.browUp) * slow
        c.squint += ((if (microKind == 2) 0.5f * micro else 0f) - c.squint) * slow
        c.listen += ((if (presence.listening) 1f else 0f) - c.listen) * slow
        c.think += ((if (presence.thinking) 1f else 0f) - c.think) * slow
        c.frown += ((when (mood) {
            MashaMood.Concerned -> 0.45f
            MashaMood.Analytical -> 0.15f
            else -> 0f
        }) - c.frown) * slow

        // Parpadeo del contrato (más espaciado pensando; el derecho, un pelo detrás).
        blink.update(t, slow = presence.thinking)
        c.blinkL = blink.left(t)
        c.blinkR = blink.right(t)

        morphs.write(c, weights)
        face.setMorphWeights(weights, 0)
    }

    /* ── holograma ────────────────────────────────────────────── */

    private fun hologram(t: Float, dt: Float) {
        val mood = presence.visibleMood
        // El color se desliza hacia el del ánimo en ~1 s.
        val k = 1f - exp(-dt * 2.5f)
        linearTo(mood.skin, skin, k)
        linearTo(mood.glow, glow, k)

        // Pulso: la voz (apertura de boca), el micrófono o una respiración lenta.
        val voice = if (presence.speaking) mouth.v[LipSync.V.Open.ordinal] else 0f
        val mic = if (presence.listening) presence.micLevel else 0f
        val breath = 0.5f + 0.5f * sin(t * 2f * PI.toFloat() / 3f)
        val target = max(max(voice * 0.9f, mic), 0.12f * breath) + presence.energy * 0.25f
        pulse += (target - pulse) * (1f - exp(-dt * 12f))

        // Glitch de señal: breve, más frecuente al pensar o si algo falló.
        if (t > nextGlitch) {
            glitchUntil = t + 0.08f + rnd.nextFloat() * 0.1f
            val calm = when (mood) {
                MashaMood.Thinking -> 2.5f
                MashaMood.Concerned -> 2f
                else -> 6f
            }
            nextGlitch = t + calm + rnd.nextFloat() * calm
        }
        val glitch = t < glitchUntil
        val flicker = if (glitch) 0.45f + 0.4f * rnd.nextFloat() else 1f
        val tear = if (glitch) (rnd.nextFloat() - 0.5f) * 0.06f else 0f

        for (s in slots) {
            val kind = s.kind
            val mi = s.mi
            var alpha = kind.alpha
            var emit = kind.emit
            when (kind) {
                Kind.Skin, Kind.Face, Kind.Hands, Kind.Hair -> {
                    alpha *= flicker
                    emit *= (0.85f + 0.45f * pulse) * (if (glitch) 1.5f else 1f)
                }
                Kind.Rim -> {
                    alpha *= (0.75f + 0.5f * pulse) * flicker
                    emit *= 0.8f + 0.6f * pulse
                }
                Kind.Eyes -> emit *= 0.9f + 0.35f * pulse
                Kind.Beam -> {
                    alpha *= 0.7f + 0.8f * presence.energy
                    emit *= 0.7f + 0.9f * presence.energy
                }
                Kind.Ring, Kind.Glow -> emit *= 0.8f + 0.3f * breath + 0.3f * presence.energy
            }
            if (s.hasBase) {
                when (kind) {
                    // Cara y manos más claras: el AO horneado multiplica este color.
                    Kind.Skin -> mi.setParameter("baseColorFactor", skin[0], skin[1], skin[2], alpha)
                    Kind.Face, Kind.Hands -> mi.setParameter("baseColorFactor", skin[0] * 1.6f + 0.05f, skin[1] * 1.5f + 0.05f, skin[2], alpha)
                    Kind.Hair -> mi.setParameter("baseColorFactor", skin[0] * 0.8f + glow[0] * 0.25f, skin[1] * 0.7f, skin[2], alpha)
                    Kind.Rim -> mi.setParameter("baseColorFactor", glow[0], glow[1], glow[2], alpha)
                    else -> if (kind.alpha < 1f) mi.setParameter("baseColorFactor", glow[0] * 0.5f, glow[1] * 0.8f, glow[2], alpha)
                }
            }
            if (s.hasEmit) {
                when (kind) {
                    Kind.Skin, Kind.Face, Kind.Hands, Kind.Rim, Kind.Eyes -> mi.setParameter("emissiveFactor", glow[0], glow[1], glow[2])
                    Kind.Hair -> mi.setParameter("emissiveFactor", glow[0] * 0.9f + 0.1f, glow[1] * 0.8f, glow[2])
                    else -> Unit
                }
            }
            if (s.hasStrength) mi.setParameter("emissiveStrength", emit)
            val scale = kind.uvScale
            if (s.hasUv && scale != null) {
                // Matriz 3x3 (por columnas): escala + desplazamiento que avanza con el tiempo.
                uv[0] = scale[0]; uv[1] = 0f; uv[2] = 0f
                uv[3] = 0f; uv[4] = scale[1]; uv[5] = 0f
                uv[6] = (t * kind.scroll[0] + tear) % 1f
                uv[7] = (t * kind.scroll[1] * (1f + presence.energy)) % 1f
                uv[8] = 1f
                mi.setParameter("emissiveUvMatrix", MaterialInstance.FloatElement.MAT3, uv, 0, 1)
            }
        }
        shader?.frame(t, presence.energy, pulse, if (glitch) 1f else 0f, skin, glow)
    }

    companion object {
        private const val HEAD = "Masha_Head"

        /** ¿Es el modelo v2 (el que usa los materiales de [HoloShader])? */
        fun isV2(node: ModelNode): Boolean =
            FaceMorphs.forNames(node.renderableNodes.firstOrNull { it.name == HEAD }?.morphTargetNames.orEmpty()) is FaceMorphs.V2

        /** Segundos de fundido entre clips. */
        private const val FADE = 0.45f

        private fun smooth(x: Float): Float {
            val c = x.coerceIn(0f, 1f)
            return c * c * (3 - 2 * c)
        }

        /** ARGB sRGB → RGB lineal. */
        private fun linear(argb: Long, out: FloatArray) {
            out[0] = channel(argb shr 16)
            out[1] = channel(argb shr 8)
            out[2] = channel(argb)
        }

        private fun linearTo(argb: Long, out: FloatArray, k: Float) {
            out[0] += (channel(argb shr 16) - out[0]) * k
            out[1] += (channel(argb shr 8) - out[1]) * k
            out[2] += (channel(argb) - out[2]) * k
        }

        private fun channel(v: Long): Float = ((v and 0xFF) / 255f).pow(2.2f)
    }
}
